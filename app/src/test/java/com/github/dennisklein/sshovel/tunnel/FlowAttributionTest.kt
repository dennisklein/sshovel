// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowAttributionTest {
    @Test fun parsesNumericAddressesOnly() {
        assertEquals(443, FlowAttribution.socketAddress("10.77.0.20:443")!!.port)
        assertEquals("10.77.0.20", FlowAttribution.socketAddress("10.77.0.20:443")!!.address.hostAddress)
        assertEquals(22, FlowAttribution.socketAddress("[fd00::1]:22")!!.port)
        assertNull(FlowAttribution.socketAddress("wiki.corp.test:443")) // never a DNS lookup
        assertNull(FlowAttribution.socketAddress("10.77.0.20"))
        assertNull(FlowAttribution.socketAddress(":443"))
    }
}
