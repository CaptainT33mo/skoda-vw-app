package dev.openvw.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Demo mode: a clearly labelled sample car, so people (and Play reviewers, who can't receive an owner's OTP) can
 * use the whole app without an account. Nothing here touches the network; the app shows a "Demo" bar throughout.
 */
object Demo {
  private data class Place(val title: String, val sub: String, val lat: Double, val lon: Double)

  private val places = listOf(
    Place("MG Road", "Bengaluru, Karnataka", 12.9756, 77.6050),
    Place("Indiranagar", "Bengaluru, Karnataka", 12.9784, 77.6408),
    Place("Koramangala", "Bengaluru, Karnataka", 12.9352, 77.6245),
    Place("Whitefield", "Bengaluru, Karnataka", 12.9698, 77.7500),
    Place("HSR Layout", "Bengaluru, Karnataka", 12.9116, 77.6474),
    Place("Hebbal", "Bengaluru, Karnataka", 13.0358, 77.5970),
  )

  // (from, to, km, minutes, start hour, days ago, score, type) — a believable week of driving
  private val plan = listOf(
    listOf(1, 0, 6.2, 24, 9, 0, 88, "Business"), listOf(0, 1, 6.8, 31, 18, 0, 81, "Business"),
    listOf(1, 2, 7.4, 26, 11, 1, 92, "Personal"), listOf(2, 1, 7.1, 29, 13, 1, 86, "Personal"),
    listOf(1, 3, 14.9, 48, 8, 2, 79, "Business"), listOf(3, 1, 15.3, 55, 19, 2, 74, "Business"),
    listOf(1, 4, 9.6, 33, 10, 3, 90, "Personal"), listOf(4, 1, 9.9, 35, 21, 3, 93, "Personal"),
    listOf(1, 5, 12.4, 41, 7, 5, 84, "Personal"), listOf(5, 1, 12.0, 44, 16, 5, 82, "Personal"),
    listOf(1, 0, 6.1, 22, 10, 6, 91, "Business"), listOf(0, 1, 6.9, 34, 17, 6, 77, "Business"),
  )

  const val ODOMETER = 18240

  /** Fill [Store] with the sample car for [brand] (a Slavia for Skoda, a Taigun for Volkswagen). */
  /** The demo account owns one car of each brand, to show the car switcher. */
  val cars = listOf(
    CarRef("demo-skoda", "DEMO-SKODA", "Demo car", "Slavia", "Style 1.0 TSI AT", Brand.SKODA, "DEMO 0001"),
    CarRef("demo-vw", "DEMO-VW", "Demo car", "Taigun", "GT Plus 1.5 TSI DSG", Brand.VW, "DEMO 0002"),
  )

