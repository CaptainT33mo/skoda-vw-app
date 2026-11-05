package dev.openvw.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.sin

/**
 * CarPulse's own car illustrations: a clean side profile per body style, painted in the car's colour and drawn
 * in code, so they are transparent, sharp at any size, right in light and dark, and free of anyone else's
 * artwork. Design space is 1000 x 420 units, car facing right; shapes are SVG path data.
 */
object CarArt {
  enum class Body { SEDAN, SUV, HATCH }

  data class Wheel(val cx: Float, val cy: Float, val r: Float)

  private enum class Style(
    val body: String, val cladding: String, val glass: String, val pillars: String,
    val top: Float, val belt: Float, val sill: Float,
    val shoulder: String, val doors: String, val handles: String,
    val head: String, val tail: String, val grille: String, val mirror: String,
    val wheels: List<Wheel>, val arches: List<Wheel>, val shadow: RectF,
  ) {
    SEDAN(
      body = "M118 336 C104 330 98 312 100 292 C102 276 106 264 118 254 C160 242 230 236 300 230 C340 200 372 168 410 152 C450 140 520 136 570 140 C610 144 650 172 692 210 C760 222 830 230 878 240 C905 246 922 256 930 268 C938 282 940 302 934 322 C930 332 920 338 906 340 L834 338 C834 298 802 266 762 266 C722 266 690 298 690 338 L322 338 C322 298 290 266 250 266 C210 266 178 298 178 338 L140 338 C130 338 122 338 118 336 Z",
      cladding = "",
      glass = "M318 226 C352 196 382 170 414 160 C450 150 515 147 562 150 C598 153 630 178 662 214 Z",
      pillars = "M503 151 L515 150 L514 220 L502 221 Z",
      top = 140f, belt = 228f, sill = 338f,
      shoulder = "M140 262 C300 256 600 252 900 258",
      doors = "M662 216 C672 260 676 300 680 336 M508 222 L508 336 M350 228 C352 270 345 310 330 336",
      handles = "M455 266 L485 266 L485 272 L455 272 Z M605 263 L635 263 L635 269 L605 269 Z",
      head = "M872 246 C896 250 916 258 926 270 L912 274 C898 266 882 260 866 256 Z",
      tail = "M104 268 C112 258 124 252 140 250 L142 262 C128 264 116 268 106 278 Z",
      grille = "M930 290 C934 296 935 306 933 314 L922 316 C924 306 924 298 922 292 Z",
      mirror = "M652 214 C664 204 680 204 688 210 L684 222 L660 224 Z",
      wheels = listOf(Wheel(250f, 330f, 60f), Wheel(762f, 330f, 60f)),
      arches = listOf(Wheel(250f, 338f, 72f), Wheel(762f, 338f, 72f)),
      shadow = RectF(130f, 372f, 880f, 408f),
    ),
    SUV(
      body = "M118 318 C104 312 100 292 100 268 C100 236 104 200 110 178 C116 154 132 134 162 126 C280 112 460 108 572 112 C614 114 652 142 688 186 C760 196 846 204 900 218 C930 226 946 240 950 262 C954 286 952 306 944 318 C940 322 934 322 926 322 L842 322 C842 276 804 238 758 238 C712 238 674 276 674 322 L346 322 C346 276 308 238 262 238 C216 238 178 276 178 322 L130 322 C124 322 120 320 118 318 Z",
      cladding = "M852 324 C852 270 810 228 758 228 C706 228 664 270 664 324 L674 324 C674 276 712 238 758 238 C804 238 842 276 842 324 Z M356 324 C356 270 314 228 262 228 C210 228 168 270 168 324 L178 324 C178 276 216 238 262 238 C308 238 346 276 346 324 Z M346 306 L674 306 L674 326 L346 326 Z M100 288 L130 288 L130 324 L108 322 Z M896 300 L948 300 L944 318 L896 322 Z",
      glass = "M152 196 C158 172 172 150 200 144 L556 132 C598 134 630 158 662 192 Z",
      pillars = "M478 136 L490 136 L489 194 L477 194 Z M312 140 L326 140 L324 196 L310 196 Z M196 124 L552 112 L552 118 L196 130 Z",
      top = 110f, belt = 196f, sill = 322f,
      shoulder = "M128 240 C300 234 600 230 920 236",
      doors = "M662 194 C668 240 672 284 674 306 M484 196 L484 306 M318 198 C320 240 322 280 330 306",
      handles = "M424 232 L456 232 L456 239 L424 239 Z M600 230 L632 230 L632 237 L600 237 Z",
      head = "M896 220 C922 226 940 236 948 250 L934 254 C918 244 900 236 884 232 Z",
      tail = "M102 214 C106 200 114 190 126 186 L128 224 C116 226 108 230 102 238 Z",
      grille = "M948 264 L952 290 L934 292 L930 266 Z",
      mirror = "M652 192 C664 180 680 180 690 186 L686 200 L660 202 Z",
      wheels = listOf(Wheel(262f, 326f, 68f), Wheel(758f, 326f, 68f)),
      arches = listOf(Wheel(262f, 322f, 84f), Wheel(758f, 322f, 84f)),
      shadow = RectF(120f, 372f, 920f, 414f),
    ),
    HATCH(
      body = "M134 334 C122 326 118 306 120 282 C122 252 126 222 136 198 C146 176 162 160 190 152 C280 136 400 132 480 134 C534 136 586 164 626 204 C690 214 752 222 792 234 C816 240 830 252 836 266 C842 286 842 310 836 326 C832 334 822 338 808 338 L772 338 C772 298 740 262 700 262 C660 262 628 298 628 338 L322 338 C322 298 290 262 250 262 C210 262 178 298 178 338 L148 338 C142 338 138 336 134 334 Z",
      cladding = "",
      glass = "M168 210 C176 186 192 168 216 162 L470 146 C520 148 560 174 594 210 Z",
      pillars = "M384 150 L396 150 L394 214 L382 214 Z",
      top = 134f, belt = 214f, sill = 338f,
      shoulder = "M140 254 C300 248 520 244 810 250",
      doors = "M594 212 C602 258 606 300 610 336 M390 214 L390 336",
      handles = "M320 256 L350 256 L350 262 L320 262 Z M520 253 L550 253 L550 259 L520 259 Z",
      head = "M786 236 C810 242 826 252 832 264 L818 268 C806 260 790 254 774 250 Z",
      tail = "M122 236 C126 220 134 210 146 206 L148 242 C136 244 128 248 122 256 Z",
      grille = "M838 278 L840 304 L828 306 L826 280 Z",
      mirror = "M584 208 C596 196 612 196 620 202 L616 216 L590 218 Z",
      wheels = listOf(Wheel(250f, 330f, 60f), Wheel(700f, 330f, 60f)),
      arches = listOf(Wheel(250f, 338f, 72f), Wheel(700f, 338f, 72f)),
      shadow = RectF(140f, 374f, 800f, 408f),
    ),
  }

