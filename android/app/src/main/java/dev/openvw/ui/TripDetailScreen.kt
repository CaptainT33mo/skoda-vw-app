package dev.openvw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openvw.data.GoogleMaps
import dev.openvw.data.CarEvent
import dev.openvw.data.GeoPt
import dev.openvw.data.Trip
import dev.openvw.data.TripRoute
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import dev.openvw.theme.Red
import dev.openvw.theme.AmberDark
import dev.openvw.theme.AmberSoftBg
import dev.openvw.theme.Border
import dev.openvw.theme.Card
import dev.openvw.theme.Chip
import dev.openvw.theme.Field
import dev.openvw.theme.Ink
import dev.openvw.theme.LineSoft
import dev.openvw.theme.Muted

@Composable
fun TripDetailScreen(
  trip: Trip?, route: TripRoute?, loadingRoute: Boolean, onLoadRoute: (Trip) -> Unit, onBack: () -> Unit,
  focus: CarEvent? = null, // opened from an alert in Updates: show that alert on the map
) {
  val ctx = LocalContext.current
  LaunchedEffect(trip?.id) { trip?.let(onLoadRoute) }
  var selected by remember(trip?.id) { mutableStateOf<CarEvent?>(null) } // alert shown on the map
  LaunchedEffect(focus, route) {
    if (focus != null && route != null) selected = focusedAlert(focus, route.events)
  }
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    BackBar(onBack)
    Text("Trip details", style = H1, modifier = Modifier.padding(start = 20.dp, top = 4.dp))

    if (trip == null) {
      Text("Trip not available.", style = TextStyle(color = Muted, fontSize = 14.sp), modifier = Modifier.padding(20.dp))
      return@Column
    }

    // headline: duration · distance · date
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).card(24.dp).padding(18.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(trip.durMin.toString(), style = TextStyle(color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.0).sp).merge(Tnum))
          Text(
            if (trip.km > 0) "  min · %.1f km · %s".format(trip.km, trip.date) else "  min · ${trip.date}",
            style = TextStyle(color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
          )
        }
        if (trip.pending)
          Box(Modifier.clip(RoundedCornerShape(99.dp)).background(AmberSoftBg).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text("Finalizing…", style = TextStyle(color = AmberDark, fontSize = 12.sp, fontWeight = FontWeight.Bold))
          }
        else
          Box(Modifier.clip(RoundedCornerShape(99.dp)).background(Chip).border(1.dp, Border, RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(trip.type, style = TextStyle(color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
          }
      }
      Spacer(Modifier.height(16.dp))
      TripRoute(trip.start, trip.end, trip.fromTitle, trip.fromSub, trip.toTitle, trip.toSub)
      // the first tag is the chip above; any others
      val more = trip.tags.drop(1)
      if (!trip.pending && more.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          more.forEach { tag ->
            Box(Modifier.clip(RoundedCornerShape(99.dp)).background(Chip).border(1.dp, Border, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
              Text(tag, style = TextStyle(color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
          }
        }
      }
    }

    // the road actually driven, with where each alert happened
    if (!trip.pending) TripMap(trip, route, loadingRoute, selected) { selected = it }

    if (trip.pending) {
      Text(
        "Full trip stats (distance, mileage, driver score) arrive a little after you park.",
        style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 17.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
      )
    } else {
      // mileage highlight
      Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("MILEAGE (THIS TRIP)", style = label())
          Spacer(Modifier.height(4.dp))
          Text(
            if (trip.mileage > 0) "≈ %.1f km/l".format(trip.mileage) else "—",
            style = TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold).merge(Tnum),
          )
          // fuel comes in whole percent, so say how wide the honest answer is
          trip.mileageRange?.let { (lo, hi) ->
            Text(
              if (hi != null) "Somewhere between %.1f and %.1f".format(lo, hi) else "At least %.1f".format(lo),
              style = TextStyle(color = Muted, fontSize = 12.sp).merge(Tnum),
            )
          }
        }
        Column(horizontalAlignment = Alignment.End) {
          Text("FUEL USED", style = label())
          Spacer(Modifier.height(4.dp))
          val delta = trip.startFuelPct - trip.endFuelPct
          Text(if (delta > 0) "$delta%" else if (delta == 0) "Under 1%" else "Refuelled", style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold).merge(Tnum))
          Text("${trip.startFuelPct}% → ${trip.endFuelPct}%", style = TextStyle(color = Muted, fontSize = 11.sp).merge(Tnum))
        }
      }

      // full stat grid
      Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(horizontal = 18.dp, vertical = 4.dp)) {
        val rows = listOf(
          listOf("Distance" to km(trip.km), "Avg speed" to "${trip.avgSpeed} km/h"),
          listOf("Driving time" to mins(trip.drivingMin), "Max speed" to "${trip.maxSpeed} km/h"),
          listOf("Idle time" to mins(trip.idleMin), "Stopped" to mins(trip.haltMin)),
          listOf("Driver score" to "${trip.score}/100", "Hard brakes" to trip.brakes.toString()),
          listOf("Odometer start" to km(trip.startOdo), "Odometer end" to km(trip.endOdo)),
        )
        rows.forEachIndexed { i, pair ->
          if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
          Row(Modifier.fillMaxWidth()) {
            pair.forEach { (lbl, v) ->
              Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                Text(lbl.uppercase(), style = label())
                Spacer(Modifier.height(4.dp))
                Text(v, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold).merge(Tnum))
              }
            }
          }
        }
      }

      // every driving alert on this trip: each one with its time once the trip's events load, else the counts
      val instances = route?.events?.filter { it.kind == "alarm" }
      if (instances != null || trip.alarms != null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
          Text("DRIVING ALERTS", style = label())
          Spacer(Modifier.height(6.dp))
          val counts = trip.alarms.orEmpty()
          if (instances?.isEmpty() ?: counts.isEmpty()) Text("None on this trip", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
          if (instances != null) instances.forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            Row(
              Modifier.fillMaxWidth().clickable(enabled = e.lat != 0.0) { selected = e }.padding(vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Red))
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(e.title, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = if (e == selected) FontWeight.ExtraBold else FontWeight.Bold))
                if (e.detail.isNotBlank()) Text(e.detail, style = TextStyle(color = Muted, fontSize = 12.sp))
              }
              Text(clockOf(e.epoch), style = TextStyle(color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold).merge(Tnum))
            }
          } else counts.forEachIndexed { i, (name, count) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(name, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
              Text("×$count", style = TextStyle(color = AmberDark, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold).merge(Tnum))
            }
          }
        }
      }

      Text(
        "Mileage is estimated: the car reports its fuel level in whole percent (about half a litre each), never litres, so a short trip's figure is rough. Your average over many trips (Statistics) is much closer.",
        style = TextStyle(color = Muted, fontSize = 11.sp, lineHeight = 15.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
      )

      if (trip.endLat != 0.0) {
        Box(
          Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(99.dp)).background(Field).border(1.dp, dev.openvw.theme.Hairline, RoundedCornerShape(99.dp))
            .clickable {
              if (trip.startLat != 0.0) GoogleMaps.route(ctx, trip.startLat, trip.startLon, trip.endLat, trip.endLon)
              else GoogleMaps.pin(ctx, trip.endLat, trip.endLon)
            },
          contentAlignment = Alignment.Center,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            VIcon(Ic.navArrow, size = 16.dp, color = Ink, stroke = 2f)
            Spacer(Modifier.width(8.dp))
            Text("Directions in Google Maps", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
          }
        }
      }
    }
  }
}

