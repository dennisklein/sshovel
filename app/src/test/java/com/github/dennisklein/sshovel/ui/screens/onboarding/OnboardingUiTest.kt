// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.screens.onboarding

import com.github.dennisklein.sshovel.ui.screens.profile.FieldMsg
import com.github.dennisklein.sshovel.ui.screens.profile.Fields
import com.github.dennisklein.sshovel.ui.screens.profile.ProfileDraft
import com.github.dennisklein.sshovel.ui.screens.profile.fieldMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingUiTest {
    private val noName = ProfileDraft(
        host = "lxlogin.gsi.de", user = "dklein", keyId = "k1",
        routes = listOf("10.0.0.0/8"), dnsServer = "140.181.61.62", suffixes = listOf("gsi.de"),
    )

    @Test
    fun missingNameNotReadyAndErrorShownOnceNextIsTapped() {
        val ui = OnboardingUi(draft = noName, messages = fieldMessages(noName, emptyList()))
        assertFalse(ui.serverReady)
        // Untouched fields stay quiet while typing …
        assertEquals(null, ui.messageFor(Fields.NAME))
        // … and saveServer marks them shown, which is what the always-enabled Next triggers.
        val tapped = ui.copy(shown = ui.shown + OnboardingUi.SERVER_FIELDS)
        assertEquals(FieldMsg.Kind.REQUIRED, tapped.messageFor(Fields.NAME)?.kind)
    }

    @Test
    fun filledServerStepIsReady() {
        val d = noName.copy(name = "GSI")
        assertTrue(OnboardingUi(draft = d, messages = fieldMessages(d, emptyList())).serverReady)
    }
}
