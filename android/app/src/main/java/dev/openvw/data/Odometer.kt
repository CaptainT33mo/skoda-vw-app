package dev.openvw.data

/** Where the odometer on screen comes from. */
enum class OdoSource {
  NONE,      // nothing yet (still loading, or the car sends none and the owner hasn't typed it in)
  CAR,       // the car's live data or profile
  LAST_TRIP, // the car sends no live reading, but its trips carry one: the newest trip's end
  READING,   // the car sends none at all: the owner's reading plus the trips driven since
}

/**
 * Some cars (seen on a 2026 VW Virtus) send no odometer anywhere: not in the live data, the trips or the car's
 * profile. The owner types in the dashboard reading once and CarPulse adds each trip's distance on top of it. Trip
 * distance is the dongle's own count, so the estimate can drift a little; the owner can correct it any time.
 */
object Odometer {
  /** The odometer read [km] at [at] (epoch seconds). */
  data class Reading(val km: Double, val at: Long) {
    fun save() = "$km@$at"
    companion object {
      fun parse(s: String): Reading? {
        val p = s.split('@'); if (p.size != 2) return null
        val km = p[0].toDoubleOrNull() ?: return null; val at = p[1].toLongOrNull() ?: return null
        return if (km >= 0 && at > 0) Reading(km, at) else null
      }
    }
  }

  private fun counted(t: Trip) = !t.pending && t.km > 0

  /**
   * The reading moved on to the end of the newest trip driven after it. Saving this keeps the count right even when
   * older trips drop out of the server's list before the app is opened again.
   */
  fun advance(r: Reading, trips: List<Trip>): Reading {
    val after = trips.filter { counted(it) && it.epoch >= r.at }
    if (after.isEmpty()) return r
    return Reading(r.km + after.sumOf { it.km }, after.maxOf { maxOf(it.endEpoch, it.epoch) })
  }

  /**
   * Trips without a reading of their own get one walked from [r]: forwards for trips after it, backwards for trips
   * before it (a trip under way when the reading was taken counts as before). Trips with the car's own reading keep it.
   */
  fun fill(r: Reading, trips: List<Trip>): List<Trip> {
    val asc = trips.filter(::counted).distinctBy { it.id }.sortedBy { it.epoch }
    val est = HashMap<String, Pair<Double, Double>>()
    var acc = r.km
    for (t in asc.filter { it.epoch >= r.at }) { est[t.id] = acc to acc + t.km; acc += t.km }
    acc = r.km
    for (t in asc.filter { it.epoch < r.at }.asReversed()) { est[t.id] = (acc - t.km).coerceAtLeast(0.0) to acc; acc -= t.km }
    return trips.map { t ->
      if (t.endOdo > 0 && !t.odoEstimated) t
      else est[t.id]?.let { (s, e) -> t.copy(startOdo = s, endOdo = e, odoEstimated = true) } ?: t
    }
  }

  /** Trips with estimated readings put back to none (the owner cleared the reading). */
  fun clear(trips: List<Trip>) = trips.map { if (it.odoEstimated) it.copy(startOdo = 0.0, endOdo = 0.0, odoEstimated = false) else it }
}
