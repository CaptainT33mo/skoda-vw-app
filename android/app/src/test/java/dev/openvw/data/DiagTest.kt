package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {
  @Test fun scrubRemovesPersonalData() {
    val s = Diag.scrub("user 9876543210 / +91 98765 43210? mail a.b@x.in token=abcdefghijklmnop1234 Bearer eyJhbGciOiJIUzI1NiJ9.x.y vin TMBJB9NW1P0000000")
    listOf("9876543210", "98765 43210", "a.b@x.in", "abcdefghijklmnop1234", "eyJhbGciOiJIUzI1NiJ9", "TMBJB9NW1P0000000").forEach { assertFalse("leaked $it in $s", s.contains(it)) }
  }

  @Test fun scrubKeepsUsefulNumbers() {
    val s = Diag.scrub("telemetry fuel=73% odo=35174 reportedAt=1793806229 status=200 in 312 ms")
    assertEquals("telemetry fuel=73% odo=35174 reportedAt=1793806229 status=200 in 312 ms", s)
  }

  @Test fun apiLineHasPathStatusAndError() {
    Diag.api("GET", "https://savwipl-connect15.in.onecloud.cerenceapi.com/api/v1/car/abc/trips?from=1", 500, 42, "{\"message\":\"boom\"}")
    Diag.api("POST", "https://savwconnect.com/registration/api/x", 200, 7, "ignored on success")
    val f = Diag::class.java.getDeclaredField("lines").apply { isAccessible = true }.get(Diag) as ArrayDeque<*>
    val tail = f.takeLast(2).map { it.toString() }
    assertTrue(tail[0], tail[0].contains(" X api GET savwipl-connect15/api/v1/car/abc/trips → 500 in 42 ms · {\"message\":\"boom\"}"))
    assertTrue(tail[1], tail[1].contains(" A api POST savwconnect/registration/api/x → 200 in 7 ms") && !tail[1].contains("ignored"))
  }
}
