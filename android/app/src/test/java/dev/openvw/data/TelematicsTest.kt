package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelematicsTest {
  // ---- per-trip alarms ----
  @Test fun everyAlarmIsKeptNotOnlyBrakes() = assertEquals(
    listOf("Sudden brake" to 2, "Sharp turn" to 1, "Sudden acceleration" to 1),
    Telematics.alarms(mapOf("sudden brake" to 2, "sharp turn" to 1, "suddenAcceleration" to "1", "idle" to 0)),
  )

  // the exact keys a real Slavia's trips carry
  @Test fun realTripKeys() = assertEquals(
    listOf("Over the speed limit" to 3, "Sharp turn" to 1, "Sudden brake" to 1),
    Telematics.alarms(mapOf("sudden brake" to 1, "sharp turn" to 1, "speedLimit" to 3)),
  )

  @Test fun spellingsOfOneAlarmMerge() =
    assertEquals(listOf("Sudden brake" to 3), Telematics.alarms(mapOf("sudden brake" to 1, "suddenBrake" to 2)))

  @Test fun countsInsideObjects() =
    assertEquals(listOf("Over speed" to 4), Telematics.alarms(mapOf("OVER_SPEED" to mapOf("count" to 4))))

  @Test fun noEventCountIsUnknownNotNone() {
    assertNull(Telematics.alarms(null))
    assertEquals(emptyList<Pair<String, Int>>(), Telematics.alarms(mapOf("sudden brake" to 0)))
  }

  // ---- tags ----
  @Test fun allTags() {
    assertEquals(listOf("Business", "Airport"), Telematics.tags(listOf("Business", " Airport ", "", "Business", null)))
    assertEquals(listOf("Personal"), Telematics.tags("Personal"))
    assertEquals(emptyList<String>(), Telematics.tags(null))
  }

  // ---- positions ----
  @Test fun points() {
    assertEquals(GeoPt(12.97, 77.6), Telematics.point(mapOf("latitude" to 12.97, "longitude" to 77.6)))
    assertEquals(GeoPt(12.97, 77.6), Telematics.point(mapOf("lat" to "12.97", "lng" to "77.6")))
    assertNull(Telematics.point(mapOf("latitude" to 0.0, "longitude" to 0.0)))   // "no fix", not the Atlantic
    assertNull(Telematics.point(mapOf("latitude" to 120.0, "longitude" to 77.6)))
    assertNull(Telematics.point(mapOf("latitude" to 12.97)))
  }

  @Test fun headingAndCompass() {
    assertEquals(90, Telematics.heading(mapOf("heading" to 90.4)))
    assertEquals(0, Telematics.heading(mapOf("course" to 360)))
    assertNull(Telematics.heading(mapOf("heading" to 0)))   // the dongle's "not measured"
    assertNull(Telematics.heading(mapOf("heading" to -1)))
    assertNull(Telematics.heading(mapOf("speed" to 40)))
    assertEquals("N", Telematics.compass(0)); assertEquals("N", Telematics.compass(350))
    assertEquals("NE", Telematics.compass(45)); assertEquals("S", Telematics.compass(180)); assertEquals("NW", Telematics.compass(310))
  }

  // ---- geofences ----
  @Test fun circleFence() {
    val f = Telematics.fence(mapOf("name" to "Home", "center" to mapOf("latitude" to 12.97, "longitude" to 77.64), "radius" to 300), 0)
    assertEquals(Fence("Home", GeoPt(12.97, 77.64), 300.0), f)
    assertTrue(f.drawable)
  }

  @Test fun flatCircleFenceInKm() {
    val f = Telematics.fence(mapOf("latitude" to 12.97, "longitude" to 77.64, "radiusInKm" to 1.5), 2)
    assertEquals("Geofence 3", f.name)
    assertEquals(1500.0, f.radiusM, 0.001)
  }

  @Test fun polygonFenceOfPoints() {
    val pts = listOf(mapOf("latitude" to 1.0, "longitude" to 2.0), mapOf("latitude" to 1.0, "longitude" to 3.0), mapOf("latitude" to 2.0, "longitude" to 3.0))
    val f = Telematics.fence(mapOf("name" to "Office", "points" to pts), 0)
    assertEquals(listOf(GeoPt(1.0, 2.0), GeoPt(1.0, 3.0), GeoPt(2.0, 3.0)), f.polygon)
    assertTrue(f.drawable)
  }

  @Test fun geoJsonPolygonIsLonLatAndUnclosed() {
    val ring = listOf(listOf(77.0, 12.0), listOf(78.0, 12.0), listOf(78.0, 13.0), listOf(77.0, 12.0))
    val f = Telematics.fence(mapOf("geometry" to mapOf("type" to "Polygon", "coordinates" to listOf(ring))), 0)
    assertEquals(listOf(GeoPt(12.0, 77.0), GeoPt(12.0, 78.0), GeoPt(13.0, 78.0)), f.polygon)
  }

  @Test fun unknownShapeIsNotDrawn() =
    assertFalse(Telematics.fence(mapOf("name" to "Mystery", "id" to "f1"), 0).drawable)

  // ---- drive trail ----
  @Test fun trailGrowsSkipsJitterAndRestartsAfterAGap() {
    val a = GeoPt(12.0, 77.0); val b = GeoPt(12.001, 77.0)
    var t = Telematics.extendTrail(emptyList(), a, 0)
    assertEquals(listOf(a), t)
    t = Telematics.extendTrail(t, GeoPt(12.00002, 77.00002), 5)  // ~3 m: jitter
    assertEquals(listOf(a), t)
    t = Telematics.extendTrail(t, b, 5)
    assertEquals(listOf(a, b), t)
    assertEquals(listOf(a), Telematics.extendTrail(t, a, 30 * 60))  // 30 min later: a new drive
  }

  @Test fun trailIsCapped() {
    var t = emptyList<GeoPt>()
    repeat(30) { t = Telematics.extendTrail(t, GeoPt(10.0 + it * 0.01, 77.0), 5, max = 10) }
    assertEquals(10, t.size)
    assertEquals(10.29, t.last().lat, 1e-9)
  }

  // ---- no placeholder data in the defaults ----
  @Test fun defaultsInventNothing() {
    assertNull(Telemetry().distanceSinceDtc)
    assertNull(Telemetry().batteryV)
    assertEquals("", Parked().title)
    assertEquals(0.0, Parked().lat, 0.0)
  }
}
