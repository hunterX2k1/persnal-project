# minivpn (vendored)

A copy of github.com/ooni/minivpn v0.0.7 (GPL-3.0, see LICENSE), used by bbwg's OpenVPN mode,
without its tests, examples and tools. One change:

- `internal/tlssession/tlshandshake.go`: the server's certificate is verified together with the
  intermediate certificates the server sends, as OpenVPN itself does. Without this, servers with
  a certificate from a public CA (VPN Gate uses Let's Encrypt, with ISRG Root X1 as `<ca>`) fail
  with "x509: certificate signed by unknown authority".
- `pkg/config/vpnoptions.go`: no "unsupported key in line N" warning for every option minivpn
  ignores (VPN Gate configs have dozens).
