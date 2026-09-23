package app.sshovel.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand fallback scheme. Seed #005EA8, Material Color Utilities SchemeTonalSpot, contrast 0.
// Used when dynamic color is unavailable or the user turns off "Use wallpaper colors".

val SshovelLightScheme = lightColorScheme(
    primary = Color(0xFF3A608F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF1F4876),
    inversePrimary = Color(0xFFA4C9FE),
    secondary = Color(0xFF545F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF3C4758),
    tertiary = Color(0xFF6D5677),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5D9FF),
    onTertiaryContainer = Color(0xFF543F5E),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF3A608F),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFEFF0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9DAE0),
)

val SshovelDarkScheme = darkColorScheme(
    primary = Color(0xFFA4C9FE),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF1F4876),
    onPrimaryContainer = Color(0xFFD3E3FF),
    inversePrimary = Color(0xFF3A608F),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFD9BDE3),
    onTertiary = Color(0xFF3C2946),
    tertiaryContainer = Color(0xFF543F5E),
    onTertiaryContainer = Color(0xFFF5D9FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceTint = Color(0xFFA4C9FE),
    inverseSurface = Color(0xFFE1E2E9),
    inverseOnSurface = Color(0xFF2E3035),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceDim = Color(0xFF111318),
)

/** Connection-state accents (§4). Harmonized to the seed (blend = true) so they sit well with dynamic color too. */
@Immutable
data class StateColors(
    val stateOn: Color, val onStateOn: Color, val stateOnContainer: Color, val onStateOnContainer: Color,
    val stateReconnecting: Color, val onStateReconnecting: Color,
    val stateReconnectingContainer: Color, val onStateReconnectingContainer: Color,
)

val LightStateColors = StateColors(
    stateOn = Color(0xFF006D43),
    onStateOn = Color(0xFFFFFFFF),
    stateOnContainer = Color(0xFF92F7BC),
    onStateOnContainer = Color(0xFF002111),
    stateReconnecting = Color(0xFF974806),
    onStateReconnecting = Color(0xFFFFFFFF),
    stateReconnectingContainer = Color(0xFFFFDBC8),
    onStateReconnectingContainer = Color(0xFF321300),
)

val DarkStateColors = StateColors(
    stateOn = Color(0xFF76DAA1),
    onStateOn = Color(0xFF003920),
    stateOnContainer = Color(0xFF005231),
    onStateOnContainer = Color(0xFF92F7BC),
    stateReconnecting = Color(0xFFFFB68A),
    onStateReconnecting = Color(0xFF522300),
    stateReconnectingContainer = Color(0xFF743400),
    onStateReconnectingContainer = Color(0xFFFFDBC8),
)

// With dynamic color on, re-harmonize the two source colors (#2E7D32, #B26A00) against
// colorScheme.primary at runtime (MaterialColors.harmonize / Blend.harmonize) and keep tones 40/100/90/10 (light), 80/20/30/90 (dark).
val LocalStateColors = staticCompositionLocalOf { LightStateColors }
