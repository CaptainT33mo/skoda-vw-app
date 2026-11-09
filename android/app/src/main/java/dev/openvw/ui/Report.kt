package dev.openvw.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.openvw.data.Alerts
import dev.openvw.data.Diag
import dev.openvw.data.Session
import dev.openvw.data.Store
import kotlin.math.sqrt

/** "Report a problem": the diagnostics report (data/Diag) as an email to the developer, sent from the user's mail app. */
object Report {
  const val EMAIL = "playstore@vibhor.tech"

  /** "1.1.1 (3)". */
  fun version(ctx: Context): String = runCatching {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    "${pi.versionName} ($code)"
  }.getOrDefault("?")

  fun appName(ctx: Context): String = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()

  private fun installer(ctx: Context): String = runCatching {
    val p = if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
    else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    when (p) { "com.android.vending" -> "Google Play"; null -> "APK / adb"; else -> p }
  }.getOrDefault("unknown")

  private fun summary(ctx: Context): List<Pair<String, String>> {
    val s = Session(ctx)
    val v = Store.vehicle
    val pm = ctx.getSystemService(PowerManager::class.java)
    fun yn(b: Boolean) = if (b) "yes" else "no"
    return listOf(
      "App" to "${appName(ctx)} ${version(ctx)} · ${ctx.packageName} · installed from ${installer(ctx)}",
      "Phone" to Diag.device(),
      "Signed in" to "${yn(s.loggedIn)}${if (s.demo) " (demo car)" else ""} · brand ${Store.brand.label} · sign-in ${if (s.userId > 0) "Skoda/VW account (savwconnect)" else "Cerence"}",
      "Car" to listOf(v.model, v.variant, v.modelYear, v.fuelType, v.colour).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
      "Cars on account" to Store.cars.size.toString(),
      "Dongle" to "status ${Store.dongle.status.ifBlank { "—" }} · firmware ${v.dongleFirmware.ifBlank { Store.dongle.firmware.ifBlank { "—" } }} · signal ${Store.dongle.signal}",
      "Now" to "status ${v.status} · live feed ${yn(Store.liveConnected)} · fuel ${Store.telemetry.fuelPct}% · odometer ${Store.telemetry.odometerKm ?: "—"}${if (Store.odometerFromTrip) " (from last trip)" else ""} · battery ${Store.telemetry.batteryV ?: "—"} V",
      "Trips / events loaded" to "${Store.trips.size} / ${Store.events.size}",
      "Car alerts" to "${yn(Alerts.isOn(ctx))} · instant ${yn(Alerts.instant(ctx) && Store.instantAvailable)} · notifications allowed ${yn(dev.openvw.work.CarNotifications.canPost(ctx))} · battery unrestricted ${yn(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true)}",
    )
  }

  /** Builds the report and opens the user's email app with it attached, addressed to [EMAIL]. */
  fun send(ctx: Context) {
    val file = runCatching { Diag.build(ctx, summary(ctx)) }.getOrElse { Diag.line('E', "report: build failed${it.message?.let { m -> " ($m)" }.orEmpty()}"); return }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".reports", file)
    val ver = version(ctx)
    val mail = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
      putExtra(Intent.EXTRA_SUBJECT, "CarPulse problem report · ${appName(ctx)} $ver${Store.vehicle.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
      putExtra(Intent.EXTRA_TEXT, "What went wrong? (please describe it here)\n\n\n\n—\n${appName(ctx)} $ver\n${Diag.device()}\nThe attached report has no phone number, email, VIN, registration or locations.")
      putExtra(Intent.EXTRA_STREAM, uri)
      clipData = ClipData.newRawUri("CarPulse report", uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    Diag.line('I', "report: opened email (${file.length() / 1024} KB)")
    // email apps only (the ones that handle mailto:); any app that takes a file when there is none
    val mailApps = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
      .map { it.activityInfo.packageName }.distinct()
    val targets = mailApps.map { Intent(mail).setPackage(it) }
    val start = when (targets.size) {
      0 -> Intent.createChooser(mail, "Send report")
      1 -> targets[0]
      else -> Intent.createChooser(targets[0], "Send report by email").putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(start) }
    catch (_: ActivityNotFoundException) { runCatching { ctx.startActivity(Intent.createChooser(mail, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
  }
}

/** Shake to report: three hard shakes within a second, at most once every 4 s, while the app is on screen. */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
  private val peaks = ArrayDeque<Long>()
  private var lastFired = 0L

  fun start(ctx: Context) {
    val sm = ctx.getSystemService(SensorManager::class.java) ?: return
    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
  }
  fun stop(ctx: Context) { ctx.getSystemService(SensorManager::class.java)?.unregisterListener(this) }

  override fun onSensorChanged(e: SensorEvent) {
    val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
    if (sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH < 2.6f) return
    val now = e.timestamp / 1_000_000
    if (peaks.lastOrNull()?.let { now - it < 150 } == true) return // one shake, not every sample of it
    peaks.addLast(now)
    while (peaks.isNotEmpty() && now - peaks.first() > 1000) peaks.removeFirst()
    if (peaks.size >= 3 && now - lastFired > 4000) { lastFired = now; peaks.clear(); onShake() }
  }
  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Shown after a shake: send the report, or not. */
@Composable
fun ReportPrompt() {
  if (!Store.reportPrompt) return
  val ctx = LocalContext.current
  AlertDialog(
    onDismissRequest = { Store.reportPrompt = false },
    title = { Text("Report a problem?") },
    text = {
      Text("Email the developer a diagnostics report: app version, phone model, the car data CarPulse received and recent errors. " +
        "Your phone number, email, VIN, registration and locations are removed, and you see the email before it's sent.\n\n" +
        "You can turn off shake to report in My car → Settings.")
    },
    confirmButton = { TextButton(onClick = { Store.reportPrompt = false; Report.send(ctx) }) { Text("Send report") } },
    dismissButton = { TextButton(onClick = { Store.reportPrompt = false }) { Text("Not now") } },
  )
}
