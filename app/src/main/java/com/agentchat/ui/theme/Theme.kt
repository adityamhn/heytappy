package com.agentchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BrandPurpleLight,
    onPrimary = Color.White,
    primaryContainer = BubbleReceivedLight,
    onPrimaryContainer = OnBubbleReceivedLight,
    secondary = AgentGreen,
    onSecondary = Color.White,
    background = BackgroundLight,
    onBackground = Color(0xFF1A1720),
    surface = BackgroundLight,
    onSurface = Color(0xFF1A1720),
    surfaceVariant = SurfaceElevatedLight,
    onSurfaceVariant = SecondaryLabelLight,
    outlineVariant = DividerLight,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = BrandPurpleDark,
    onPrimary = Color(0xFF191330),
    primaryContainer = BubbleReceivedDark,
    onPrimaryContainer = OnBubbleReceivedDark,
    secondary = AgentGreen,
    onSecondary = Color.White,
    background = BackgroundDark,
    onBackground = Color(0xFFF3F1FA),
    surface = BackgroundDark,
    onSurface = Color(0xFFF3F1FA),
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = SecondaryLabelDark,
    outlineVariant = DividerDark,
    error = ErrorRed,
)

@Composable
fun TappyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
