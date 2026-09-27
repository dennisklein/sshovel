// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.data

import com.github.dennisklein.sshovel.TestStores
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileValidationTest {
    private val t = TestStores()

    @After fun tearDown() = t.close()

    @Test fun parsesValidateConfigOutput() {
        assertEquals(emptyList<ValidationIssue>(), GoProfileValidator.parse(""))
        // Exactly what core/mobile.ValidateConfig returns (see core/mobile/mobile_test.go).
        assertEquals(
            listOf(ValidationIssue("routes[1]", "ROUTE_OVERLAP", ValidationIssue.WARNING, "10.0.0.0/8")),
            GoProfileValidator.parse("""[{"field":"routes[1]","code":"ROUTE_OVERLAP","severity":"warning","suggestion":"10.0.0.0/8"}]"""),
        )
    }

    /** A validator that flags a tun subnet overlapping a route, like core/config does. */
    private val overlap = ProfileValidator { p ->
        buildList {
            val i = p.routes.indexOf(p.tun.cidr)
            if (i >= 0) add(ValidationIssue("routes[$i]", "TUN_OVERLAPS_ROUTE", ValidationIssue.ERROR))
            if (p.routes.size > 1) add(ValidationIssue("routes[1]", "ROUTE_OVERLAP", ValidationIssue.WARNING, "10.0.0.0/8"))
        }
    }

    private fun profile(tun: String = "198.18.0.0/24", routes: List<String> = listOf("10.77.0.0/24")) = Profile(
        id = "", name = "A", server = Server("h", 22, "u"), auth = Auth(Auth.IMPORTED, "k"),
        routes = routes, tun = Tun(cidr = tun),
    )

    @Test fun errorsBlockSavingWarningsDont() = runBlocking {
        val repo = ProfileRepository(t.store, t.scope, overlap)
        val saved = repo.save(profile(routes = listOf("10.0.0.0/8", "10.1.0.0/16"))) // warning only
        try {
            repo.save(saved.copy(tun = Tun(cidr = "10.0.0.0/8")))
            fail("saved an overlapping tun subnet")
        } catch (e: ProfileInvalidException) {
            assertEquals(listOf("TUN_OVERLAPS_ROUTE"), e.issues.filter { it.isError }.map { it.code })
        }
        // The stored profile is unchanged.
        assertEquals("198.18.0.0/24", repo.profile(saved.id)?.tun?.cidr)
        assertTrue(repo.validate(profile()).isEmpty())
    }
}
