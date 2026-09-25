// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package netstack

import (
	"fmt"
	"sync"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// NewTUNLink wraps a TUN fd (from ParcelFileDescriptor.detachFd, or
// /dev/net/tun without IFF_PI) as a link endpoint. The endpoint owns fd and
// closes it when the stack is closed.
func NewTUNLink(fd int, mtu uint32) (stack.LinkEndpoint, error) {
	if err := unix.SetNonblock(fd, true); err != nil {
		unix.Close(fd)
		return nil, fmt.Errorf("tun: set nonblock: %w", err)
	}
	ep, err := fdbased.New(&fdbased.Options{
		FDs: []int{fd},
		MTU: mtu,
		// TUN fds aren't sockets: read with readv, and let WritePackets fall
		// back to writev.
		PacketDispatchMode:    fdbased.Readv,
		MaxSyscallHeaderBytes: 0,
	})
	if err != nil {
		unix.Close(fd)
		return nil, fmt.Errorf("tun: %w", err)
	}
	return &tunLink{LinkEndpoint: ep, fd: fd}, nil
}

type tunLink struct {
	stack.LinkEndpoint
	fd   int
	once sync.Once
}

// Close runs after the stack detached the endpoint (which stops and waits
// for the read loop), so no goroutine still uses fd.
func (t *tunLink) Close() {
	t.LinkEndpoint.Close()
	t.once.Do(func() { unix.Close(t.fd) })
}
