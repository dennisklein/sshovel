// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.profile

import com.github.dennisklein.sshovel.data.Apps
import com.github.dennisklein.sshovel.data.Auth
import com.github.dennisklein.sshovel.data.Dns
import com.github.dennisklein.sshovel.data.HostKey
import com.github.dennisklein.sshovel.data.Profile
import com.github.dennisklein.sshovel.data.Server
import com.github.dennisklein.sshovel.data.ValidationIssue
import com.github.dennisklein.sshovel.keys.KeyEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDraftTest {
    private val key = KeyEntry("k1", "Pixel", KeyEntry.KEYSTORE, "ecdsa-sha2-nistp256", "SHA256:x", "line", "pkix", KeyEntry.STRONGBOX, "2026-03-12T10:00:00Z")
    private val profile = Profile(
        id = "p", name = "Office", server = Server("jump.corp.example", 2222, "alex"),
        auth = Auth(Auth.KEYSTORE, "k1", "pkix"),
        hostKey = HostKey("ssh-ed25519", "SHA256:pin"),
        routes = listOf("10.20.0.0/16"), excludedRoutes = listOf("10.20.99.0/24"),
        dns = Dns("10.20.0.53", listOf("corp.example"), listOf("corp.example"), reverseLookups = false, hideAAAA = true),
        apps = Apps(Apps.INCLUDE, listOf("com.termux")), keepaliveSec = 30, connectTimeoutSec = 15,
    )

    @Test fun roundTripsAProfileWithoutThePin() {
        val back = ProfileDraft.of(profile, isDefault = true).toProfile(listOf(key))
        assertEquals(profile.copy(hostKey = null), back)
    }

    @Test fun keepsTypedNumbersAsTextAndFlagsThem() {
        val d = ProfileDraft.of(profile, false).copy(port = "22x", mtu = "")
        assertEquals(0, d.toProfile(listOf(key)).server.port)
        val m = fieldMessages(d, emptyList())
        assertEquals(FieldMsg.Kind.NUMBER, m[Fields.PORT]?.kind)
        assertEquals(FieldMsg.Kind.NUMBER, m[Fields.MTU]?.kind)
    }

    @Test fun missingKeyAndNameAreLocalErrors() {
        val m = fieldMessages(ProfileDraft(), emptyList())
        assertEquals(FieldMsg.Kind.REQUIRED, m[Fields.NAME]?.kind)
        assertEquals(FieldMsg.Kind.KEY, m[Fields.KEY]?.kind)
    }

    @Test fun appsAllDropsPackages() {
        val p = ProfileDraft.of(profile, false).copy(appsMode = Apps.ALL).toProfile(listOf(key))
        assertEquals(Apps(Apps.ALL, emptyList()), p.apps)
    }

    @Test fun dnsVirtualIpFollowsTheTunnelSubnet() {
        assertEquals("198.18.0.53", ProfileDraft.dnsVirtualIpFor("198.18.0.0/24"))
        assertEquals("10.99.0.53", ProfileDraft.dnsVirtualIpFor("10.99.0.0/16"))
        assertEquals("10.99.0.2", ProfileDraft.dnsVirtualIpFor("10.99.0.0/30"))
    }

    @Test fun mapsGoIssuesToFields() {
        val d = ProfileDraft.of(profile, false).copy(routes = listOf("10.20.0.0/16", "10.20.4.0/24", "10.1.2.3/8"), dnsServer = "")
        val issues = listOf(
            ValidationIssue("server.host", "INVALID_HOST", ValidationIssue.ERROR),
            ValidationIssue("routes[1]", "ROUTE_OVERLAP", ValidationIssue.WARNING, "10.20.0.0/16"),
            ValidationIssue("routes[2]", "NOT_CANONICAL", ValidationIssue.ERROR, "10.0.0.0/8"),
            ValidationIssue("routes[0]", "TUN_OVERLAPS_ROUTE", ValidationIssue.ERROR),
            ValidationIssue("dns.server", "DNS_SERVER_MISSING", ValidationIssue.ERROR),
            ValidationIssue("keepaliveSec", "OUT_OF_RANGE", ValidationIssue.ERROR),
        )
        val m = fieldMessages(d, issues)
        assertEquals(FieldMsg.Kind.HOST, m[Fields.HOST]?.kind)
        assertEquals(FieldMsg(FieldMsg.Kind.COVERED, listOf("10.20.0.0/16"), warning = true), m[Fields.route(1)])
        assertEquals(FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf("10.0.0.0/8")), m[Fields.route(2)])
        // Go reports the overlap on the route; the editor shows it on the tunnel subnet (handoff P4).
        assertEquals(FieldMsg(FieldMsg.Kind.TUN_OVERLAP, listOf("10.20.0.0/16", "198.18.0.0/24")), m[Fields.TUN])
        assertEquals(FieldMsg.Kind.DNS_REQUIRED, m[Fields.DNS]?.kind)
        assertEquals(FieldMsg(FieldMsg.Kind.RANGE, listOf("5", "300")), m[Fields.KEEPALIVE])
    }

    @Test fun overlapIsMarkedOnTheCoveredRow() {
        // The shorter prefix came second: Go reports it with itself as the merged prefix.
        val d = ProfileDraft.of(profile, false).copy(routes = listOf("10.20.4.0/24", "10.20.0.0/16"))
        val m = fieldMessages(d, listOf(ValidationIssue("routes[1]", "ROUTE_OVERLAP", ValidationIssue.WARNING, "10.20.0.0/16")))
        assertEquals(FieldMsg(FieldMsg.Kind.COVERED, listOf("10.20.0.0/16"), warning = true), m[Fields.route(0)])
        assertNull(m[Fields.route(1)])
    }

    @Test fun errorsWinOverWarnings() {
        val d = ProfileDraft.of(profile, false).copy(routes = listOf("10.20.0.0/16", "10.20.0.0/16"))
        val m = fieldMessages(
            d,
            listOf(
                ValidationIssue("routes[1]", "DUPLICATE", ValidationIssue.WARNING),
                ValidationIssue("routes[1]", "INVALID_CIDR", ValidationIssue.ERROR),
            ),
        )
        assertFalse(m[Fields.route(1)]!!.warning)
    }

    @Test fun checksNewSubnets() {
        assertEquals(FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOf("10.40.0.0/16")), checkNewCidr("10.40.0.0/33", emptyList()))
        assertEquals(FieldMsg(FieldMsg.Kind.CIDR_NOT_CANONICAL, listOf("10.40.0.0/16")), checkNewCidr("10.40.1.2/16", emptyList()))
        assertEquals(FieldMsg(FieldMsg.Kind.DUPLICATE), checkNewCidr("10.40.0.0/16", listOf("10.40.0.0/16")))
        assertEquals(FieldMsg(FieldMsg.Kind.CIDR_INVALID, listOf("10.0.0.0")), checkNewCidr("intranet", emptyList()))
        assertNull(checkNewCidr(" 10.40.0.0/16 ", listOf("10.20.0.0/16")))
    }

    @Test fun cidrContains() {
        val wide = Cidr.parse("10.20.0.0/16")!!
        assertTrue(wide.contains(Cidr.parse("10.20.4.0/24")!!))
        assertFalse(wide.contains(Cidr.parse("10.21.0.0/24")!!))
        assertTrue(Cidr.parse("0.0.0.0/0")!!.contains(wide))
        assertNull(Cidr.parse("300.1.1.1/8"))
    }

    @Test fun domains() {
        assertTrue(isValidDomain("corp.example"))
        assertTrue(isValidDomain("internal"))
        assertFalse(isValidDomain("https://corp.example"))
        assertFalse(isValidDomain("-bad.example"))
        assertFalse(isValidDomain(""))
    }

    @Test fun summarizesChanges() {
        val a = ProfileDraft.of(profile, false)
        val b = a.copy(host = "jump2.corp.example", routes = a.routes + "10.30.4.0/24")
        assertEquals(Changes(listOf(Change.HOST, Change.SUBNETS), 1), changesBetween(a, b))
        assertEquals(Changes(emptyList(), 0), changesBetween(a, a.copy(name = " Office ")))
        assertEquals(listOf(Change.APPS), changesBetween(a, a.copy(packages = listOf("com.termux", "com.Slack"))).parts)
    }
}
