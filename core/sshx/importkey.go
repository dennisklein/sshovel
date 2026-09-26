// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/errcode"
)

// MinRSABits is the smallest RSA key ImportKey accepts (handoff
// err_unsupported_key: "RSA 2048+").
const MinRSABits = 2048

// ImportedKey is a private key normalized for storage in the Android vault:
// an unencrypted OpenSSH private key, plus what the Keys screen shows.
type ImportedKey struct {
	Key            []byte // OpenSSH PEM, no passphrase; the caller encrypts and zeroes it
	Type           string // e.g. ssh-ed25519
	Fingerprint    string // SHA256:…
	AuthorizedLine string // recommended authorized_keys line (ARCHITECTURE §6)
}

// ImportKey parses an OpenSSH or PEM private key, decrypting it with
// passphrase if it is encrypted, and re-encodes it without a passphrase: the
// tile can't ask for one (ARCHITECTURE §6, "Auth: imported key"). key and
// passphrase are zeroed before it returns. Errors carry KEY_PASSPHRASE,
// KEY_UNSUPPORTED, or KEY_PUTTY and never contain key material.
func ImportKey(key, passphrase []byte, comment string) (ImportedKey, error) {
	defer clear(key)
	defer clear(passphrase)
	if bytes.HasPrefix(bytes.TrimSpace(key), []byte("PuTTY-User-Key-File")) {
		return ImportedKey{}, errcode.New(errcode.KeyPutty, errors.New("PuTTY key"))
	}
	raw, err := ssh.ParseRawPrivateKey(key)
	var missing *ssh.PassphraseMissingError
	if errors.As(err, &missing) {
		if len(passphrase) == 0 {
			return ImportedKey{}, errcode.New(errcode.KeyPassphrase, errors.New("key is encrypted"))
		}
		raw, err = ssh.ParseRawPrivateKeyWithPassphrase(key, passphrase)
		if errors.Is(err, x509.IncorrectPasswordError) {
			return ImportedKey{}, errcode.New(errcode.KeyPassphrase, errors.New("wrong passphrase"))
		}
	}
	if err != nil {
		return ImportedKey{}, errcode.New(errcode.KeyUnsupported, err)
	}
	if err := checkKeyType(raw); err != nil {
		return ImportedKey{}, errcode.New(errcode.KeyUnsupported, err)
	}
	signer, err := ssh.NewSignerFromKey(raw)
	if err != nil {
		return ImportedKey{}, errcode.New(errcode.KeyUnsupported, err)
	}
	block, err := ssh.MarshalPrivateKey(raw, "")
	if err != nil {
		return ImportedKey{}, errcode.New(errcode.KeyUnsupported, err)
	}
	out := pem.EncodeToMemory(block)
	clear(block.Bytes)
	pub := signer.PublicKey()
	return ImportedKey{
		Key:            out,
		Type:           pub.Type(),
		Fingerprint:    ssh.FingerprintSHA256(pub),
		AuthorizedLine: authorizedLine(pub, comment),
	}, nil
}

func checkKeyType(raw any) error {
	switch k := raw.(type) {
	case ed25519.PrivateKey, *ed25519.PrivateKey, *ecdsa.PrivateKey:
		return nil
	case *rsa.PrivateKey:
		if n := k.N.BitLen(); n < MinRSABits {
			return fmt.Errorf("RSA key has %d bits, need %d or more", n, MinRSABits)
		}
		return nil
	default:
		return fmt.Errorf("unsupported key type %T", raw)
	}
}
