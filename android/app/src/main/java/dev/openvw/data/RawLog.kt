package dev.openvw.data

/**
 * Server payloads made safe to log and share: identifiers (VIN, IMEI, phone, email, registration, names,
 * tokens, addresses) and coordinates are masked; every key and every other value is kept, so the logs show
 * exactly which fields the car and server send.
 */
object RawLog {
  private val SECRET = Regex(
    "imei|iccid|imsi|msisdn|phone|mobile|sms|email|token|password|secret|registration|regno|plate|licen[cs]e|deviceid|serial|" +
      "address|firstname|lastname|fullname|ownername|username|customer",
    RegexOption.IGNORE_CASE,
  )
  // "vin", "carVin", "VIN_NO" — but not "drivingTime" (case-sensitive on purpose)
  private val VIN_KEY = Regex("^vin|Vin|VIN")
  private val COORD = Regex("lat|lon|lng|long|latitude|longitude", RegexOption.IGNORE_CASE)
  // a VIN anywhere in a string ("Factory:MEXB…" names carry one), and 15-digit IMEIs
  private val VIN = Regex("(?<![A-Za-z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])")
  private val IMEI = Regex("(?<![0-9])[0-9]{15}(?![0-9])")

  private fun mask(s: String): String =
    IMEI.replace(VIN.replace(s) { m -> if (m.value.any { it.isLetter() } && m.value.any { it.isDigit() }) "<hidden>" else m.value }, "<hidden>")

  fun redact(v: Any?, key: String = ""): Any? = when {
    v == null -> null
    key.isNotEmpty() && (SECRET.containsMatchIn(key) || VIN_KEY.containsMatchIn(key)) -> "<hidden>"
    key.isNotEmpty() && COORD.matches(key) && Telematics.num(v) != null -> "<coord>"
    v is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to redact(x, k.toString()) }
    v is List<*> -> v.map { redact(it, key) }
    v is String -> mask(v)
    v is Number && IMEI.matches(v.toString()) -> "<hidden>"
    else -> v
  }

  /** Compact JSON. */
  fun render(v: Any?): String = when (v) {
    null -> "null"
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> quote(k.toString()) + ":" + render(x) }
    is List<*> -> v.joinToString(",", "[", "]") { render(it) }
    is String -> quote(v)
    else -> v.toString()
  }

  private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  /** Every key path in a payload ("telemetry.Speed.value", "trips[].eventCount.sharp turn"): its shape. */
  fun shape(v: Any?, prefix: String = ""): Set<String> = when (v) {
    is Map<*, *> -> v.entries.flatMap { (k, x) ->
      val p = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
      shape(x, p) + p
    }.toSet()
    is List<*> -> v.take(3).flatMap { shape(it, "$prefix[]") }.toSet()
    else -> emptySet()
  }
}
