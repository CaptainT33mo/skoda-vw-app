package dev.openvw.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun OpenVWTheme(content: @Composable () -> Unit) {
  // follow the system theme live (unless the owner overrode it); MainActivity sets the first frame's value
  val sys = isSystemInDarkTheme()
  val want = Palette.resolve(dev.openvw.data.Store.themeMode, sys)
  SideEffect {
    Palette.systemDark = sys
    if (Palette.dark != want) Palette.dark = want
    if (dev.openvw.data.Store.dark != want) dev.openvw.data.Store.dark = want
  }
  val scheme = if (Palette.dark)
    darkColorScheme(primary = Ink, onPrimary = Card, secondary = Accent, background = Bg, onBackground = Ink, surface = Card, onSurface = Ink)
  else
    lightColorScheme(primary = Ink, onPrimary = Card, secondary = Accent, background = Bg, onBackground = Ink, surface = Card, onSurface = Ink)

  val view = LocalView.current
  if (!view.isInEditMode) SideEffect {
    val window = (view.context as Activity).window
    // light status/nav icons in dark mode, dark icons in light mode
    WindowCompat.getInsetsController(window, view).apply {
      isAppearanceLightStatusBars = !Palette.dark
      isAppearanceLightNavigationBars = !Palette.dark
    }
  }
  MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
