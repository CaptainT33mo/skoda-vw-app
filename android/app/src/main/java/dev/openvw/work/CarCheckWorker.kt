package dev.openvw.work

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.openvw.AppGraph
import dev.openvw.MainActivity
import dev.openvw.R
import dev.openvw.data.Alerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Every ~15 minutes (Android's minimum for periodic work, and only with a network): refresh the car's data the
 * same way the app does (which also redraws the home-screen widgets), then post any [Alerts] that are due.
 * Nothing runs while alerts are off.
 */
class CarCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    val ctx = applicationContext
    if (!Alerts.isOn(ctx)) return Result.success()
    // the app's state lives on the main thread
    val snap = withContext(Dispatchers.Main) { AppGraph.vm(ctx as Application).backgroundCheck() } ?: return Result.success()
    val hour = java.util.Calendar.getInstance()
    val clock = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
    val (alerts, next) = Alerts.evaluate(
      snap, Alerts.state(ctx), Alerts.enabled(ctx),
      hourOf = { s -> hour.timeInMillis = s * 1000; hour.get(java.util.Calendar.HOUR_OF_DAY) },
      clock = { s -> clock.format(java.util.Date(s * 1000)) },
    )
    Alerts.saveState(ctx, next)
    alerts.forEach { CarNotifications.post(ctx, it) }
    Log.d(TAG, "car check: status=${snap.status} signedIn=${snap.signedIn} alerts=${alerts.map { it.type }}")
    return Result.success()
  }

  companion object {
    private const val TAG = "CarPulse"
    private const val WORK = "car-check"

    /** Start (or keep) the 15-minute check when alerts are on; stop it when they're off. */
    fun schedule(ctx: Context) {
      val wm = WorkManager.getInstance(ctx)
      if (!Alerts.isOn(ctx)) { wm.cancelUniqueWork(WORK); return }
      val req = PeriodicWorkRequestBuilder<CarCheckWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
      wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
  }
}

object CarNotifications {
  private const val CHANNEL = "car_alerts"

  fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun channel(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null)
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car alerts", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Battery, check-engine light, night-time engine starts, fuel and sign-in"
      })
  }

  private const val CH_ENGINE = "car_engine"
  private const val CH_DRIVING = "car_driving"

  private fun eventChannels(ctx: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CH_ENGINE) == null)
      nm.createNotificationChannel(NotificationChannel(CH_ENGINE, "Engine on / off", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "When your car's engine is switched on or off"
      })
    if (nm.getNotificationChannel(CH_DRIVING) == null)
      nm.createNotificationChannel(NotificationChannel(CH_DRIVING, "Driving alerts", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Sudden braking, sharp turns, speeding and other alarms from your car"
      })
  }

  /** "Engine on" / "Sudden brake" with the car, time, place and detail; tapping opens Updates. */
  fun postEvent(ctx: Context, e: dev.openvw.data.CarEvent, carName: String, place: String?, night: Boolean) {
    if (!canPost(ctx)) return
    eventChannels(ctx)
    val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(e.epoch * 1000))
    val engine = e.kind == "engine"
    val title = if (night) "${e.title} at night" else e.title
    val text = listOfNotNull(carName.ifBlank { null }, time, place?.ifBlank { null }?.let { if (engine) "at $it" else "near $it" }, e.detail.ifBlank { null })
      .joinToString(" · ")
    val big = if (night) "$text\nThe engine was switched on during the night. If this wasn't you or someone you know, check on your car." else text
    val open = PendingIntent.getActivity(
      ctx, 2,
      Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(dev.openvw.ui.NavRequest.EXTRA, dev.openvw.ui.Screen.ALERTS.name),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = NotificationCompat.Builder(ctx, if (engine) CH_ENGINE else CH_DRIVING)
      .setSmallIcon(R.drawable.ic_carpulse_mark)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(big))
      .setWhen(e.epoch * 1000).setShowWhen(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(if (engine) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_EVENT)
      .setGroup("car_events")
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    @Suppress("MissingPermission") // checked in canPost()
    NotificationManagerCompat.from(ctx).notify(Alerts.eventKey(e).hashCode(), n)
  }

  fun post(ctx: Context, a: Alerts.Alert) {
    if (!canPost(ctx)) return
    channel(ctx)
    val open = PendingIntent.getActivity(
      ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = NotificationCompat.Builder(ctx, CHANNEL)
      .setSmallIcon(R.drawable.ic_carpulse_mark)
      .setContentTitle(a.title)
      .setContentText(a.text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    @Suppress("MissingPermission") // checked in canPost()
    NotificationManagerCompat.from(ctx).notify(a.type.ordinal + 100, n)
  }
}
