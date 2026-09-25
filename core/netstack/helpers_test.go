// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package netstack_test

import (
	"net/netip"

	"gvisor.dev/gvisor/pkg/tcpip"
)

func tcpipAddr(a netip.Addr) tcpip.Address { return tcpip.AddrFrom4(a.As4()) }
