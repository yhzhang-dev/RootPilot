package com.hangge.rootpilot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.hangge.rootpilot.domain.model.AppTheme

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF24180A),
    primaryContainer = AmberDim,
    onPrimaryContainer = Color(0xFFFFDFA8),
    secondary = Teal,
    onSecondary = Color(0xFF06251F),
    secondaryContainer = TealDim,
    onSecondaryContainer = Color(0xFFB8F3E4),
    tertiary = TextSecondary,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = Panel,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelRaised,
    surfaceContainerHighest = PanelRaised,
    outline = Line,
    outlineVariant = Line,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerDim,
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = AmberInk,
    onPrimary = Color.White,
    primaryContainer = AmberInkDim,
    onPrimaryContainer = Color(0xFF2B1A00),
    secondary = TealInk,
    onSecondary = Color.White,
    secondaryContainer = TealInkDim,
    onSecondaryContainer = Color(0xFF00201A),
    background = Paper,
    onBackground = InkText,
    surface = PaperPanel,
    onSurface = InkText,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = InkTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PaperPanel,
    surfaceContainer = PaperPanel,
    surfaceContainerHigh = PaperRaised,
    surfaceContainerHighest = PaperRaised,
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = DangerInk,
    onError = Color.White,
    errorContainer = DangerInkDim,
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun RootPilotTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            // Keep status/navigation bar icons readable when the in-app theme differs from the system.
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = RootPilotTypography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
