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
