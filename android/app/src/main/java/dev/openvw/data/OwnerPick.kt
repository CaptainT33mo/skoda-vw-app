package dev.openvw.data

/**
 * The owner's name and email from the several places Skoda's account stores them. A phone-registered account has
 * an empty `userInfo.name` / `primaryMailID`, while the car's telematics record carries the real ones, so the
 * first usable candidate wins. Candidates come already decrypted; what failed to decrypt (still hex) is skipped.
 */
object OwnerPick {
  private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
  private val HEX = Regex("^[0-9A-Fa-f]{16,}$")

  fun email(vararg candidates: String?): String =
    candidates.mapNotNull { it?.trim() }.firstOrNull { EMAIL.matches(it) } ?: ""

  fun name(vararg candidates: String?): String =
    candidates.mapNotNull { it?.trim()?.replace(Regex("\\s+"), " ") }
      .firstOrNull { c ->
        c.isNotEmpty() && c.any(Char::isLetter) && !HEX.matches(c) && c.none(Char::isISOControl) &&
          !c.equals("null", ignoreCase = true) && !c.contains('@')
      } ?: ""
}
