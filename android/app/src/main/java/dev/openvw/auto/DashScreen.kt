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
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import dev.openvw.R
import java.util.Locale

/**
 * The live dashboard: a grid of gauge tiles (speed, fuel, range, engine, battery, RPM), with "Details" and "More"
 * as screens of their own.
 *
 * Android Auto allows an app only 5 template "steps" per task and closes it with an error after that. An update
 * is free (a "refresh") only while the screen title, the number of tiles and every tile title stay the same, so
 * here they never change: titles are fixed labels, the tile count is fixed when the screen opens, and the live
 * numbers go in the ring images and the tiles' second lines. Details is pushed as its own screen, so going back
 * hands its steps back (swapping templates in place used one every time).
 */
internal class DashScreen(ctx: CarContext, private val hu: HeadUnit) : LiveScreen(ctx) {
  /** Tiles to send, fixed for this screen's life (a changing count would cost a step each time). */
  private val tileCount = carContext.limit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID, 6)
  private val bigTiles = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_8
  private var shownTitle: String? = null

  override fun key(): Any = Dash.read(carContext, hu)

  override fun render(): Template {
    val d = Dash.read(carContext, hu)
    if (!d.signedIn) return signInTemplate()
    val b = GridTemplate.Builder()
      .setTitle(title(d))
      .setHeaderAction(Action.APP_ICON)
      .setActionStrip(strip())
    if (bigTiles) b.setItemSize(GridTemplate.ITEM_SIZE_LARGE)
    if (!d.hasData) return b.setLoading(true).build()
    val list = ItemList.Builder()
    tiles(d).take(tileCount).forEach { list.addItem(it) }
    return b.setSingleList(list.build()).build()
  }

  private fun tiles(d: Dash): List<GridItem> {
    val dark = carContext.isDarkMode
    val fuelColor = GaugeArt.fuelColor(d.fuelPct, d.fuelLow)
    val parked = d.status == "Parked"
    return listOf(
      // 1. speed: the head unit's own reading when the car shares it; the second line says how fresh it is
      tile(
        "Speed",
        GaugeArt.ring(d.speedKmh?.let { it / GaugeArt.MAX_SPEED } ?: if (parked) 0f else null, GaugeArt.ACCENT, dark,
          d.speedKmh?.toString() ?: if (parked) "0" else "—"),
        statusLine(d),
      ),
      // 2. fuel
      tile(
        "Fuel",
        GaugeArt.ring(d.fuelPct?.let { it / 100f }, fuelColor, dark, d.fuelPct?.let { "$it%" } ?: "—"),
        if (d.fuelLow) colored("Low — refuel soon", CarColor.RED) else text("of the tank", "tank"),
      ),
      // 3. range
      tile(
        "Range",
        GaugeArt.ring(d.rangeKm?.let { it.toFloat() / d.rangeMaxKm.coerceAtLeast(1) }, fuelColor, dark, d.rangeKm?.toString() ?: "—"),
        text("km left (estimate)", "km left"),
      ),
      // 4. engine: the check-engine light wins over the coolant temperature when it's on
      if (d.milOn) tile("Engine", GaugeArt.ring(1f, GaugeArt.RED, dark, "!"), colored("Check-engine light ON", CarColor.RED))
      else tile(
        "Engine",
        GaugeArt.ring(d.coolantC?.let { (it - 40) / 80f }, GaugeArt.coolantColor(d.coolantC), dark, d.coolantC?.let { "$it°" } ?: "—"),
        when (GaugeArt.coolantColor(d.coolantC)) {
          GaugeArt.BLUE -> text("Coolant · warming up", "Warming up")
          GaugeArt.AMBER, GaugeArt.RED -> colored("Coolant · running hot", CarColor.RED)
          else -> text("Coolant °C", "Coolant")
        },
      ),
      // 5. battery
      tile(
        "Battery",
        GaugeArt.ring(d.batteryV?.let { ((it - 11.0) / 4.0).toFloat() }, GaugeArt.batteryColor(d.batteryV, d.rpm != null), dark,
          d.batteryV?.let { "%.1f".format(Locale.US, it) } ?: "—"),
        text("volts", "V"),
      ),
      // 6. rpm (the dongle keeps its last value after the engine stops, so none while parked)
      tile(
        "RPM",
        GaugeArt.ring(d.rpm?.let { it / 7000f } ?: if (parked) 0f else null, if ((d.rpm ?: 0) > 6000) GaugeArt.RED else GaugeArt.BLUE, dark,
          d.rpm?.let { "%,d".format(Locale.US, it) } ?: if (parked) "0" else "—"),
        text(if (parked) "engine off" else "engine speed", if (parked) "off" else "rpm"),
      ),
    )
  }

  private fun tile(title: String, icon: CarIcon, sub: CarText): GridItem =
    GridItem.Builder().setTitle(title).setText(sub).setImage(icon, GridItem.IMAGE_TYPE_LARGE).build()

  /** "km/h · Driving · Live", "Parked · 14:05", … (shorter variants for narrow panels). */
  private fun statusLine(d: Dash): CarText {
    val fresh = when {
      d.feedOn -> "Live"
      d.updatedAt > 0 -> clock(d.updatedAt)
      else -> ""
    }
    val long = listOf(if (d.speedKmh != null) "km/h" else "", d.status, fresh).filter { it.isNotBlank() }.joinToString(" · ")
    return text(long, listOf(d.status, fresh).filter { it.isNotBlank() }.joinToString(" · "), d.status)
  }

  /** The car's name, frozen once known: a changing title would also cost a step. */
  private fun title(d: Dash): String = shownTitle ?: if (d.hasData) d.model.also { shownTitle = it } else "CarPulse"

  /** Details (its own screen) and More. Max 1 titled action allowed. */
  private fun strip(): ActionStrip = ActionStrip.Builder()
    .addAction(
      Action.Builder().setTitle("Details").setOnClickListener { screenManager.push(DetailsScreen(carContext, hu)) }.build(),
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

/**
 * Every value as rows beside a mini cluster. Row titles are fixed labels and there are always 4 rows, so live
 * updates stay refreshes (see [DashScreen]); the values are in the rows' text lines and the cluster image.
 */
internal class DetailsScreen(ctx: CarContext, private val hu: HeadUnit) : LiveScreen(ctx) {
  override fun key(): Any = Dash.read(carContext, hu)

  override fun render(): Template {
    val d = Dash.read(carContext, hu)
    val p = Pane.Builder()
    val b = { pane: Pane -> PaneTemplate.Builder(pane).setTitle("Details").setHeaderAction(Action.BACK).build() }
    if (!d.hasData) return b(p.setLoading(true).build())

    // speed + rpm
    p.addRow(Row.Builder().setTitle("Speed")
      .addText(when {
        d.speedKmh != null && d.rpm != null -> text("${d.speedKmh} km/h · ${"%,d".format(Locale.US, d.rpm)} rpm", "${d.speedKmh} km/h")
        d.speedKmh != null -> text("${d.speedKmh} km/h")
        else -> text(d.status)
      })
      .addText(sourceLine(d))
      .build())

    // fuel + range + odometer
    p.addRow(Row.Builder().setTitle("Fuel")
      .addText(text("${d.fuelPct ?: "—"}% · ~${d.rangeKm ?: "—"} km range", "${d.fuelPct ?: "—"}% · ${d.rangeKm ?: "—"} km"))
      .addText(
        if (d.fuelLow) colored("Fuel low — refuel soon", CarColor.RED)
        else text(d.odometerKm?.let { "Odometer %,d km".format(Locale.US, it) } ?: "Odometer —", d.odometerKm?.let { "%,d km".format(Locale.US, it) } ?: "—"),
      )
      .build())

    // engine health
    val eng = listOfNotNull(d.coolantC?.let { "Coolant $it°C" }, d.batteryV?.let { "Battery %.1f V".format(Locale.US, it) })
    val engShort = listOfNotNull(d.coolantC?.let { "$it°C" }, d.batteryV?.let { "%.1f V".format(Locale.US, it) })
    p.addRow(Row.Builder().setTitle("Engine")
      .addText(if (eng.isEmpty()) text("—") else text(eng.joinToString(" · "), engShort.joinToString(" · ")))
      .addText(
        if (d.milOn) colored("Check-engine light is ON", CarColor.RED)
        else {
          val more = listOfNotNull(d.engineLoadPct?.let { "Load $it%" }, d.intakeC?.let { "Intake $it°C" })
          if (more.isEmpty()) colored("No engine warnings", CarColor.GREEN) else text(more.joinToString(" · "))
        },
      )
      .build())

    // where
    val where = Row.Builder().setTitle("Location")
      .addText(text(if (d.place.isBlank()) "Not known yet" else if (d.moving) "Near ${d.place}" else "At ${d.place}", d.place.ifBlank { "—" }))
    if (d.placeSub.isNotBlank()) where.addText(text(d.placeSub))
    p.addRow(where.build())

    p.setImage(GaugeArt.cluster(d.speedKmh, d.rpm, d.fuelPct, GaugeArt.fuelColor(d.fuelPct, d.fuelLow), carContext.isDarkMode))
    p.addAction(Action.Builder().setTitle("Trips").setOnClickListener { screenManager.push(TripsScreen(carContext)) }.build())
    p.addAction(Action.Builder().setTitle("Car health").setOnClickListener { screenManager.push(HealthScreen(carContext, hu)) }.build())
    return b(p.build())
  }

  private fun sourceLine(d: Dash): CarText = when {
    d.speedFromCar && d.feedOn -> text("Speed from car · engine data live", "Live")
    d.speedFromCar -> text("Speed from car · engine data ${updated(d)}", "From car")
    d.feedOn -> colored("Live from your car", CarColor.GREEN)
    else -> text("Last update ${updated(d)}", updated(d))
  }

  private fun updated(d: Dash) = if (d.updatedAt > 0) clock(d.updatedAt) else "—"
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
