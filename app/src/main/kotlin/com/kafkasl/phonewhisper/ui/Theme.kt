package com.kafkasl.phonewhisper.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (Android 11, or dynamic color off): Material 3 baseline tones from a blue seed.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5EA7), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8), onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6F5675), tertiaryContainer = Color(0xFFF9D8FE), onTertiaryContainer = Color(0xFF28132F),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF07458C), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC), onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDCBCE1), tertiaryContainer = Color(0xFF563E5C), onTertiaryContainer = Color(0xFFF9D8FE),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
)

/** Material 3 with the wallpaper's dynamic colors on Android 12+. */
@Composable
fun PhoneWhisperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
