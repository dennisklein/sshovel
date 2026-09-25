// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package sshx_test

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/dennisklein/sshovel/core/errcode"
	"github.com/dennisklein/sshovel/core/internal/testutil"
	"github.com/dennisklein/sshovel/core/sshx"
)

const ipRouteOut = `default via 172.18.0.1 dev eth0
10.77.0.0/24 dev eth1 proto kernel scope link src 10.77.0.2
10.10.0.0/16 via 10.77.0.1 dev eth1 proto static metric 100
169.254.0.0/16 dev eth0 scope link metric 1000
172.18.0.0/16 dev eth0 proto kernel scope link src 172.18.0.3
192.0.2.7 via 10.77.0.1 dev eth1
unreachable 10.9.0.0/16
`

const netstatLinuxOut = `Kernel IP routing table
Destination     Gateway         Genmask         Flags   MSS Window  irtt Iface
0.0.0.0         172.18.0.1      0.0.0.0         UG        0 0          0 eth0
10.77.0.0       0.0.0.0         255.255.255.0   U         0 0          0 eth1
10.10.0.0       10.77.0.1       255.255.0.0     UG        0 0          0 eth1
172.18.0.0      0.0.0.0         255.255.0.0     U         0 0          0 eth0
`

const netstatBSDOut = `Routing tables

Internet:
Destination        Gateway            Flags     Netif Expire
default            192.168.1.1        UGS         em0
10/8               10.77.0.1          UGS         em1
10.77.0/24         link#2             U           em1
127.0.0.1          link#3             UH          lo0
192.168.1          link#1             U           em0

Internet6:
Destination        Gateway            Flags     Netif Expire
::/96              ::1                UGRS        lo0
`

// /proc/net/route on a little-endian server.
const procOut = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" +
	"eth0\t00000000\t010012AC\t0003\t0\t0\t0\t00000000\t0\t0\t0\n" +
	"eth1\t00004D0A\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n" +
	"eth1\t00000A0A\t01004D0A\t0003\t0\t0\t0\t0000FFFF\t0\t0\t0\n"

// The same table as printed by a big-endian server.
const procBEOut = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" +
	"eth1\t0A4D0000\t00000000\t0001\t0\t0\t0\tFFFFFF00\t0\t0\t0\n"

func fmtRoutes(rs []sshx.Route) string {
	s := ""
	for _, r := range rs {
		s += fmt.Sprintf("%s %s", r.CIDR, r.Dev)
		if r.IsDefault {
			s += " default"
		}
		if r.IsLinkLocal {
			s += " linklocal"
		}
		s += "\n"
	}
	return s
}

func discover(t *testing.T, exec map[string]string, denyExec bool) ([]sshx.Route, error) {
	t.Helper()
	ks, pkix, pub := newKeystore(t)
	signer, _ := sshx.NewPlatformSigner(pkix, "sshovel-key-1", ks.sign)
	srv := testutil.NewSSHServer(t, pub)
	srv.Exec = exec
	srv.DenyExec.Store(denyExec)
	c, err := sshx.Dial(context.Background(), opts(srv, signer, pinOf(srv.HostSigner.PublicKey())))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.DiscoverRoutes(ctx)
}

func TestDiscoverRoutes(t *testing.T) {
	cases := []struct {
		name string
		exec map[string]string
		want string
	}{
		{"ip", map[string]string{"ip -4 route show": ipRouteOut}, `0.0.0.0/0 eth0 default
10.10.0.0/16 eth1
10.77.0.0/24 eth1
169.254.0.0/16 eth0 linklocal
172.18.0.0/16 eth0
192.0.2.7/32 eth1
`},
		{"netstat linux", map[string]string{"netstat -rn": netstatLinuxOut}, `0.0.0.0/0 eth0 default
10.10.0.0/16 eth1
10.77.0.0/24 eth1
172.18.0.0/16 eth0
`},
		{"netstat bsd", map[string]string{"netstat -rn": netstatBSDOut}, `0.0.0.0/0 em0 default
10.0.0.0/8 em1
10.77.0.0/24 em1
192.168.1.0/24 em0
`},
		{"proc little-endian", map[string]string{"cat /proc/net/route": procOut}, `0.0.0.0/0 eth0 default
10.10.0.0/16 eth1
10.77.0.0/24 eth1
`},
		{"proc big-endian", map[string]string{"cat /proc/net/route": procBEOut}, "10.77.0.0/24 eth1\n"},
		{"empty ip output falls through", map[string]string{"ip -4 route show": "", "cat /proc/net/route": procBEOut}, "10.77.0.0/24 eth1\n"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rs, err := discover(t, tc.exec, false)
			if err != nil {
				t.Fatal(err)
			}
			if got := fmtRoutes(rs); got != tc.want {
				t.Errorf("got\n%s\nwant\n%s", got, tc.want)
			}
		})
	}
}

func TestDiscoverRoutesUnavailable(t *testing.T) {
	// `restrict` without `port-forwarding` still allows exec, but a server
	// with ForceCommand or no shell refuses it.
	if _, err := discover(t, map[string]string{"ip -4 route show": ipRouteOut}, true); errcode.Of(err) != errcode.RouteDiscoveryUnavailable {
		t.Errorf("exec denied: %v", err)
	}
	if _, err := discover(t, map[string]string{}, false); errcode.Of(err) != errcode.RouteDiscoveryUnavailable {
		t.Errorf("no commands: %v", err)
	}
}
