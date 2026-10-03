// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.format

import com.github.dennisklein.sshovel.tunnel.Codes
import com.github.dennisklein.sshovel.ui.components.errorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every code the Go core can send (core/errcode) has its copy (DESIGN_BRIEF §8, M7: "error
 * catalog strings wired to every error code"), so a new Go code can't reach the UI unnamed.
 */
class ErrorCatalogTest {
    /** The Go codes, by block: "Errors", "Warnings", "Key import errors", "Reasons attached to failed flows". */
    private val goCodes: Map<String, List<String>> by lazy {
        val src = File("../core/errcode/errcode.go").readText()
        val blocks = Regex("""// ([^\n]+)\n(?:// [^\n]*\n)*const \((.*?)\)""", RegexOption.DOT_MATCHES_ALL).findAll(src)
        blocks.associate { m -> m.groupValues[1] to Regex(""""([A-Z_]+)"""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
    }

    private fun block(prefix: String) = goCodes.entries.first { it.key.startsWith(prefix) }.value

    private val kotlinCodes = Codes::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

    @Test fun kotlinKnowsEveryGoCode() {
        val all = goCodes.values.flatten()
        assertTrue("parsed ${goCodes.keys}", all.size >= 18)
        assertEquals(emptyList<String>(), all.filter { it !in kotlinCodes })
    }

    @Test fun everyStateCodeHasCatalogCopy() {
        for (code in block("Errors")) assertNotNull(code, errorCopy(code))
        // ROUTE_DISCOVERY_UNAVAILABLE only comes from DiscoverRoutes: the discover sheet words it.
        for (code in block("Warnings") - Codes.ROUTE_DISCOVERY_UNAVAILABLE) assertNotNull(code, errorCopy(code))
    }

    @Test fun stoppingErrorsOfferTheirAction() {
        // NETWORK_LOST has no action (sshovel reconnects by itself); the rest have one on Home.
        for (code in block("Errors") - Codes.NETWORK_LOST) assertNotNull(code, errorAction(code))
        assertNull(errorCopy(Codes.NETWORK_LOST)!!.action)
        // The warnings point to Diagnostics.
        for (code in listOf(Codes.FORWARDING_DENIED, Codes.DNS_UNREACHABLE)) assertNotNull(errorCopy(code)!!.action)
    }

    @Test fun everyFlowReasonHasConnectionsCopy() {
        for (code in block("Reasons") + Codes.FORWARDING_DENIED) assertNotNull(code, failureCopy(code))
    }
}
