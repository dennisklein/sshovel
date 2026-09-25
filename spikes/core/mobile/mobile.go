// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

// Package mobile is the M0 spike 1 gomobile surface: it proves that a gVisor
// netstack (the @go branch) links into a gomobile AAR and runs on Android.
package mobile

import (
	"fmt"
	"runtime"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// Hello builds a gVisor stack on one end of a socketpair (standing in for the
// TUN fd), attaches a NIC with a default route, and reports what it built.
func Hello() (string, error) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_SEQPACKET, 0)
	if err != nil {
		return "", fmt.Errorf("socketpair: %w", err)
	}
	defer unix.Close(fds[1])

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	defer s.Destroy()

	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fds[0]}, MTU: 1500})
	if err != nil {
		unix.Close(fds[0])
		return "", fmt.Errorf("fdbased: %w", err)
	}
	const nic = 1
	if tcpErr := s.CreateNIC(nic, ep); tcpErr != nil {
		return "", fmt.Errorf("CreateNIC: %s", tcpErr)
	}
	s.SetPromiscuousMode(nic, true)
	s.SetSpoofing(nic, true)
	s.SetRouteTable([]tcpip.Route{{Destination: header4Any(), NIC: nic}})

	fwd := tcp.NewForwarder(s, 0, 1024, func(r *tcp.ForwarderRequest) { r.Complete(true) })
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)

	return fmt.Sprintf("gVisor netstack up: nic=%d routes=%d go=%s %s/%s",
		nic, len(s.GetRouteTable()), runtime.Version(), runtime.GOOS, runtime.GOARCH), nil
}

func header4Any() tcpip.Subnet {
	sn, _ := tcpip.NewSubnet(tcpip.AddrFrom4([4]byte{}), tcpip.MaskFromBytes([]byte{0, 0, 0, 0}))
	return sn
}
