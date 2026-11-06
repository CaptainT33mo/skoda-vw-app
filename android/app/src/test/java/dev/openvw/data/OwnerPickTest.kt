package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerPickTest {
  @Test fun phoneRegisteredAccountFallsBackToTheTelematicsRecord() {
    assertEquals("Asha Rao", OwnerPick.name("", "Asha  Rao", "ASHA RAO"))
    assertEquals("asha@example.com", OwnerPick.email("", "asha@example.com", "other@example.com"))
  }

  @Test fun initialsAreFirstAndLastName() {
    assertEquals("VS", Owner(name = "Vibhor Hemant Sharma").initials)
    assertEquals("VS", Owner(name = "  vibhor   sharma ").initials)
    assertEquals("V", Owner(name = "Vibhor").initials)
  }

  @Test fun skipsWhatFailedToDecryptOrIsNotAName() {
    assertEquals("Asha", OwnerPick.name("FEB959B7D4642FCB", "null", "12345", "a@b.co", "Asha"))
    assertEquals("", OwnerPick.email("FEB959B7D4642FCB", "not an email", null))
  }
}
