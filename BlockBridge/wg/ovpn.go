package main

// OpenVPN mode (VPN Gate and other OpenVPN servers): minivpn (OONI, GPL-3.0) runs the
// OpenVPN protocol in userspace; its IP packets are bridged into a gVisor netstack so the
// same SOCKS5 server as WireGuard mode can dial through the tunnel.

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"time"

	ovpnconfig "github.com/ooni/minivpn/pkg/config"
	"github.com/ooni/minivpn/pkg/tunnel"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// quietLogger keeps minivpn's chatter out of the app's log (warnings still show).
type quietLogger struct{}

func (quietLogger) Debug(m string)            { dbg("%s", m) }
func (quietLogger) Debugf(f string, v ...any) { dbg(f, v...) }
func (quietLogger) Info(m string)             { dbg("%s", m) }
func (quietLogger) Infof(f string, v ...any)  { dbg(f, v...) }

// BB_DEBUG=1 shows minivpn's full handshake log.
func dbg(f string, v ...any) {
	if os.Getenv("BB_DEBUG") != "" {
		fmt.Fprintf(os.Stderr, "openvpn: "+f+"\n", v...)
	}
}
func (quietLogger) Warn(m string)            { warn(m) }
func (quietLogger) Warnf(f string, v ...any) { warn(fmt.Sprintf(f, v...)) }

// ovpnAbort, while connecting, ends the attempt at once (instead of waiting for the timeout).
var ovpnAbort atomic.Pointer[func(error)]

func warn(m string) {
	fmt.Fprintln(os.Stderr, "openvpn:", m)
	// A failed TLS handshake won't fix itself (bad certificate, wrong key…): give up now and
	// report why, rather than "no response" after the full timeout.
	if i := strings.Index(m, "handshake failure"); i >= 0 {
		if abort := ovpnAbort.Load(); abort != nil {
			reason := strings.TrimPrefix(strings.TrimPrefix(m[i:], "handshake failure"), ":")
			(*abort)(errors.New(strings.TrimSpace(reason)))
		}
	}
}

// ovpnLink is a running OpenVPN tunnel; done is closed when it goes down.
type ovpnLink struct {
	tun  *tunnel.TUN
	done chan struct{}
}

// startOpenVPN connects with the .ovpn file at path (dialing the server with dialer) and
// opens the SOCKS5 listener on listen.
func startOpenVPN(path, listen string, dialer tunnel.SimpleDialer) (*ovpnLink, net.Listener, error) {
	opts, err := ovpnconfig.ReadConfigFile(path)
	if err != nil {
		return nil, nil, fmt.Errorf("bad OpenVPN config: %w", err)
	}
	if !opts.HasAuthInfo() {
		return nil, nil, errors.New("OpenVPN config has no certificate/key or username/password")
	}
	cfg := ovpnconfig.NewConfig(ovpnconfig.WithOpenVPNOptions(opts), ovpnconfig.WithLogger(quietLogger{}))

	base, abort := context.WithCancelCause(context.Background())
	ctx, cancel := context.WithTimeout(base, 40*time.Second)
	defer cancel()
	abortFn := func(err error) { abort(err) }
	ovpnAbort.Store(&abortFn)
	t, err := tunnel.Start(ctx, dialer, cfg)
	ovpnAbort.Store(nil)
	if err != nil {
		if cause := context.Cause(base); cause != nil && !errors.Is(cause, context.Canceled) {
			return nil, nil, fmt.Errorf("OpenVPN handshake failed: %v", cause)
		}
		return nil, nil, fmt.Errorf("OpenVPN connect: %w", err)
	}

	local, err := netip.ParseAddr(t.LocalAddr().String())
	if err != nil {
		t.Close()
		return nil, nil, fmt.Errorf("OpenVPN gave no usable address (%q)", t.LocalAddr().String())
	}
	mtu := 1400
	dns := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}
	tunDev, n, err := netstack.CreateNetTUN([]netip.Addr{local}, dns, mtu)
	if err != nil {
		t.Close()
		return nil, nil, err
	}
	tnet = n
	tunnelAddrs = []netip.Addr{local}

	link := &ovpnLink{tun: t, done: make(chan struct{})}
	closeOnce := make(chan struct{}, 1)
	down := func() {
		select {
		case closeOnce <- struct{}{}:
			close(link.done)
		default:
		}
	}

	// netstack -> OpenVPN
	go func() {
		defer down()
		bufs := [][]byte{make([]byte, 65535)}
		sizes := []int{0}
		for {
			count, err := tunDev.Read(bufs, sizes, 0)
			if err != nil {
				return
			}
			for i := 0; i < count; i++ {
				pkt := append([]byte(nil), bufs[i][:sizes[i]]...)
				if _, err := t.Write(pkt); err != nil {
					return
				}
			}
		}
	}()
	// OpenVPN -> netstack. minivpn's Read is a byte stream, so split it into IP packets.
	go func() {
		defer down()
		var pending []byte
		buf := make([]byte, 65535)
		for {
			k, err := t.Read(buf)
			if err != nil {
				return
			}
			pending = append(pending, buf[:k]...)
			for {
				size := ipPacketLen(pending)
				if size <= 0 || size > len(pending) {
					if size < 0 { // garbage: drop it rather than wedge the stream
						pending = pending[:0]
					}
					break
				}
				pkt := append([]byte(nil), pending[:size]...)
				pending = pending[size:]
				if _, err := tunDev.Write([][]byte{pkt}, 0); err != nil {
					return
				}
			}
		}
	}()

	ln, err := net.Listen("tcp", listen)
	if err != nil {
		t.Close()
		return nil, nil, err
	}
	return link, ln, nil
}

// ipPacketLen returns the full length of the IP packet at the start of b,
// 0 if more bytes are needed, or -1 if b doesn't start with an IP packet.
func ipPacketLen(b []byte) int {
	if len(b) < 1 {
		return 0
	}
	switch b[0] >> 4 {
	case 4:
		if len(b) < 4 {
			return 0
		}
		n := int(binary.BigEndian.Uint16(b[2:4]))
		if n < 20 {
			return -1
		}
		return n
	case 6:
		if len(b) < 6 {
			return 0
		}
		return 40 + int(binary.BigEndian.Uint16(b[4:6]))
	default:
		return -1
	}
}
