package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventTextTest {
  private val pretty: (String) -> String = { it.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { c -> c.uppercase() } }
  private fun of(type: String, event: String = "", name: String = "", code: String = "", speed: Double? = null, limit: Double? = null) =
    EventText.of(type, event, name, code, speed, limit, pretty)

  @Test fun engine() {
    assertEquals(Triple("engine", "Engine on", ""), of("engine", event = "on"))
    assertEquals(Triple("engine", "Engine off", ""), of("engine", event = "off"))
  }

  // the exact shapes the car sent on 2 Nov
  @Test fun overSpeedHasATitleAndDetail() =
    assertEquals(Triple("alarm", "Over the speed limit", "92 km/h · limit 80"), of("speedLimit", speed = 92.0, limit = 80.0))

  @Test fun backUnderTheLimitIsLeftOut() = assertNull(of("speedLimit", speed = 62.0, limit = 80.0))

  @Test fun namedAlarm() = assertEquals("Sudden brake", of("alarm", name = "Sudden Brake", code = "sudden brake")!!.second)

  @Test fun neverBlank() {
    assertEquals("Tow alert", of("towAlert")!!.second)           // unknown type: its own name
    assertEquals("Car alert", of("")!!.second)                    // nothing at all
  }
}
