module blockbridge/bbwg

go 1.24.7

require (
	github.com/ooni/minivpn v0.0.7
	golang.org/x/crypto v0.37.0
	golang.zx2c4.com/wireguard v0.0.0-20261024220952-b3bc704ef0ee
)

require (
	github.com/andybalholm/brotli v1.0.4 // indirect
	github.com/apex/log v1.9.0 // indirect
	github.com/google/btree v1.1.2 // indirect
	github.com/klauspost/compress v1.15.15 // indirect
	github.com/pkg/errors v0.9.1 // indirect
	github.com/refraction-networking/utls v1.3.1 // indirect
	golang.org/x/net v0.39.0 // indirect
	golang.org/x/sys v0.32.0 // indirect
	golang.org/x/time v0.7.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	gvisor.dev/gvisor v0.0.0-20250503011706-39ed1f5ac29c // indirect
)

replace github.com/ooni/minivpn => ./third_party/minivpn
