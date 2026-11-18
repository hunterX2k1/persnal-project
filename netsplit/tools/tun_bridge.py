#!/usr/bin/env python3
"""Test helper (Linux, root): creates a TUN interface and relays its packets to a TCP
socket as 2-byte length-prefixed frames, so the JVM engine can be tested against the
real kernel TCP/IP stack.  usage: tun_bridge.py <ifname> <bridge_port>"""
import fcntl, os, select, socket, struct, sys

ifname, port = sys.argv[1], int(sys.argv[2])
TUNSETIFF, IFF_TUN, IFF_NO_PI = 0x400454CA, 0x0001, 0x1000
fd = os.open("/dev/net/tun", os.O_RDWR)
fcntl.ioctl(fd, TUNSETIFF, struct.pack("16sH", ifname.encode(), IFF_TUN | IFF_NO_PI))

def sin(a):
    return struct.pack("16sH2s4s8x", ifname.encode(), socket.AF_INET, b"\0\0", socket.inet_aton(a))

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
fcntl.ioctl(s, 0x8916, sin("198.18.0.2"))       # SIOCSIFADDR
fcntl.ioctl(s, 0x891C, sin("255.254.0.0"))      # SIOCSIFNETMASK  (198.18.0.0/15 via tun)
fcntl.ioctl(s, 0x8922, struct.pack("16si", ifname.encode(), 1500))  # SIOCSIFMTU
fcntl.ioctl(s, 0x8914, struct.pack("16sH", ifname.encode(), 0x1 | 0x40))  # SIOCSIFFLAGS UP|RUNNING

# IPv6: fd00:198::2/64 on the tun (best effort; skipped if IPv6 is disabled).
try:
    s6 = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
    req = struct.pack("16sIi", socket.inet_pton(socket.AF_INET6, "fd00:198::2"), 64, socket.if_nametoindex(ifname))
    fcntl.ioctl(s6, 0x8916, req)  # SIOCSIFADDR
except OSError as e:
    print("ipv6 setup failed:", e, file=sys.stderr)

c = socket.create_connection(("127.0.0.1", port))
c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
print("ready", flush=True)
buf = bytearray()
while True:
    r, _, _ = select.select([fd, c], [], [])
    if fd in r:
        p = os.read(fd, 65535)
        c.sendall(struct.pack(">H", len(p)) + p)
    if c in r:
        d = c.recv(262144)
        if not d:
            break
        buf += d
        i = 0
        while len(buf) - i >= 2:
            n = (buf[i] << 8) | buf[i + 1]
            if len(buf) - i < 2 + n:
                break
            os.write(fd, bytes(buf[i + 2:i + 2 + n]))
            i += 2 + n
        del buf[:i]
