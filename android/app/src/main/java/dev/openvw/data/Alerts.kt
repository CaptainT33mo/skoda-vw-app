package dev.openvw.data

import android.content.Context

/**
 * Car alerts checked in the background (see work/CarCheckWorker). The rules are a pure function of what the car
 * last reported plus what was already announced, so each alert fires once and only re-arms after the condition
 * clears: no repeated notifications every 15 minutes.
 */
object Alerts {
  enum class Type(val title: String, val description: String) {
    ENGINE("Engine on / off", "Every time the engine is switched on or off"),
    DRIVING("Driving alerts", "Sudden braking, sharp turns, speeding and other alarms from the car"),
    BATTERY("Battery low while parked", "12 V battery under 12.0 V while the car is parked"),
    CHECK_ENGINE("Check-engine light", "The engine warning light comes on"),
    NIGHT_START("Engine started at night", "Engine switched on between midnight and 5 AM"),
    FUEL("Fuel low", "Fuel at 12% or less"),
    SIGNED_OUT("Sign-in expired", "CarPulse needs a new OTP to keep checking your car"),
  }

  const val BATTERY_LOW_V = 12.0
  const val BATTERY_OK_V = 12.4      // re-arm the battery alert once it's back above this
  const val FUEL_LOW_PCT = 12
  const val FUEL_OK_PCT = 20         // re-arm after refuelling
  const val NIGHT_FROM = 0           // engine-on between 00:00 …
  const val NIGHT_TO = 5             // … and 04:59 local time
  const val FRESH_MS = 6 * 3600_000L // a report older than this is too stale to warn about

  /** What the car last reported (times in ms, except engine-on events: seconds, as the server sends them). */
  data class Snapshot(
    val now: Long,
    val signedIn: Boolean,
    val status: String,
    val batteryV: Double?,
    val reportedAt: Long,
    val milOn: Boolean,
    val fuelPct: Int?,
    val engineOnEpochs: List<Long>,
  )

  /** What was already announced. */
  data class State(
    val batteryAlerted: Boolean = false,
    val milAlerted: Boolean = false,
    val fuelAlerted: Boolean = false,
    val signedOutAlerted: Boolean = false,
    val lastEngineOnSeen: Long = 0L, // newest engine-on (s) already looked at; 0 = never checked
  )

  data class Alert(val type: Type, val title: String, val text: String)

  /** Alerts to post now and the updated state. [hourOf] gives the local hour of an epoch-seconds time. */
  fun evaluate(s: Snapshot, st: State, enabled: Set<Type>, hourOf: (Long) -> Int, clock: (Long) -> String): Pair<List<Alert>, State> {
    val out = mutableListOf<Alert>()
    var n = st

    if (!s.signedIn) {
      if (Type.SIGNED_OUT in enabled && !st.signedOutAlerted)
        out += Alert(Type.SIGNED_OUT, "Sign in to CarPulse again", "Your car login expired, so alerts are paused until you sign in with a new OTP.")
      return out to n.copy(signedOutAlerted = true)
    }
    n = n.copy(signedOutAlerted = false)
    val fresh = s.reportedAt > 0 && s.now - s.reportedAt < FRESH_MS

    // battery draining while parked (the plug-in dongle itself draws a little current)
    val v = s.batteryV
    if (v != null && fresh) {
      if (s.status == "Parked" && v < BATTERY_LOW_V && !st.batteryAlerted) {
        if (Type.BATTERY in enabled) out += Alert(Type.BATTERY, "Car battery is low (%.1f V)".format(java.util.Locale.US, v),
          "Your parked car's 12 V battery is getting weak. A 20–30 minute drive recharges it; if it keeps dropping, have it checked.")
        n = n.copy(batteryAlerted = true)
      } else if (v >= BATTERY_OK_V) n = n.copy(batteryAlerted = false)
    }

    // check-engine light: once per time it comes on
    if (s.milOn && !st.milAlerted) {
      if (Type.CHECK_ENGINE in enabled) out += Alert(Type.CHECK_ENGINE, "Check-engine light is on",
        "Your car reported an engine fault. Get it checked soon; if the light flashes or the car runs badly, stop driving.")
      n = n.copy(milAlerted = true)
    } else if (!s.milOn) n = n.copy(milAlerted = false)

    // fuel low: once per tank
    val f = s.fuelPct
    if (f != null && fresh) {
      if (f <= FUEL_LOW_PCT && !st.fuelAlerted) {
        if (Type.FUEL in enabled) out += Alert(Type.FUEL, "Fuel low ($f%)", "Time to refuel.")
        n = n.copy(fuelAlerted = true)
      } else if (f >= FUEL_OK_PCT) n = n.copy(fuelAlerted = false)
    }

    // engine started at night: only engine-on events newer than the last check (none on the very first check).
    // With engine on/off alerts on, that notification already says it was at night, so this one stays quiet.
    // baseline = newest event *time* (not the check time): an event reported late still counts as new
    val newest = s.engineOnEpochs.maxOrNull() ?: st.lastEngineOnSeen.takeIf { it > 0 } ?: (s.now / 1000)
    if (st.lastEngineOnSeen > 0) {
      val night = s.engineOnEpochs.filter { it > st.lastEngineOnSeen && hourOf(it) in NIGHT_FROM until NIGHT_TO }.maxOrNull()
      if (night != null && Type.NIGHT_START in enabled && Type.ENGINE !in enabled) out += Alert(Type.NIGHT_START, "Engine started at ${clock(night)}",
        "Your car's engine was switched on during the night. If this wasn't you or someone you know, check on your car.")
    }
    n = n.copy(lastEngineOnSeen = maxOf(newest, st.lastEngineOnSeen))
    return out to n
  }

