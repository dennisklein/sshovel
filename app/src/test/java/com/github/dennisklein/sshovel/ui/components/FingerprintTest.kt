// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import com.github.dennisklein.sshovel.ui.format.formatBytes
import com.github.dennisklein.sshovel.ui.format.formatCount
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FingerprintTest {
    @Test fun groupsOfFour() {
        assertEquals(listOf("nThb", "Rk2m", "2rY"), fingerprintGroups("SHA256:nThbRk2m2rY"))
    }

    @Test fun spokenCharacterByCharacter() {
        assertEquals("n, capital T, h, b. plus, slash", spokenFingerprint("SHA256:nThb+/"))
    }

    @Test fun bytesAndCounts() {
        Locale.setDefault(Locale.US)
        assertEquals("48.2 MB", formatBytes(50_540_000))
        assertEquals("512 B", formatBytes(512))
        assertEquals("150 MB", formatBytes(157_286_400))
        assertEquals("1,902", formatCount(1902))
    }
}
