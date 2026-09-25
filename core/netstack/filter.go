// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package netstack

import (
	"sync/atomic"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/nested"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// filter sits between the TUN link and the stack. It drops everything the
// tunnel can't carry before gVisor sees it: non-IPv4 packets and ICMP. gVisor
// would otherwise answer pings for every address itself (the NIC is
// promiscuous), making unreachable hosts look alive.
type filter struct {
	nested.Endpoint
	droppedICMP  atomic.Uint64
	droppedOther atomic.Uint64
}

func newFilter(child stack.LinkEndpoint) *filter {
	f := &filter{}
	f.Endpoint.Init(child, f)
	return f
}

// DeliverNetworkPacket implements stack.NetworkDispatcher.
func (f *filter) DeliverNetworkPacket(proto tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) {
	if proto != header.IPv4ProtocolNumber {
		f.droppedOther.Add(1)
		return
	}
	if b, ok := pkt.Data().PullUp(header.IPv4MinimumSize); ok &&
		header.IPv4(b).Protocol() == uint8(header.ICMPv4ProtocolNumber) {
		f.droppedICMP.Add(1)
		return
	}
	f.Endpoint.DeliverNetworkPacket(proto, pkt)
}
