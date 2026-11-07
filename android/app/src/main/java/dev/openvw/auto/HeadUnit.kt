package dev.openvw.auto

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Values straight from the car's own head unit, via Android Auto's car hardware API: speed many times a
 * second, plus the fuel gauge, range and odometer when the car shares them. This is independent of the
 * telematics dongle/cloud feed, so speed stays live even when mobile data is patchy. Not every car exposes
 * every value; anything missing stays null and the screen falls back to the cloud telemetry.
 */
internal class HeadUnit(private val ctx: CarContext, private val onChange: () -> Unit = {}) {
  @Volatile var speedKmh: Int? = null; private set
  @Volatile var fuelPct: Int? = null; private set
  @Volatile var rangeKm: Int? = null; private set
  @Volatile var fuelLow: Boolean? = null; private set
  @Volatile var odometerKm: Int? = null; private set
  private var speedAt = 0L

  /**
   * Speed is only trusted for a few seconds: a stale reading must not freeze on screen. A standstill is kept for
   * a minute, because cars stop sending speed updates while the value doesn't change, and a reading that keeps
   * appearing and vanishing would make the dashboard flicker between "0" and "—".
   */
  val freshSpeedKmh: Int? get() = speedKmh?.takeIf { SystemClock.elapsedRealtime() - speedAt < if (it == 0) 60_000 else 4_000 }

  private var info: CarInfo? = null
  private var running = false

  val supported: Boolean get() = ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_3

  fun missingPermissions(): List<String> =
    PERMISSIONS.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

  private val speedListener = OnCarDataAvailableListener<Speed> { s ->
    val mps = s.displaySpeedMetersPerSecond.ok() ?: s.rawSpeedMetersPerSecond.ok() ?: return@OnCarDataAvailableListener
    speedKmh = (mps * 3.6f).roundToInt().coerceAtLeast(0)
    speedAt = SystemClock.elapsedRealtime()
    onChange()
  }

  private val energyListener = OnCarDataAvailableListener<EnergyLevel> { e ->
    e.fuelPercent.ok()?.let { fuelPct = it.roundToInt().coerceIn(0, 100) }
    e.rangeRemainingMeters.ok()?.let { rangeKm = (it / 1000f).roundToInt().coerceAtLeast(0) }
    e.energyIsLow.ok()?.let { fuelLow = it }
    onChange()
  }

  private val mileageListener = OnCarDataAvailableListener<Mileage> { m ->
    m.odometerMeters.ok()?.let { odometerKm = (it / 1000f).roundToInt() }
    onChange()
  }

  /** Subscribe to whatever the car shares and the owner allowed. Safe to call again after a permission grant. */
  fun start() {
    if (running || !supported) return
    val ci = runCatching { ctx.getCarService(CarHardwareManager::class.java).carInfo }
      .onFailure { Log.w(TAG, "car hardware unavailable", it) }.getOrNull() ?: return
    info = ci
    running = true
    val ex = ContextCompat.getMainExecutor(ctx)
    if (granted(PERM_SPEED)) runCatching { ci.addSpeedListener(ex, speedListener) }.onFailure { Log.w(TAG, "speed", it) }
    if (granted(PERM_FUEL)) runCatching { ci.addEnergyLevelListener(ex, energyListener) }.onFailure { Log.w(TAG, "energy", it) }
    if (granted(PERM_MILEAGE)) runCatching { ci.addMileageListener(ex, mileageListener) }.onFailure { Log.w(TAG, "mileage", it) }
  }

  fun stop() {
    val ci = info ?: return
    running = false
    runCatching { ci.removeSpeedListener(speedListener) }
    runCatching { ci.removeEnergyLevelListener(energyListener) }
    runCatching { ci.removeMileageListener(mileageListener) }
  }

  fun restart() { stop(); start() }

  private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

  private fun <T : Any> CarValue<T>.ok(): T? = if (status == CarValue.STATUS_SUCCESS) value else null

  companion object {
    private const val TAG = "CarPulseAuto"
    const val PERM_SPEED = "com.google.android.gms.permission.CAR_SPEED"
    const val PERM_FUEL = "com.google.android.gms.permission.CAR_FUEL"
    const val PERM_MILEAGE = "com.google.android.gms.permission.CAR_MILEAGE"
    val PERMISSIONS = listOf(PERM_SPEED, PERM_FUEL, PERM_MILEAGE)
  }
}
