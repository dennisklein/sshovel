// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package errcode defines the error and warning codes shared with the Kotlin
// app (ARCHITECTURE §8) and an error type that carries one.
package errcode

import "errors"

// Code is a stable identifier that the UI maps to copy (DESIGN_BRIEF §8).
type Code string

// Errors: the tunnel is not usable.
const (
	AuthFailed        Code = "AUTH_FAILED"
	HostUnreachable   Code = "HOST_UNREACHABLE"
	HostKeyUnverified Code = "HOST_KEY_UNVERIFIED"
	HostKeyMismatch   Code = "HOST_KEY_MISMATCH"
	NetworkLost       Code = "NETWORK_LOST"
	VPNRevoked        Code = "VPN_REVOKED"
	VPNPermission     Code = "VPN_PERMISSION"
	KeyUnavailable    Code = "KEY_UNAVAILABLE"
	Internal          Code = "INTERNAL"
)

// Warnings: the tunnel is up but something is degraded.
const (
	ForwardingDenied          Code = "FORWARDING_DENIED"
	DNSUnreachable            Code = "DNS_UNREACHABLE"
	RouteDiscoveryUnavailable Code = "ROUTE_DISCOVERY_UNAVAILABLE"
)

// Reasons attached to failed flows (diagnostics only, never a state code).
const (
	DestUnreachable Code = "DEST_UNREACHABLE"
	DestTimeout     Code = "DEST_TIMEOUT"
	TunnelDown      Code = "TUNNEL_DOWN"
)

// Error wraps an underlying error with a Code.
type Error struct {
	Code Code
	Err  error
}

func (e *Error) Error() string {
	if e.Err == nil {
		return string(e.Code)
	}
	return string(e.Code) + ": " + e.Err.Error()
}

func (e *Error) Unwrap() error { return e.Err }

// New wraps err with code.
func New(code Code, err error) error { return &Error{Code: code, Err: err} }

// Of returns the code carried by err, or Internal if there is none.
func Of(err error) Code {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return Internal
}

// Permanent reports whether an error with this code needs the user and must
// not be retried automatically (ARCHITECTURE §6, Reconnect).
func Permanent(c Code) bool {
	switch c {
	case AuthFailed, HostKeyUnverified, HostKeyMismatch, KeyUnavailable, VPNRevoked, VPNPermission, Internal:
		return true
	}
	return false
}
