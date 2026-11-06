package dev.openvw.data

/**
 * One car on the account, as the server's car list (inventory) reports it. A phone number can own several, even
 * of both brands (a Slavia and a Taigun in one household); the app shows one at a time and can switch.
 */
data class CarRef(
  val id: String,
  val vin: String,
  val name: String,
  val model: String,
  val variant: String,
  val brand: Brand?,
  val regNo: String,
  val imei: String = "",       // the plug-in dongle's IMEI
  val firmware: String = "",   // the dongle's firmware ("1.0.0.27")
  val fuelType: String = "",
  val modelYear: String = "",
) {
  /** "Slavia", "Taigun", … (falls back to the brand, then "Car"). */
  val title: String get() = Brand.model(model)?.let { CarLook.name(it) } ?: model.ifBlank { brand?.label ?: "Car" }

  /** Second line for the switcher: variant and reg number (masked in privacy mode), whichever are known. */
  val detail: String get() = listOf(variant, if (regNo.isBlank()) "" else Mask.reg(regNo)).filter { it.isNotBlank() }.joinToString(" · ")
}

object Cars {
  /** The car to show: the one picked before (by VIN), else the first. */
  fun pick(cars: List<CarRef>, savedVin: String): CarRef? = cars.firstOrNull { it.vin == savedVin && it.vin.isNotBlank() } ?: cars.firstOrNull()

  /** Clear everything that belongs to one car (switching cars, leaving demo). Account-level data stays. */
  fun clearCarState() {
    Store.live = false; Store.liveConnected = false; Store.updatedAt = 0L; Store.reportedAt = 0L
    Store.vehicle = Repo.vehicle; Store.telemetry = Repo.telemetry
    Store.trips = emptyList(); Store.events = emptyList(); Store.parked = Parked(); Store.trail = emptyList(); Store.routes = emptyMap(); Store.routeLoading = null; Store.drive = DriveStats()
    Store.dongle = Dongle(); Store.alertSetup = AlertSetup()
    Store.pendingDrive = null; Store.detailTrip = null
    Store.tripRange = null; Store.tripResults = emptyList(); Store.tripSearching = false; Store.tripSearchError = null
  }
}
