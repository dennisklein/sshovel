// SPDX-FileCopyrightText: 2026 <Copyright holder>
// SPDX-License-Identifier: GPL-3.0-or-later

package mobile

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"

	"golang.org/x/crypto/ssh"
)

// DigestSigner mirrors Platform.SignDigest from ARCHITECTURE §8. On Android it
// is implemented in Kotlin with Signature.getInstance("NONEwithECDSA") on a
// Keystore key and returns an ASN.1 DER signature.
type DigestSigner interface {
	SignDigest(keyAlias string, digest []byte) ([]byte, error)
}

// platformSigner adapts DigestSigner to crypto.Signer so that
// ssh.NewSignerFromSigner can hash and re-encode the signature.
type platformSigner struct {
	pub   *ecdsa.PublicKey
	alias string
	ds    DigestSigner
}

func (s *platformSigner) Public() crypto.PublicKey { return s.pub }

func (s *platformSigner) Sign(_ io.Reader, digest []byte, _ crypto.SignerOpts) ([]byte, error) {
	return s.ds.SignDigest(s.alias, digest)
}

func newPlatformSSHSigner(pkix []byte, alias string, ds DigestSigner) (ssh.Signer, error) {
	pk, err := x509.ParsePKIXPublicKey(pkix)
	if err != nil {
		return nil, fmt.Errorf("parse PKIX public key: %w", err)
	}
	ec, ok := pk.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("public key is %T, want *ecdsa.PublicKey", pk)
	}
	return ssh.NewSignerFromSigner(&platformSigner{pub: ec, alias: alias, ds: ds})
}

// AuthorizedKeyLine renders the recommended authorized_keys line for a PKIX key.
func AuthorizedKeyLine(pkix []byte, comment string) (string, error) {
	pk, err := x509.ParsePKIXPublicKey(pkix)
	if err != nil {
		return "", err
	}
	sp, err := ssh.NewPublicKey(pk)
	if err != nil {
		return "", err
	}
	line := string(ssh.MarshalAuthorizedKey(sp))
	return "restrict,port-forwarding " + line[:len(line)-1] + " " + comment, nil
}

// TestAuth dials host:port, authenticates user with the platform-backed key,
// and, if probeTarget is non-empty, opens a direct-tcpip channel to it.
// It returns a one-line summary. Host keys are accepted blindly: spike only.
func TestAuth(host string, port int, user string, pkix []byte, alias string, ds DigestSigner, probeTarget string) (string, error) {
	signer, err := newPlatformSSHSigner(pkix, alias, ds)
	if err != nil {
		return "", err
	}
	var hostKey ssh.PublicKey
	cfg := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: func(_ string, _ net.Addr, k ssh.PublicKey) error {
			hostKey = k
			return nil
		},
		Timeout: 10 * time.Second,
	}
	c, err := ssh.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)), cfg)
	if err != nil {
		return "", fmt.Errorf("ssh: %w", err)
	}
	defer c.Close()
	msg := fmt.Sprintf("authenticated as %s with %s; server %s host key %s",
		user, signer.PublicKey().Type(), c.ServerVersion(), ssh.FingerprintSHA256(hostKey))
	if probeTarget == "" {
		return msg, nil
	}
	conn, err := c.Dial("tcp", probeTarget)
	if err != nil {
		var oce *ssh.OpenChannelError
		if errors.As(err, &oce) {
			return msg, fmt.Errorf("direct-tcpip %s: reason=%s msg=%q", probeTarget, oce.Reason, oce.Message)
		}
		return msg, fmt.Errorf("direct-tcpip %s: %w", probeTarget, err)
	}
	conn.Close()
	return msg + "; direct-tcpip to " + probeTarget + " OK", nil
}
