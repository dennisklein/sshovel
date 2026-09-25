// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package dnsproxy

import (
	"context"
	"fmt"
	"net/netip"

	"github.com/miekg/dns"
)

// LookupIPv4 resolves host's A records through upstream. The engine uses it
// for the jump host so the lookup never enters the VPN.
func LookupIPv4(ctx context.Context, upstream UpstreamFunc, host string) ([]netip.Addr, error) {
	q := new(dns.Msg)
	q.SetQuestion(dns.Fqdn(host), dns.TypeA)
	raw, err := q.Pack()
	if err != nil {
		return nil, err
	}
	resp, err := upstream(ctx, raw)
	if err != nil {
		return nil, err
	}
	r := new(dns.Msg)
	if err := r.Unpack(resp); err != nil {
		return nil, err
	}
	if r.Rcode != dns.RcodeSuccess {
		return nil, fmt.Errorf("lookup %s: %s", host, dns.RcodeToString[r.Rcode])
	}
	var out []netip.Addr
	for _, rr := range r.Answer {
		if a, ok := rr.(*dns.A); ok {
			if ip, ok := netip.AddrFromSlice(a.A.To4()); ok {
				out = append(out, ip)
			}
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("lookup %s: no IPv4 address", host)
	}
	return out, nil
}
