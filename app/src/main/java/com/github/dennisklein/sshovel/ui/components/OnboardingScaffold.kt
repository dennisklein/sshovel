// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package com.github.dennisklein.sshovel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.dennisklein.sshovel.R

/**
 * OnboardingScaffold (handoff §2, O1–O9): back, "Step n of 7", Skip, a progress bar, scrolling
 * content with 24 dp margins, and a bottom action row (secondary text button, primary button).
 * Step 1 has no back or progress; [onSkip] null hides Skip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScaffold(
    step: Int,
    steps: Int,
    onBack: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    topDivider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (step > 1) {
                            Text(
                                stringResource(R.string.onb_step, step, steps),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onBack) { SymbolIcon(R.drawable.ic_arrow_back, stringResource(R.string.cd_back)) }
                        }
                    },
                    actions = {
                        if (onSkip != null) TextButton(onSkip) { Text(stringResource(R.string.onb_skip)) }
                    },
                )
                if (step > 1) {
                    LinearProgressIndicator(
                        progress = { step.toFloat() / steps },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.onboarding),
                        drawStopIndicator = {},
                    )
                }
            }
        },
        bottomBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                if (topDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MaxWidth { bottomBar() }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MaxWidth(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.onboarding)
                    .padding(top = if (step > 1) 32.dp else 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                content = content,
            )
        }
    }
}

/**
 * Bottom action row (handoff §1.4): 16 dp vertical, 24 dp horizontal; the primary on the right.
 * Buttons stack vertically, primary on top, when they don't fit (200 % font).
 */
@Composable
fun BottomActions(secondary: (@Composable () -> Unit)?, primary: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.onboarding, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondary != null) {
            Row(Modifier.weight(1f, fill = false)) { secondary() }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        primary()
    }
}

/** Headline and body of an onboarding or full-screen explainer (headlineMedium + bodyLarge). */
@Composable
fun Headline(title: String, body: CharSequence? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        when (body) {
            null -> {}
            is androidx.compose.ui.text.AnnotatedString -> Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(body.toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
