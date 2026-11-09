package dev.openvw.data

/**
 * The two car brands CarPulse works with. Skoda and Volkswagen India (SAVWIPL) share one backend: the
 * savwconnect.com API only differs by its brand code, and both use the same Cerence telematics tenant. So one
 * app serves both; this enum holds everything that is brand-specific.
 */
enum class Brand(
  /** savwconnect.com brand code, sent as the `brand` header/field: what each official app sends. */
  val code: String,
  /** Name shown to the user. */
  val label: String,
  /** The official app the account belongs to (for "set this up in … app" hints). */
  val officialApp: String,
  /** Model names as they appear in account/inventory data (upper case). */
  val models: List<String>,
) {
  SKODA("C", "Skoda", "MySkoda Connected", listOf("KUSHAQ", "SLAVIA", "KYLAQ", "KODIAQ", "OCTAVIA", "SUPERB", "RAPID", "FABIA")),
  VW("VW", "Volkswagen", "My Volkswagen Connect Plus", listOf("TAIGUN", "VIRTUS", "TIGUAN", "T-ROC", "TROC", "POLO", "VENTO")),
  ;

  companion object {
    /** Saved name → brand; unknown or blank → null. */
    fun of(name: String?): Brand? = entries.firstOrNull { it.name == name }

    /**
     * Brand named by account/inventory data: an explicit brand word wins ("Skoda", "Škoda", "Volkswagen", "VW"),
     * then a model name. Null when nothing matches, so callers keep the brand the user picked at sign-in.
     */
    fun detect(vararg texts: String?): Brand? {
      val all = texts.filterNotNull().joinToString(" ").uppercase()
      if (all.isBlank()) return null
      if (Regex("\\bSKODA\\b|ŠKODA").containsMatchIn(all)) return SKODA
      if (Regex("\\bVOLKSWAGEN\\b|\\bVW\\b").containsMatchIn(all)) return VW
      return entries.firstOrNull { b -> b.models.any { all.contains(it) } }
    }

    /**
     * Fuel tank in litres for the model named in [text] (Indian spec sheets); 50 L when unknown. Telematics reports
     * fuel only as a percentage, so this turns a % drop into litres (trip mileage, range).
     */
    fun tankLitres(text: String): Double = when (model(text)) {
      "SLAVIA", "VIRTUS", "POLO", "FABIA" -> 45.0
      "KYLAQ" -> 44.0
      "KUSHAQ", "TAIGUN", "OCTAVIA", "T-ROC", "TROC" -> 50.0
      "RAPID", "VENTO" -> 55.0
      "KODIAQ", "TIGUAN" -> 58.0
      "SUPERB" -> 66.0
      else -> 50.0
    }

    /** Model name found in [text] across both brands, upper case, or null. */
    fun model(text: String): String? {
      val up = text.uppercase()
      return entries.flatMap { it.models }.firstOrNull { up.contains(it) }
    }
  }
}
