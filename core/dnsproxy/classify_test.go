// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package dnsproxy

import (
	"net/netip"
	"testing"
)

func TestClassifier(t *testing.T) {
	c := newClassifier([]string{"corp.example", "Internal.", " lab.test "}, true,
		[]netip.Prefix{netip.MustParsePrefix("10.77.0.0/24"), netip.MustParsePrefix("172.16.0.0/12")})
	cases := map[string]bool{
		"corp.example.":             true,
		"wiki.corp.example.":        true,
		"WIKI.Corp.Example":         true,
		"deep.a.b.corp.example.":    true,
		"notcorp.example.":          false,
		"corp.example.com.":         false,
		"internal.":                 true,
		"host.internal":             true,
		"x.lab.test.":               true,
		"example.":                  false,
		"www.google.com.":           false,
		"5.0.77.10.in-addr.arpa.":   true,  // 10.77.0.5 is routed
		"5.1.77.10.in-addr.arpa.":   false, // 10.77.1.5 is not
		"0.77.10.in-addr.arpa.":     true,  // the /24 zone itself
		"77.10.in-addr.arpa.":       false, // /16 zone is wider than the route
		"20.16.172.in-addr.arpa.":   true,  // 172.16.20.0/24 inside 172.16/12
		"1.1.168.192.in-addr.arpa.": false,
		"x.0.77.10.in-addr.arpa.":   false, // malformed
		"05.0.77.10.in-addr.arpa.":  false, // non-canonical label
		"1.2.3.4.5.in-addr.arpa.":   false,
		"4.3.2.1.ip6.arpa.":         false,
	}
	for name, want := range cases {
		if got := c.tunnelBound(name); got != want {
			t.Errorf("tunnelBound(%q) = %v, want %v", name, got, want)
		}
	}
	off := newClassifier(nil, false, []netip.Prefix{netip.MustParsePrefix("10.77.0.0/24")})
	if off.tunnelBound("5.0.77.10.in-addr.arpa.") {
		t.Error("reverse lookups disabled but PTR classified as tunnel-bound")
	}
}
