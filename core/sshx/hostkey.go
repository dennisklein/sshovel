// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx

import (
	"errors"
	"net"

	"golang.org/x/crypto/ssh"

	"github.com/dennisklein/sshovel/core/config"
	"github.com/dennisklein/sshovel/core/errcode"
)

// HostKeyInfo describes a server host key for the verification UI.
type HostKeyInfo struct {
	Type        string `json:"type"`
	Fingerprint string `json:"fingerprint"`
	Line        string `json:"line"` // known_hosts-style "type base64"
}

func infoOf(k ssh.PublicKey) HostKeyInfo {
	line := string(ssh.MarshalAuthorizedKey(k))
	return HostKeyInfo{Type: k.Type(), Fingerprint: ssh.FingerprintSHA256(k), Line: line[:len(line)-1]}
}

var (
	errNotPinned = errors.New("no pinned host key")
	errMismatch  = errors.New("host key does not match the pinned key")
)

// pinnedCallback enforces the profile's pin. There is deliberately no way to
// accept a changed key here (CLAUDE.md, Host keys).
func pinnedCallback(pin *config.HostKey, seen func(ssh.PublicKey)) ssh.HostKeyCallback {
	return func(_ string, _ net.Addr, k ssh.PublicKey) error {
		if seen != nil {
			seen(k)
		}
		if pin == nil || pin.Fingerprint == "" {
			return errcode.New(errcode.HostKeyUnverified, errNotPinned)
		}
		if k.Type() != pin.Type || ssh.FingerprintSHA256(k) != pin.Fingerprint {
			return errcode.New(errcode.HostKeyMismatch, errMismatch)
		}
		return nil
	}
}
