package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	rand.Read(priv)
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return
}

// A stand-in "VPN server": a second WireGuard device on loopback with TCP and UDP
// echo services at 10.9.0.1:7, reachable only through the encrypted tunnel.
func startServer(t *testing.T, serverPriv, clientPub []byte) int {
	tunDev, snet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "server: "))
	probe, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	port := probe.LocalAddr().(*net.UDPAddr).Port
	probe.Close()
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(serverPriv), port, hex.EncodeToString(clientPub)))
	if err != nil {
		t.Fatal(err)
	}
	dev.Up()
	t.Cleanup(dev.Close)

	tl, err := snet.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 9, 0, 1), Port: 7})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := tl.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	ul, err := snet.ListenUDP(&net.UDPAddr{IP: net.IPv4(10, 9, 0, 1), Port: 7})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := ul.ReadFrom(buf)
			if err != nil {
				return
			}
			ul.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()

	return port
}

func socksHandshake(t *testing.T, port int) (net.Conn, *bufio.Reader) {
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReader(c)
	c.Write([]byte{5, 1, 2})
	resp := make([]byte, 2)
	io.ReadFull(r, resp)
	c.Write(append(append([]byte{1, byte(len(username))}, username...), append([]byte{byte(len(password))}, password...)...))
	io.ReadFull(r, resp)
	if resp[1] != 0 {
		t.Fatal("auth failed")
	}
	return c, r
}

func readReply(t *testing.T, r *bufio.Reader) (byte, int) {
	h := make([]byte, 10)
	if _, err := io.ReadFull(r, h); err != nil {
		t.Fatal(err)
	}
	return h[1], int(binary.BigEndian.Uint16(h[8:]))
}

func TestTunnelEndToEnd(t *testing.T) {
	serverPriv, serverPub := keypair(t)
	clientPriv, clientPub := keypair(t)
	srvPort := startServer(t, serverPriv, clientPub)

	username, password = "u", "p"
	conf := fmt.Sprintf(`# Proton-style config
[Interface]
PrivateKey = %s
Address = 10.9.0.2/32
DNS = 10.9.0.1

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 127.0.0.1:%d
`, base64.StdEncoding.EncodeToString(clientPriv), base64.StdEncoding.EncodeToString(serverPub), srvPort)

	dev, ln, err := start(conf, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer dev.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go serve(c)
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port

	// TCP through the tunnel
	c, r := socksHandshake(t, port)
	c.Write([]byte{5, 1, 0, 1, 10, 9, 0, 1, 0, 7})
	if code, _ := readReply(t, r); code != 0 {
		t.Fatalf("CONNECT failed: %d", code)
	}
	c.Write([]byte("hello through wireguard"))
	got := make([]byte, 23)
	if _, err := io.ReadFull(r, got); err != nil || string(got) != "hello through wireguard" {
		t.Fatalf("tcp echo: %q %v", got, err)
	}
	c.Close()

	// UDP through the tunnel
	ctrl, cr := socksHandshake(t, port)
	defer ctrl.Close()
	ctrl.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	code, relay := readReply(t, cr)
	if code != 0 {
		t.Fatalf("ASSOCIATE failed: %d", code)
	}
	u, _ := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relay})
	defer u.Close()
	u.SetDeadline(time.Now().Add(10 * time.Second))
	u.Write(udpHeader(netip.MustParseAddrPort("10.9.0.1:7"), []byte("ping")))
	buf := make([]byte, 256)
	n, err := u.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	from, payload, ok := parseUDPHeader(buf[:n])
	if !ok || from.String() != "10.9.0.1:7" || string(payload) != "echo:ping" {
		t.Fatalf("udp echo: %v %q", from, payload)
	}

	// Unreachable destination fails cleanly
	c2, r2 := socksHandshake(t, port)
	defer c2.Close()
	c2.Write([]byte{5, 1, 0, 1, 10, 9, 0, 1, 0, 9})
	if code, _ := readReply(t, r2); code == 0 {
		t.Fatal("expected failure for closed port")
	}
}

func TestParseConfigErrors(t *testing.T) {
	if _, err := parseConfig("[Interface]\nAddress = 10.0.0.2/32\n"); err == nil {
		t.Fatal("expected error for missing keys")
	}
	c, err := parseConfig("[Interface]\nPrivateKey = " + base64.StdEncoding.EncodeToString(make([]byte, 32)) +
		"\nAddress = 10.2.0.2/32, fd00::2/128\nDNS = 10.2.0.1, search.local\nMTU = 1280\n[Peer]\nPublicKey = " +
		base64.StdEncoding.EncodeToString(make([]byte, 32)) + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = vpn.example.com:51820\n")
	if err != nil {
		t.Fatal(err)
	}
	if len(c.addresses) != 2 || len(c.dns) != 1 || c.mtu != 1280 {
		t.Fatalf("parsed %+v", c)
	}
	if _, err := c.uapi(); err == nil || !strings.Contains(err.Error(), "IP:port") {
		t.Fatalf("hostname endpoint must be rejected (app resolves it first): %v", err)
	}
}
