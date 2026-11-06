package dev.openvw.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A point on the map. */
data class GeoPt(val lat: Double, val lon: Double)

/** A geofence from the alert policies: a circle (centre + radius in metres) or a polygon. */
data class Fence(
  val name: String,
  val center: GeoPt? = null,
  val radiusM: Double = 0.0,
  val polygon: List<GeoPt> = emptyList(),
) {
  /** Enough geometry to draw it (the server's exact fence format isn't confirmed yet, so some may not be). */
  val drawable: Boolean get() = polygon.size >= 3 || (center != null && radiusM > 0)
}

/** JSON -> plain Kotlin maps / lists (JSONObject.NULL -> null), so the parsers below are unit-testable. */
fun JSONObject.toPlain(): Map<String, Any?> = keys().asSequence().associateWith { jsonPlain(opt(it)) }
fun JSONArray.toPlain(): List<Any?> = (0 until length()).map { jsonPlain(opt(it)) }
fun jsonPlain(v: Any?): Any? = when (v) {
  is JSONObject -> v.toPlain()
  is JSONArray -> v.toPlain()
  JSONObject.NULL -> null
  else -> v
}

/**
 * Readers for the parts of the Cerence payloads the app didn't use before: every per-trip alarm count, all trip
 * tags, geofence shapes, and the heading in live positions. Field names beyond the ones already seen from a real
 * car are tolerant guesses; the raw-payload logs (RawLog) show the real shapes.
 */
