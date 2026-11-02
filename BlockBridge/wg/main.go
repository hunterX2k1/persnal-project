// bbwg is BlockBridge's VPN client: a userspace WireGuard tunnel (the official
// wireguard-go implementation with its gVisor netstack) exposed to the app as a
// local, password-protected SOCKS5 server (CONNECT + UDP ASSOCIATE).
//
// BlockBridge's relay keeps filtering every connection and then hands the
// allowed ones to bbwg, so they leave the phone from the VPN server's country.
//
// Usage: BB_USER=u BB_PASS=p bbwg -config wg.conf
// Prints "READY <port>" once listening and "HANDSHAKE <unix-seconds>" whenever
// the server handshake time changes. Exits when stdin closes (the app went away).
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type peer struct {
	publicKey, presharedKey, endpoint string
	allowedIPs                        []string
	keepalive                         int
}

type config struct {
	privateKey string
	addresses  []netip.Addr
	dns        []netip.Addr
	mtu        int
	peers      []peer
}

// parseConfig reads the wg-quick format that VPN providers hand out.
func parseConfig(text string) (*config, error) {
	c := &config{mtu: 1420}
	var cur *peer
	section := ""
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				c.peers = append(c.peers, peer{})
				cur = &c.peers[len(c.peers)-1]
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.TrimSpace(v)
		switch section {
		case "interface":
			switch k {
			case "privatekey":
				c.privateKey = v
			case "address":
				for _, a := range splitList(v) {
					p, err := netip.ParsePrefix(a)
					if err != nil {
						addr, err2 := netip.ParseAddr(a)
						if err2 != nil {
							return nil, fmt.Errorf("bad Address %q", a)
						}
						c.addresses = append(c.addresses, addr)
						continue
					}
					c.addresses = append(c.addresses, p.Addr())
				}
			case "dns":
				for _, a := range splitList(v) {
					if addr, err := netip.ParseAddr(a); err == nil {
						c.dns = append(c.dns, addr)
					}
				}
			case "mtu":
				if n, err := strconv.Atoi(v); err == nil && n >= 1280 {
					c.mtu = n
				}
			}
		case "peer":
			switch k {
			case "publickey":
				cur.publicKey = v
			case "presharedkey":
				cur.presharedKey = v
			case "endpoint":
				cur.endpoint = v
			case "allowedips":
				cur.allowedIPs = append(cur.allowedIPs, splitList(v)...)
			case "persistentkeepalive":
				cur.keepalive, _ = strconv.Atoi(v)
			}
		}
	}
	if c.privateKey == "" || len(c.addresses) == 0 || len(c.peers) == 0 {
		return nil, errors.New("config needs PrivateKey, Address and a [Peer]")
	}
	if len(c.dns) == 0 {
		c.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return c, nil
}

func splitList(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

func keyHex(b64 string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("bad key %q", b64)
	}
	return hex.EncodeToString(b), nil
}

// uapi converts the config to wireguard-go's control protocol.
func (c *config) uapi() (string, error) {
	var sb strings.Builder
	k, err := keyHex(c.privateKey)
	if err != nil {
		return "", err
	}
	fmt.Fprintf(&sb, "private_key=%s\n", k)
	for _, p := range c.peers {
		pk, err := keyHex(p.publicKey)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&sb, "public_key=%s\n", pk)
		if p.presharedKey != "" {
			psk, err := keyHex(p.presharedKey)
			if err != nil {
				return "", err
			}
			fmt.Fprintf(&sb, "preshared_key=%s\n", psk)
		}
		if p.endpoint != "" {
			ap, err := netip.ParseAddrPort(p.endpoint)
			if err != nil {
				return "", fmt.Errorf("Endpoint must be IP:port here, got %q", p.endpoint)
			}
			fmt.Fprintf(&sb, "endpoint=%s\n", ap)
		}
		keepalive := p.keepalive
		if keepalive == 0 {
			keepalive = 25 // keep mobile NAT mappings alive
		}
		fmt.Fprintf(&sb, "persistent_keepalive_interval=%d\n", keepalive)
		sb.WriteString("replace_allowed_ips=true\n")
		for _, a := range p.allowedIPs {
			fmt.Fprintf(&sb, "allowed_ip=%s\n", a)
		}
	}
	return sb.String(), nil
}

var (
	tnet        *netstack.Net
	tunnelAddrs []netip.Addr
	username    string
	password    string
)

