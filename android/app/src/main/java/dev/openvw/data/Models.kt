package dev.openvw.data

// --- Data shapes (mirror the live Skoda / Volkswagen Connect API fields) ---

data class Telemetry(
  val speedKmh: Int? = null,
  val rpm: Int? = null,
  val fuelPct: Int = 0,
  val batteryV: Double? = null,      // 12 V battery (null = the car hasn't reported it)
  val coolantC: Int? = null,
  val intakeC: Int? = null,
  val engineLoadPct: Int? = null,
  val odometerKm: Int? = null,
  val milOn: Boolean = false,          // check-engine light
  val distanceWithMil: Int? = 0,       // km driven with MIL on
  val distanceSinceDtc: Int? = null,   // km since last diagnostic reset (null = the car hasn't reported it)
  val engineOn: Boolean = false,
)

/** OBD dongle health. */
data class Dongle(val signal: Int = 0, val status: String = "", val firmware: String = "")

/** Driving-behaviour summary computed from per-trip telematics. */
data class DriveStats(
  val tripCount: Int = 0,
  val avgScore: Int = 0,
  val maxSpeedKmh: Int = 0,
  val idleMin: Int = 0,
  val harshBrakes: Int = 0,
  val realKmpl: Double = 0.0,   // measured mileage from per-trip fuel use (0 = unknown)
  val totalKm: Double = 0.0,
  val totalMin: Int = 0,
  val weekTrips: Int = 0,       // trips in the last 7 days
  val weekKm: Double = 0.0,
  val weekMin: Int = 0,
)

/** Something the car reported: engine on/off or a driving alarm (sudden brake, sharp turn…). */
data class CarEvent(
  val id: String,
  val kind: String,      // "engine" | "alarm" (speeding counts as an alarm)
  val title: String,     // "Engine off", "Sudden brake", "Over the speed limit"
  val epoch: Long,       // seconds
  val lat: Double,
  val lon: Double,
  val detail: String = "", // extra line, e.g. "92 km/h · limit 80"
)

object EventText {
  /**
   * Title / kind / detail for a raw car event, or null to leave it out. Every event gets a readable title: the
   * server sends speed-limit events with no name at all ({type: speedLimit, actualSpeed, speedLimit}).
   */
  fun of(type: String, event: String, name: String, code: String, actualSpeed: Double?, limit: Double?, pretty: (String) -> String): Triple<String, String, String>? =
    when (type) {
      "engine" -> Triple("engine", if (event.equals("on", ignoreCase = true)) "Engine on" else "Engine off", "")
      "speedLimit" -> {
        // the car sends one event going over the limit and one coming back under; only "over" is news
        if (actualSpeed != null && limit != null && actualSpeed <= limit) null
        else Triple("alarm", "Over the speed limit",
          if (actualSpeed != null && limit != null) "${actualSpeed.toInt()} km/h · limit ${limit.toInt()}" else "")
      }
      else -> {
        val t = pretty(name.ifEmpty { event }.ifEmpty { code }).ifEmpty { pretty(type) }.ifEmpty { "Car alert" }
        Triple(if (type == "alarm" || type.isBlank()) "alarm" else type, t, "")
      }
    }
}

/** Where the car was last seen (latest event / trip end). */
data class Parked(
  val lat: Double = 0.0,      // 0 / "" until a real position arrives (see Store.parkedKnown): no placeholder city
  val lon: Double = 0.0,
  val epoch: Long = 0,
  val title: String = "",
  val sub: String = "",
  val heading: Int? = null,   // direction of travel (0 = north) from the live feed, while driving
)

/** Geofences + alert-trigger status from Cerence /policies. */
data class AlertSetup(
  val fences: List<String> = emptyList(),
  val triggers: List<String> = emptyList(),
  val triggerStates: Map<String, Boolean> = emptyMap(),
  val shapes: List<Fence> = emptyList(),   // the geofences' geometry, for the Locate map
)

