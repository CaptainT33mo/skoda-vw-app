package dev.openvw.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Drop-in for android.util.Log (same calls, same logcat output) that also keeps every line for the diagnostics
 * report ([Diag]). Files log through this by importing dev.openvw.data.Log instead of android.util.Log.
 */
object Log {
  const val VERBOSE = android.util.Log.VERBOSE
  fun isLoggable(tag: String, level: Int) = android.util.Log.isLoggable(tag, level)
  fun d(tag: String, msg: String): Int { Diag.line('D', msg); return android.util.Log.d(tag, msg) }
  fun i(tag: String, msg: String): Int { Diag.line('I', msg); return android.util.Log.i(tag, msg) }
  fun w(tag: String, msg: String, tr: Throwable? = null): Int { Diag.line('W', msg + Diag.cause(tr)); return android.util.Log.w(tag, msg, tr) }
  fun e(tag: String, msg: String, tr: Throwable? = null): Int { Diag.line('E', msg + Diag.cause(tr)); return android.util.Log.e(tag, msg, tr) }
}

/**
 * The diagnostics report a user can email to the developer (Settings → Report a problem, or shake the phone).
 * Keeps, on the phone only: the app's log lines and every server call (method, path, status, time, error text),
 * persisted across restarts in a small rolling file, plus the latest server payload of each kind, redacted like
 * the debug logs (RawLog: phone, email, VIN, IMEI, registration, names, addresses and coordinates removed).
 * Nothing leaves the phone unless the user sends the email themselves.
 */
object Diag {
  private const val MAX_LINES = 1500          // in memory
  private const val FILE_MAX = 400_000L       // bytes on disk before the older half is dropped
  private const val PAYLOAD_MAX = 60_000      // characters kept per payload kind
  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }
  private val lines = ArrayDeque<String>()
  private val pending = ArrayList<String>()   // lines logged before init(), written once the file is known
  private val payloads = LinkedHashMap<String, Pair<Long, String>>()
  private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
  @Volatile private var file: File? = null

  // phone numbers, emails and long digit runs that slip into error texts (VINs/IMEIs are RawLog's job)
  private val PHONE = Regex("(?<![0-9])(\\+?91[ -]?)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])")
  private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
  private val TOKEN = Regex("(?i)(bearer\\s+|token[\"=:\\s]+)[A-Za-z0-9._~+/=-]{12,}")

  fun scrub(s: String): String =
    TOKEN.replace(EMAIL.replace(PHONE.replace(RawLog.redact(s) as String, "<hidden>"), "<hidden>")) { it.groupValues[1] + "<hidden>" }

  internal fun cause(tr: Throwable?) = tr?.let { " — ${it.javaClass.simpleName}: ${it.message.orEmpty().take(300)}" }.orEmpty()

  /** Start persisting (idempotent) and record crashes. Lines logged before this are kept. */
  fun init(ctx: Context) {
    if (file != null) return
    synchronized(this) {
      if (file != null) return
      val f = File(ctx.applicationContext.filesDir, "diag/log.txt").apply { parentFile?.mkdirs() }
      file = f
      val early = ArrayList(pending); pending.clear()
      io.execute { trim(f); f.appendText("---- app start ${stamp.format(Date())} ----\n" + early.joinToString("") { "$it\n" }) }
      val previous = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching { f.appendText("${stamp.format(Date())} E CRASH in ${t.name}: ${scrub(android.util.Log.getStackTraceString(e)).take(6000)}\n") }
        previous?.uncaughtException(t, e)
      }
    }
  }

  fun line(level: Char, msg: String) {
    val l = "${stamp.format(Date())} $level ${scrub(msg).take(2000)}"
    synchronized(this) {
      lines.addLast(l); while (lines.size > MAX_LINES) lines.removeFirst()
      val f = file
      if (f == null) { if (pending.size < 300) pending += l } else io.execute { runCatching { f.appendText("$l\n") } }
    }
  }

  /** One server call: "GET /api/v1/car/… → 200 in 312 ms", plus the error text when it failed. */
  fun api(method: String, url: String, code: Int, ms: Long, errorText: String?) {
    val path = runCatching { java.net.URL(url).let { it.host.substringBefore('.') + it.path } }.getOrDefault(url)
    val tail = if (code in 200..299 || errorText.isNullOrBlank()) "" else " · ${errorText.replace('\n', ' ').take(400)}"
    line(if (code in 200..399) 'A' else 'X', "api $method $path → ${if (code < 0) "no response" else code} in $ms ms$tail")
  }

  /** Latest server payload of a kind ("car", "trips", …), redacted; replaces the previous one. */
  fun payload(label: String, v: Any?) {
    if (v == null) return
    val text = runCatching { RawLog.render(RawLog.redact(v)) }.getOrElse { "<unreadable: ${it.javaClass.simpleName}>" }
    synchronized(this) { payloads[label] = System.currentTimeMillis() to text.take(PAYLOAD_MAX) }
  }

  private fun trim(f: File) {
    if (!f.exists() || f.length() <= FILE_MAX) return
    val keep = f.readText().takeLast((FILE_MAX / 2).toInt())
    f.writeText("…(older lines dropped)\n" + keep.substringAfter('\n'))
  }

  /** The report as a text file in cache/reports/ (shared through the app's FileProvider). */
  fun build(ctx: Context, summary: List<Pair<String, String>>): File {
    val now = Date()
    val (pl, mem) = synchronized(this) { payloads.toList() to lines.toList() }
    val saved = runCatching { io.submit<String> { file?.takeIf { it.exists() }?.readText().orEmpty() }.get() }.getOrDefault("")
    val day = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    val text = buildString {
      appendLine("CarPulse diagnostics report")
      appendLine("Created: ${day.format(now)}")
      appendLine("Phone numbers, emails, VIN, IMEI, registration number, names, addresses and coordinates are removed.")
      appendLine()
      summary.forEach { (k, v) -> appendLine("$k: $v") }
      appendLine()
      appendLine("==== Latest data from the car's servers (${pl.size} kinds, redacted) ====")
      pl.forEach { (label, p) -> appendLine("-- $label (${day.format(Date(p.first))})"); appendLine(p.second); appendLine() }
      appendLine("==== Log: server calls (A ok, X failed), warnings (W), errors (E) — newest last ====")
      append(saved.ifBlank { mem.joinToString("\n") })
      appendLine()
    }
    val dir = File(ctx.cacheDir, "reports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    return File(dir, "carpulse-report-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)}.txt").apply { writeText(text) }
  }

  /** "OnePlus CPH2551 · Android 15 (API 35)". */
  fun device() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