func main() {
	confPath := flag.String("config", "", "WireGuard config (wg-quick format)")
	ovpnPath := flag.String("ovpn", "", "OpenVPN config (.ovpn) — VPN Gate and other OpenVPN servers")
	listen := flag.String("listen", "127.0.0.1:0", "SOCKS5 listen address")
	flag.Parse()
	username, password = os.Getenv("BB_USER"), os.Getenv("BB_PASS")
	if username == "" || password == "" {
		fail("BB_USER and BB_PASS must be set")
	}

	var ln net.Listener
	if *ovpnPath != "" {
		link, l, err := startOpenVPN(*ovpnPath, *listen, &net.Dialer{Timeout: 15 * time.Second})
		if err != nil {
			fail(err.Error())
		}
		ln = l
		fmt.Printf("READY %d\n", ln.Addr().(*net.TCPAddr).Port)
		// OpenVPN has no handshake counter to watch: report "alive" while the link is up.
		go func() {
			tick := time.NewTicker(20 * time.Second)
			fmt.Printf("HANDSHAKE %d\n", time.Now().Unix())
			for {
				select {
				case <-link.done:
					fail("OpenVPN connection closed by the server")
				case <-tick.C:
					fmt.Printf("HANDSHAKE %d\n", time.Now().Unix())
				}
			}
		}()
	} else {
		text, err := os.ReadFile(*confPath)
		if err != nil {
			fail(err.Error())
		}
		dev, l, err := start(string(text), *listen)
		if err != nil {
			fail(err.Error())
		}
		ln = l
		fmt.Printf("READY %d\n", ln.Addr().(*net.TCPAddr).Port)
		go watchHandshake(dev)
	}

	go func() { // exit with the app
		io.Copy(io.Discard, os.Stdin)
		os.Exit(0)
	}()
	for {
		c, err := ln.Accept()
		if err != nil {
			fail(err.Error())
		}
		go serve(c)
	}
}

// start brings the WireGuard tunnel up and opens the SOCKS5 listener.
func start(text, listen string) (*device.Device, net.Listener, error) {
	cfg, err := parseConfig(text)
	if err != nil {
		return nil, nil, err
	}
	uapi, err := cfg.uapi()
	if err != nil {
		return nil, nil, err
	}
	tunDev, n, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return nil, nil, err
	}
	tnet = n
	tunnelAddrs = cfg.addresses
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "bbwg: "))
	if err := dev.IpcSet(uapi); err != nil {
		return nil, nil, fmt.Errorf("wireguard: %w", err)
	}
	if err := dev.Up(); err != nil {
		return nil, nil, fmt.Errorf("wireguard up: %w", err)
	}
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		return nil, nil, err
	}
	return dev, ln, nil
}

func fail(msg string) {
	fmt.Printf("ERROR %s\n", msg)
	os.Exit(1)
}

func watchHandshake(dev *device.Device) {
	last := ""
	for {
		state, err := dev.IpcGet()
		if err == nil {
			for _, l := range strings.Split(state, "\n") {
				if v, ok := strings.CutPrefix(l, "last_handshake_time_sec="); ok && v != "0" && v != last {
					last = v
					fmt.Printf("HANDSHAKE %s\n", v)
				}
			}
		}
		time.Sleep(2 * time.Second)
	}
}

// ---------------- SOCKS5 server ----------------

