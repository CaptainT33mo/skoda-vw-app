package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandTest {
  @Test fun brandWordWins() {
    assertEquals(Brand.SKODA, Brand.detect("Skoda", "Something"))
    assertEquals(Brand.SKODA, Brand.detect("ŠKODA AUTO"))
    assertEquals(Brand.VW, Brand.detect("Volkswagen"))
    assertEquals(Brand.VW, Brand.detect("VW"))
  }

  @Test fun brandWordBeatsModel() = assertEquals(Brand.VW, Brand.detect("Volkswagen", "Slavia")) // inventory brand field first

  @Test fun modelNamesTheBrand() {
    assertEquals(Brand.SKODA, Brand.detect(null, "SLAVIA", "Factory:MEX123"))
    assertEquals(Brand.SKODA, Brand.detect("", "Kylaq Prestige"))
    assertEquals(Brand.VW, Brand.detect("", "Taigun GT"))
    assertEquals(Brand.VW, Brand.detect("", "T-ROC"))
  }

  @Test fun vwInsideAWordIsNotABrand() = assertNull(Brand.detect("NEWVWX"))

  @Test fun nothingKnown() {
    assertNull(Brand.detect("", null, "Factory:MEX123"))
    assertNull(Brand.detect())
  }

  @Test fun savedName() {
    assertEquals(Brand.VW, Brand.of("VW"))
    assertNull(Brand.of(""))
    assertNull(Brand.of("SEAT"))
  }

  @Test fun serverCodes() {
    assertEquals("C", Brand.SKODA.code)  // what the official Skoda app sends
    assertEquals("VW", Brand.VW.code)    // what the official VW app sends
  }
}

class TankTest {
  @Test fun tankByModel() {
    org.junit.Assert.assertEquals(45.0, Brand.tankLitres("Sportline 1.0 AT Slavia"), 0.0)
    org.junit.Assert.assertEquals(45.0, Brand.tankLitres("VIRTUS GT"), 0.0)
    org.junit.Assert.assertEquals(50.0, Brand.tankLitres("Kushaq"), 0.0)
    org.junit.Assert.assertEquals(50.0, Brand.tankLitres("something else"), 0.0)
  }

  @Test fun mileageRangeFromWholePercents() {
    // the VW Virtus trip from the report: 14.8 km, 41 % → 36 %, 45 L tank
    val t = Trip(id = "t", start = "", end = "", fromTitle = "", fromSub = "", toTitle = "", toSub = "", durMin = 40, type = "Personal",
      date = "", km = 14.8, startFuelPct = 41, endFuelPct = 36, tankL = 45.0)
    org.junit.Assert.assertEquals(6.58, t.mileage, 0.01)
    val (lo, hi) = t.mileageRange!!
    org.junit.Assert.assertEquals(5.48, lo, 0.01)   // really 6 %
    org.junit.Assert.assertEquals(8.22, hi!!, 0.01) // really 4 %
  }
}
