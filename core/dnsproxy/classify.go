// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package dnsproxy

import (
	"net/netip"
	"strconv"
	"strings"
)

// classifier decides whether a name is tunnel-bound (ARCHITECTURE §5).
type classifier struct {
	suffixes []string // lower case, FQDN, e.g. "corp.example."
	reverse  bool
	routes   []netip.Prefix
}

func newClassifier(suffixes []string, reverse bool, routes []netip.Prefix) *classifier {
	c := &classifier{reverse: reverse, routes: routes}
	for _, s := range suffixes {
		s = fqdn(s)
		if s != "." {
			c.suffixes = append(c.suffixes, s)
		}
	}
	return c
}

func fqdn(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	if !strings.HasSuffix(s, ".") {
		s += "."
	}
	return s
}

func (c *classifier) tunnelBound(name string) bool {
	name = fqdn(name)
	for _, s := range c.suffixes {
		if name == s || strings.HasSuffix(name, "."+s) {
			return true
		}
	}
	if c.reverse {
		if p, ok := reversePrefix(name); ok {
			for _, r := range c.routes {
				if r.Bits() <= p.Bits() && r.Contains(p.Addr()) {
					return true
				}
			}
		}
	}
	return false
}

// reversePrefix turns "5.0.77.10.in-addr.arpa." into 10.77.0.5/32 and a zone
// name such as "77.10.in-addr.arpa." into 10.77.0.0/16.
func reversePrefix(name string) (netip.Prefix, bool) {
	rest, ok := strings.CutSuffix(name, ".in-addr.arpa.")
	if !ok || rest == "" {
		return netip.Prefix{}, false
	}
	labels := strings.Split(rest, ".")
	if len(labels) > 4 {
		return netip.Prefix{}, false
	}
	var b [4]byte
	for i, l := range labels {
		n, err := strconv.Atoi(l)
		if err != nil || n < 0 || n > 255 || strconv.Itoa(n) != l {
			return netip.Prefix{}, false
		}
		b[len(labels)-1-i] = byte(n)
	}
	return netip.PrefixFrom(netip.AddrFrom4(b), 8*len(labels)), true
}

func (c *classifier) inRoutes(a netip.Addr) bool {
	for _, r := range c.routes {
		if r.Contains(a) {
			return true
		}
	}
	return false
}
