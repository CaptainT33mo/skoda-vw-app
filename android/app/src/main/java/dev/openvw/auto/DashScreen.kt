// setTitle/setHeaderAction/setActionStrip are deprecated in favour of Header, but Header needs car API level 7;
// these work on every Android Auto version.
@file:Suppress("DEPRECATION")

package dev.openvw.auto

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import dev.openvw.R
import java.util.Locale

/**
 * The live dashboard. Two layouts, remembered per phone:
 *  - Glance (default): a grid of gauge tiles — speed, fuel, range first. Grids reflow to the panel width, so
 *    when Android Auto shows the app in a narrow column beside Maps (split screen / Coolwalk) you still get
 *    the top tiles at full size; on a wide screen you get them all.
 *  - Details: rows with every engine value plus a mini cluster image the host drops on narrow panels.
 * All text carries short variants ([CarText] variants): the host picks the longest one that fits, so lines
 * shrink to "62 km/h" or "64% · 420 km" in a narrow panel instead of being cut off.
 */
internal class DashScreen(ctx: CarContext, private val hu: HeadUnit) : LiveScreen(ctx) {
  private val prefs = ctx.getSharedPreferences("openvw_auto", CarContext.MODE_PRIVATE)
  private var details: Boolean
    get() = prefs.getBoolean("details", false)
    set(v) = prefs.edit { putBoolean("details", v) }

  override fun key(): Any = Dash.read(carContext, hu) to details

  override fun render(): Template {
    val d = Dash.read(carContext, hu)
    if (!d.signedIn) return signInTemplate()
    return if (details) detailsTemplate(d) else glanceTemplate(d)
  }

  // ---- Glance: gauge tiles -------------------------------------------------------------------------

