// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx

import (
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

// HostKeyError is the cause of HOST_KEY_UNVERIFIED and HOST_KEY_MISMATCH. It
// carries the key the server presented, so the mismatch screen can show the
// pinned and received fingerprints side by side (DESIGN_BRIEF §5.5).
type HostKeyError struct {
	Received HostKeyInfo
	pinned   bool
}

func (e *HostKeyError) Error() string {
	if !e.pinned {
		return "no pinned host key; server presented " + e.Received.Type + " " + e.Received.Fingerprint
	}
	return "host key does not match the pinned key; server presented " + e.Received.Type + " " + e.Received.Fingerprint
}

// pinnedCallback enforces the profile's pin. There is deliberately no way to
// accept a changed key here (CLAUDE.md, Host keys).
func pinnedCallback(pin *config.HostKey, seen func(ssh.PublicKey)) ssh.HostKeyCallback {
	return func(_ string, _ net.Addr, k ssh.PublicKey) error {
		if seen != nil {
			seen(k)
		}
		if pin == nil || pin.Fingerprint == "" {
			return errcode.New(errcode.HostKeyUnverified, &HostKeyError{Received: infoOf(k)})
		}
		if k.Type() != pin.Type || ssh.FingerprintSHA256(k) != pin.Fingerprint {
			return errcode.New(errcode.HostKeyMismatch, &HostKeyError{Received: infoOf(k), pinned: true})
		}
		return nil
	}
}