/** Car render + paint selection from the account (or the owner's manual pick). */
object CarLook {
  enum class Paint(val label: String, val swatch: Long) {
    WHITE("White", 0xFFF4F4F2), BLACK("Black", 0xFF16161A), GREY("Grey", 0xFF5B6B78), RED("Red", 0xFFC8102E), BLUE("Blue", 0xFF1F7FD8), YELLOW("Yellow", 0xFFF2C200),
  }

  private val words = listOf(
    Regex("white|silver") to Paint.WHITE,
    Regex("grey|gray|carbon|steel|graphite") to Paint.GREY,
    Regex("black") to Paint.BLACK,
    Regex("red|cherry|rosso|crimson|maroon") to Paint.RED,
    Regex("blue|lava|race|water") to Paint.BLUE,
    Regex("yellow|curcuma|gold|bronx|beige|cappuccino") to Paint.YELLOW,
    Regex("green|shimla") to Paint.BLUE,
  )

  private fun current() = Store.vehicle.variant + " " + Store.vehicle.model

  /** Short model label ("Slavia", "Taigun", "T-Roc"); falls back to the brand name ("Skoda" / "Volkswagen"). */
  fun name(model: String = current()): String = when (val m = Brand.model(model)) {
    null -> Store.brand.label
    "T-ROC", "TROC" -> "T-Roc"
    else -> m.lowercase().replaceFirstChar { it.uppercase() }
  }

  /** Every paint can be drawn now that the car art is CarPulse's own (no per-model photo sets). */
  fun paints(model: String = current()): List<Paint> = Paint.entries

  /** Fallback when the account colour is unknown or unrecognised. */
  private fun fallback(model: String) = Paint.WHITE

  /** The first colour word wins: "Candy White Deep/Black" is a white car with a black roof. */
  fun paint(colour: String, model: String = current()): Paint {
    val c = colour.lowercase()
    val p = words.mapNotNull { (re, p) -> re.find(c)?.let { it.range.first to p } }.minByOrNull { it.first }?.second
    return p?.takeIf { it in paints(model) } ?: fallback(model)
  }

  /** Paint to show: the owner's manual pick (Store.paintPick, a Paint name) wins over the account colour. */
  fun effective(colour: String, model: String = current()): Paint =
    paints(model).firstOrNull { it.name == Store.paintPick } ?: paint(colour, model)

  /** Body style for a model: sedans, compact SUVs and hatchbacks (unknown models: SUV, the most common). */
  fun body(model: String = current()): CarArt.Body = when (Brand.model(model)) {
    "SLAVIA", "VIRTUS", "OCTAVIA", "SUPERB", "RAPID", "VENTO" -> CarArt.Body.SEDAN
    "FABIA", "POLO" -> CarArt.Body.HATCH
    else -> CarArt.Body.SUV
  }

  /** Paint colour to draw (0xRRGGBB), a touch richer than the picker swatches so the shading reads. */
  fun rgb(colour: String, model: String = current()): Int = when (effective(colour, model)) {
    Paint.WHITE -> 0xECECE9
    Paint.BLACK -> 0x1E1F23
    Paint.GREY -> 0x626E7A
    Paint.RED -> 0xB21228
    Paint.BLUE -> 0x1F6EC4
    Paint.YELLOW -> 0xE2B414
  }

  /** The car illustration, [widthPx] wide, in the account's (or the owner's picked) colour. */
  fun bitmap(colour: String, widthPx: Int, model: String = current()): android.graphics.Bitmap =
    CarArt.bitmap(body(model), rgb(colour, model), widthPx)
}

data class VehicleInfo(
  val variant: String = "",
  val model: String = "",
  val vehicleName: String = "Factory:MEXA11AA0AA000000",
  val regNo: String = "",
  val colour: String = "", // "" = the account didn't report one (Cerence sign-in never does)
  val engineType: String = "DUM A00000",
  val dongleImei: String = "860000000000000",
  val dongleFirmware: String = "1.0.0.27",
  val status: String = "Parked",
)

