// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package dnsproxy_test

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"

	"github.com/dennisklein/sshovel/core/dnsproxy"
	"github.com/dennisklein/sshovel/core/internal/testutil"
)

var server = netip.MustParseAddr("10.77.0.53")

type fixture struct {
	p        *dnsproxy.Proxy
	dns      *testutil.FakeDNS
	tunnelUp atomic.Bool
	upstream atomic.Int64
	events   chan dnsproxy.Event
	health   chan bool
}

func newFixture(t *testing.T, hideAAAA bool) *fixture {
	t.Helper()
	f := &fixture{dns: testutil.NewFakeDNS(t), events: make(chan dnsproxy.Event, 1000), health: make(chan bool, 10)}
	f.tunnelUp.Store(true)
	f.dns.A["wiki.corp.test."] = []string{"10.77.0.20"}
	f.dns.A["cdn.corp.test."] = []string{"203.0.113.9"} // outside routes
	f.dns.TXT["big.corp.test."] = []string{strings.Repeat("a", 1500)}
	tunnel := func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		if dst != netip.AddrPortFrom(server, 53) {
			t.Errorf("tunnel dial to %s", dst)
		}
		if !f.tunnelUp.Load() {
			return nil, errors.New("tunnel is reconnecting")
		}
		var d net.Dialer
		return d.DialContext(ctx, "tcp", f.dns.Addr)
	}
	upstream := func(_ context.Context, q []byte) ([]byte, error) {
		f.upstream.Add(1)
		m := new(dns.Msg)
		if err := m.Unpack(q); err != nil {
			return nil, err
		}
		r := new(dns.Msg)
		r.SetReply(m)
		if m.Question[0].Qtype == dns.TypeA {
			rr, _ := dns.NewRR(m.Question[0].Name + " 60 IN A 93.184.215.14")
			r.Answer = append(r.Answer, rr)
		}
		return r.Pack()
	}
	f.p = dnsproxy.New(dnsproxy.Config{
		Server: server, Suffixes: []string{"corp.test"}, ReverseLookups: true,
		Routes: []netip.Prefix{netip.MustParsePrefix("10.77.0.0/24")}, HideAAAA: hideAAAA,
		QueryTimeout: 2 * time.Second,
	}, tunnel, upstream)
	f.p.OnEvent = func(e dnsproxy.Event) { f.events <- e }
	f.p.OnTunnelHealth = func(ok bool) { f.health <- ok }
	t.Cleanup(f.p.Close)
	return f
}

