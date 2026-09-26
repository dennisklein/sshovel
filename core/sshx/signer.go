// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx

import (
	"crypto"
	"crypto/ecdsa"
	"errors"
	"io"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
)

// SignDigestFunc signs a SHA-256 digest with the Keystore key named alias and
// returns an ASN.1 DER ECDSA signature (Kotlin: NONEwithECDSA).
type SignDigestFunc func(alias string, digest []byte) ([]byte, error)

// platformSigner adapts SignDigestFunc to crypto.Signer so that
// ssh.NewSignerFromSigner hashes the data and converts DER to SSH wire format.
type platformSigner struct {
	pub   *ecdsa.PublicKey
	alias string
	sign  SignDigestFunc
}

func (s *platformSigner) Public() crypto.PublicKey { return s.pub }

func (s *platformSigner) Sign(_ io.Reader, digest []byte, _ crypto.SignerOpts) ([]byte, error) {
	sig, err := s.sign(s.alias, digest)
	if err != nil {
		return nil, errcode.New(errcode.KeyUnavailable, err)
	}
	return sig, nil
}

// NewPlatformSigner returns an SSH signer backed by a Keystore key.
func NewPlatformSigner(pkix []byte, alias string, sign SignDigestFunc) (ssh.Signer, error) {
	pub, err := config.ParsePKIX(pkix)
	if err != nil {
		return nil, errcode.New(errcode.KeyUnavailable, err)
	}
	return ssh.NewSignerFromSigner(&platformSigner{pub: pub, alias: alias, sign: sign})
}

// ErrNoImportedKey is returned when an imported-key profile is started
// without key bytes.
var ErrNoImportedKey = errors.New("imported key bytes missing")

// ParseImportedKey parses an unencrypted OpenSSH or PEM private key and
// zeroes key before returning, whatever the outcome (CLAUDE.md, Secrets).
func ParseImportedKey(key []byte) (ssh.Signer, error) {
	defer clear(key)
	if len(key) == 0 {
		return nil, errcode.New(errcode.KeyUnavailable, ErrNoImportedKey)
	}
	raw, err := ssh.ParseRawPrivateKey(key)
	if err != nil {
		// Never include key bytes; the parser's message doesn't contain them.
		return nil, errcode.New(errcode.KeyUnavailable, err)
	}
	s, err := ssh.NewSignerFromKey(raw)
	if err != nil {
		return nil, errcode.New(errcode.KeyUnavailable, err)
	}
	return s, nil
}

// SignerFor builds the signer for a profile: a Keystore-backed signer for
// "keystore" profiles, or a parsed imported key otherwise. importedKey is
// always zeroed.
func SignerFor(p *config.Profile, sign SignDigestFunc, importedKey []byte) (ssh.Signer, error) {
	if p.Auth.Kind == config.AuthImported {
		return ParseImportedKey(importedKey)
	}
	clear(importedKey)
	return NewPlatformSigner(p.Auth.PublicKeyPkix, p.Auth.Alias, sign)
}

// AuthorizedKeyLine renders the recommended authorized_keys line for a PKIX
// public key (ARCHITECTURE §6).
func AuthorizedKeyLine(pkix []byte, comment string) (string, error) {
	pub, err := config.ParsePKIX(pkix)
	if err != nil {
		return "", err
	}
	sp, err := ssh.NewPublicKey(pub)
	if err != nil {
		return "", err
	}
	return authorizedLine(sp, comment), nil
}

func authorizedLine(k ssh.PublicKey, comment string) string {
	line := string(ssh.MarshalAuthorizedKey(k))
	line = "restrict,port-forwarding " + line[:len(line)-1]
	if comment != "" {
		line += " " + comment
	}
	return line
}
