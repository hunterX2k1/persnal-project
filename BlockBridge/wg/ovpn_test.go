package main

import "testing"

func TestIPPacketLen(t *testing.T) {
	v4 := make([]byte, 60)
	v4[0] = 0x45
	v4[2], v4[3] = 0, 60
	if n := ipPacketLen(v4); n != 60 {
		t.Fatalf("v4 len %d", n)
	}
	if n := ipPacketLen(v4[:3]); n != 0 {
		t.Fatalf("need more bytes, got %d", n)
	}
	v6 := make([]byte, 48)
	v6[0] = 0x60
	v6[4], v6[5] = 0, 8
	if n := ipPacketLen(v6); n != 48 {
		t.Fatalf("v6 len %d", n)
	}
	if n := ipPacketLen([]byte{0x00, 1, 2, 3}); n != -1 {
		t.Fatalf("garbage should be -1, got %d", n)
	}
	// two packets glued together split cleanly
	both := append(append([]byte{}, v4...), v6...)
	if a := ipPacketLen(both); a != 60 || ipPacketLen(both[a:]) != 48 {
		t.Fatal("split failed")
	}
}