/** The trip on a map: the GPS path the car reported, start / end, and a red dot per driving alert. */
@Composable
private fun TripMap(trip: Trip, route: TripRoute?, loading: Boolean, selected: CarEvent?, onSelect: (CarEvent?) -> Unit) {
  val path = route?.path.orEmpty()
  val ends = listOfNotNull(
    GeoPt(trip.startLat, trip.startLon).takeIf { trip.startLat != 0.0 },
    GeoPt(trip.endLat, trip.endLon).takeIf { trip.endLat != 0.0 },
  )
  val frame = path.ifEmpty { ends }
  if (frame.isEmpty()) return
  var mode by remember { mutableStateOf(MapMode.STREET) }
  val ink = Ink.toArgb()
  val alerts = route?.events.orEmpty().filter { it.kind == "alarm" && it.lat != 0.0 }
  val start = path.firstOrNull() ?: ends.first()
  val end = path.lastOrNull() ?: ends.last()
  // (pin, the alert it stands for); start and end stand for none
  val pins = listOf(MapPin(start.lat, start.lon, START) to null, MapPin(end.lat, end.lon, ink, big = true) to null) +
    alerts.map { MapPin(it.lat, it.lon, ALERT, big = it == selected) to it }
  Box(
    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(280.dp)
      .clip(RoundedCornerShape(24.dp)).border(1.dp, dev.openvw.theme.Hairline, RoundedCornerShape(24.dp)),
  ) {
    OsmMap(
      frame.first().lat, frame.first().lon, Modifier.matchParentSize(), interactive = true, showMarker = false, mode = mode,
      trail = path, pins = pins.map { it.first }, fitTo = frame, onPinClick = { i -> onSelect(pins.getOrNull(i)?.second) },
    )
    MapModeToggle(mode, Modifier.align(Alignment.TopEnd).padding(10.dp)) { mode = it }
    Box(Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Card).padding(horizontal = 10.dp, vertical = 6.dp)) {
      Text(
        when { path.size >= 2 -> "Route you drove"; loading -> "Loading route…"; else -> "Start and end only" },
        style = TextStyle(color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold),
      )
    }
    selected?.let { e ->
      Row(
        Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp, end = 60.dp)
          .clip(RoundedCornerShape(16.dp)).background(Card).clickable { onSelect(null) }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Red))
        Spacer(Modifier.width(8.dp))
        Column {
          Text(e.title, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
          Text(listOf(clockOf(e.epoch), e.detail).filter { it.isNotBlank() }.joinToString(" · "), style = TextStyle(color = Muted, fontSize = 11.sp))
        }
      }
    }
  }
  if (alerts.isNotEmpty()) Text(
    "Green: start · Red: driving alerts (tap one) · Large dot: end",
    style = TextStyle(color = Muted, fontSize = 11.sp), modifier = Modifier.padding(start = 22.dp, end = 20.dp, top = 8.dp),
  )
}

/** The trip's own copy of an alert from Updates: the same event id, else the same alert closest in time. */
internal fun focusedAlert(focus: CarEvent, events: List<CarEvent>): CarEvent? {
  val alerts = events.filter { it.kind == "alarm" && it.lat != 0.0 }
  return alerts.firstOrNull { it.id == focus.id }
    ?: alerts.filter { it.title == focus.title }.minByOrNull { kotlin.math.abs(it.epoch - focus.epoch) }
}

private const val START = 0xFF1E9E5A.toInt()
private const val ALERT = 0xFFD93A2B.toInt()
private fun clockOf(epoch: Long) = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(epoch * 1000))

private fun km(v: Double) = if (v > 0) "%.1f km".format(v) else "—"
private fun mins(v: Int) = if (v >= 60) "${v / 60}h ${v % 60}m" else "$v min"
