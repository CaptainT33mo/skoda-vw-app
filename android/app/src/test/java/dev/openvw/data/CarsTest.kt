package dev.openvw.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarsTest {
  private val slavia = CarRef("1", "VIN-S", "Factory:VIN-S", "Slavia", "Sportline 1.0 AT", Brand.SKODA, "KA01AB1234")
  private val taigun = CarRef("2", "VIN-T", "Factory:VIN-T", "TAIGUN", "", Brand.VW, "")

  @After fun reset() { Store.privacy = false; Demo.clear() }

  @Test fun picksTheSavedCarElseTheFirst() {
    assertEquals(taigun, Cars.pick(listOf(slavia, taigun), "VIN-T"))
    assertEquals(slavia, Cars.pick(listOf(slavia, taigun), "VIN-GONE")) // sold / unmapped: fall back
    assertEquals(slavia, Cars.pick(listOf(slavia, taigun), ""))
    assertNull(Cars.pick(emptyList(), "VIN-T"))
  }

  @Test fun switcherLabels() {
    assertEquals("Slavia", slavia.title)
    assertEquals("Taigun", taigun.title)
    assertEquals("Sportline 1.0 AT · KA01AB1234", slavia.detail)
    assertEquals("", taigun.detail)
  }

  @Test fun privacyModeMasksOnlyTheReg() {
    Store.privacy = true
    assertEquals("Sportline 1.0 AT · KA01AA0000", slavia.detail)
  }

  @Test fun demoHasOneCarOfEachBrandAndSwitches() {
    Demo.load(Brand.SKODA)
    assertEquals(2, Store.cars.size)
    assertEquals("DEMO-SKODA", Store.carVin)
    Demo.load(Brand.VW) // what the switcher does in demo mode
    assertEquals("DEMO-VW", Store.carVin)
    assertEquals(Brand.VW, Store.brand)
    assertEquals("Taigun", CarLook.name())
    assertEquals("DEMO 0002", Store.vehicle.regNo)
  }

  @Test fun clearingACarKeepsTheAccount() {
    Demo.load(Brand.SKODA)
    Cars.clearCarState()
    assertEquals(0, Store.trips.size)
    assertEquals("Demo Driver", Store.owner.name) // account-level, not per car
  }
}