  private fun style(b: Body) = when (b) { Body.SEDAN -> Style.SEDAN; Body.SUV -> Style.SUV; Body.HATCH -> Style.HATCH }

  // parsed paths, per style (parsing is the slow part)
  private val paths = HashMap<String, Path>()
  private fun p(d: String): Path = paths.getOrPut(d) { PathParser.createPathFromPathData(d) ?: Path() }

  /** Design-space area worth showing: the car plus its shadow. */
  private fun bounds(s: Style): RectF {
    val r = RectF(); p(s.body).computeBounds(r, true)
    r.union(s.shadow)
    r.inset(-6f, -6f)
    return r
  }

  /** Aspect ratio (width / height) of a style's image. */
  fun aspect(b: Body): Float = bounds(style(b)).let { it.width() / it.height() }

  private val cache = LruCache<String, Bitmap>(12)

  /** The car as a transparent bitmap [widthPx] wide (cached). [rgb] is the paint colour (0xRRGGBB). */
  fun bitmap(b: Body, rgb: Int, widthPx: Int): Bitmap {
    val w = widthPx.coerceIn(32, 2048)
    val key = "$b/$rgb/$w"
    cache.get(key)?.let { return it }
    val s = style(b)
    val area = bounds(s)
    val scale = w / area.width()
    val h = (area.height() * scale).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(scale, scale)
    c.translate(-area.left, -area.top)
    draw(c, s, rgb)
    cache.put(key, bmp)
    return bmp
  }