object Telematics {
  fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
  }?.takeIf { !it.isNaN() && !it.isInfinite() }

  @Suppress("UNCHECKED_CAST")
  private fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

  /** First non-null value under any of [keys] (case-insensitive). */
  private fun get(m: Map<String, Any?>, vararg keys: String): Any? {
    for (k in keys) m[k]?.let { return it }
    for (k in keys) m.entries.firstOrNull { it.key.equals(k, ignoreCase = true) && it.value != null }?.let { return it.value }
    return null
  }

  /** "sudden brake" / "suddenBrake" / "SUDDEN_BRAKE" -> "Sudden brake". */
  fun label(raw: String): String = if (raw.equals("speedLimit", true) || raw.equals("speed limit", true)) "Over the speed limit" else
    raw.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')
      .trim().replace(Regex("\\s+"), " ").lowercase().replaceFirstChar { it.uppercase() }

  /**
   * A trip's `eventCount` ({"sudden brake": 2, "sharp turn": 1, …}) -> (label, count), most frequent first, zeros
   * left out. Null when the trip has no eventCount at all (unknown, not "none").
   */
  fun alarms(eventCount: Map<String, Any?>?): List<Pair<String, Int>>? {
    if (eventCount == null) return null
    return eventCount.entries
      .mapNotNull { (k, v) ->
        val n = num(v) ?: map(v)?.let { num(get(it, "count", "value", "total")) }
        n?.toInt()?.takeIf { it > 0 }?.let { label(k) to it }
      }
      .groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.sum() } // "suddenBrake" + "sudden brake"
      .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
  }

  /** Trip `tags`: a list of strings (or a single string) -> trimmed, non-blank, de-duplicated. */
  fun tags(v: Any?): List<String> = when (v) {
    is List<*> -> v.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }.distinct()
    is String -> listOfNotNull(v.trim().ifEmpty { null })
    else -> emptyList()
  }

  private fun valid(lat: Double, lon: Double): GeoPt? =
    if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) GeoPt(lat, lon) else null

  /** {latitude, longitude} (or lat / lon / lng) -> a point; null when missing or (0, 0). */
  fun point(m: Map<String, Any?>?): GeoPt? {
    if (m == null) return null
    val lat = num(get(m, "latitude", "lat")) ?: return null
    val lon = num(get(m, "longitude", "lon", "lng", "long")) ?: return null
    return valid(lat, lon)
  }

  /**
   * Direction of travel in degrees (360 = north) from a live position. The Indian plug-in dongle sends
   * `heading: 0` in every position it reports (seen on a real Slavia), so 0 means "not measured", not north.
   */
  fun heading(m: Map<String, Any?>?): Int? {
    if (m == null) return null
    val d = num(get(m, "heading", "course", "bearing", "direction", "azimuth")) ?: return null
    return if (d > 0.0 && d <= 360.0) d.toInt() % 360 else null
  }

  /** 0..359° -> "N", "NE", … */
  fun compass(deg: Int): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360) % 360 + 22) / 45 % 8]

  /** One entry of policies.fences -> a Fence (circle or polygon; neither when the shape isn't recognised). */
  fun fence(m: Map<String, Any?>, index: Int): Fence {
    val name = (get(m, "name", "title", "label") as? String)?.trim()?.ifEmpty { null } ?: "Geofence ${index + 1}"
    val center = map(get(m, "center", "centre", "location", "position", "point", "coordinate"))?.let(::point) ?: point(m)
    val radius = num(get(m, "radius", "radiusInMeters", "radiusInMeter", "radiusMeters", "radiusM", "range"))
      ?: num(get(m, "radiusInKm", "radiusKm"))?.times(1000)
    return Fence(name, center, radius?.takeIf { it > 0 } ?: 0.0, polygon(m))
  }

  private fun polygon(m: Map<String, Any?>): List<GeoPt> {
    // GeoJSON: {geometry: {type: Polygon, coordinates: [[[lon, lat], …]]}} or the geometry object itself
    map(get(m, "geometry"))?.let { g -> (get(g, "coordinates") as? List<*>)?.let { return ring(it, lonFirst = true) } }
    for (k in listOf("polygon", "points", "vertices", "path", "boundary", "coordinates", "coords", "area")) {
      when (val v = get(m, k)) {
        is List<*> -> ring(v, lonFirst = k == "coordinates").takeIf { it.isNotEmpty() }?.let { return it }
        is Map<*, *> -> polygon(map(v)!!).takeIf { it.isNotEmpty() }?.let { return it }
      }
    }
    return emptyList()
  }

  /** Points as maps or [a, b] pairs; a GeoJSON polygon's first (outer) ring. GeoJSON order is [lon, lat]. */
  private fun ring(v: List<*>, lonFirst: Boolean): List<GeoPt> {
    val first = v.firstOrNull()
    if (first is List<*> && first.firstOrNull() is List<*>) return ring(first, lonFirst)
    val pts = v.mapNotNull { p ->
      when (p) {
        is Map<*, *> -> point(map(p))
        is List<*> -> {
          val a = num(p.getOrNull(0)); val b = num(p.getOrNull(1))
          if (a == null || b == null) null else if (lonFirst) valid(b, a) else valid(a, b)
        }
        else -> null
      }
    }
    return if (pts.size > 1 && pts.first() == pts.last()) pts.dropLast(1) else pts // GeoJSON closes the ring
  }

  /** A trip's `itinerary` (positions in time order) -> its path; points without a fix are skipped. */
  fun path(itinerary: List<Any?>): List<GeoPt> =
    buildList { for (x in itinerary) point(map(x))?.let { if (lastOrNull() != it) add(it) } }

  /**
   * Add a live position to the current drive's trail. A gap of over 20 minutes since the last point starts a new
   * trail; jitter under ~10 m is skipped; the oldest points drop off past [max].
   */
  fun extendTrail(trail: List<GeoPt>, p: GeoPt, gapS: Long, max: Int = 2000): List<GeoPt> {
    if (trail.isEmpty() || gapS > 20 * 60) return listOf(p)
    val last = trail.last()
    if (abs(last.lat - p.lat) < 0.0001 && abs(last.lon - p.lon) < 0.0001) return trail
    return (if (trail.size >= max) trail.drop(trail.size - max + 1) else trail) + p
  }
}
