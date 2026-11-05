package dev.openvw

import android.os.Bundle
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.openvw.theme.Bg
import dev.openvw.theme.OpenVWTheme
import dev.openvw.ui.AppLock
import dev.openvw.ui.LockScreen
import dev.openvw.ui.OpenVWApp

// FragmentActivity (a ComponentActivity) because the biometric prompt for App lock needs one
class MainActivity : FragmentActivity() {
  private var stoppedAt = 0L // elapsedRealtime when the app left the screen; 0 = hasn't yet

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // App lock: locked on a cold start (not on a rotation / theme change, where state is restored)
    if (savedInstanceState == null && AppLock.isOn(this)) AppLock.locked = true
    if (savedInstanceState == null) dev.openvw.ui.NavRequest.from(intent) // opened from a widget
    AppLock.hideFromRecents(this, AppLock.isOn(this))
    // theme before the first frame: the owner's choice, else the system setting (no light flash on a dark phone)
    val sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES
    val mode = getSharedPreferences("openvw", MODE_PRIVATE).getString("themeMode", "").orEmpty()
    dev.openvw.theme.Palette.systemDark = sysDark
    dev.openvw.theme.Palette.dark = dev.openvw.theme.Palette.resolve(mode, sysDark)
    dev.openvw.data.Store.themeMode = mode
    dev.openvw.data.Store.dark = dev.openvw.theme.Palette.dark
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (dev.openvw.theme.Palette.dark) 0xFF121214.toInt() else 0xFFF5F4F0.toInt()))
    val osm = org.osmdroid.config.Configuration.getInstance()
    osm.load(applicationContext, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
    osm.userAgentValue = "CarPulse/1.0 ($packageName)"
    osm.osmdroidBasePath = java.io.File(cacheDir, "osmdroid").apply { mkdirs() }
    osm.osmdroidTileCache = java.io.File(osm.osmdroidBasePath, "tiles").apply { mkdirs() }
    enableEdgeToEdge()
    setContent {
      OpenVWTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
          // only a signed-in (or demo) app has anything to hide; the sign-in screen never locks
          val vm = AppGraph.vm(application)
          if (AppLock.locked && vm.phase != dev.openvw.ui.Phase.LOGIN && vm.phase != dev.openvw.ui.Phase.OTP) LockScreen(::unlock)
          else OpenVWApp(vm)
        }
      }
    }
  }

  // already running (singleTask): a widget tap arrives here
  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    dev.openvw.ui.NavRequest.from(intent)
  }

  override fun onStart() {
    super.onStart()
    if (AppLock.isOn(this) && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > AppLock.GRACE_MS) AppLock.locked = true
  }

  override fun onResume() {
    super.onResume()
    if (AppLock.locked) unlock()
  }

  override fun onStop() {
    super.onStop()
    stoppedAt = SystemClock.elapsedRealtime()
  }

  private fun unlock() = AppLock.authenticate(this, "Unlock CarPulse") { AppLock.locked = false }
}
