package com.mubox.app.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mubox.app.model.AppThemeMode
import com.mubox.app.model.AppThemeStyle

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF08223A),
    primaryContainer = Color(0xFF173D60),
    onPrimaryContainer = Color(0xFFC8E4FF),
    secondary = Color(0xFF9FB4C9),
    onSecondary = Color(0xFF172B3E),
    secondaryContainer = Color(0xFF263D53),
    onSecondaryContainer = Color(0xFFD9E7F4),
    tertiary = Color(0xFF70B9F5),
    background = AppBackground,
    onBackground = Color(0xFFEAF3FC),
    surface = SurfaceCard,
    onSurface = Color(0xFFEAF3FC),
    surfaceVariant = SurfaceCardAlt,
    onSurfaceVariant = Color(0xFFA9BACB),
    outline = Color(0xFF2D465E),
    outlineVariant = Color(0xFF243B52),
    error = Destructive
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = PureWhite,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color(0xFF155B95),
    secondary = LightTextSecondary,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFE7F0F8),
    onSecondaryContainer = Color(0xFF344B61),
    tertiary = Color(0xFF4AA6E8),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightDivider,
    outlineVariant = Color(0xFFE0E9F2),
    error = Destructive
)

@Composable
fun appBackgroundBrush(): Brush {
    val isDark = MaterialTheme.colorScheme.background == AppBackground
    return remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0C2037),
                    Color(0xFF091827),
                    Color(0xFF071522)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE8F4FF),
                    Color(0xFFF8FBFF),
                    Color(0xFFEEF6FF)
                )
            )
        }
    }
}

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingBoxTheme(
    appTheme: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val useDarkTheme = when (appTheme) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }
    CompositionLocalProvider(
        LocalAppThemeStyle provides AppThemeStyle.DEFAULT,
        LocalRippleConfiguration provides RippleConfiguration()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