  private fun glanceTemplate(d: Dash): Template {
    val b = GridTemplate.Builder()
      .setTitle(title(d))
      .setHeaderAction(Action.APP_ICON)
      .setActionStrip(strip(toggleLabel = "Details"))
    if (!d.hasData) return b.setLoading(true).build()

    val dark = carContext.isDarkMode
    val tiles = mutableListOf<GridItem>()
    // 1. speed — the head unit's reading when the car shares it
    tiles += tile(
      title = d.speedKmh?.let { text("$it km/h", "$it") } ?: text(if (d.status == "Parked") "Parked" else "— km/h", "—"),
      sub = text(if (d.speedFromCar) "Speed · from car" else "Speed", "Speed"),
      icon = GaugeArt.ring(d.speedKmh?.let { it / GaugeArt.MAX_SPEED }, GaugeArt.ACCENT, dark),
    )
    // 2. fuel
    val fuelColor = GaugeArt.fuelColor(d.fuelPct, d.fuelLow)
    tiles += tile(
      title = d.fuelPct?.let { text("$it%", "$it%") } ?: text("—"),
      sub = if (d.fuelLow) colored("Fuel low", CarColor.RED) else text("Fuel"),
      icon = GaugeArt.ring(d.fuelPct?.let { it / 100f }, fuelColor, dark),
    )
    // 3. range
    tiles += tile(
      title = d.rangeKm?.let { text("~$it km", "$it km") } ?: text("—"),
      sub = text("Range"),
      icon = GaugeArt.ring(d.rangeKm?.let { it.toFloat() / d.rangeMaxKm.coerceAtLeast(1) }, fuelColor, dark),
    )
    // 4. engine: check-engine light wins over the temperature when it's on
    tiles += if (d.milOn) tile(
      title = text("Check engine", "MIL on"),
      sub = colored("Engine light is on", CarColor.RED),
      icon = GaugeArt.ring(1f, GaugeArt.RED, dark),
    ) else tile(
      title = d.coolantC?.let { text("$it°C") } ?: text("—"),
      sub = text("Coolant"),
      icon = GaugeArt.ring(d.coolantC?.let { ((it - 40) / 80f) }, GaugeArt.coolantColor(d.coolantC), dark),
    )
    // 5. battery, 6. rpm
    tiles += tile(
      title = d.batteryV?.let { text("%.1f V".format(Locale.US, it)) } ?: text("—"),
      sub = text("Battery"),
      icon = GaugeArt.ring(d.batteryV?.let { ((it - 11.0) / 4.0).toFloat() }, GaugeArt.batteryColor(d.batteryV, d.rpm != null), dark),
    )
    tiles += tile(
      title = d.rpm?.let { text("%,d rpm".format(Locale.US, it), "%,d".format(Locale.US, it)) } ?: text("—"),
      sub = text("Engine speed", "RPM"),
      icon = GaugeArt.ring(d.rpm?.let { it / 7000f }, if ((d.rpm ?: 0) > 6000) GaugeArt.RED else GaugeArt.BLUE, dark),
    )

    val list = ItemList.Builder()
    tiles.take(carContext.limit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID, 6)).forEach { list.addItem(it) }
    return b.setSingleList(list.build()).build()
  }

  private fun tile(title: CarText, sub: CarText, icon: CarIcon): GridItem =
    GridItem.Builder().setTitle(title).setText(sub).setImage(icon, GridItem.IMAGE_TYPE_LARGE).build()

  // ---- Details: rows + mini cluster ----------------------------------------------------------------

  private fun detailsTemplate(d: Dash): Template {
    val b = PaneTemplate.Builder(pane(d))
      .setTitle(title(d))
      .setHeaderAction(Action.APP_ICON)
      .setActionStrip(strip(toggleLabel = "Glance"))
    return b.build()
  }

  private fun pane(d: Dash): Pane {
    val p = Pane.Builder()
    if (!d.hasData) return p.setLoading(true).build()
    val rows = mutableListOf<Row>()

    // speed + rpm
    rows += Row.Builder()
      .setTitle(
        when {
          d.speedKmh != null && d.rpm != null -> text(
            "${d.speedKmh} km/h · ${"%,d".format(Locale.US, d.rpm)} rpm", "${d.speedKmh} km/h",
          )
          d.speedKmh != null -> text("${d.speedKmh} km/h")
          else -> text(d.status)
        },
      )
      .addText(sourceLine(d))
      .build()

    // fuel + range
    val fuelRow = Row.Builder()
      .setTitle(text("Fuel ${d.fuelPct ?: "—"}% · ~${d.rangeKm ?: "—"} km range", "${d.fuelPct ?: "—"}% · ${d.rangeKm ?: "—"} km"))
    if (d.fuelLow) fuelRow.addText(colored("Fuel low — refuel soon", CarColor.RED))
    d.odometerKm?.let { fuelRow.addText(text("Odometer %,d km".format(Locale.US, it), "%,d km".format(Locale.US, it))) }
    rows += fuelRow.build()

    // engine health
    val eng = listOfNotNull(
      d.coolantC?.let { "Coolant $it°C" },
      d.batteryV?.let { "Battery %.1f V".format(Locale.US, it) },
    )
    val engShort = listOfNotNull(d.coolantC?.let { "$it°C" }, d.batteryV?.let { "%.1f V".format(Locale.US, it) })
    rows += Row.Builder()
      .setTitle(if (eng.isEmpty()) text("Engine") else text(eng.joinToString(" · "), engShort.joinToString(" · ")))
      .addText(
        if (d.milOn) colored("Check-engine light is ON", CarColor.RED)
        else {
          val more = listOfNotNull(d.engineLoadPct?.let { "Load $it%" }, d.intakeC?.let { "Intake $it°C" })
          if (more.isEmpty()) colored("No engine warnings", CarColor.GREEN) else text(more.joinToString(" · "))
        },
      )
      .build()

    // where
    if (d.place.isNotBlank()) {
      val where = Row.Builder().setTitle(text(if (d.moving) "Near ${d.place}" else "At ${d.place}", d.place))
      if (d.placeSub.isNotBlank()) where.addText(text(d.placeSub))
      rows += where.build()
    }

    rows.take(carContext.limit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE, 4)).forEach { p.addRow(it) }
    p.setImage(GaugeArt.cluster(d.speedKmh, d.rpm, d.fuelPct, GaugeArt.fuelColor(d.fuelPct, d.fuelLow), carContext.isDarkMode))
    p.addAction(Action.Builder().setTitle("Trips").setOnClickListener { screenManager.push(TripsScreen(carContext)) }.build())
    p.addAction(Action.Builder().setTitle("Car health").setOnClickListener { screenManager.push(HealthScreen(carContext, hu)) }.build())
    return p.build()
  }

  // ---- shared --------------------------------------------------------------------------------------

  /** Title: status first, so a cut-off title still says the important part. */
  private fun title(d: Dash): String = buildString {
    append(d.status)
    when {
      d.feedOn -> append(" · Live")
      d.stale && d.updatedAt > 0 -> append(" · ").append(clock(d.updatedAt))
      !d.hasData -> {}
      else -> append(" · ").append(d.model)
    }
  }

  private fun sourceLine(d: Dash): CarText = when {
    d.speedFromCar && d.feedOn -> text("Speed from car · engine data live", "Live")
    d.speedFromCar -> text("Speed from car · engine data ${updated(d)}", "From car")
    d.feedOn -> colored("Live from your car", CarColor.GREEN)
    else -> text("Last update ${updated(d)}", updated(d))
  }

  private fun updated(d: Dash) = if (d.updatedAt > 0) clock(d.updatedAt) else "—"

  /** Action strip: the layout toggle and "More" (trips, health, car sensors). Max 1 titled action allowed. */
  private fun strip(toggleLabel: String): ActionStrip = ActionStrip.Builder()
    .addAction(
      Action.Builder().setTitle(toggleLabel).setOnClickListener { details = !details; invalidate() }.build(),
    )
    .addAction(
      Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_auto_more)).build())
        .setOnClickListener { screenManager.push(MoreScreen(carContext, hu)) }
        .build(),
    )
    .build()

  private fun signInTemplate(): Template = MessageTemplate.Builder(
    "Sign in to CarPulse on your phone once. Your car's live data will then show up here.",
  )
    .setTitle("CarPulse")
    .setHeaderAction(Action.APP_ICON)
    .addAction(Action.Builder().setTitle("Retry").setOnClickListener { invalidate() }.build())
    .build()

}

/** Long text with optional shorter variants, longest first; the host shows the longest that fits. */
internal fun text(long: String, vararg shorter: String): CarText {
  val b = CarText.Builder(long)
  shorter.filter { it.isNotBlank() && it != long }.forEach { b.addVariant(it) }
  return b.build()
}

internal fun colored(s: String, color: CarColor): CarText {
  val sp = SpannableString(s)
  sp.setSpan(ForegroundCarColorSpan.create(color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
  return CarText.create(sp)
}
