// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package config

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/x509"
	"fmt"
)

// ParsePKIX parses a Keystore public key. Only ECDSA P-256 is supported
// (ARCHITECTURE §6).
func ParsePKIX(der []byte) (*ecdsa.PublicKey, error) {
	pk, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("parse PKIX public key: %w", err)
	}
	ec, ok := pk.(*ecdsa.PublicKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, fmt.Errorf("public key is %T, want ECDSA P-256", pk)
	}
	return ec, nil
}
