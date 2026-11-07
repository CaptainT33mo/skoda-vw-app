package dev.openvw.data

import dev.openvw.data.Alerts.Snapshot
import dev.openvw.data.Alerts.State
import dev.openvw.data.Alerts.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
  private val now = 1_800_000_000_000L
  private val all = Type.entries.toSet()
  private val noEngine = all - Type.ENGINE // the separate night alert only shows when engine on/off alerts are off
  private fun snap(
    status: String = "Parked", v: Double? = 12.6, reportedAgoMin: Long = 10, mil: Boolean = false, fuel: Int? = 50,
    engineOn: List<Long> = emptyList(), signedIn: Boolean = true,
  ) = Snapshot(now, signedIn, status, v, now - reportedAgoMin * 60_000, mil, fuel, engineOn)

  // hour = (epoch seconds / 3600) % 24, so tests pick exact local hours without a time zone
  private fun run(s: Snapshot, st: State = State(lastEngineOnSeen = 1L), enabled: Set<Type> = all) =
    Alerts.evaluate(s, st, enabled, hourOf = { ((it / 3600) % 24).toInt() }, clock = { "t$it" })

  private fun types(r: Pair<List<Alerts.Alert>, State>) = r.first.map { it.type }

  @Test fun quietWhenAllIsWell() = assertTrue(run(snap()).first.isEmpty())

  @Test fun batteryLowWhileParkedFiresOnceThenRearms() {
    val (a1, st1) = run(snap(v = 11.8))
    assertEquals(listOf(Type.BATTERY), a1.map { it.type })
    assertTrue(run(snap(v = 11.7), st1).first.isEmpty())            // still low: no repeat
    val (_, st3) = run(snap(v = 12.2), st1)                           // between low and OK: stays armed-off
    assertTrue(run(snap(v = 11.6), st3).first.isEmpty())
    val (_, st4) = run(snap(v = 12.6), st3)                           // recovered: re-arms
    assertEquals(listOf(Type.BATTERY), types(run(snap(v = 11.9), st4)))
  }

  @Test fun noBatteryAlertWhileDrivingOrOnStaleData() {
    assertTrue(run(snap(status = "Driving", v = 11.5)).first.isEmpty())
    assertTrue(run(snap(v = 11.5, reportedAgoMin = 7 * 60)).first.isEmpty()) // last report 7 h old
  }

  @Test fun checkEngineOncePerOccurrence() {
    val (a1, st1) = run(snap(mil = true))
    assertEquals(listOf(Type.CHECK_ENGINE), a1.map { it.type })
    assertTrue(run(snap(mil = true), st1).first.isEmpty())
    val (_, st2) = run(snap(mil = false), st1)
    assertEquals(listOf(Type.CHECK_ENGINE), types(run(snap(mil = true), st2)))
  }

  @Test fun fuelLowOncePerTank() {
    val (a1, st1) = run(snap(fuel = 10))
    assertEquals(listOf(Type.FUEL), a1.map { it.type })
    assertTrue(run(snap(fuel = 8), st1).first.isEmpty())
    val (_, st2) = run(snap(fuel = 70), st1)
    assertEquals(listOf(Type.FUEL), types(run(snap(fuel = 11), st2)))
  }

  @Test fun nightStartOnlyForNewEvents() {
    val day = 86_400L * 20_000
    val twoAm = day + 2 * 3600
    val tenAm = day + 10 * 3600
    assertEquals(listOf(Type.NIGHT_START), types(run(snap(engineOn = listOf(twoAm)), State(lastEngineOnSeen = day), noEngine)))
    assertTrue(run(snap(engineOn = listOf(tenAm)), State(lastEngineOnSeen = day)).first.isEmpty())     // daytime
    assertTrue(run(snap(engineOn = listOf(twoAm)), State(lastEngineOnSeen = twoAm)).first.isEmpty())   // already seen
  }

  @Test fun firstCheckOnlySetsTheBaseline() {
    val twoAm = 86_400L * 20_000 + 2 * 3600
    val (a, st) = run(snap(engineOn = listOf(twoAm)), State())
    assertTrue(a.isEmpty())
    assertEquals(twoAm, st.lastEngineOnSeen)
  }

  @Test fun lateReportedNightStartStillCounts() {
    // the check ran at 2:03 (baseline = newest event, from yesterday), the 2:00 event only arrived by 2:18
    val day = 86_400L * 20_000
    val (_, st) = run(snap(engineOn = listOf(day - 3600)), State(lastEngineOnSeen = 1L))
    assertEquals(listOf(Type.NIGHT_START), types(run(snap(engineOn = listOf(day - 3600, day + 2 * 3600)), st, noEngine)))
  }

  @Test fun nightStartFoldsIntoTheEngineAlert() {
    val day = 86_400L * 20_000
    assertTrue(run(snap(engineOn = listOf(day + 2 * 3600)), State(lastEngineOnSeen = day)).first.isEmpty())
  }

  // ---- engine on/off and driving alarm events ----
  private fun ev(title: String, epoch: Long, kind: String = if (title.startsWith("Engine")) "engine" else "alarm") =
    CarEvent("id-$epoch", kind, title, epoch, 12.9, 77.6)

  @Test fun turningAlertsOnDoesNotReplayHistory() {
    val (fresh, seen) = Alerts.newEvents(listOf(ev("Engine on", 1_000)), Alerts.EventSeen(), nowS = 2_000)
    assertTrue(fresh.isEmpty())
    assertEquals(2_000L, seen.baseline)
  }

  @Test fun eachEventIsNotifiedOnceWhetherPushedOrListed() {
    var seen = Alerts.EventSeen(baseline = 1_000)
    val pushed = ev("Engine on", 1_500)
    val (a, s1) = Alerts.newEvents(listOf(pushed), seen, nowS = 1_600); seen = s1
    assertEquals(listOf(pushed), a)
    // the event list later has the same event (its id may differ) plus a new alarm
    val listed = listOf(pushed.copy(id = "server-id"), ev("Sudden brake", 1_700), ev("Engine on", 900))
    val (b, _) = Alerts.newEvents(listed, seen, nowS = 1_800)
    assertEquals(listOf("Sudden brake"), b.map { it.title }) // the 900 one is from before alerts were on
  }

  @Test fun oldEventsAfterALongGapAreSkippedAndOrderIsOldestFirst() {
    val now = 100_000L
    val seen = Alerts.EventSeen(baseline = 1)
    val (fresh, _) = Alerts.newEvents(listOf(ev("Engine off", now - 60), ev("Engine on", now - 600), ev("Engine on", now - 13 * 3600)), seen, now)
    assertEquals(listOf(now - 600, now - 60), fresh.map { it.epoch })
  }

  @Test fun eventTypes() {
    assertEquals(Type.ENGINE, Alerts.typeOf(ev("Engine off", 1)))
    assertEquals(Type.DRIVING, Alerts.typeOf(ev("Over the speed limit", 1)))
  }

  @Test fun signedOutAnnouncedOnceAndNothingElse() {
    val (a1, st1) = run(snap(signedIn = false, v = 11.0, mil = true))
    assertEquals(listOf(Type.SIGNED_OUT), a1.map { it.type })
    assertTrue(run(snap(signedIn = false), st1).first.isEmpty())
    val (_, st2) = run(snap(), st1)                                   // signed in again: re-arms
    assertEquals(listOf(Type.SIGNED_OUT), types(run(snap(signedIn = false), st2)))
  }

  @Test fun disabledTypesStaySilentButStillTrackState() {
    val (a, st) = run(snap(v = 11.5), enabled = all - Type.BATTERY)
    assertTrue(a.isEmpty())
    // turning it on later doesn't fire for the same low spell
    assertTrue(run(snap(v = 11.5), st).first.isEmpty())
  }
}
