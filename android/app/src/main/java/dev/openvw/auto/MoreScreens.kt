// setTitle/setHeaderAction/setActionStrip are deprecated in favour of Header, but Header needs car API level 7;
// these work on every Android Auto version.
@file:Suppress("DEPRECATION")

package dev.openvw.auto

import android.app.Application
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.openvw.AppGraph
import dev.openvw.data.Store
import dev.openvw.data.Trip
import java.util.Locale

/** Recent trips (the same list as the phone's Trips tab). */
internal class TripsScreen(ctx: CarContext) : LiveScreen(ctx, periodMs = 5_000L) {
  override fun key(): Any = Triple(Store.trips, Store.pendingDrive, Store.live)

  override fun render(): Template {
    val b = ListTemplate.Builder().setTitle("Recent trips").setHeaderAction(Action.BACK)
    if (!Store.live) return b.setLoading(true).build()
    val trips = listOfNotNull(Store.pendingDrive) + Store.trips.filter { it.id != Store.pendingDrive?.id }
    val list = ItemList.Builder().setNoItemsMessage("No trips in the last week")
    trips.take(carContext.limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 8)).forEach { list.addItem(row(it)) }
    return b.setSingleList(list.build()).build()
  }

  private fun row(t: Trip): Row {
    val r = Row.Builder()
      .setTitle(text("${t.fromTitle.ifBlank { "Start" }} → ${t.toTitle.ifBlank { "End" }}", t.toTitle.ifBlank { t.date }))
      .addText(text("${t.date} · ${t.start}–${t.end}", "${t.date} · ${t.start}"))
    if (t.pending) r.addText(colored("Finalising on the server…", CarColor.YELLOW))
    else {
      val parts = listOfNotNull(
        t.km.takeIf { it > 0 }?.let { "%.1f km".format(Locale.US, it) },
        "${t.durMin} min",
        t.score.takeIf { it > 0 }?.let { "score $it" },
      )
      r.addText(text(parts.joinToString(" · "), parts.take(2).joinToString(" · ")))
    }
    return r.build()
  }
}

/** Engine & dongle health in one pane. */
internal class HealthScreen(ctx: CarContext, private val hu: HeadUnit) : LiveScreen(ctx, periodMs = 2_000L) {
  override fun key(): Any = Triple(Dash.read(carContext, hu), Store.dongle, Store.telemetry.distanceWithMil to Store.telemetry.distanceSinceDtc)

  override fun render(): Template {
    val d = Dash.read(carContext, hu)
    val p = Pane.Builder()
    if (!d.hasData) return PaneTemplate.Builder(p.setLoading(true).build()).setTitle("Car health").setHeaderAction(Action.BACK).build()
    val t = Store.telemetry
    val rows = mutableListOf<Row>()

    rows += Row.Builder().setTitle(if (d.milOn) "Check-engine light ON" else "Check-engine light off")
      .addText(
        if (d.milOn) colored("Driven ${t.distanceWithMil ?: 0} km with it on — get it checked", CarColor.RED)
        else colored("No engine fault reported", CarColor.GREEN),
      ).build()

    d.batteryV?.let { v ->
      val engineOn = d.rpm != null
      val ok = GaugeArt.batteryColor(v, engineOn) == GaugeArt.GREEN
      rows += Row.Builder().setTitle(text("Battery %.1f V".format(Locale.US, v), "%.1f V".format(Locale.US, v)))
        .addText(
          if (ok) colored(if (engineOn) "Charging normally" else "Healthy at rest", CarColor.GREEN)
          else colored(if (engineOn) "Check charging system" else "Low — may struggle to start", CarColor.YELLOW),
        ).build()
    }

    val temps = listOfNotNull(d.coolantC?.let { "Coolant $it°C" }, d.intakeC?.let { "Intake $it°C" })
    if (temps.isNotEmpty()) rows += Row.Builder().setTitle(text(temps.joinToString(" · "), temps.first()))
      .addText(
        when (GaugeArt.coolantColor(d.coolantC)) {
          GaugeArt.BLUE -> text("Engine warming up")
          GaugeArt.GREEN -> colored("Normal operating temperature", CarColor.GREEN)
          else -> colored("Running hot — pull over safely if it keeps rising", CarColor.RED)
        },
      ).build()

    val dg = Store.dongle
    val dongle = Row.Builder().setTitle(text(if (d.feedOn) "Telematics: live" else "Telematics: ${dg.status.ifBlank { "idle" }}", "Telematics"))
    val sub = listOfNotNull(
      dg.signal.takeIf { it > 0 }?.let { "signal $it" },
      t.distanceSinceDtc?.let { "%,d km since diagnostics reset".format(Locale.US, it) },
    )
    if (sub.isNotEmpty()) dongle.addText(text(sub.joinToString(" · "), sub.first()))
    rows += dongle.build()

    rows.take(carContext.limit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE, 4)).forEach { p.addRow(it) }
    return PaneTemplate.Builder(p.build()).setTitle("Car health").setHeaderAction(Action.BACK).build()
  }
}

/** Shortcuts plus the car-sensor permission (speed, fuel and odometer straight from the head unit). */
internal class MoreScreen(ctx: CarContext, private val hu: HeadUnit) : LiveScreen(ctx, periodMs = 2_000L) {
  override fun key(): Any = hu.missingPermissions() to Store.liveConnected

  override fun render(): Template {
    val list = ItemList.Builder()
    list.addItem(Row.Builder().setTitle("Recent trips").setBrowsable(true)
      .setOnClickListener { screenManager.push(TripsScreen(carContext)) }.build())
    list.addItem(Row.Builder().setTitle("Car health").setBrowsable(true)
      .setOnClickListener { screenManager.push(HealthScreen(carContext, hu)) }.build())

    if (hu.supported) {
      val missing = hu.missingPermissions()
      list.addItem(
        if (missing.isEmpty()) Row.Builder().setTitle("Car sensors: on")
          .addText(text("Speed, fuel and odometer come straight from your car when it shares them", "Speed & fuel from car"))
          .build()
        else Row.Builder().setTitle("Use your car's own sensors")
          .addText(text("Live speed & fuel from the car — tap, then allow on your phone", "Tap, then allow on phone"))
          .setOnClickListener {
            carContext.requestPermissions(missing) { _, _ -> hu.restart(); invalidate() }
          }
          .build(),
      )
    }

    list.addItem(Row.Builder().setTitle("Refresh now")
      .addText(text(if (Store.liveConnected) "Live feed connected" else "Live feed reconnecting…", if (Store.liveConnected) "Live" else "Reconnecting"))
      .setOnClickListener {
        AppGraph.vm(carContext.applicationContext as Application).poll()
        CarToast.makeText(carContext, "Refreshing…", CarToast.LENGTH_SHORT).show()
      }.build())

    return ListTemplate.Builder().setTitle("CarPulse").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
  }
}
