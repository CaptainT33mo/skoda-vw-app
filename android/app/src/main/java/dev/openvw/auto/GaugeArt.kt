package dev.openvw.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gauge images for the car screen, drawn on the phone. A tile's live number is drawn inside its ring: Android
 * Auto counts a grid update as a cheap "refresh" only while every tile title stays the same, so titles are
 * fixed labels ("Speed", "Fuel") and the changing numbers live in the image and the tile's second line.
 * Bitmaps cross a Binder call on every refresh, so they are kept small (a few tens of KB each).
 */
internal object GaugeArt {
  const val GREEN = 0xFF2BD08C.toInt()
  const val AMBER = 0xFFF5A524.toInt()
  const val RED = 0xFFE5484D.toInt()
  const val BLUE = 0xFF4EA8FF.toInt()
  const val ACCENT = 0xFFE8562A.toInt() // CarPulse orange

  private const val START = 135f   // 270° dial, gap at the bottom
  private const val SWEEP = 270f

  /**
   * A ring gauge for a grid tile: [fraction] 0..1 of the dial filled with [color] (null = no reading), with
   * [value] written in the middle.
   */
  fun ring(fraction: Float?, color: Int, dark: Boolean, value: String? = null, sizePx: Int = 128): CarIcon {
    val bmp = createBitmap(sizePx, sizePx)
    val c = Canvas(bmp)
    val stroke = sizePx * 0.11f
    val r = RectF(stroke, stroke, sizePx - stroke, sizePx - stroke)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    p.color = track(dark)
    c.drawArc(r, START, SWEEP, false, p)
    if (fraction != null) {
      val f = fraction.coerceIn(0f, 1f)
      p.color = color
      if (f > 0.005f) c.drawArc(r, START, SWEEP * f, false, p)
      // tip dot, so a near-zero value is still visible
      val a = Math.toRadians((START + SWEEP * f).toDouble())
      val cx = sizePx / 2f + (r.width() / 2f) * cos(a).toFloat()
      val cy = sizePx / 2f + (r.height() / 2f) * sin(a).toFloat()
      p.style = Paint.Style.FILL
      c.drawCircle(cx, cy, stroke * 0.62f, p)
    }
    if (!value.isNullOrBlank()) {
      val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        this.color = if (dark) 0xFFF3F2EF.toInt() else 0xFF16161A.toInt()
        textSize = sizePx * 0.30f
      }
      // shrink long values ("1,850") to fit inside the ring
      val room = sizePx - stroke * 4.2f
      val w = t.measureText(value)
      if (w > room) t.textSize *= room / w
      val fm = t.fontMetrics
      c.drawText(value, sizePx / 2f, sizePx / 2f - (fm.ascent + fm.descent) / 2f, t)
    }
    return icon(bmp)
  }

  /**
   * The "mini cluster" for the detailed view: outer arc = speed (0–[maxSpeed] km/h), inner arc = RPM
   * (0–7000, redline tinted), bottom bar = fuel. Hosts show it beside the rows on wide panels and may drop it
   * on narrow ones, which is exactly what we want there.
   */
  fun cluster(speed: Int?, rpm: Int?, fuelPct: Int?, fuelColor: Int, dark: Boolean, sizePx: Int = 220): CarIcon {
    val bmp = createBitmap(sizePx, sizePx)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val outerW = sizePx * 0.075f
    val outer = RectF(outerW, outerW, sizePx - outerW, sizePx - outerW)
    val innerW = sizePx * 0.045f
    val inset = outerW * 2.6f
    val inner = RectF(inset, inset, sizePx - inset, sizePx - inset)

    // speed ticks every 20 km/h
    p.strokeWidth = sizePx * 0.012f; p.color = track(dark, strong = true)
    val tickR = sizePx / 2f - outerW * 2.0f
    for (i in 0..10) {
      val a = Math.toRadians((START + SWEEP * i / 10f).toDouble())
      val len = if (i % 5 == 0) sizePx * 0.05f else sizePx * 0.028f
      c.drawLine(
        sizePx / 2f + tickR * cos(a).toFloat(), sizePx / 2f + tickR * sin(a).toFloat(),
        sizePx / 2f + (tickR - len) * cos(a).toFloat(), sizePx / 2f + (tickR - len) * sin(a).toFloat(), p,
      )
    }
    // outer: speed
    p.strokeWidth = outerW; p.color = track(dark)
    c.drawArc(outer, START, SWEEP, false, p)
    speed?.let { p.color = ACCENT; c.drawArc(outer, START, SWEEP * (it / MAX_SPEED).coerceIn(0.004f, 1f), false, p) }
    // inner: rpm, last seventh in red
    p.strokeWidth = innerW; p.color = track(dark)
    c.drawArc(inner, START, SWEEP, false, p)
    p.color = RED and 0x55FFFFFF
    c.drawArc(inner, START + SWEEP * 6f / 7f, SWEEP / 7f, false, p)
    rpm?.let {
      val f = (it / MAX_RPM).coerceIn(0.004f, 1f)
      p.color = if (f > 6f / 7f) RED else if (dark) 0xFFF3F2EF.toInt() else 0xFF2C2C31.toInt()
      c.drawArc(inner, START, SWEEP * f, false, p)
    }
    // fuel bar across the dial's gap
    val barW = sizePx * 0.36f; val barH = sizePx * 0.045f
    val bx = (sizePx - barW) / 2f; val by = sizePx * 0.86f
    p.style = Paint.Style.FILL; p.color = track(dark)
    c.drawRoundRect(RectF(bx, by, bx + barW, by + barH), barH, barH, p)
    fuelPct?.let {
      p.color = fuelColor
      c.drawRoundRect(RectF(bx, by, bx + barW * (it / 100f).coerceIn(0.04f, 1f), by + barH), barH, barH, p)
    }
    return icon(bmp)
  }

  fun fuelColor(pct: Int?, low: Boolean): Int = when {
    pct == null -> GREEN
    low || pct <= 12 -> RED
    pct <= 25 -> AMBER
    else -> GREEN
  }

  /** Coolant: blue while warming up, green in the normal band, red when hot. */
  fun coolantColor(c: Int?): Int = when {
    c == null -> GREEN
    c < 70 -> BLUE
    c <= 105 -> GREEN
    c <= 112 -> AMBER
    else -> RED
  }

  /** 12 V system: ~13.5–14.8 V charging with the engine on, ≥12.2 V resting; lower is a weak battery. */
  fun batteryColor(v: Double?, engineOn: Boolean): Int = when {
    v == null -> GREEN
    engineOn && v in 13.2..15.0 -> GREEN
    !engineOn && v >= 12.2 -> GREEN
    v >= 11.8 -> AMBER
    else -> RED
  }

  const val MAX_SPEED = 200f
  private const val MAX_RPM = 7000f

  private fun track(dark: Boolean, strong: Boolean = false) = when {
    dark && strong -> 0x66FFFFFF
    dark -> 0x2EFFFFFF
    strong -> 0x55000000
    else -> 0x1F000000
  }

  private fun icon(bmp: Bitmap) = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
}