  private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
  private fun shade(rgb: Int, f: Float): Int {
    fun ch(v: Int) = (v * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(rgb shr 16 and 0xFF) shl 16) or (ch(rgb shr 8 and 0xFF) shl 8) or ch(rgb and 0xFF)
  }
  private fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0xFFFFFF)
  private fun vgrad(y0: Float, y1: Float, vararg stops: Pair<Float, Int>) =
    LinearGradient(0f, y0, 0f, y1, stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)

  private fun draw(c: Canvas, s: Style, rgb: Int) {
    val paint = 0xFF000000.toInt() or rgb
    // soft ground shadow
    val sh = s.shadow
    c.save()
    c.scale(1f, sh.height() / sh.width(), sh.centerX(), sh.centerY())
    c.drawCircle(sh.centerX(), sh.centerY(), sh.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = RadialGradient(sh.centerX(), sh.centerY(), sh.width() / 2f, intArrayOf(0x66000000, 0x33000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    })
    c.restore()
    // dark wheel wells, then the body over them
    s.arches.forEach { a ->
      c.drawArc(RectF(a.cx - a.r, a.cy - a.r, a.cx + a.r, a.cy + a.r), 180f, 180f, true, fill(0xFF0E0E10.toInt()))
    }
    val body = p(s.body)
    c.drawPath(body, fill(paint))
    // shading: highlight along the roof, darker toward the sills
    c.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = vgrad(s.top, s.sill, 0f to 0x46FFFFFF, (s.belt - s.top) / (s.sill - s.top) to 0x00FFFFFF,
        ((s.belt + 40f - s.top) / (s.sill - s.top)).coerceAtMost(0.99f) to 0x00000000, 1f to 0x5F000000)
    })
    if (s.cladding.isNotEmpty()) c.drawPath(p(s.cladding), fill(0xFF1C1D21.toInt()))
    c.drawPath(p(s.glass), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = vgrad(s.top, s.belt, 0f to 0xFF465262.toInt(), 1f to 0xFF181C24.toInt()) })
    c.drawPath(p(s.pillars), fill(0xFF141518.toInt()))
    // character line, door cuts, handles, lights, grille, mirror
    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      this.color = color; style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    c.drawPath(p(s.shoulder), stroke(0x3CFFFFFF, 2.2f))
    c.drawPath(p(s.doors), stroke(withAlpha(shade(rgb, 0.55f), 200), 1.6f))
    c.drawPath(p(s.handles), fill(shade(rgb, 0.7f)))
    c.drawPath(p(s.head), fill(0xFFE8ECF0.toInt()))
    c.drawPath(p(s.tail), fill(0xFFB01624.toInt()))
    c.drawPath(p(s.grille), fill(0xFF18191C.toInt()))
    c.drawPath(p(s.mirror), fill(shade(rgb, 0.85f)))
    // outline: darker for light paints, a light rim for dark ones (so a black car still reads on a dark screen)
    val lum = 0.3f * (rgb shr 16 and 0xFF) + 0.59f * (rgb shr 8 and 0xFF) + 0.11f * (rgb and 0xFF)
    c.drawPath(body, if (lum < 70f) stroke(0xDC60646E.toInt(), 1.6f) else stroke(withAlpha(shade(rgb, 0.6f), 160), 1.4f))
    // wheels: tyre, alloy rim, five spokes, hub
    s.wheels.forEach { w ->
      c.drawCircle(w.cx, w.cy, w.r, fill(0xFF121214.toInt()))
      c.drawCircle(w.cx, w.cy, w.r * 0.68f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = vgrad(w.cy - w.r, w.cy + w.r, 0f to 0xFFD6DBE2.toInt(), 1f to 0xFF787E88.toInt())
      })
      c.drawCircle(w.cx, w.cy, w.r * 0.56f, fill(0xFF282A2E.toInt()))
      val spoke = Path()
      for (k in 0 until 5) {
        val a = Math.toRadians(k * 72.0 - 90.0)
        val ri = w.r * 0.14f; val ro = w.r * 0.6f
        spoke.moveTo(w.cx + ri * cos(a - 0.16).toFloat(), w.cy + ri * sin(a - 0.16).toFloat())
        spoke.lineTo(w.cx + ro * cos(a - 0.2).toFloat(), w.cy + ro * sin(a - 0.2).toFloat())
        spoke.lineTo(w.cx + ro * cos(a + 0.2).toFloat(), w.cy + ro * sin(a + 0.2).toFloat())
        spoke.lineTo(w.cx + ri * cos(a + 0.16).toFloat(), w.cy + ri * sin(a + 0.16).toFloat())
        spoke.close()
      }
      c.drawPath(spoke, fill(0xFFC4C9D0.toInt()))
      c.drawCircle(w.cx, w.cy, w.r * 0.13f, fill(0xFF969BA2.toInt()))
    }
  }
}