data class PlanInfo(
  val title: String,
  val details: String = "",
  val active: Boolean? = null,
)

data class Owner(
  val name: String = "CAR OWNER",
  val email: String = "",
  val mobile: String = "+91 00000 00000",
  val address: String = "",
) {
  val initials: String
    get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
      .take(2).joinToString("") { it.first().uppercase() }
  val first: String
    get() = name.trim().split(Regex("\\s+")).firstOrNull()
      ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
  val proper: String
    get() = name.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

data class Trip(
  val id: String,
  val start: String,
  val end: String,
  val fromTitle: String,
  val fromSub: String,
  val toTitle: String,
  val toSub: String,
  val durMin: Int,
  val type: String,
  val date: String = "16 Oct",
  val km: Double = 0.0,
  val score: Int = 0,
  val epoch: Long = 0,   // trip start, seconds
  val endEpoch: Long = 0, // trip end, seconds
  val pending: Boolean = false,  // derived from engine on/off; full trip still finalizing on the server
  val drivingMin: Int = 0,
  val idleMin: Int = 0,
  val haltMin: Int = 0,
  val maxSpeed: Int = 0,
  val avgSpeed: Int = 0,
  val startOdo: Double = 0.0,
  val endOdo: Double = 0.0,
  val startFuelPct: Int = 0,
  val endFuelPct: Int = 0,
  val brakes: Int = 0,
  val startLat: Double = 0.0,
  val startLon: Double = 0.0,
  val endLat: Double = 0.0,
  val endLon: Double = 0.0,
  val alarms: List<Pair<String, Int>>? = null, // every driving alarm on the trip (label, count); null = not reported
  val tags: List<String> = emptyList(),         // all of the trip's tags; [type] is the first
) {
  /** Litres burned, from the fuel-% drop × the default 50 L tank (telematics reports percent only). */
  val fuelUsedL: Double get() = (startFuelPct - endFuelPct).coerceAtLeast(0) / 100.0 * 50.0
  /** Measured mileage for this trip (km/L); 0 = not computable (fuel % didn't change). */
  val mileage: Double get() = if (fuelUsedL > 0 && km > 0) km / fuelUsedL else 0.0
}

/** A workshop / showroom the owner saved (the hearts on Locate). Kept on this phone only. */
data class Workshop(val id: String, val name: String, val address: String, val phone: String = "")

data class Spec(val label: String, val value: String, val mono: Boolean = false)

// --- Sample payload shown until live data loads (identifiers are placeholders) ---
object Repo {
  val vehicle = VehicleInfo()
  val telemetry = Telemetry()
  val owner = Owner()

  val trips = listOf(
    Trip(
      "a", "10:10 AM", "10:24 AM",
      "MG Road", "Mahatma Gandhi Rd, Bengaluru, Karnataka 560001",
      "Indiranagar", "100 Feet Rd, Indiranagar, Bengaluru, Karnataka 560038", 14, "Personal",
    ),
    Trip(
      "b", "09:13 AM", "09:40 AM",
      "Indiranagar", "100 Feet Rd, Indiranagar, Bengaluru, Karnataka 560038",
      "MG Road", "Mahatma Gandhi Rd, Bengaluru, Karnataka 560001", 27, "Personal",
    ),
  )


  val specs: List<Spec>
    get() = listOf(
      Spec("Model", vehicle.model),
      Spec("Variant", vehicle.variant),
      Spec("Colour", vehicle.colour),
      Spec("Fuel", "Petrol"),
      Spec("Engine type", vehicle.engineType, mono = true),
      Spec("Dongle firmware", vehicle.dongleFirmware, mono = true),
      Spec("Dongle IMEI", vehicle.dongleImei, mono = true),
    )
}
