package dev.openvw.auto

import android.content.Context
import dev.openvw.data.CarLook
import dev.openvw.data.Session
import dev.openvw.data.Store
import dev.openvw.data.WidgetData
import dev.openvw.ui.FuelCalc

/**
 * Everything the car screen shows, merged from three sources (best first):
 *  1. the car's head unit (speed, fuel gauge, range, odometer) — instant, when the car shares it;
 *  2. the live telematics push feed / REST telemetry in [Store] — the dongle's view of the engine;
 *  3. the last snapshot the app saved for the widgets — so the screen isn't empty while connecting.
 *
 * It is a data class on purpose: the screen re-renders only when this value actually changes.
 */
internal data class Dash(
  val signedIn: Boolean,
  val hasData: Boolean,
  val stale: Boolean,          // only the saved snapshot so far (not this session's data)
  val feedOn: Boolean,         // telematics push feed connected
  val model: String,
  val status: String,          // Driving / Idling / Parked
  val speedKmh: Int?,
  val speedFromCar: Boolean,
  val rpm: Int?,
  val fuelPct: Int?,
  val fuelLow: Boolean,
  val rangeKm: Int?,
  val rangeMaxKm: Int,
  val coolantC: Int?,
  val batteryV: Double?,
  val engineLoadPct: Int?,
  val intakeC: Int?,
  val odometerKm: Int?,
  val milOn: Boolean,
  val place: String,
  val placeSub: String,
  val updatedMinute: Long,     // minute of the last update (minute, so the key doesn't churn every push)
) {
  val moving: Boolean get() = (speedKmh ?: 0) > 0
  val updatedAt: Long get() = updatedMinute * 60_000L

  companion object {
    fun read(ctx: Context, hu: HeadUnit?): Dash {
      val session = Session(ctx)
      val kmpl = FuelCalc.mileage(Store.drive.realKmpl)
      val rangeMax = (FuelCalc.TANK_L * kmpl).toInt()
      val huSpeed = hu?.freshSpeedKmh
      val huFuel = hu?.fuelPct
      val huRange = hu?.rangeKm
      fun range(fuel: Int?) = huRange ?: fuel?.let { (it / 100.0 * FuelCalc.TANK_L * kmpl).toInt() }

      if (Store.live) {
        val t = Store.telemetry
        val parked = Store.vehicle.status == "Parked"
        val fuel = huFuel ?: t.fuelPct
        val speed = huSpeed ?: if (parked) null else t.speedKmh
        return Dash(
          signedIn = session.loggedIn || session.demo,
          hasData = true,
          stale = false,
          feedOn = Store.liveConnected,
          model = CarLook.name(),
          status = statusFor(Store.vehicle.status, huSpeed),
          speedKmh = speed,
          speedFromCar = huSpeed != null,
          rpm = if (parked) null else t.rpm,
          fuelPct = fuel,
          fuelLow = hu?.fuelLow == true || fuel in 0..12,
          rangeKm = range(fuel),
          rangeMaxKm = rangeMax,
          coolantC = t.coolantC,
          batteryV = t.batteryV,
          engineLoadPct = if (parked) null else t.engineLoadPct,
          intakeC = t.intakeC,
          odometerKm = hu?.odometerKm ?: t.odometerKm,
          milOn = t.milOn,
          place = if (Store.parkedKnown) Store.parked.title else "",
          placeSub = if (Store.parkedKnown) Store.parked.sub else "",
          updatedMinute = Store.updatedAt / 60_000L,
        )
      }

      // not loaded yet this session: last saved snapshot (written by the phone app), if any
      val s = WidgetData.load(ctx)
      val have = s.updatedAt > 0L
      val fuel = huFuel ?: s.fuelPct.takeIf { have }
      return Dash(
        signedIn = session.loggedIn,
        hasData = have || huSpeed != null || huFuel != null,
        stale = true,
        feedOn = false,
        model = CarLook.name("${s.variant} ${s.model}"),
        status = statusFor(if (have) s.status else "", huSpeed),
        speedKmh = huSpeed,
        speedFromCar = huSpeed != null,
        rpm = null,
        fuelPct = fuel,
        fuelLow = hu?.fuelLow == true || (fuel ?: 100) <= 12,
        rangeKm = huRange ?: s.rangeKm.takeIf { have && it > 0 },
        rangeMaxKm = rangeMax,
        coolantC = s.coolantC.takeIf { have && it > 0 },
        batteryV = s.batteryV?.takeIf { have },
        engineLoadPct = null,
        intakeC = null,
        odometerKm = hu?.odometerKm ?: s.odometerKm.takeIf { have && it > 0 },
        milOn = have && s.milOn,
        place = s.parkTitle,
        placeSub = s.parkSub,
        updatedMinute = s.updatedAt / 60_000L,
      )
    }

    /** The head unit knows we're moving before the dongle's next report does. */
    private fun statusFor(cloud: String, huSpeed: Int?): String = when {
      huSpeed != null && huSpeed > 0 -> "Driving"
      cloud.isNotBlank() -> cloud
      huSpeed != null -> "Stopped"
      else -> "Connecting"
    }
  }
}
