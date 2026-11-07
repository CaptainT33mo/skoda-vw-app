package dev.openvw.work

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.openvw.AppGraph
import dev.openvw.MainActivity
import dev.openvw.R
import dev.openvw.data.Alerts
import dev.openvw.data.Session
import dev.openvw.ui.AppViewModel

/**
 * Instant car alerts. Skoda's own apps get engine on/off and driving alarms as push messages from Skoda's servers,
 * which only reach those apps. CarPulse instead keeps the car's live feed (the same connection the app uses on
 * screen) open in the background, so the car's "events" messages arrive within seconds. Android only lets an app
 * do that from a foreground service, hence the small ongoing notification. Off when car alerts or "Instant alerts"
 * are off, when signed out, and in demo mode; the 15-minute [CarCheckWorker] still catches anything missed.
 */
class CarWatchService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    runCatching { goForeground() }.onFailure { Log.w(TAG, "watch: can't start in the foreground", it); stopSelf(); return }
    AppGraph.vm(application).acquireLive(AppViewModel.HOLDER_ALERTS)
    Log.d(TAG, "watch: started")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!shouldRun(this)) { stopSelf(); return START_NOT_STICKY }
    return START_STICKY // restarted by the system if it's killed for memory
  }

  override fun onDestroy() {
    AppGraph.vm(application).releaseLive(AppViewModel.HOLDER_ALERTS)
    Log.d(TAG, "watch: stopped")
    super.onDestroy()
  }

  private fun goForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watching for car alerts", NotificationManager.IMPORTANCE_MIN).apply {
        description = "The ongoing notification while CarPulse keeps a live connection to your car"
        setShowBadge(false)
      })
    val open = PendingIntent.getActivity(
      this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_carpulse_mark)
      .setContentTitle("Watching your car")
      .setContentText("Engine and driving alerts arrive as they happen")
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setOngoing(true)
      .setSilent(true)
      .setShowWhen(false)
      .setContentIntent(open)
      .build()
    ServiceCompat.startForeground(
      this, NOTIFICATION_ID, n,
      if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
    )
  }

  companion object {
    private const val TAG = "CarPulse"
    private const val CHANNEL = "car_watch"
    private const val NOTIFICATION_ID = 7

    fun shouldRun(ctx: Context): Boolean {
      val s = Session(ctx)
      return Alerts.isOn(ctx) && Alerts.instant(ctx) && s.loggedIn && !s.demo && CarNotifications.canPost(ctx)
    }

    /**
     * Start or stop the watch to match the settings. Android only allows starting it while the app is on screen
     * or right after a reboot / update, so a refused start (the app in the background) is ignored here.
     */
    fun sync(ctx: Context) {
      val i = Intent(ctx, CarWatchService::class.java)
      if (shouldRun(ctx)) runCatching { ContextCompat.startForegroundService(ctx, i) }
        .onFailure { Log.w(TAG, "watch: start refused (${it.javaClass.simpleName})") }
      else ctx.stopService(i)
    }
  }
}

/** Brings the watch and the 15-minute check back after the phone restarts or CarPulse is updated. */
class CarWatchBoot : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    CarCheckWorker.schedule(ctx)
    CarWatchService.sync(ctx)
  }
}

/** Engine on/off and driving alarm notifications, from live pushes and from the event list alike. */
object CarEventAlerts {
  private val lock = kotlinx.coroutines.sync.Mutex()

  /** Notifies each of [events] not notified before (see [Alerts.newEvents]); safe to call with the whole list. */
  suspend fun check(ctx: Context, events: List<dev.openvw.data.CarEvent>, carName: String) {
    if (!Alerts.isOn(ctx) || events.isEmpty()) return
    lock.lock()
    try {
      val (fresh, seen) = Alerts.newEvents(events, Alerts.eventSeen(ctx), System.currentTimeMillis() / 1000)
      Alerts.saveEventSeen(ctx, seen)
      val enabled = Alerts.enabled(ctx)
      val post = fresh.filter { Alerts.typeOf(it) in enabled }.takeLast(6) // after a long gap: the latest few only
      val cal = java.util.Calendar.getInstance()
      post.forEach { e ->
        val place = if (e.lat == 0.0) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          runCatching { dev.openvw.data.Places.lookup(ctx, e.lat, e.lon).first }.getOrNull()
        }
        val night = e.kind == "engine" && e.title == "Engine on" && Alerts.Type.NIGHT_START in enabled &&
          Alerts.isNight(e.epoch) { s -> cal.timeInMillis = s * 1000; cal.get(java.util.Calendar.HOUR_OF_DAY) }
        CarNotifications.postEvent(ctx, e, carName, place, night)
      }
      if (post.isNotEmpty()) Log.d("CarPulse", "event alerts: ${post.map { it.title }}")
    } finally { lock.unlock() }
  }
}