func query(t *testing.T, name string, qtype uint16, id uint16, edns uint16) []byte {
	t.Helper()
	m := new(dns.Msg)
	m.SetQuestion(name, qtype)
	m.Id = id
	if edns > 0 {
		m.SetEdns0(edns, false)
	}
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func unpack(t *testing.T, b []byte) *dns.Msg {
	t.Helper()
	if b == nil {
		t.Fatal("no reply")
	}
	m := new(dns.Msg)
	if err := m.Unpack(b); err != nil {
		t.Fatal(err)
	}
	return m
}

func (f *fixture) event(t *testing.T) dnsproxy.Event {
	t.Helper()
	select {
	case e := <-f.events:
		return e
	case <-time.After(3 * time.Second):
		t.Fatal("no DNS event")
		return dnsproxy.Event{}
	}
}

func TestRouting(t *testing.T) {
	f := newFixture(t, true)
	ctx := context.Background()

	r := unpack(t, f.p.HandleUDP(ctx, query(t, "wiki.corp.test.", dns.TypeA, 7, 0)))
	if r.Id != 7 || len(r.Answer) != 1 || r.Answer[0].(*dns.A).A.String() != "10.77.0.20" {
		t.Fatalf("intranet answer %v", r)
	}
	if e := f.event(t); e.Route != dnsproxy.RouteTunnel || e.Rcode != "NOERROR" || e.Answers[0] != "10.77.0.20" || e.ResolvedOutsideRoutes {
		t.Errorf("event %+v", e)
	}

	r = unpack(t, f.p.HandleUDP(ctx, query(t, "example.com.", dns.TypeA, 8, 0)))
	if r.Id != 8 || r.Answer[0].(*dns.A).A.String() != "93.184.215.14" {
		t.Fatalf("direct answer %v", r)
	}
	if e := f.event(t); e.Route != dnsproxy.RouteDirect {
		t.Errorf("event %+v", e)
	}

	// PTR for a routed address goes through the tunnel.
	before := f.dns.Queries.Load()
	unpack(t, f.p.HandleUDP(ctx, query(t, "20.0.77.10.in-addr.arpa.", dns.TypePTR, 9, 0)))
	if f.dns.Queries.Load() != before+1 {
		t.Error("reverse lookup didn't use the tunnel")
	}
	if e := f.event(t); e.Route != dnsproxy.RouteTunnel {
		t.Errorf("event %+v", e)
	}
	if tun, dir := f.p.Counts(); tun != 2 || dir != 1 {
		t.Errorf("counts %d/%d", tun, dir)
	}
}

func TestResolvedOutsideRoutes(t *testing.T) {
	f := newFixture(t, true)
	unpack(t, f.p.HandleUDP(context.Background(), query(t, "cdn.corp.test.", dns.TypeA, 1, 0)))
	if e := f.event(t); !e.ResolvedOutsideRoutes {
		t.Errorf("event %+v, want resolvedOutsideRoutes", e)
	}
}

func TestHideAAAA(t *testing.T) {
	f := newFixture(t, true)
	before := f.dns.Queries.Load()
	r := unpack(t, f.p.HandleUDP(context.Background(), query(t, "wiki.corp.test.", dns.TypeAAAA, 3, 0)))
	if r.Rcode != dns.RcodeSuccess || len(r.Answer) != 0 || r.Id != 3 {
		t.Errorf("want NODATA, got %v", r)
	}
	if f.dns.Queries.Load() != before {
		t.Error("AAAA query was sent to the intranet resolver")
	}
	// Direct AAAA queries are not touched.
	ups := f.upstream.Load()
	f.p.HandleUDP(context.Background(), query(t, "example.com.", dns.TypeAAAA, 4, 0))
	if f.upstream.Load() != ups+1 {
		t.Error("direct AAAA not forwarded upstream")
	}

	g := newFixture(t, false)
	r = unpack(t, g.p.HandleUDP(context.Background(), query(t, "wiki.corp.test.", dns.TypeAAAA, 3, 0)))
	if len(r.Answer) != 1 {
		t.Errorf("hideAAAA off: %v", r)
	}
}

func TestServfailWhenTunnelDown(t *testing.T) {
	f := newFixture(t, true)
	f.tunnelUp.Store(false)
	start := time.Now()
	r := unpack(t, f.p.HandleUDP(context.Background(), query(t, "wiki.corp.test.", dns.TypeA, 5, 0)))
	if r.Rcode != dns.RcodeServerFailure || r.Id != 5 {
		t.Errorf("got %v", r)
	}
	if d := time.Since(start); d > time.Second {
		t.Errorf("SERVFAIL took %s", d)
	}
	if ok := <-f.health; ok {
		t.Error("health reported ok")
	}
	if e := f.event(t); e.Rcode != "SERVFAIL" || e.Error == "" {
		t.Errorf("event %+v", e)
	}
	// Recovery flips health back.
	f.tunnelUp.Store(true)
	unpack(t, f.p.HandleUDP(context.Background(), query(t, "wiki.corp.test.", dns.TypeA, 6, 0)))
	if ok := <-f.health; !ok {
		t.Error("health not restored")
	}
}

func TestResolverTimeout(t *testing.T) {
	f := newFixture(t, true)
	f.dns.Delay = func(string) time.Duration { return 5 * time.Second }
	r := unpack(t, f.p.HandleUDP(context.Background(), query(t, "wiki.corp.test.", dns.TypeA, 5, 0)))
	if r.Rcode != dns.RcodeServerFailure {
		t.Errorf("got %v", r)
	}
}

func TestChannelErrorRetry(t *testing.T) {
	f := newFixture(t, true)
	ctx := context.Background()
	unpack(t, f.p.HandleUDP(ctx, query(t, "wiki.corp.test.", dns.TypeA, 1, 0)))
	f.dns.KillConnections()
	time.Sleep(50 * time.Millisecond)
	r := unpack(t, f.p.HandleUDP(ctx, query(t, "wiki.corp.test.", dns.TypeA, 2, 0)))
	if r.Rcode != dns.RcodeSuccess || len(r.Answer) != 1 {
		t.Fatalf("after channel loss: %v", r)
	}
	if f.dns.Conns.Load() < 2 {
		t.Error("channel was not reopened")
	}
}

func TestTruncation(t *testing.T) {
	f := newFixture(t, true)
	ctx := context.Background()

	r := unpack(t, f.p.HandleUDP(ctx, query(t, "big.corp.test.", dns.TypeTXT, 1, 0)))
	if !r.Truncated {
		t.Error("TC not set on 512-byte client")
	}
	if b := f.p.HandleUDP(ctx, query(t, "big.corp.test.", dns.TypeTXT, 1, 0)); len(b) > 512 {
		t.Errorf("UDP reply %d bytes > 512", len(b))
	}

	r = unpack(t, f.p.HandleUDP(ctx, query(t, "big.corp.test.", dns.TypeTXT, 1, 4096)))
	if r.Truncated || len(r.Answer) != 1 {
		t.Errorf("EDNS0 4096 reply truncated: %v", r.Truncated)
	}

	// The client retries over TCP and gets the full answer.
	a, b := net.Pipe()
	go f.p.ServeTCP(ctx, b)
	defer a.Close()
	q := query(t, "big.corp.test.", dns.TypeTXT, 77, 0)
	msg := binary.BigEndian.AppendUint16(nil, uint16(len(q)))
	if _, err := a.Write(append(msg, q...)); err != nil {
		t.Fatal(err)
	}
	r = readTCP(t, a)
	if r.Truncated || r.Id != 77 || len(strings.Join(r.Answer[0].(*dns.TXT).Txt, "")) != 1500 {
		t.Errorf("TCP reply %v", r)
	}
}

func readTCP(t *testing.T, c net.Conn) *dns.Msg {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, binary.BigEndian.Uint16(hdr[:]))
	if _, err := io.ReadFull(c, b); err != nil {
		t.Fatal(err)
	}
	return unpack(t, b)
}

