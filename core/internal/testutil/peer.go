// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package testutil

import (
	"context"
	"net"
	"net/netip"
	"testing"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// Peer is a second gVisor stack that plays the phone's apps. Its link is
// wired back to back with Link, which the code under test uses as its TUN.
type Peer struct {
	Stack *stack.Stack
	// Link is the engine's side of the wire; hand it to netstack/engine.
	Link *channel.Endpoint
	own  *channel.Endpoint
	Addr netip.Addr
}

// NewPeer creates a peer with address addr (e.g. the TUN address 10.99.0.1)
// and a default route into the wire.
func NewPeer(t testing.TB, addr netip.Addr, mtu uint32) *Peer {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	own := channel.New(1024, mtu, "")
	engine := channel.New(1024, mtu, "")
	if err := s.CreateNIC(1, own); err != nil {
		t.Fatal(err)
	}
	pa := tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4(addr.As4()).WithPrefix(),
	}
	if err := s.AddProtocolAddress(1, pa, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})

	ctx, cancel := context.WithCancel(context.Background())
	go pump(ctx, own, engine)
	go pump(ctx, engine, own)
	p := &Peer{Stack: s, Link: engine, own: own, Addr: addr}
	t.Cleanup(func() {
		cancel()
		s.Close()
		s.Wait()
	})
	return p
}

// pump moves packets written to from's outbound queue into to's inbound path.
func pump(ctx context.Context, from, to *channel.Endpoint) {
	for {
		pkt := from.ReadContext(ctx)
		if pkt == nil {
			return
		}
		v := pkt.ToView()
		pkt.DecRef()
		np := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithView(v)})
		to.InjectInbound(ipv4.ProtocolNumber, np)
		np.DecRef()
	}
}

// DialTCP connects from the peer ("an app") to dst.
func (p *Peer) DialTCP(ctx context.Context, dst netip.AddrPort) (*gonet.TCPConn, error) {
	return gonet.DialContextTCP(ctx, p.Stack, fullAddr(dst), ipv4.ProtocolNumber)
}

// DialUDP opens a connected UDP socket from the peer to dst.
func (p *Peer) DialUDP(dst netip.AddrPort) (*gonet.UDPConn, error) {
	ra := fullAddr(dst)
	return gonet.DialUDP(p.Stack, nil, &ra, ipv4.ProtocolNumber)
}

// InjectToEngine delivers a raw IPv4 packet to the engine's side.
func (p *Peer) InjectToEngine(raw []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(raw)})
	p.Link.InjectInbound(ipv4.ProtocolNumber, pkt)
	pkt.DecRef()
}

func fullAddr(ap netip.AddrPort) tcpip.FullAddress {
	return tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(ap.Addr().As4()), Port: ap.Port()}
}

// EchoServer listens on 127.0.0.1 and echoes until the client half-closes,
// then closes its write side.
func EchoServer(t testing.TB) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				buf := make([]byte, 64<<10)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						if _, werr := c.Write(buf[:n]); werr != nil {
							return
						}
					}
					if err != nil {
						_ = c.(*net.TCPConn).CloseWrite()
						return
					}
				}
			}()
		}
	}()
	return ln
}