  fun load(brand: Brand) {
    val nowS = System.currentTimeMillis() / 1000
    val vw = brand == Brand.VW
    Cars.clearCarState()
    Store.brand = brand
    Store.cars = cars
    Store.carVin = cars.first { it.brand == brand }.vin
    Store.vehicle = VehicleInfo(
      variant = if (vw) "GT Plus 1.5 TSI DSG" else "Style 1.0 TSI AT",
      model = if (vw) "Taigun" else "Slavia",
      vehicleName = "Demo car",
      regNo = if (vw) "DEMO 0002" else "DEMO 0001",
      colour = "Lava Blue",
      engineType = "DEMO",
      dongleImei = "000000000000000",
      dongleFirmware = "demo",
      status = "Driving",
    )
    Store.owner = Owner(name = "Demo Driver", email = "demo@carpulse.example", mobile = "+91 00000 00000", address = "Bengaluru, Karnataka")
    Store.telemetry = Telemetry(
      speedKmh = 38, rpm = 1850, fuelPct = 64, batteryV = 14.1, coolantC = 89, intakeC = 31, engineLoadPct = 30,
      odometerKm = ODOMETER, milOn = false, distanceWithMil = 0, distanceSinceDtc = 4210, engineOn = true,
    )

    val clock = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
    val day = SimpleDateFormat("d MMM", Locale.ENGLISH)
    // place the week's drives in time first, newest first, then walk the odometer and fuel back from today
    val timed = plan.mapIndexed { i, p ->
      val start = startOfDay(nowS - (p[5] as Int) * 86400L) + (p[4] as Int) * 3600L + (i % 3) * 600L
      Triple(i, p, start)
    }.filter { (_, p, start) -> start + (p[3] as Int) * 60L < nowS }.sortedByDescending { it.third }
    var odo = ODOMETER.toDouble()
    var fuel = 64
    val trips = timed.map { (i, p, start) ->
      val from = places[p[0] as Int]; val to = places[p[1] as Int]
      val km = p[2] as Double; val min = p[3] as Int
      val end = start + min * 60L
      val endOdo = odo; val startOdo = odo - km; odo = startOdo
      val endFuel = fuel; val startFuel = fuel + (km / 13.0 / 50.0 * 100).roundToInt().coerceAtLeast(1); fuel = startFuel
      Trip(
        id = "demo-$i", start = clock.format(Date(start * 1000)), end = clock.format(Date(end * 1000)),
        fromTitle = from.title, fromSub = from.sub, toTitle = to.title, toSub = to.sub,
        durMin = min, type = p[7] as String, date = day.format(Date(start * 1000)), km = km, score = p[6] as Int,
        epoch = start, endEpoch = end, drivingMin = (min * 0.8).toInt(), idleMin = (min * 0.12).toInt(),
        haltMin = min - (min * 0.8).toInt() - (min * 0.12).toInt(), maxSpeed = 48 + (i * 7) % 30,
        avgSpeed = (km / (min / 60.0)).roundToInt(), startOdo = startOdo, endOdo = endOdo,
        startFuelPct = startFuel, endFuelPct = endFuel, brakes = i % 3,
        startLat = from.lat, startLon = from.lon, endLat = to.lat, endLon = to.lon,
        alarms = listOfNotNull(
          (i % 3).takeIf { it > 0 }?.let { "Sudden brake" to it },
          if (i % 4 == 1) "Sharp turn" to 1 else null,
          if (i % 5 == 2) "Over the speed limit" to 2 else null,
        ).sortedByDescending { it.second },
        tags = if (i % 6 == 4) listOf(p[7] as String, "Airport run") else listOf(p[7] as String),
      )
    }
    Store.trips = trips

    val events = trips.flatMap { t ->
      listOf(
        CarEvent("${t.id}-off", "engine", "Engine off", t.endEpoch, t.endLat, t.endLon),
        CarEvent("${t.id}-on", "engine", "Engine on", t.epoch, t.startLat, t.startLon),
      )
    } + listOfNotNull(
      trips.getOrNull(2)?.let { CarEvent("demo-brake", "alarm", "Sudden brake", it.epoch + 600, it.startLat, it.startLon) },
      trips.getOrNull(5)?.let { CarEvent("demo-turn", "alarm", "Sharp turn", it.epoch + 900, it.startLat, it.startLon) },
    )
    Store.events = (listOf(CarEvent("demo-now", "engine", "Engine on", nowS - 420, places[1].lat, places[1].lon)) + events)
      .sortedByDescending { it.epoch }
    Store.parked = Parked(places[1].lat, places[1].lon, nowS - 420, places[1].title, places[1].sub, heading = 20)
    // today's drive so far: Koramangala up to Indiranagar, with a little wiggle so it reads as roads
    val a = places[2]; val b = places[1]
    Store.trail = (0 until 24).map { k ->
      val f = k / 24.0
      GeoPt(a.lat + (b.lat - a.lat) * f + 0.0018 * sin(f * 3 * PI), a.lon + (b.lon - a.lon) * f + 0.0012 * sin(f * 5 * PI))
    } + GeoPt(b.lat, b.lon) // ends exactly at the car

    val week = trips.filter { it.epoch > nowS - 7 * 86400 }
    Store.drive = DriveStats(
      tripCount = trips.size, avgScore = trips.map { it.score }.average().roundToInt(), maxSpeedKmh = trips.maxOf { it.maxSpeed },
      idleMin = trips.sumOf { it.idleMin }, harshBrakes = trips.sumOf { it.brakes }, realKmpl = 13.6,
      totalKm = trips.sumOf { it.km }, totalMin = trips.sumOf { it.durMin },
      weekTrips = week.size, weekKm = week.sumOf { it.km }, weekMin = week.sumOf { it.durMin },
    )
    Store.dongle = Dongle(signal = 4, status = "active", firmware = "demo")
    Store.alertSetup = AlertSetup(
      fences = listOf("Home", "Office"),
      triggers = listOf("Engine on", "Overspeed", "Geofence exit"),
      triggerStates = mapOf("Engine on" to true, "Overspeed" to true, "Geofence exit" to false),
      shapes = listOf(
        Fence("Home", center = GeoPt(places[1].lat, places[1].lon), radiusM = 350.0),
        Fence("Office", polygon = listOf(GeoPt(12.9790, 77.6010), GeoPt(12.9790, 77.6090), GeoPt(12.9722, 77.6090), GeoPt(12.9722, 77.6010))),
      ),
    )
    Store.plans = emptyList()
    Store.connectedSince = "2024-03-12"
    Store.pendingDrive = null
    Store.live = true
    Store.liveConnected = true
    Store.updatedAt = System.currentTimeMillis()
  }

  /** One step of the demo drive (call every ~1.5 s): city traffic between 0 and ~60 km/h. */
  fun tick(step: Int) {
    val phase = (step % 80) / 80.0 * 2 * PI
    val speed = (32 + 30 * sin(phase) + 6 * sin(phase * 5)).roundToInt().coerceIn(0, 70)
    val stopped = speed < 4
    Store.telemetry = Store.telemetry.copy(
      speedKmh = if (stopped) 0 else speed,
      rpm = if (stopped) 820 else 900 + speed * 32,
      engineLoadPct = if (stopped) 14 else 18 + speed / 3,
      coolantC = 89 + (step / 40) % 2,
    )
    Store.vehicle = Store.vehicle.copy(status = if (stopped) "Idling" else "Driving")
    Store.updatedAt = System.currentTimeMillis()
  }

  /** Back to an empty app (leaving demo mode). */
  fun clear() {
    Cars.clearCarState()
    Store.owner = Repo.owner; Store.plans = emptyList(); Store.connectedSince = ""; Store.cars = emptyList(); Store.carVin = ""
  }

  /** Demo trips inside a date range (the Trips date search, offline). */
  fun tripsBetween(startMs: Long, endMs: Long): List<Trip> = Store.trips.filter { it.epoch * 1000 in startMs..endMs }

  private fun startOfDay(epochS: Long): Long {
    val c = java.util.Calendar.getInstance().apply {
      timeInMillis = epochS * 1000
      set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
      set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis / 1000
  }
}
