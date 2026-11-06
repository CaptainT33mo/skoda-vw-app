package dev.openvw.ui

import dev.openvw.data.CarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusedAlertTest {
  private fun ev(id: String, title: String, epoch: Long, kind: String = "alarm", lat: Double = 12.9) = CarEvent(id, kind, title, epoch, lat, 77.6)
  private val trip = listOf(
    ev("on", "Engine on", 100, kind = "engine"),
    ev("a1", "Sudden brake", 200),
    ev("a2", "Sudden brake", 400),
    ev("a3", "Sharp turn", 300),
    ev("a4", "Over the speed limit", 500, lat = 0.0), // no position: can't be shown on the map
  )

  @Test fun sameEventId() = assertEquals("a3", focusedAlert(ev("a3", "Sharp turn", 300), trip)?.id)

  @Test fun elseSameAlertClosestInTime() = assertEquals("a2", focusedAlert(ev("x", "Sudden brake", 390), trip)?.id)

  @Test fun nothingToShow() {
    assertNull(focusedAlert(ev("a4", "Over the speed limit", 500), trip))
    assertNull(focusedAlert(ev("on", "Engine on", 100, kind = "engine"), trip))
  }
}
