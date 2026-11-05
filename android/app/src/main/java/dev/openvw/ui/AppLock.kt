package dev.openvw.ui

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.openvw.R
import dev.openvw.theme.Bg
import dev.openvw.theme.Ink
import dev.openvw.theme.Muted

/**
 * App lock: fingerprint, face or the phone's screen lock (PIN / pattern / password) before CarPulse shows anything,
 * on start and after more than [GRACE_MS] in the background. The car screen (Android Auto) never locks.
 */
object AppLock {
  const val GRACE_MS = 30_000L
  private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL // any biometric, or the screen lock as fallback

  /** True while the app content is hidden behind the lock screen. */
  var locked by mutableStateOf(false)

  fun isOn(ctx: Context) = ctx.getSharedPreferences("openvw", Context.MODE_PRIVATE).getBoolean("appLock", false)

  fun setOn(activity: FragmentActivity, on: Boolean) {
    activity.getSharedPreferences("openvw", Context.MODE_PRIVATE).edit().putBoolean("appLock", on).apply()
    hideFromRecents(activity, on)
  }

  /** Can this phone lock the app at all (a screen lock or biometric is set up)? */
  fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

  /** Recents shows a blank card instead of the car's location (Android 13+; screenshots still work). */
  fun hideFromRecents(activity: FragmentActivity, on: Boolean) {
    if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!on)
  }

  /** A prompt is on screen. The PIN/pattern screen pauses and resumes the app, so don't stack a second one. */
  private var prompting = false

  fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
    if (prompting) return
    prompting = true
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; onSuccess() }
      // errors / cancel: stay as we are; the lock screen keeps its Unlock button
      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
    })
    prompt.authenticate(
      BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("Use your fingerprint, face or screen lock")
        .setAllowedAuthenticators(AUTH) // no negative button: the screen lock is the fallback
        .build(),
    )
  }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
  Column(
    Modifier.fillMaxSize().background(Bg).padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Image(painterResource(R.drawable.ic_carpulse_mark), contentDescription = null, modifier = Modifier.size(56.dp))
    Spacer(Modifier.height(20.dp))
    Text("CarPulse is locked", style = TextStyle(color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(8.dp))
    Text("Unlock to see your car.", style = TextStyle(color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center))
    Spacer(Modifier.height(28.dp))
    DarkButton("Unlock", Modifier.widthIn(min = 200.dp).height(52.dp), onClick = onUnlock)
  }
}
