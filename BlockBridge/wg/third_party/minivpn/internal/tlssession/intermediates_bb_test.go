package tlssession

// BlockBridge: VPN Gate servers present a Let's Encrypt leaf plus its intermediate, and their
// configs pin only the root (ISRG Root X1). Verification must use the sent intermediate.

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"testing"
	"time"
)

func mkCert(t *testing.T, cn string, isCA bool, parent *x509.Certificate, parentKey *ecdsa.PrivateKey) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: cn},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  isCA,
		BasicConstraintsValid: true,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if isCA {
		tmpl.KeyUsage = x509.KeyUsageCertSign
	}
	if parent == nil {
		parent, parentKey = tmpl, key
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, &key.PublicKey, parentKey)
	if err != nil {
		t.Fatal(err)
	}
	c, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return c, key
}

func TestVerifyUsesServerIntermediates(t *testing.T) {
	root, rootKey := mkCert(t, "Test Root X1", true, nil, nil)
	inter, interKey := mkCert(t, "Test R11", true, root, rootKey)
	leaf, _ := mkCert(t, "vpn.example", false, inter, interKey)

	pool := x509.NewCertPool()
	pool.AddCert(root)
	verify := customVerifyFactory(&certConfig{ca: pool})

	if err := verify([][]byte{leaf.Raw, inter.Raw}, nil); err != nil {
		t.Fatalf("leaf + intermediate should verify against the root: %v", err)
	}
	// Without the intermediate there's no path to the root.
	if err := verify([][]byte{leaf.Raw}, nil); !errors.Is(err, ErrCannotVerifyCertChain) {
		t.Fatalf("leaf alone must fail, got %v", err)
	}
	// A chain from a different root must still be rejected.
	other, otherKey := mkCert(t, "Other Root", true, nil, nil)
	otherInter, otherInterKey := mkCert(t, "Other R1", true, other, otherKey)
	bad, _ := mkCert(t, "evil.example", false, otherInter, otherInterKey)
	if err := verify([][]byte{bad.Raw, otherInter.Raw}, nil); !errors.Is(err, ErrCannotVerifyCertChain) {
		t.Fatalf("foreign chain must fail, got %v", err)
	}
}
