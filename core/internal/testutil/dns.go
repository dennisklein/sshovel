// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package testutil

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
)

// FakeDNS is a DNS-over-TCP server that answers pipelined queries
// concurrently and out of order, like a real resolver under load.
type FakeDNS struct {
	Addr string

	// A maps lower-case FQDNs to IPv4 answers. TXT maps names to TXT data
	// (use long strings to force truncation).
	A   map[string][]string
	TXT map[string][]string
	// Delay, if set, returns how long to wait before answering a name.
	Delay func(name string) time.Duration

	Queries     atomic.Int64
	Conns       atomic.Int64
	MaxInflight atomic.Int64 // highest number of queries in flight on one connection

	ln net.Listener
	mu sync.Mutex
	cs map[net.Conn]bool
	wg sync.WaitGroup
}

// NewFakeDNS starts a TCP DNS server on 127.0.0.1.
func NewFakeDNS(t testing.TB) *FakeDNS {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	d := &FakeDNS{Addr: ln.Addr().String(), A: map[string][]string{}, TXT: map[string][]string{},
		ln: ln, cs: map[net.Conn]bool{}}
	d.wg.Add(1)
	go d.serve()
	t.Cleanup(d.Close)
	return d
}

func (d *FakeDNS) Close() {
	d.ln.Close()
	d.KillConnections()
	d.wg.Wait()
}

// KillConnections drops all client connections.
func (d *FakeDNS) KillConnections() {
	d.mu.Lock()
	defer d.mu.Unlock()
	for c := range d.cs {
		c.Close()
	}
}

func (d *FakeDNS) serve() {
	defer d.wg.Done()
	for {
		c, err := d.ln.Accept()
		if err != nil {
			return
		}
		d.Conns.Add(1)
		d.mu.Lock()
		d.cs[c] = true
		d.mu.Unlock()
		d.wg.Add(1)
		go func() {
			defer d.wg.Done()
			d.conn(c)
			d.mu.Lock()
			delete(d.cs, c)
			d.mu.Unlock()
			c.Close()
		}()
	}
}

func (d *FakeDNS) conn(c net.Conn) {
	var wmu sync.Mutex
	var inflight atomic.Int64
	var wg sync.WaitGroup
	defer wg.Wait()
	var hdr [2]byte
	for {
		if _, err := io.ReadFull(c, hdr[:]); err != nil {
			return
		}
		buf := make([]byte, binary.BigEndian.Uint16(hdr[:]))
		if _, err := io.ReadFull(c, buf); err != nil {
			return
		}
		d.Queries.Add(1)
		n := inflight.Add(1)
		for {
			m := d.MaxInflight.Load()
			if n <= m || d.MaxInflight.CompareAndSwap(m, n) {
				break
			}
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer inflight.Add(-1)
			out := d.answer(buf)
			if out == nil {
				return
			}
			b := make([]byte, 2+len(out))
			binary.BigEndian.PutUint16(b, uint16(len(out)))
			copy(b[2:], out)
			wmu.Lock()
			_, _ = c.Write(b)
			wmu.Unlock()
		}()
	}
}

func (d *FakeDNS) answer(raw []byte) []byte {
	q := new(dns.Msg)
	if q.Unpack(raw) != nil || len(q.Question) == 0 {
		return nil
	}
	qn := q.Question[0]
	name := strings.ToLower(qn.Name)
	if d.Delay != nil {
		time.Sleep(d.Delay(name))
	}
	r := new(dns.Msg)
	r.SetReply(q)
	r.Authoritative = true
	switch qn.Qtype {
	case dns.TypeA:
		for _, ip := range d.A[name] {
			rr, _ := dns.NewRR(qn.Name + " 60 IN A " + ip)
			r.Answer = append(r.Answer, rr)
		}
	case dns.TypeTXT:
		for _, s := range d.TXT[name] {
			r.Answer = append(r.Answer, &dns.TXT{
				Hdr: dns.RR_Header{Name: qn.Name, Rrtype: dns.TypeTXT, Class: dns.ClassINET, Ttl: 60},
				Txt: splitTXT(s)})
		}
	case dns.TypeAAAA:
		if _, ok := d.A[name]; ok {
			rr, _ := dns.NewRR(qn.Name + " 60 IN AAAA fd00::1")
			r.Answer = append(r.Answer, rr)
		}
	case dns.TypePTR:
		rr, _ := dns.NewRR(qn.Name + " 60 IN PTR host.corp.test.")
		r.Answer = append(r.Answer, rr)
	}
	if len(r.Answer) == 0 && d.A[name] == nil && d.TXT[name] == nil && qn.Qtype != dns.TypePTR {
		r.Rcode = dns.RcodeNameError
	}
	out, _ := r.Pack()
	return out
}

func splitTXT(s string) []string {
	var out []string
	for len(s) > 255 {
		out = append(out, s[:255])
		s = s[255:]
	}
	return append(out, s)
}
