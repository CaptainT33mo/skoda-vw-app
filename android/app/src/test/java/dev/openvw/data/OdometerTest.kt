package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerTest {
  private fun trip(id: String, start: Long, km: Double, endOdo: Double = 0.0, pending: Boolean = false) =
    Trip(id, "", "", "", "", "", "", 30, "Personal", km = km, epoch = start, endEpoch = start + 1800, endOdo = endOdo, pending = pending)

  // newest first, as Store.trips keeps them
  private val trips = listOf(trip("c", 3000, 10.0), trip("b", 2000, 20.0), trip("a", 1000, 5.0))

  @Test fun savesAndParses() {
    val r = Odometer.Reading(12345.0, 1794256405)
    assertEquals(r, Odometer.Reading.parse(r.save()))
    assertNull(Odometer.Reading.parse(""))
    assertNull(Odometer.Reading.parse("abc@1"))
    assertNull(Odometer.Reading.parse("100@0"))
  }

  @Test fun noTripsSinceKeepsTheReading() {
    val r = Odometer.Reading(1000.0, 5000)
    assertEquals(r, Odometer.advance(r, trips))
  }

  @Test fun tripsSinceAreAdded() {
    // reading taken between trip a and trip b: b and c were driven after it
    val r = Odometer.Reading(1000.0, 1500)
    val now = Odometer.advance(r, trips)
    assertEquals(1030.0, now.km, 0.001)
    assertEquals(3000L + 1800, now.at) // moved on to the end of the newest trip
    assertEquals(now, Odometer.advance(now, trips)) // advancing again adds nothing twice
  }

  @Test fun pendingTripsWait() {
    val r = Odometer.Reading(1000.0, 1500)
    assertEquals(r, Odometer.advance(r, listOf(trip("p", 4000, 12.0, pending = true))))
  }

  @Test fun tripsGetReadingsBothWays() {
    val filled = Odometer.fill(Odometer.Reading(1000.0, 1500), trips).associateBy { it.id }
    assertEquals(995.0, filled.getValue("a").startOdo, 0.001); assertEquals(1000.0, filled.getValue("a").endOdo, 0.001)
    assertEquals(1000.0, filled.getValue("b").startOdo, 0.001); assertEquals(1020.0, filled.getValue("b").endOdo, 0.001)
    assertEquals(1020.0, filled.getValue("c").startOdo, 0.001); assertEquals(1030.0, filled.getValue("c").endOdo, 0.001)
    assertTrue(filled.values.all { it.odoEstimated })
  }

  @Test fun advancedReadingGivesTheSameTrips() {
    val r = Odometer.Reading(1000.0, 1500)
    assertEquals(Odometer.fill(r, trips), Odometer.fill(Odometer.advance(r, trips), trips))
  }

  @Test fun carReadingsAreKept() {
    val own = listOf(trip("x", 2000, 20.0, endOdo = 5555.0))
    val filled = Odometer.fill(Odometer.Reading(1000.0, 1500), own).single()
    assertEquals(5555.0, filled.endOdo, 0.001)
    assertFalse(filled.odoEstimated)
  }

  @Test fun correctedReadingReplacesEstimates() {
    val first = Odometer.fill(Odometer.Reading(1000.0, 1500), trips)
    val again = Odometer.fill(Odometer.Reading(2000.0, 1500), first).associateBy { it.id }
    assertEquals(2030.0, again.getValue("c").endOdo, 0.001)
    val cleared = Odometer.clear(first)
    assertTrue(cleared.all { it.endOdo == 0.0 && !it.odoEstimated })
  }
}
