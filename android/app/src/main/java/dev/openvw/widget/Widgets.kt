package dev.openvw.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import dev.openvw.MainActivity
import dev.openvw.R
import dev.openvw.data.WidgetData
import dev.openvw.data.WidgetSnapshot
import dev.openvw.ui.NavRequest
import dev.openvw.ui.Screen

/** [opens]: the screen a tap takes you to. */
internal enum class Kind(val defW: Int, val defH: Int, val opens: Screen) {
  FUEL(150, 150, Screen.HOME), OVERVIEW(320, 158, Screen.HOME), LARGE(320, 336, Screen.HOME),
  TRIP(320, 150, Screen.TRIPS), PARKED(320, 150, Screen.LOCATE),
}

private fun dpToPx(ctx: Context, dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()

/** Opens the app on [screen]; one PendingIntent per screen (the request code keeps them apart). */
private fun openAppIntent(ctx: Context, screen: Screen): PendingIntent {
  val i = Intent(ctx, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    putExtra(NavRequest.EXTRA, screen.name)
  }
  return PendingIntent.getActivity(ctx, screen.ordinal, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

private fun draw(ctx: Context, kind: Kind, wDp: Float, hDp: Float, data: WidgetSnapshot, cap: Int, dark: Boolean): Bitmap {
  WidgetRender.dark = dark
  var w = dpToPx(ctx, wDp.toInt().coerceIn(80, 700))
  var h = dpToPx(ctx, hDp.toInt().coerceIn(80, 700))
  // cap pixel size (keep aspect) so the bitmaps stay within RemoteViews memory limits
  val longest = maxOf(w, h)
  if (longest > cap) { val f = cap.toFloat() / longest; w = (w * f).toInt(); h = (h * f).toInt() }
  return when (kind) {
    Kind.FUEL -> WidgetRender.fuel(ctx, w, h, data)
    Kind.OVERVIEW -> WidgetRender.overview(ctx, w, h, data)
    Kind.LARGE -> WidgetRender.large(ctx, w, h, data)
    Kind.TRIP -> WidgetRender.trip(ctx, w, h, data)
    Kind.PARKED -> WidgetRender.parked(ctx, w, h, data)
  }
}

/**
 * Both themes go into one RemoteViews: layout/widget_image shows the light bitmap and layout-night/widget_image
 * the dark one. The home screen inflates the layout with the system's day/night setting, so widgets follow the
 * system theme, and switch with it, without the app running.
 */
private fun views(ctx: Context, kind: Kind, wDp: Float, hDp: Float, data: WidgetSnapshot, cap: Int) =
  RemoteViews(ctx.packageName, R.layout.widget_image).apply {
    setImageViewBitmap(R.id.widget_image, draw(ctx, kind, wDp, hDp, data, cap, dark = false))
    setImageViewBitmap(R.id.widget_image_dark, draw(ctx, kind, wDp, hDp, data, cap, dark = true))
    setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx, kind.opens))
    // the large widget draws "Locate car" and "All trips" buttons along its bottom: make them real
    val buttons = kind == Kind.LARGE
    setViewVisibility(R.id.widget_buttons, if (buttons) android.view.View.VISIBLE else android.view.View.GONE)
    if (buttons) {
      setOnClickPendingIntent(R.id.widget_btn_left, openAppIntent(ctx, Screen.LOCATE))
      setOnClickPendingIntent(R.id.widget_btn_right, openAppIntent(ctx, Screen.TRIPS))
    }
  }

private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, kind: Kind) {
  val opts = mgr.getAppWidgetOptions(id)
  val data = WidgetData.load(ctx)

  // Android 12+: the launcher reports every size it may show the widget at (e.g. a foldable's
  // cover vs inner screen, portrait vs landscape). Draw one bitmap per size and let it pick.
  if (Build.VERSION.SDK_INT >= 31) {
    @Suppress("DEPRECATION")
    val sizes = opts.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
      ?.filter { it.width > 0 && it.height > 0 }?.distinct()?.take(4).orEmpty()
    if (sizes.isNotEmpty()) {
      // two bitmaps (light + dark) per size: keep the total within the RemoteViews bitmap budget
      val cap = if (sizes.size > 2) 640 else 900
      mgr.updateAppWidget(id, RemoteViews(sizes.associateWith { views(ctx, kind, it.width, it.height, data, cap) }))
      return
    }
  }

  // Older launchers: portrait shows MIN width + MAX height, landscape MAX width + MIN height.
  val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
  val wDp = (if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
             else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)).let { if (it <= 0) kind.defW else it }
  val hDp = (if (portrait) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
             else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).let { if (it <= 0) kind.defH else it }
  mgr.updateAppWidget(id, views(ctx, kind, wDp.toFloat(), hDp.toFloat(), data, 1000))
}

abstract class BaseWidget internal constructor(private val kind: Kind) : AppWidgetProvider() {
  override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
    ids.forEach { render(ctx, mgr, it, kind) }
  }

  override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
    render(ctx, mgr, id, kind)
  }
}

class FuelWidget : BaseWidget(Kind.FUEL)
class OverviewWidget : BaseWidget(Kind.OVERVIEW)
class LargeWidget : BaseWidget(Kind.LARGE)
class LastTripWidget : BaseWidget(Kind.TRIP)
class ParkedWidget : BaseWidget(Kind.PARKED)

/** Called by the app after fresh data loads, to refresh any placed widgets. */
object OpenVWWidgets {
  fun updateAll(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    for (cls in listOf(
      FuelWidget::class.java, OverviewWidget::class.java, LargeWidget::class.java,
      LastTripWidget::class.java, ParkedWidget::class.java,
    )) {
      val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
      if (ids.isNotEmpty()) {
        val i = Intent(ctx, cls).apply {
          action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        ctx.sendBroadcast(i)
      }
    }
  }
}
