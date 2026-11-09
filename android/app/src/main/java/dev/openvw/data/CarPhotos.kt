package dev.openvw.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Real car pictures (transparent background) bundled in assets/cars/, listed in assets/cars/index.json: one per
 * model, generation (the year the picture set is from), variant and colour, for Skoda and Volkswagen India models
 * sold from 2020 on. Models that left before 2020 are kept out of the app (repo folder car-images-archive/).
 * [choose] picks the closest picture for the signed-in car; no match means CarPulse's drawn car instead.
 */
object CarPhotos {
  data class Entry(val model: String, val year: Int?, val variant: String, val colour: String, val file: String)

  private const val DIR = "cars"
  @Volatile private var entries: List<Entry>? = null
  private val cache = LruCache<String, Bitmap>(4)

  fun all(ctx: Context): List<Entry> = entries ?: synchronized(this) {
    entries ?: runCatching {
      val a = org.json.JSONArray(ctx.assets.open("$DIR/index.json").bufferedReader().use { it.readText() })
      (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        Entry(o.getString("model"), if (o.isNull("year")) null else o.optInt("year"), o.optString("variant"), o.optString("colour"), o.getString("file"))
      }
    }.getOrDefault(emptyList()).also { entries = it }
  }

  private val FILLER = setOf("metallic", "pearl", "effect", "with", "the", "and", "colour", "color", "paint", "painted")
  private fun words(s: String) = s.lowercase().replace("gray", "grey").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in FILLER }.toSet()
  private fun squash(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

  /**
   * The best picture for a car, or null. [modelText] is the account's model and variant ("Slavia Sportline 1.0 AT"),
   * [colour] its paint name ("Tornado Red Dual Tone", may be ""), [modelYear] e.g. 2024, [pick] the owner's manual
   * paint choice (wins over the account colour).
   */
  fun choose(list: List<Entry>, modelText: String, colour: String, modelYear: Int?, pick: CarLook.Paint?): Entry? {
    val text = squash(modelText)
    // the most specific model name in the text: "Tiguan Allspace" over "Tiguan"
    val model = list.map { it.model }.distinct().filter { squash(it) in text }.maxByOrNull { it.length } ?: return null
    val pool = list.filter { it.model == model }
    // the account's exact paint name counts unless the owner picked a different colour family by hand
    val want = if (pick == null || CarLook.paintOf(colour) == pick) words(colour) else emptySet()
    val wantPaint = pick ?: CarLook.paintOf(colour)
    val variantWords = words(modelText)
    fun colourScore(e: Entry): Int {
      val ew = words(e.colour)
      if (want.isNotEmpty()) {
        val hit = (ew intersect want).size
        if (hit > 0) return 100 + hit * 10 - (ew - want).size * 3 // exact names first, fewer extra words better
      }
      return if (wantPaint != null && CarLook.paintOf(e.colour) == wantPaint) 50 else 0
    }
    fun yearScore(e: Entry): Int {
      val y = e.year ?: return -3
      val my = modelYear ?: return -(2026 - y) / 2  // unknown model year: the newest set
      return if (y > my + 1) -10 - (y - my) else -(my + 1 - y) // the set from the car's year (or just after), not a later facelift
    }
    fun variantScore(e: Entry): Int {
      val v = e.variant.lowercase()
      if (v == "all variants" || v.startsWith("pictures from") || v.isBlank()) return 0
      val vw = words(e.variant)
      return if (vw.any { it in variantWords }) 6 else -2 // a Monte Carlo picture only for a Monte Carlo
    }
    return pool.filter { colourScore(it) > 0 }.maxByOrNull { colourScore(it) + yearScore(it) + variantScore(it) }
  }

  /** The chosen picture as a bitmap at most [widthPx] wide, or null (no picture for this car / unreadable). */
  fun bitmap(ctx: Context, modelText: String, colour: String, modelYear: Int?, pick: CarLook.Paint?, widthPx: Int): Bitmap? {
    val e = choose(all(ctx), modelText, colour, modelYear, pick) ?: return null
    val w = widthPx.coerceIn(64, 1200)
    val key = "${e.file}/$w"
    cache.get(key)?.let { return it }
    val src = runCatching { ctx.assets.open("$DIR/${e.file}").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
    val out = if (src.width <= w) src else Bitmap.createScaledBitmap(src, w, (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1), true)
    cache.put(key, out)
    return out
  }
}
