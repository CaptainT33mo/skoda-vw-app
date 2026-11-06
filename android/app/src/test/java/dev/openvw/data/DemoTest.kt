package dev.openvw.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTest {
  @After fun reset() = Demo.clear()

  @Test fun skodaDemoIsASlavia() {
    Demo.load(Brand.SKODA)
    assertEquals("Slavia", CarLook.name())
    assertTrue(Store.live)
    assertEquals("DEMO 0001", Store.vehicle.regNo) // never looks like a real plate
  }

  @Test fun vwDemoIsATaigun() {
    Demo.load(Brand.VW)
    assertEquals("Taigun", CarLook.name())
  }

  @Test fun tripsAreRecentNewestFirstAndConsistent() {
    Demo.load(Brand.SKODA)
    val t = Store.trips
    assertTrue(t.size >= 10)
    assertEquals(t.sortedByDescending { it.epoch }, t)
    assertTrue(t.all { it.endEpoch > it.epoch && it.km > 0 && it.endOdo > it.startOdo })
    assertTrue(t.all { it.startFuelPct >= it.endFuelPct })
    assertEquals(Demo.ODOMETER.toDouble(), t.first().endOdo, 0.01) // the newest trip ends at today's odometer
    assertTrue(Store.parkedKnown)
  }

  @Test fun demoShowsTheNewData() {
    Demo.load(Brand.SKODA)
    assertTrue(Store.trips.all { it.alarms != null && it.tags.isNotEmpty() && it.tags.first() == it.type })
    assertTrue(Store.trips.any { it.alarms!!.size > 1 })
    assertTrue(Store.trips.all { t -> (t.alarms!!.firstOrNull { it.first == "Sudden brake" }?.second ?: 0) == t.brakes })
    assertTrue(Store.alertSetup.shapes.all { it.drawable } && Store.alertSetup.shapes.size == 2)
    assertTrue(Store.trail.size > 2)
    assertEquals(GeoPt(Store.parked.lat, Store.parked.lon), Store.trail.last()) // the trail ends at the car
  }

  @Test fun driveStaysWithinCityLimits() {
    Demo.load(Brand.SKODA)
    repeat(200) { step ->
      Demo.tick(step)
      val s = Store.telemetry.speedKmh ?: 0
      assertTrue(s in 0..70)
      assertEquals(if (s == 0) "Idling" else "Driving", Store.vehicle.status)
    }
  }

  @Test fun dateSearchIsOffline() {
    Demo.load(Brand.SKODA)
    val newest = Store.trips.first()
    val found = Demo.tripsBetween(newest.epoch * 1000 - 1000, newest.epoch * 1000 + 1000)
    assertEquals(listOf(newest), found)
  }

  @Test fun clearLeavesNoDemoData() {
    Demo.load(Brand.SKODA)
    Demo.clear()
    assertFalse(Store.live)
    assertTrue(Store.trips.isEmpty())
    assertFalse(Store.parkedKnown)
    assertTrue(Store.trail.isEmpty())
    assertEquals("", Store.vehicle.regNo)
  }
}
