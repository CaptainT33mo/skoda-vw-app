package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawLogTest {
  private val payload = mapOf(
    "vin" to "TMBJB9NW1P0000000",
    "carId" to "65f0c0ffee0000000000abcd",
    "registrationNo" to "KA01AB1234",
    "owner" to mapOf("mobile" to "+919876543210", "email" to "a@b.c"),
    "location" to mapOf("latitude" to 12.97, "longitude" to 77.6, "heading" to 90),
    "telemetry" to mapOf("Speed" to mapOf("value" to 42), "EngineSpeed" to mapOf("value" to 1800)),
    "note" to "TMBJB9NW1P0000000",
    "name" to "Factory:MEXBPLPB0AA000000",   // the car's name carries its VIN
    "deviceId" to "860000000000001",          // the dongle IMEI
    "imeiNumber" to 860000000000002,
    "notifications" to mapOf("sms" to listOf("+919800000000")),
    "carVin" to "x", "drivingTime" to 1234,
    "events" to listOf(mapOf("type" to "alarm", "name" to "Sudden Brake")),
  )

  @Test fun identifiersAndCoordinatesAreMasked() {
    val out = RawLog.render(RawLog.redact(payload))
    listOf("TMBJB9NW1P0000000", "MEXBPLPB0AA000000", "860000000000001", "860000000000002", "+919800000000",
      "KA01AB1234", "+919876543210", "a@b.c", "12.97", "77.6").forEach {
      assertFalse("leaked $it in $out", out.contains(it))
    }
  }

  @Test fun everythingElseIsKept() {
    val out = RawLog.render(RawLog.redact(payload))
    listOf("\"carId\":\"65f0c0ffee0000000000abcd\"", "\"heading\":90", "\"Speed\":{\"value\":42}", "\"name\":\"Sudden Brake\"", "Factory:<hidden>", "\"drivingTime\":1234", "\"carVin\":\"<hidden>\"", "\"latitude\":\"<coord>\"")
      .forEach { assertTrue("missing $it in $out", out.contains(it)) }
  }

  @Test fun shapeListsEveryKeyPath() {
    val s = RawLog.shape(payload)
    assertTrue("telemetry.Speed.value" in s)
    assertTrue("events[].name" in s)
    assertEquals(RawLog.shape(payload), RawLog.shape(payload + ("note" to "other value"))) // values don't change the shape
    assertTrue(RawLog.shape(payload + ("Throttle" to 3)) != s)
  }

  @Test fun renderEscapes() = assertEquals("{\"a\":\"x\\\"y\",\"b\":[1,null,true]}", RawLog.render(mapOf("a" to "x\"y", "b" to listOf(1, null, true))))
}