func serve(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReader(c)

	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil || head[0] != 5 {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	c.Write([]byte{5, 2})
	if !auth(r, c) {
		return
	}

	req := make([]byte, 3)
	if _, err := io.ReadFull(r, req); err != nil {
		return
	}
	dst, err := readAddr(r)
	if err != nil {
		reply(c, 8, netip.AddrPort{})
		return
	}
	c.SetDeadline(time.Time{})
	switch req[1] {
	case 1:
		connect(c, r, dst)
	case 3:
		associate(c)
	default:
		reply(c, 7, netip.AddrPort{})
	}
}

func auth(r *bufio.Reader, c net.Conn) bool {
	ver, _ := r.ReadByte()
	ul, _ := r.ReadByte()
	u := make([]byte, ul)
	io.ReadFull(r, u)
	pl, _ := r.ReadByte()
	p := make([]byte, pl)
	_, err := io.ReadFull(r, p)
	ok := err == nil && ver == 1 && string(u) == username && string(p) == password
	if ok {
		c.Write([]byte{1, 0})
	} else {
		c.Write([]byte{1, 1})
	}
	return ok
}

// readAddr reads ATYP + address + port. Hostnames are resolved through the tunnel.
func readAddr(r io.Reader) (netip.AddrPort, error) {
	t := make([]byte, 1)
	if _, err := io.ReadFull(r, t); err != nil {
		return netip.AddrPort{}, err
	}
	var addr netip.Addr
	switch t[0] {
	case 1, 4:
		n := 4
		if t[0] == 4 {
			n = 16
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(r, b); err != nil {
			return netip.AddrPort{}, err
		}
		addr, _ = netip.AddrFromSlice(b)
	case 3:
		l := make([]byte, 1)
		io.ReadFull(r, l)
		host := make([]byte, l[0])
		if _, err := io.ReadFull(r, host); err != nil {
			return netip.AddrPort{}, err
		}
		ips, err := tnet.LookupHost(string(host))
		if err != nil || len(ips) == 0 {
			return netip.AddrPort{}, errors.New("lookup failed")
		}
		addr, _ = netip.ParseAddr(ips[0])
	default:
		return netip.AddrPort{}, errors.New("bad address type")
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(addr.Unmap(), binary.BigEndian.Uint16(pb)), nil
}

func reply(c net.Conn, code byte, bind netip.AddrPort) {
	out := []byte{5, code, 0}
	a := bind.Addr()
	if !a.IsValid() || a.Is4() {
		var b [4]byte
		if a.IsValid() {
			b = a.As4()
		}
		out = append(out, 1)
		out = append(out, b[:]...)
	} else {
		b := a.As16()
		out = append(out, 4)
		out = append(out, b[:]...)
	}
	out = binary.BigEndian.AppendUint16(out, bind.Port())
	c.Write(out)
}

func connect(c net.Conn, r *bufio.Reader, dst netip.AddrPort) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	remote, err := tnet.DialContextTCPAddrPort(ctx, dst)
	cancel()
	if err != nil {
		reply(c, 5, netip.AddrPort{})
		return
	}
	defer remote.Close()
	reply(c, 0, netip.AddrPort{})

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		io.Copy(remote, r) // r may hold bytes already buffered after the request
		remote.CloseWrite()
	}()
	io.Copy(c, remote)
	if tc, ok := c.(*net.TCPConn); ok {
		tc.CloseWrite()
	}
	wg.Wait()
}

// associate relays UDP (RFC 1928 §7) through the tunnel until the control connection closes.
func associate(c net.Conn) {
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		reply(c, 1, netip.AddrPort{})
		return
	}
	defer local.Close()
	reply(c, 0, local.LocalAddr().(*net.UDPAddr).AddrPort())

	var mu sync.Mutex
	var client *net.UDPAddr
	conns := map[bool]net.PacketConn{} // keyed by "is IPv4"
	defer func() {
		mu.Lock()
		for _, pc := range conns {
			pc.Close()
		}
		mu.Unlock()
	}()

	tunnelConn := func(v4 bool) (net.PacketConn, error) {
		mu.Lock()
		defer mu.Unlock()
		if pc, ok := conns[v4]; ok {
			return pc, nil
		}
		// Bind to the tunnel's own address: gVisor has no route for an unspecified source.
		var src netip.Addr
		for _, a := range tunnelAddrs {
			if a.Is4() == v4 {
				src = a
				break
			}
		}
		if !src.IsValid() {
			return nil, errors.New("tunnel has no address of this IP family")
		}
		pc, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(src, 0))
		if err != nil {
			return nil, err
		}
		conns[v4] = pc
		go func() { // tunnel -> app
			buf := make([]byte, 65535)
			for {
				n, from, err := pc.ReadFrom(buf)
				if err != nil {
					return
				}
				ua, ok := from.(*net.UDPAddr)
				mu.Lock()
				to := client
				mu.Unlock()
				if !ok || to == nil {
					continue
				}
				local.WriteToUDP(udpHeader(ua.AddrPort(), buf[:n]), to)
			}
		}()
		return pc, nil
	}

	go func() { // app -> tunnel
		buf := make([]byte, 65535)
		for {
			n, from, err := local.ReadFromUDP(buf)
			if err != nil {
				return
			}
			mu.Lock()
			client = from
			mu.Unlock()
			dst, payload, ok := parseUDPHeader(buf[:n])
			if !ok {
				continue
			}
			pc, err := tunnelConn(dst.Addr().Is4())
			if err != nil {
				continue
			}
			pc.WriteTo(payload, net.UDPAddrFromAddrPort(dst))
		}
	}()

	io.Copy(io.Discard, c) // lives as long as the TCP control connection
}

func parseUDPHeader(b []byte) (netip.AddrPort, []byte, bool) {
	if len(b) < 10 || b[2] != 0 {
		return netip.AddrPort{}, nil, false
	}
	var addr netip.Addr
	off := 0
	switch b[3] {
	case 1:
		addr, _ = netip.AddrFromSlice(b[4:8])
		off = 8
	case 4:
		if len(b) < 22 {
			return netip.AddrPort{}, nil, false
		}
		addr, _ = netip.AddrFromSlice(b[4:20])
		off = 20
	default:
		return netip.AddrPort{}, nil, false
	}
	port := binary.BigEndian.Uint16(b[off:])
	return netip.AddrPortFrom(addr.Unmap(), port), b[off+2:], true
}

func udpHeader(from netip.AddrPort, data []byte) []byte {
	a := from.Addr().Unmap()
	out := []byte{0, 0, 0}
	if a.Is4() {
		b := a.As4()
		out = append(out, 1)
		out = append(out, b[:]...)
	} else {
		b := a.As16()
		out = append(out, 4)
		out = append(out, b[:]...)
	}
	out = binary.BigEndian.AppendUint16(out, from.Port())
	return append(out, data...)
}
