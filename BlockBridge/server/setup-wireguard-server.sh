#!/usr/bin/env bash
# Turns a fresh Ubuntu/Debian server (e.g. Oracle Cloud "Always Free") into a WireGuard
# VPN server for BlockBridge, and prints the config to paste into the app's VPN tab.
#
#   curl -O https://raw.githubusercontent.com/<you>/<repo>/<branch>/BlockBridge/server/setup-wireguard-server.sh
#   sudo bash setup-wireguard-server.sh     # run it again to add another phone
#
# Also open UDP port 51820 in your cloud provider's firewall
# (Oracle: Networking › Virtual Cloud Networks › your VCN › Security Lists › Add Ingress Rule:
#  source 0.0.0.0/0, IP protocol UDP, destination port 51820).
set -euo pipefail

PORT=51820
NET=10.66.66
WG=/etc/wireguard

[ "$(id -u)" -eq 0 ] || { echo "Run with sudo."; exit 1; }

if [ ! -f "$WG/wg0.conf" ]; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq wireguard iptables qrencode curl >/dev/null

    umask 077
    mkdir -p "$WG"
    wg genkey | tee "$WG/server.key" | wg pubkey > "$WG/server.pub"
    IFACE=$(ip -4 route show default | awk '{print $5; exit}')

    cat > "$WG/wg0.conf" <<CONF
[Interface]
Address = $NET.1/24
ListenPort = $PORT
PrivateKey = $(cat "$WG/server.key")
PostUp = iptables -I INPUT -p udp --dport $PORT -j ACCEPT; iptables -I FORWARD -i wg0 -j ACCEPT; iptables -I FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o $IFACE -j MASQUERADE
PostDown = iptables -D INPUT -p udp --dport $PORT -j ACCEPT; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o $IFACE -j MASQUERADE
CONF

    echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf
    sysctl -q -p /etc/sysctl.d/99-wireguard.conf
    systemctl enable -q --now wg-quick@wg0
    echo "WireGuard server is running."
fi

# Add a client (phone)
N=$(( $(grep -c '^\[Peer\]' "$WG/wg0.conf" || true) + 2 ))
[ "$N" -lt 255 ] || { echo "Too many clients."; exit 1; }
CKEY=$(wg genkey); CPUB=$(echo "$CKEY" | wg pubkey); PSK=$(wg genpsk)
cat >> "$WG/wg0.conf" <<CONF

[Peer]
PublicKey = $CPUB
PresharedKey = $PSK
AllowedIPs = $NET.$N/32
CONF
wg syncconf wg0 <(wg-quick strip wg0)

PUBLIC_IP=$(curl -4 -fsS https://api.ipify.org || curl -4 -fsS https://ifconfig.me)
CLIENT=$(cat <<CONF
[Interface]
PrivateKey = $CKEY
Address = $NET.$N/32
DNS = 1.1.1.1, 1.0.0.1

[Peer]
PublicKey = $(cat "$WG/server.pub")
PresharedKey = $PSK
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = $PUBLIC_IP:$PORT
PersistentKeepalive = 25
CONF
)
echo
echo "===== Paste this into BlockBridge › VPN › Paste config ====="
echo "$CLIENT"
echo "============================================================="
qrencode -t ansiutf8 <<< "$CLIENT" || true