// TestPipelining sends many concurrent queries that all carry the same client
// ID. The resolver answers out of order; every client must still get the
// answer to its own question, with its own ID, over at most two channels.
func TestPipelining(t *testing.T) {
	f := newFixture(t, true)
	const n = 300
	for i := range n {
		f.dns.A[fmt.Sprintf("h%d.corp.test.", i)] = []string{fmt.Sprintf("10.77.0.%d", i%250+1)}
	}
	f.dns.Delay = func(string) time.Duration { return time.Duration(rand.IntN(20)) * time.Millisecond }
	go func() {
		for range f.events {
		}
	}()
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			name := fmt.Sprintf("h%d.corp.test.", i)
			b := f.p.HandleUDP(context.Background(), query(t, name, dns.TypeA, 4242, 0))
			m := new(dns.Msg)
			if err := m.Unpack(b); err != nil {
				errs <- err
				return
			}
			want := fmt.Sprintf("10.77.0.%d", i%250+1)
			if m.Id != 4242 || len(m.Answer) != 1 || m.Question[0].Name != name || m.Answer[0].(*dns.A).A.String() != want {
				errs <- fmt.Errorf("query %d: got %v", i, m)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if c := f.dns.Conns.Load(); c > 2 {
		t.Errorf("%d resolver connections, want ≤ 2", c)
	}
	if m := f.dns.MaxInflight.Load(); m < 2 {
		t.Errorf("max in flight per connection %d: not pipelined", m)
	}
}

func TestTCPPipelinedClient(t *testing.T) {
	// A client may send several queries on one TCP connection without
	// waiting; answers may come back in any order.
	f := newFixture(t, true)
	a, b := net.Pipe()
	go f.p.ServeTCP(context.Background(), b)
	defer a.Close()
	go func() {
		for _, id := range []uint16{1, 2, 3} {
			q := query(t, "wiki.corp.test.", dns.TypeA, id, 0)
			_, _ = a.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(q))), q...))
		}
	}()
	seen := map[uint16]bool{}
	for range 3 {
		seen[readTCP(t, a).Id] = true
	}
	if !seen[1] || !seen[2] || !seen[3] {
		t.Errorf("ids %v", seen)
	}
}

func TestMalformed(t *testing.T) {
	f := newFixture(t, true)
	if b := f.p.HandleUDP(context.Background(), []byte{1, 2, 3}); b != nil {
		t.Error("replied to a 3-byte packet")
	}
	junk := make([]byte, 20)
	junk[0], junk[1] = 0xab, 0xcd
	junk[5] = 5                  // QDCOUNT=5, but only 8 bytes follow the header
	junk[12], junk[13] = 63, 'x' // a label that runs past the end
	r := unpack(t, f.p.HandleUDP(context.Background(), junk))
	if r.Rcode != dns.RcodeFormatError || r.Id != 0xabcd {
		t.Errorf("got %v", r)
	}
}

func TestLookupIPv4(t *testing.T) {
	f := newFixture(t, true)
	up := func(ctx context.Context, q []byte) ([]byte, error) {
		return f.p.HandleUDP(ctx, q), nil
	}
	addrs, err := dnsproxy.LookupIPv4(context.Background(), up, "jump.example.org")
	if err != nil || len(addrs) != 1 || addrs[0].String() != "93.184.215.14" {
		t.Errorf("got %v, %v", addrs, err)
	}
}