  // ---- car events (engine on/off, driving alarms) ----

  /** Events already notified: nothing older than [baseline] (s) counts; [keys] stops repeats. */
  data class EventSeen(val baseline: Long = 0L, val keys: List<String> = emptyList())

  /** One key per event, the same whether it came pushed live or from the event list (their ids can differ). */
  fun eventKey(e: CarEvent) = "${e.kind}:${e.title}@${e.epoch}"

  const val EVENT_MAX_AGE_S = 12 * 3600L // a phone that was offline doesn't post a day of old events

  /**
   * Which of [events] are new: newer than when alerts were turned on, not older than [EVENT_MAX_AGE_S], and not
   * notified before. Oldest first. A first call (no baseline) only sets the baseline, so turning alerts on
   * doesn't replay history.
   */
  fun newEvents(events: List<CarEvent>, seen: EventSeen, nowS: Long): Pair<List<CarEvent>, EventSeen> {
    if (seen.baseline == 0L) return emptyList<CarEvent>() to seen.copy(baseline = nowS)
    val floor = maxOf(seen.baseline, nowS - EVENT_MAX_AGE_S)
    val known = seen.keys.toHashSet()
    val fresh = events.filter { it.epoch >= floor && eventKey(it) !in known }.distinctBy(::eventKey).sortedBy { it.epoch }
    return fresh to seen.copy(keys = (seen.keys + fresh.map(::eventKey)).takeLast(300))
  }

  /** Which alert type an event belongs to. */
  fun typeOf(e: CarEvent): Type = if (e.kind == "engine") Type.ENGINE else Type.DRIVING

  fun isNight(epochS: Long, hourOf: (Long) -> Int) = hourOf(epochS) in NIGHT_FROM until NIGHT_TO

  // ---- settings + state, kept on the phone ----

  private fun prefs(ctx: Context) = ctx.getSharedPreferences("openvw_alerts", Context.MODE_PRIVATE)

  fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
  fun setOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()

  /** Instant alerts: a live connection kept open in the background (see work/CarWatchService). */
  fun instant(ctx: Context) = prefs(ctx).getBoolean("instant", true)
  fun setInstant(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("instant", on).apply()

  fun eventSeen(ctx: Context): EventSeen {
    val p = prefs(ctx)
    return EventSeen(p.getLong("eventBaseline", 0L), p.getString("eventKeys", "").orEmpty().split('\n').filter { it.isNotBlank() })
  }

  fun saveEventSeen(ctx: Context, s: EventSeen) =
    prefs(ctx).edit().putLong("eventBaseline", s.baseline).putString("eventKeys", s.keys.joinToString("\n")).apply()

  fun enabled(ctx: Context): Set<Type> = Type.entries.filter { prefs(ctx).getBoolean("type_${it.name}", true) }.toSet()
  fun setEnabled(ctx: Context, t: Type, on: Boolean) = prefs(ctx).edit().putBoolean("type_${t.name}", on).apply()

  fun state(ctx: Context): State {
    val p = prefs(ctx)
    return State(p.getBoolean("batteryAlerted", false), p.getBoolean("milAlerted", false), p.getBoolean("fuelAlerted", false),
      p.getBoolean("signedOutAlerted", false), p.getLong("lastEngineOnSeen", 0L))
  }

  fun saveState(ctx: Context, s: State) = prefs(ctx).edit()
    .putBoolean("batteryAlerted", s.batteryAlerted).putBoolean("milAlerted", s.milAlerted).putBoolean("fuelAlerted", s.fuelAlerted)
    .putBoolean("signedOutAlerted", s.signedOutAlerted).putLong("lastEngineOnSeen", s.lastEngineOnSeen).apply()

  /** Forget what was announced (fresh start after sign-in or turning alerts on). */
  fun resetState(ctx: Context) {
    saveState(ctx, State())
    saveEventSeen(ctx, EventSeen(baseline = System.currentTimeMillis() / 1000))
  }
}
