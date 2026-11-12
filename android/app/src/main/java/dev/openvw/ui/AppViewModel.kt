package dev.openvw.ui

import android.app.Application
import dev.openvw.data.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openvw.data.AlertSetup
import dev.openvw.data.Api
import dev.openvw.data.CarEvent
import dev.openvw.data.CarLook
import dev.openvw.data.Brand
import dev.openvw.data.LiveFeed
import dev.openvw.data.MapSnapshot
import dev.openvw.data.Parked
import dev.openvw.data.Places
import dev.openvw.data.Trip
import dev.openvw.data.TripRoute
import dev.openvw.data.Crypto
import dev.openvw.data.DriveStats
import dev.openvw.data.Dongle
import dev.openvw.data.Owner
import dev.openvw.data.PlanInfo
import dev.openvw.data.Repo
import dev.openvw.data.Session
import dev.openvw.data.Store
import dev.openvw.data.Telemetry
import dev.openvw.data.VehicleInfo
import dev.openvw.data.WidgetData
import dev.openvw.data.WidgetSnapshot
import dev.openvw.data.RawLog
import dev.openvw.data.Telematics
import dev.openvw.data.jsonPlain
import dev.openvw.data.toPlain
import dev.openvw.widget.OpenVWWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

enum class Phase { BOOTING, LOGIN, OTP, LOADING, READY }

private const val TAG = "CarPulse"

class AppViewModel(app: Application) : AndroidViewModel(app) {
  val session = Session(app)

  var phase by mutableStateOf(Phase.BOOTING); private set
  var message by mutableStateOf<String?>(null); private set
  var busy by mutableStateOf(false); private set
  var phone by mutableStateOf("")
  /** Demo mode: a labelled sample car, no network (see [dev.openvw.data.Demo]). */
  var demo by mutableStateOf(false); private set
  private var demoJob: Job? = null

  private val loadLock = Mutex()
  private var lastFull = 0L
  private var widgetsDue = true // widgets refresh once per app open, not on every poll

  // live push feed (SignalR) — runs only while the app is in the foreground
  private var feed: LiveFeed? = null
  private var trailAt = 0L // epoch s of the newest point on Store.trail
  private var carsLoaded = false // the telematics car list was read this app start

  /** Full-payload dumps: always in debug builds; in a Play build after `adb shell setprop log.tag.CarPulse VERBOSE`. */
  private val debuggable by lazy {
    (getApplication<Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }
  private val dumpedShapes = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

  /**
   * Log a server payload, identifiers and coordinates masked (RawLog), the first time [label] is seen and again
   * whenever its set of fields changes, so a drive's pushes log once instead of every few seconds.
   * Read with: adb logcat -s CarPulse | grep raw
   */
  private fun dump(label: String, v: Any?) {
    dev.openvw.data.Diag.payload(label, v) // always: the latest of each kind goes into "Report a problem"
    if (v == null || !(debuggable || Log.isLoggable(TAG, Log.VERBOSE))) return
    val shape = RawLog.shape(v)
    if (dumpedShapes[label] == shape) return
    dumpedShapes[label] = shape
    val parts = RawLog.render(RawLog.redact(v)).chunked(3500) // logcat truncates long lines
    parts.forEachIndexed { i, part -> Log.d(TAG, "raw $label${if (parts.size > 1) " (${i + 1}/${parts.size})" else ""}: $part") }
  }
  private var liveWanted = false
  private var liveJob: Job? = null
  private val liveHolders = mutableSetOf<String>() // who wants the live feed: phone UI, Android Auto
  private var releaseJob: Job? = null
  private var refreshFailures = 0     // consecutive refusals of the Cerence refresh token
  private var refreshRetryAt = 0L     // no refresh attempt before this (ms), after a failure
  private var lastGeo: Pair<Double, Double>? = null
  private var pollQueued = false
  private var searchJob: Job? = null
  private var lastProfileSyncAt = 0L
  private var lastUserInfoPayload: JSONObject? = null
  private var lastVehicleProfilePayload: JSONObject? = null
  /** Owner as the account reports it; [Store.owner] is this with the phone-only edits on top. */
  private var accountOwner = Repo.owner

  init {
    dev.openvw.data.Diag.init(app)
    Store.shakeToReport = session.shakeToReport
    phone = session.mobile
    Store.brand = Brand.of(session.brand) ?: Brand.SKODA
    Store.savedWorkshops = dev.openvw.data.SavedWorkshops.load(app)
    Store.cars = runCatching {
      val a = org.json.JSONArray(session.cars)
      (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        dev.openvw.data.CarRef(o.optString("id"), o.optString("vin"), o.optString("name"), o.optString("model"),
          o.optString("variant"), Brand.of(o.optString("brand")), o.optString("registrationNumber"),
          o.optString("imei"), o.optString("firmware"), o.optString("fuelType"), o.optString("modelYear"))
      }
    }.getOrDefault(emptyList())
    Store.alertsOn = dev.openvw.data.Alerts.isOn(app); Store.alertTypes = dev.openvw.data.Alerts.enabled(app)
    Store.alertsInstant = dev.openvw.data.Alerts.instant(app)
    Store.instantAvailable = dev.openvw.work.CarWatchService.available(app)
    dev.openvw.work.CarCheckWorker.schedule(app)
    Store.privacy = session.privacy
    Store.paintPick = session.paintPick
    Store.fuelPrice = session.fuelPrice.toDouble()
    Store.themeMode = session.themeMode // the effective dark flag is worked out by the theme (it knows the system setting)
    Store.connectedSince = session.connectedSince
    restoreOwner()
    if (session.loggedIn) { phase = Phase.LOADING; refresh() }
    else if (session.demo) startDemo()
    else phase = Phase.LOGIN
  }

  fun setPrivacy(b: Boolean) { session.privacy = b; Store.privacy = b }

  /** Moon/sun button and the Dark mode switch: an explicit choice that stops following the system. */
  fun setDark(b: Boolean) = setThemeMode(if (b) "dark" else "light")

  /** "" = follow the system theme again. */
  fun setThemeMode(mode: String) {
    session.themeMode = mode; Store.themeMode = mode
    dev.openvw.theme.Palette.dark = dev.openvw.theme.Palette.resolve(mode, dev.openvw.theme.Palette.systemDark)
    Store.dark = dev.openvw.theme.Palette.dark
  }

  /**
   * A reg number reported by the servers: shown and remembered. It can't be edited in the app (as in the official
   * MySkoda Plus app): the registration comes from the car's account.
   */
  private fun accountRegNo(reg: String) {
    if (reg.isBlank()) return
    session.regNoOverride = reg
    Store.vehicle = Store.vehicle.copy(regNo = reg)
  }

  /** Brand picked on the sign-in screen: decides which brand code the servers get. */
  fun setBrand(b: Brand) {
    if (Store.brand == b && session.brand == b.name) return
    Store.brand = b; session.brand = b.name
  }

  /** Manual car colour ("" = follow the account colour); widgets redraw with it right away. */
  fun setPaint(name: String) {
    session.paintPick = name; Store.paintPick = name
    runCatching { saveWidgets() }
  }

  /** Owner's average petrol price in ₹/L (0 = back to the default). */
  fun setFuelPrice(p: Double) { session.fuelPrice = p.toFloat(); Store.fuelPrice = p }

  fun startLogin(p: String) {
    val mob = p.filter { it.isDigit() }
    if (mob.length != 10) { message = "Enter a 10-digit mobile number"; return }
    if (demo) exitDemo()
    busy = true; message = null
    viewModelScope.launch {
      try {
        session.mobile = mob
        session.brand = Store.brand.name
        // savwconnect device registration + bootstrap token: only the optional profile extras need them, so
        // a savwconnect hiccup must not block sign-in (the login itself is Cerence's).
        runCatching {
          val inst = Api.appInstallation(session.deviceID)
          session.installationID = inst.optInt("installationID", session.installationID)
          if (session.installationID != 0) {
            val auth = Api.authenticate(session.installationID, 0)
            auth.optString("accessToken").takeIf { it.isNotEmpty() }?.let { session.token = it; session.refreshToken = auth.optString("refreshToken") }
          }
        }.onFailure { Log.w(TAG, "savwconnect bootstrap failed (continuing with Cerence login)", it) }
        // First the savwconnect sign-in MySkoda Plus uses: one OTP gives the Skoda account (car colour, engine no.,
        // purchase date) and, through it, the telematics token. Some accounts get no SMS from Cerence's own OTP
        // (below) yet sign in fine this way. Only for numbers savwconnect already knows; anyone else signs in
        // through Cerence as before.
        savwOtp = runCatching { startSavwLogin(mob) }.onFailure { Log.w(TAG, "savw sign-in: start failed", it) }.getOrDefault(false)
        if (savwOtp) { busy = false; phase = Phase.OTP; return@launch }
        // OTP login goes through Cerence, like the official MySkoda Connected app. The savwconnect
        // registerUser/verifyLoginOTP pair needs a loginId that some accounts never get (it comes back 0).
        if (!sendCerenceOtp(mob)) return@launch
        busy = false; phase = Phase.OTP
      } catch (e: Exception) { fail(e.message ?: "Network error") }
    }
  }

  /** Asks Cerence to SMS an OTP; keeps the sessionId the login call needs. False (with [message] set) on failure. */
  private suspend fun sendCerenceOtp(mob: String): Boolean {
    val r = Api.cerenceSendOtp(mob)
    val sid = r.deepStringAny("sessionId")
    Log.d(TAG, "cerence otp: status=${r.opt("_status")} keys=${r.keys().asSequence().toList()} " +
      "sessionId=${if (sid.isBlank()) "missing" else "ok"} message=${r.cerenceError()}")
    if (sid.isBlank()) { fail(r.cerenceError().ifBlank { "Could not send OTP" }); return false }
    session.otpSessionId = sid
    return true
  }

  /** The OTP on screen was sent by savwconnect (the single Skoda sign-in), not Cerence. */
  private var savwOtp = false

  /**
   * savwconnect registerUser: for a number it already knows (isExistingUser, with a loginId and userId) it SMSes
   * the login OTP, like MySkoda Plus. True when that's the case; false sends the caller to the Cerence sign-in.
   * (MySkoda Plus registers unknown numbers as new Skoda accounts; CarPulse doesn't create accounts.)
   */
  private suspend fun startSavwLogin(mob: String): Boolean {
    if (session.installationID == 0 || session.token.isBlank()) { Log.d(TAG, "savw sign-in: no installation token"); return false }
    val reg = Api.registerUser(mob, session.installationID, session.token)
    dump("savw registerUser", reg.toPlain())
    val existing = reg.deepBooleanAny("existingUser", "isExistingUser") // the server sends "existingUser"
    val (loginId, userId) = extractOtpSessionIds(reg)
    Log.d(TAG, "savw sign-in: registerUser status=${reg.opt("_status")} existing=$existing loginId=${if (loginId > 0) "ok" else loginId} " +
      "userId=${if (userId > 0) "ok" else userId} message=${reg.optString("message").take(80)}")
    if (existing != true || loginId <= 0 || userId <= 0) return false
    session.loginId = loginId; session.userId = userId
    return true
  }

  /** savwconnect OTP check; on success the account ids stay in the session and loadData() does the rest. */
  private suspend fun verifySavwOtp(code: String) {
    val r = Api.verifyLoginOTP(code, session)
    dump("savw verifyLoginOTP", r.toPlain())
    val ok = r.optBoolean("isSuccessful") || r.deepBooleanAny("isSuccessful") == true
    Log.d(TAG, "savw sign-in: verify status=${r.opt("_status")} ok=$ok messagecode=${r.opt("messagecode")} message=${r.optString("message").take(80)}")
    if (!ok) { fail(r.optString("message").ifBlank { "Incorrect code" }); return }
    r.optLong("signStamp").takeIf { it > 0 }?.let { session.signStamp = it }
    savwOtp = false
    dev.openvw.data.Alerts.resetState(getApplication())
    session.telePhone = "+91" + session.mobile
    // telematics comes from the CMS cerenceToken minted for this userId (loadTelematics), not a Cerence login
    session.otpSessionId = ""; session.teleToken = ""; session.teleRefreshToken = ""; session.teleExpiresAt = 0
    session.cerenceToken = ""; session.carId = ""; carsLoaded = false
    session.verified = true
    busy = false; phase = Phase.LOADING
    loadData(); phase = Phase.READY; poll()
    dev.openvw.work.CarWatchService.sync(getApplication())
  }

  /** Error text from a failed Cerence response ({message} / {error} / {errorCode}), or "". */
  private fun JSONObject.cerenceError(): String =
    deepStringAny("message", "errorMessage", "error_description", "error").ifBlank { optString("_raw").take(120) }

  /** Back from the OTP step to the phone-number step. */
  fun backToPhone() { busy = false; message = null; savwOtp = false; phase = Phase.LOGIN }

  fun resend() {
    viewModelScope.launch {
      val ok = runCatching {
        if (savwOtp) Api.resendLoginOTP(session.loginId, session.userId, session.mobile, session.installationID, session.token)
          .let { it.optBoolean("isSuccessful") || it.optInt("_status") in 200..299 }
        else sendCerenceOtp(session.mobile)
      }.getOrDefault(false)
      if (ok) message = "Code re-sent" else if (message == null) message = "Could not resend code"
    }
  }

  fun verify(otp: String) {
    val code = otp.filter { it.isDigit() }
    if (code.length != 6) { message = "Enter the 6-digit code"; return }
    busy = true; message = null
    viewModelScope.launch {
      try {
        if (savwOtp) { verifySavwOtp(code); return@launch }
        if (session.otpSessionId.isBlank()) { fail("Code expired. Tap Resend and try again"); return@launch }
        val r = Api.cerenceLogin(session.otpSessionId, code)
        val tokenInfo = r.optJSONObject("loginInfo")?.optJSONObject("tokenInfo") ?: r.optJSONObject("tokenInfo")
        val access = tokenInfo?.optString("accessToken").orEmpty()
        Log.d(TAG, "cerence login: status=${r.opt("_status")} keys=${r.keys().asSequence().toList()} " +
          "accessToken=${if (access.isBlank()) "missing" else "ok"} message=${r.cerenceError()}")
        if (access.isBlank()) { fail(r.cerenceError().ifBlank { "Incorrect code" }); return@launch }
        session.otpSessionId = ""
        saveTeleTokens(tokenInfo!!)
        dev.openvw.data.Alerts.resetState(getApplication())
        session.telePhone = "+91" + session.mobile
        // savwconnect ids are not part of this login; 0 keeps the savwconnect profile calls out of loadData()
        session.loginId = 0; session.userId = 0; session.cerenceToken = ""; session.carId = ""
        r.optJSONObject("userInfo")?.let { applyCerenceUser(it) }
        loadUserCar()
        session.verified = true
        busy = false; phase = Phase.LOADING
        loadData(); phase = Phase.READY; poll()
        dev.openvw.work.CarWatchService.sync(getApplication())
      } catch (e: Exception) { fail(e.message ?: "Network error") }
    }
  }

  /** Owner name/email and "connected since" from the Cerence userInfo, kept in the session for later starts. */
  private fun applyCerenceUser(u: JSONObject) {
    fun s(k: String) = u.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    // same order as the official app's profile page: "first last", else the nickname
    val name = listOf(s("firstName"), s("lastName")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { s("nickname") }
    Log.d(TAG, "cerence user: " + listOf("firstName", "lastName", "nickname", "email", "createDate")
      .joinToString(" ") { "$it=${if (s(it).isBlank()) "missing" else "ok"}" })
    if (name.isNotBlank()) session.ownerName = name
    if (s("email").isNotBlank()) session.ownerEmail = s("email")
    resolveOwnerAddress(u).takeIf { it.isNotBlank() }?.let { session.ownerAddress = it }
    restoreOwner()
    val since = normalizeOwnershipDate(s("createDate"))
    if (since.isNotBlank() && session.connectedSince.isBlank()) { session.connectedSince = since; Store.connectedSince = since }
  }

  /** Saved owner details -> Store (the savwconnect profile, when it loads, still overwrites them). */
  private fun restoreOwner() {
    setOwner(accountOwner.copy(
      name = session.ownerName.ifBlank { accountOwner.name },
      email = session.ownerEmail.ifBlank { accountOwner.email },
      mobile = session.telePhone.ifBlank { accountOwner.mobile },
      address = session.ownerAddress.ifBlank { accountOwner.address },
    ))
  }

  private fun setOwner(o: Owner) {
    accountOwner = o
    Store.owner = o.copy(
      name = session.nameOverride.ifBlank { o.name },
      email = session.emailOverride.ifBlank { o.email },
      address = session.addressOverride.ifBlank { o.address },
    )
  }

  /** Owner info typed in on this phone; a blank field goes back to the account's value. */
  fun setOwnerDetails(name: String, email: String, address: String) {
    session.nameOverride = name.trim()
    session.emailOverride = email.trim()
    session.addressOverride = address.trim()
    setOwner(accountOwner)
  }

  /** VIN, car id and reg no of the first car mapped to this Cerence user. */
  /** The account's cars from the inventory; shows the one picked before (or the first). */
  private suspend fun loadUserCar() {
    val r = Api.userCars(session.teleToken)
    val arr = r.optJSONArray("cars")
    dev.openvw.data.Diag.payload("cars", r.toPlain())
    Log.d(TAG, "cerence cars: status=${r.opt("_status")} count=${arr?.length()} " +
      "carKeys=${arr?.optJSONObject(0)?.keys()?.asSequence()?.toList()}")
    if (arr == null || arr.length() == 0) return
    val cars = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { carRef(it) } }
    Store.cars = cars
    session.cars = org.json.JSONArray(cars.map { carJson(it) }).toString()
    dev.openvw.data.Cars.pick(cars, session.vin)?.let { applyCarRef(it) }
  }

  private fun carRef(c: JSONObject): dev.openvw.data.CarRef {
    fun str(vararg k: String) = c.optStringAny(*k).takeIf { it != "null" }.orEmpty()
    val vin = str("vin")
    return dev.openvw.data.CarRef(
      id = str("carId", "_id", "id"), vin = vin,
      // the official app titles the car with its inventory name ("Factory:<VIN>" unless the owner renamed it)
      name = str("name").ifBlank { if (vin.isNotBlank()) "Factory:$vin" else "" },
      model = str("model"), variant = str("variant"),
      brand = Brand.detect(c.optString("brand"), c.optString("model"), c.optString("name")),
      regNo = str("registrationNumber"),
      imei = str("imei"), firmware = str("firmware"), fuelType = str("fuelType"), modelYear = str("modelYear"),
    )
  }

  private fun carJson(c: dev.openvw.data.CarRef) = JSONObject().put("id", c.id).put("vin", c.vin).put("name", c.name)
    .put("model", c.model).put("variant", c.variant).put("brand", c.brand?.name ?: "").put("registrationNumber", c.regNo)
    .put("imei", c.imei).put("firmware", c.firmware).put("fuelType", c.fuelType).put("modelYear", c.modelYear)

  /** Make [c] the car on screen: ids, names, brand and its own saved settings. */
  private fun applyCarRef(c: dev.openvw.data.CarRef) {
    if (c.vin.isNotBlank()) session.vin = c.vin
    Store.carVin = c.vin
    if (c.id.isNotBlank()) session.carId = c.id
    Store.vehicle = Store.vehicle.copy(
      vehicleName = c.name.ifBlank { Store.vehicle.vehicleName },
      model = c.model.ifBlank { Store.vehicle.model },
      variant = c.variant.ifBlank { Store.vehicle.variant },
      // the dongle and car details the official apps show (the inventory has them; the old profile API rarely did)
      dongleImei = c.imei.ifBlank { Store.vehicle.dongleImei },
      dongleFirmware = c.firmware.ifBlank { Store.vehicle.dongleFirmware },
      fuelType = c.fuelType.ifBlank { Store.vehicle.fuelType },
      modelYear = c.modelYear.ifBlank { Store.vehicle.modelYear },
    )
    // older versions let the owner type a reg number in; drop it so the account's own value shows
    if (session.regNoManual) {
      session.regNoManual = false; session.regNoOverride = ""
      Store.vehicle = Store.vehicle.copy(regNo = c.regNo)
    }
    accountRegNo(c.regNo)
    Store.paintPick = session.paintPick
    // The inventory says which brand the car really is: trust it over the sign-in toggle
    c.brand?.let { b ->
      if (b != Store.brand) Log.d(TAG, "brand from inventory: $b (sign-in picked ${Store.brand})")
      setBrand(b)
    }
  }

  /** Car switcher: show another car on the account. */
  fun selectCar(vin: String) {
    val c = Store.cars.firstOrNull { it.vin == vin } ?: return
    if (demo) { dev.openvw.data.Demo.load(c.brand ?: Brand.SKODA); return }
    if (vin == session.vin) return
    // everything on screen belonged to the old car
    dev.openvw.data.Cars.clearCarState()
    session.carId = "" // re-resolved from the new car (applyCarRef sets it when the inventory gave one)
    applyCarRef(c)
    dev.openvw.data.Alerts.resetState(getApplication()) // fresh alert baseline for this car
    lastGeo = null; lastFull = 0L; widgetsDue = true
    // the live feed is bound to one car: reconnect it to the new one if anyone is watching
    val watching = liveHolders.isNotEmpty()
    stopLive()
    if (watching) { liveWanted = true; startLive() }
    poll()
  }

  /** Background car alerts on/off (the caller has asked for notification permission). */
  fun setAlertsOn(on: Boolean) {
    val ctx = getApplication<Application>()
    if (on && !dev.openvw.data.Alerts.isOn(ctx)) dev.openvw.data.Alerts.resetState(ctx)
    dev.openvw.data.Alerts.setOn(ctx, on); Store.alertsOn = on
    dev.openvw.work.CarCheckWorker.schedule(ctx)
    dev.openvw.work.CarWatchService.sync(ctx)
  }

  /** Settings: shaking the phone offers "Report a problem?" (MainActivity listens while the app is on screen). */
  fun setShakeToReport(on: Boolean) { session.shakeToReport = on; Store.shakeToReport = on }

  /** The owner typed in the dashboard odometer (null = forget it). Only for cars that send no reading of their own. */
  fun setOdometerReading(km: Int?) {
    if (demo || Store.odoSource == dev.openvw.data.OdoSource.CAR || Store.odoSource == dev.openvw.data.OdoSource.LAST_TRIP) return
    if (km == null) {
      session.odoReading = ""
      Store.odoSource = dev.openvw.data.OdoSource.NONE
      Store.telemetry = Store.telemetry.copy(odometerKm = null)
      Store.trips = dev.openvw.data.Odometer.clear(Store.trips)
      Store.tripResults = dev.openvw.data.Odometer.clear(Store.tripResults)
      Store.detailTrip = Store.detailTrip?.let { dev.openvw.data.Odometer.clear(listOf(it)).first() }
      return
    }
    session.odoReading = dev.openvw.data.Odometer.Reading(km.toDouble(), System.currentTimeMillis() / 1000).save()
    Log.d(TAG, "odometer: owner entered a reading")
    applyOdometerReading()
  }

  /** For cars that send no odometer: the owner's reading moved on by the trips since, and each trip's estimate. */
  private fun applyOdometerReading() {
    if (demo) return
    val src = Store.odoSource
    if (src == dev.openvw.data.OdoSource.CAR || src == dev.openvw.data.OdoSource.LAST_TRIP) return
    val saved = dev.openvw.data.Odometer.Reading.parse(session.odoReading) ?: return
    val now = dev.openvw.data.Odometer.advance(saved, Store.trips)
    if (now != saved) session.odoReading = now.save()
    Store.odoSource = dev.openvw.data.OdoSource.READING
    Store.telemetry = Store.telemetry.copy(odometerKm = Math.round(now.km).toInt())
    Store.trips = dev.openvw.data.Odometer.fill(now, Store.trips)
    Store.tripResults = withReading(Store.tripResults)
    Store.detailTrip = Store.detailTrip?.let { withReading(listOf(it)).first() }
  }

  /** [list] with estimated odometer readings, when the owner's reading is in use. */
  private fun withReading(list: List<Trip>): List<Trip> {
    if (Store.odoSource != dev.openvw.data.OdoSource.READING || list.isEmpty()) return list
    val r = dev.openvw.data.Odometer.Reading.parse(session.odoReading) ?: return list
    return dev.openvw.data.Odometer.fill(r, list)
  }

  /** Instant alerts: keep the live feed open in the background so engine / driving alerts arrive in seconds. */
  fun setInstantAlerts(on: Boolean) {
    val ctx = getApplication<Application>()
    dev.openvw.data.Alerts.setInstant(ctx, on); Store.alertsInstant = on
    dev.openvw.work.CarWatchService.sync(ctx)
  }

  /** Engine on/off and driving alarm notifications for events not notified yet (not for the demo car). */
  private fun notifyEvents(events: List<CarEvent>) {
    if (demo || events.isEmpty()) return
    val ctx = getApplication<Application>()
    if (!dev.openvw.data.Alerts.isOn(ctx)) return
    viewModelScope.launch { runCatching { dev.openvw.work.CarEventAlerts.check(ctx, events, CarLook.name()) } }
  }

  fun setAlertType(t: dev.openvw.data.Alerts.Type, on: Boolean) {
    val ctx = getApplication<Application>()
    dev.openvw.data.Alerts.setEnabled(ctx, t, on); Store.alertTypes = dev.openvw.data.Alerts.enabled(ctx)
  }

  /**
   * One background check (CarCheckWorker, on the main thread): the same load the app does when opened, which also
   * redraws the widgets, then a snapshot for the alert rules. Null = nothing to judge (demo, or no data).
   */
  suspend fun backgroundCheck(): dev.openvw.data.Alerts.Snapshot? {
    if (demo) return null
    fun signedOut() = dev.openvw.data.Alerts.Snapshot(System.currentTimeMillis(), false, "", null, 0L, false, null, emptyList())
    if (!session.loggedIn) return signedOut()
    // a cold start (process woken just for this check) is still running its first load
    kotlinx.coroutines.withTimeoutOrNull(90_000) { while (phase == Phase.BOOTING || phase == Phase.LOADING) delay(500) }
    if (phase == Phase.LOGIN) return signedOut()
    if (phase != Phase.READY) return null
    widgetsDue = true
    runCatching { loadLock.withLock { loadTelematics(full = true) } }
    if (phase == Phase.LOGIN || !session.loggedIn) return signedOut()
    if (!Store.live) return null
    val t = Store.telemetry
    return dev.openvw.data.Alerts.Snapshot(
      now = System.currentTimeMillis(), signedIn = true, status = Store.vehicle.status, batteryV = t.batteryV,
      reportedAt = Store.reportedAt, milOn = t.milOn, fuelPct = t.fuelPct,
      engineOnEpochs = Store.events.filter { it.kind == "engine" && it.title == "Engine on" }.map { it.epoch },
    )
  }

  /** "Try a demo car" on the sign-in screen: the sample car for the brand picked there. */
  fun startDemo() {
    if (session.loggedIn) return
    demo = true; session.demo = true
    message = null; busy = false
    dev.openvw.data.Demo.load(Store.brand)
    phase = Phase.READY
    demoJob?.cancel()
    demoJob = viewModelScope.launch {
      var step = 0
      while (isActive) {
        delay(1_500)
        if (liveHolders.isNotEmpty()) dev.openvw.data.Demo.tick(step++) // only "drive" while someone is looking
      }
    }
  }

  /** Leave demo mode: back to an empty app on the sign-in screen. */
  fun exitDemo() {
    setAlertsOn(false) // alerts were only ever about the sample car; signed-out checks must not report "expired"
    demoJob?.cancel(); demoJob = null
    demo = false; session.demo = false
    dev.openvw.data.Demo.clear()
    phase = Phase.LOGIN
  }

  fun refresh() {
    if (demo) return
    viewModelScope.launch {
      try { loadData() } catch (_: Exception) {}
      if (phase == Phase.LOGIN) return@launch // login expired while loading (see sessionExpired)
      phase = Phase.READY
      poll()
    }
  }

  /** App opened / brought to the foreground: full refresh, widgets once, then the live push feed. */
  fun onOpened() {
    acquireLive(HOLDER_PHONE)
    // the app is on screen: the one moment Android lets it (re)start the background watch
    dev.openvw.work.CarWatchService.sync(getApplication())
  }

  /** App left the foreground: drop the live connection unless Android Auto still shows it. */
  fun onBackground() = releaseLive(HOLDER_PHONE)

  /**
   * The live feed runs while anyone is looking: the phone UI in the foreground and/or the Android Auto
   * screen. Each caller holds it under its own name; the last one to let go closes it.
   */
  fun acquireLive(who: String) {
    releaseJob?.cancel(); releaseJob = null
    liveHolders += who
    widgetsDue = true; lastFull = 0L
    lastProfileSyncAt = 0L
    liveWanted = true
    poll()
    startLive()
  }

  /** Let go of the live feed; it closes [graceMs] after the last holder leaves (a quick app switch keeps it). */
  fun releaseLive(who: String, graceMs: Long = 0L) {
    liveHolders -= who
    if (liveHolders.isNotEmpty()) return
    releaseJob?.cancel()
    if (graceMs <= 0L) { stopLive(); return }
    releaseJob = viewModelScope.launch { delay(graceMs); if (liveHolders.isEmpty()) stopLive() }
  }

  private fun stopLive() {
    releaseJob = null
    liveWanted = false
    liveJob?.cancel(); liveJob = null
    feed?.let { f -> feed = null; viewModelScope.launch(Dispatchers.IO) { f.stop() } }
    Store.liveConnected = false
  }

  /** One REST refresh: live telemetry, plus trips/events if the last full load is >5 min old. */
  fun poll() {
    if (demo) return
    if (phase != Phase.READY || session.vin.isEmpty() || pollQueued) return
    pollQueued = true // waits for any running load instead of being dropped
    viewModelScope.launch {
      try {
        loadLock.withLock {
          pollQueued = false
          if (Store.vehicle.regNo.isBlank()) runCatching { refreshProfileFromServer(force = false) }
          runCatching { loadTelematics(full = System.currentTimeMillis() - lastFull > 5 * 60_000L) }
        }
      } finally { pollQueued = false }
    }
  }

  fun refreshTelematics() { if (feed?.connected != true) poll() }

  /** Pull-to-refresh is spinning. */
  var refreshing by mutableStateOf(false); private set

  /**
   * Pull-to-refresh: a full reload now (live values, trips, updates, parked spot, widgets), the live feed
   * reconnected if it dropped, and an active Trips date search re-run.
   */
  fun pullRefresh() {
    if (refreshing) return
    refreshing = true
    viewModelScope.launch {
      try {
        if (demo || phase != Phase.READY) { delay(500); return@launch } // nothing to fetch; just acknowledge the pull
        widgetsDue = true
        loadLock.withLock { runCatching { loadTelematics(full = true) } }
        if (liveHolders.isNotEmpty() && feed?.connected != true) startLive()
        Store.tripRange?.let { (a, b) -> searchTrips(a, b) }
      } finally { refreshing = false }
    }
  }

  /** One-tap debug export: all raw metadata payloads + mapped app values (for field tracing). */
  fun exportProfileMetadata() {
    viewModelScope.launch {
      val ctx = getApplication<Application>()
      runCatching {
        val payload = loadLock.withLock {
          refreshProfileFromServer(force = true, includeVehicleProfileDump = true)
          // refresh mapped values first (fuel/battery/distance/subscriptions etc.)
          runCatching { loadTelematics(full = true) }

          val raw = JSONObject()
          val userInfo = Api.userInfo(session)
          raw.put("userInfo", userInfo)

          val vehicleProfile = Api.vehicleProfileInfo(session)
          raw.put("vehicleProfileInfo", vehicleProfile ?: JSONObject.NULL)
          raw.put("savwVehicleInfoProbe", Api.savwVehicleInfoProbe(session))
          raw.put("subscriptionHistoryProbe", Api.subscriptionHistoryProbe(session))

          var teleToken = session.teleToken
          var carId = session.carId
          val phone = normalizePhoneForTelematics(
            session.telePhone.ifEmpty { if (session.mobile.isNotBlank()) "+91${session.mobile}" else "" },
          )
          val hasSessionTelematics = teleToken.isNotBlank() && carId.isNotBlank()

          // Reuse existing live session tokens first to avoid Cerence rate limits during export.
          if (hasSessionTelematics) {
            raw.put("cerenceToken", JSONObject().put("_skip", "reused session telematics token"))
            raw.put("telematicsToken", JSONObject().put("_skip", "reused session telematics token"))
            raw.put("resolveCar", JSONObject().put("_skip", "reused session carId"))
          } else {
            var cerenceToken = session.cerenceToken
            if (cerenceToken.isBlank()) {
              if (session.userId != 0) {
                val ct = Api.cerenceToken(session.userId)
                raw.put("cerenceToken", ct)
                val fresh = ct.optString("cerenceToken").ifEmpty { ct.optString("CerenceToken") }
                if (fresh.isNotBlank()) cerenceToken = fresh
              } else {
                raw.put("cerenceToken", JSONObject().put("_skip", "session.userId is 0"))
              }
            } else {
              raw.put("cerenceToken", JSONObject().put("_skip", "reused session cerence token"))
            }

            if (cerenceToken.isNotBlank() && session.vin.isNotBlank() && phone.isNotBlank()) {
              val tt = Api.telematicsToken(cerenceToken, phone, session.vin)
              raw.put("telematicsToken", tt)
              teleToken = tt.optJSONObject("loginInfo")?.optJSONObject("tokenInfo")?.optString("accessToken")
                ?: tt.optString("accessToken").ifBlank { session.teleToken }
              if (!teleToken.isNullOrBlank()) {
                val rc = Api.resolveCar(session.vin, teleToken)
                raw.put("resolveCar", rc)
                carId = rc.optJSONArray("cars")?.optJSONObject(0)?.optString("_id")
                  ?.ifEmpty { rc.optJSONArray("cars")?.optJSONObject(0)?.optString("id") ?: "" }
                  ?.ifBlank { session.carId }
                  ?: session.carId
              } else {
                raw.put("resolveCar", JSONObject().put("_skip", "telematics token unavailable"))
              }
            } else {
              raw.put("telematicsToken", JSONObject().put("_skip", "cerence token, vin, or phone unavailable"))
              raw.put("resolveCar", JSONObject().put("_skip", "cerence token, vin, or phone unavailable"))
            }
          }

          if (!teleToken.isNullOrBlank() && carId.isBlank() && session.vin.isNotBlank()) {
            val rc = Api.resolveCar(session.vin, teleToken)
            raw.put("resolveCarFallback", rc)
            carId = rc.optJSONArray("cars")?.optJSONObject(0)?.optString("_id")
              ?.ifEmpty { rc.optJSONArray("cars")?.optJSONObject(0)?.optString("id") ?: "" }
              ?.ifBlank { session.carId }
              ?: session.carId
          }

          if (!teleToken.isNullOrBlank() && carId.isNotBlank()) {
            raw.put("telematics", Api.telematics(carId, teleToken))
            raw.put("trips", Api.trips(carId, teleToken))
            raw.put("events", Api.events(carId, teleToken))
            raw.put("policies", Api.policies(carId, teleToken))
          } else {
            val why = if (teleToken.isNullOrBlank()) "telematics token unavailable" else "carId unavailable"
            raw.put("telematics", JSONObject().put("_skip", why))
            raw.put("trips", JSONObject().put("_skip", why))
            raw.put("events", JSONObject().put("_skip", why))
            raw.put("policies", JSONObject().put("_skip", why))
          }

          val plans = JSONArray().apply {
            Store.plans.forEach { p ->
              put(JSONObject().put("title", p.title).put("details", p.details).put("active", p.active))
            }
          }
          val mapped = JSONObject()
            .put("connectedSince", Store.connectedSince)
            .put("vehicle", JSONObject()
              .put("model", Store.vehicle.model)
              .put("variant", Store.vehicle.variant)
              .put("colour", Store.vehicle.colour)
              .put("regNo", Store.vehicle.regNo)
              .put("status", Store.vehicle.status)
            )
            .put("telemetry", JSONObject()
              .put("fuelPct", Store.telemetry.fuelPct)
              .put("batteryV", Store.telemetry.batteryV)
              .put("odometerKm", Store.telemetry.odometerKm)
              .put("coolantC", Store.telemetry.coolantC)
              .put("engineOn", Store.telemetry.engineOn)
            )
            .put("plans", plans)

          val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
          JSONObject()
            .put("exportedAt", exportedAt)
            .put("raw", raw)
            .put("mapped", mapped)
        }

        val outDir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        if (!outDir.exists()) outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(outDir, "carpulse-profile-metadata-$stamp.json")
        out.writeText(payload.toString(2))
        message = "Metadata exported: ${out.absolutePath}"
      }.onFailure {
        message = "Metadata export failed: ${it.message ?: "unknown error"}"
      }
    }
  }

  /** Connect to the car hub; on failure retry with backoff (re-minting the token on 401). */
  private fun startLive() {
    if (demo) return
    if (!liveWanted || liveJob?.isActive == true || feed?.connected == true) return
    liveJob = viewModelScope.launch {
      var backoff = 5_000L
      while (liveWanted && isActive) {
        if (phase == Phase.READY && session.vin.isNotEmpty()) {
          // (re)mint the telematics token / resolve the car if needed
          if (session.carId.isEmpty() || session.teleToken.isEmpty()) runCatching { loadLock.withLock { loadTelematics(full = false) } }
          if (session.carId.isEmpty() || session.teleToken.isEmpty()) { delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L); continue }
          val f = LiveFeed(
            session.carId, { session.teleToken },
            onStatus = { m -> viewModelScope.launch { applyCar(m, live = true) } },
            onEvent = { m -> viewModelScope.launch { applyEvent(m) } },
            onClosed = { e -> Log.d(TAG, "live feed: closed ${e?.message?.take(160) ?: ""}"); viewModelScope.launch { if (feed != null) { feed = null; Store.liveConnected = false; startLive() } } },
          )
          val err = withContext(Dispatchers.IO) { runCatching { f.start() }.exceptionOrNull() }
          Log.d(TAG, "live feed: " + if (err == null) "connected" else "failed: ${err.javaClass.simpleName} ${err.message?.take(160)}")
          if (err == null && liveWanted) { feed = f; Store.liveConnected = true; return@launch }
          withContext(Dispatchers.IO) { f.stop() }
          if (err?.message?.contains("401") == true) session.teleToken = "" // expired: re-mint below
          // keep the screen fresh while the hub is unreachable
          runCatching { loadLock.withLock { loadTelematics(full = false) } }
          delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
        } else delay(2_000) // still signing in / first load
      }
    }
  }

  /** The Cerence refresh token was refused: back to sign-in (phone number kept) with a clear reason. */
  private fun sessionExpired() {
    Log.w(TAG, "cerence session expired: refresh token refused $refreshFailures times, asking for a new OTP")
    refreshFailures = 0; refreshRetryAt = 0L
    stopLive() // holders stay: the phone UI / car screen pick the feed up again after sign-in
    session.clearAuth()
    dev.openvw.work.CarWatchService.sync(getApplication()) // signed out: nothing to watch
    Store.live = false; Store.liveConnected = false
    phone = session.mobile
    busy = false
    message = "Your ${Store.brand.label} login has expired. Sign in again with an OTP to see live data."
    phase = Phase.LOGIN
  }

  /**
   * Store a Cerence tokenInfo {accessToken, refreshToken, expiresIn}. The tokens are opaque, so the expiry is
   * only known from expiresIn; with it the token is renewed a day early, as the official app does.
   */
  private fun saveTeleTokens(ti: JSONObject) {
    session.teleToken = ti.optString("accessToken")
    ti.optString("refreshToken").takeIf { it.isNotBlank() }?.let { session.teleRefreshToken = it }
    val now = System.currentTimeMillis()
    val e = ti.optLong("expiresIn", 0L)
    session.teleExpiresAt = when {
      e <= 0L -> 0L
      e > 1_000_000_000_000L -> e          // already epoch ms
      e > 1_000_000_000L -> e * 1000L      // epoch seconds
      else -> now + e * 1000L              // lifetime in seconds
    }
    Log.d(TAG, "cerence token saved: expiresIn=${ti.opt("expiresIn")} -> " +
      if (session.teleExpiresAt > 0) Date(session.teleExpiresAt).toString() else "unknown")
  }

  fun logout() { if (demo) { exitDemo(); return }; setAlertsOn(false); Store.cars = emptyList(); liveHolders.clear(); stopLive(); session.clearAuth(); Store.live = false; phase = Phase.LOGIN }

  private fun fail(m: String) { busy = false; message = m; if (phase == Phase.LOADING) phase = if (session.verified) Phase.READY else Phase.LOGIN }

  private fun normKey(key: String): String =
    key.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")

  private fun keyMatches(actual: String, wanted: String): Boolean {
    if (actual.equals(wanted, ignoreCase = true)) return true
    return normKey(actual) == normKey(wanted)
  }

  private fun rawToInt(raw: Any?): Int {
    return when (raw) {
      is Number -> raw.toInt()
      is String -> {
        raw.trim().toIntOrNull()
          ?: Regex("-?\\d+").find(raw)?.value?.toIntOrNull()
          ?: 0
      }
      else -> 0
    }
  }

  private fun JSONObject.optIntAny(vararg keys: String): Int {
    for (key in keys) {
      if (has(key)) {
        val v = rawToInt(opt(key))
        if (v != 0) return v
      }
      val it = keys()
      while (it.hasNext()) {
        val actual = it.next()
        if (keyMatches(actual, key)) {
          val v = rawToInt(opt(actual))
          if (v != 0) return v
        }
      }
    }
    return 0
  }

  private fun JSONObject.deepIntAny(vararg keys: String): Int {
    val direct = optIntAny(*keys)
    if (direct != 0) return direct

    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> {
          val v = raw.deepIntAny(*keys)
          if (v != 0) return v
        }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) {
              val v = item.deepIntAny(*keys)
              if (v != 0) return v
            }
          }
        }
      }
    }
    return 0
  }

  private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
      val v = optString(key)
      if (v.isNotBlank()) return v
      val it = keys()
      while (it.hasNext()) {
        val actual = it.next()
        if (actual.equals(key, ignoreCase = true)) {
          val cv = optString(actual)
          if (cv.isNotBlank()) return cv
        }
      }
    }
    return ""
  }

  private fun JSONObject.deepStringAny(vararg keys: String): String {
    val direct = optStringAny(*keys)
    if (direct.isNotBlank()) return direct
    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> raw.deepStringAny(*keys).takeIf { it.isNotBlank() }?.let { return it }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) item.deepStringAny(*keys).takeIf { it.isNotBlank() }?.let { return it }
          }
        }
      }
    }
    return ""
  }

  private fun JSONObject.optBooleanAny(vararg keys: String): Boolean? {
    for (key in keys) {
      if (!has(key)) continue
      when (val raw = opt(key)) {
        is Boolean -> return raw
        is Number -> return raw.toInt() != 0
        is String -> {
          when (raw.trim().lowercase()) {
            "true", "yes", "active", "1" -> return true
            "false", "no", "inactive", "0" -> return false
          }
        }
      }
    }
    return null
  }

  private fun decodeRegPayload(reg: JSONObject): JSONObject? {
    val encrypted = reg.optStringAny("data", "response", "encryptedResponse", "rqt")
    if (encrypted.isBlank() || !encrypted.matches(Regex("[0-9A-Fa-f]+"))) return null
    for (k in listOf(Crypto.GEN, Crypto.DATA)) {
      val plain = runCatching { Crypto.decHex(encrypted, k) }.getOrNull().orEmpty()
      if (plain.isBlank()) continue
      val obj = runCatching { JSONObject(plain) }.getOrNull()
      if (obj != null) return obj
    }
    return null
  }

  private fun extractOtpSessionIds(reg: JSONObject): Pair<Int, Int> {
    val loginKeys = arrayOf("loginId", "loginID", "LoginId", "login_id", "loginid")
    val userKeys = arrayOf("userId", "userID", "UserId", "user_id", "userid")
    var loginId = reg.deepIntAny(*loginKeys)
    var userId = reg.deepIntAny(*userKeys)
    if (loginId != 0 && userId != 0) return loginId to userId

    decodeRegPayload(reg)?.let { decoded ->
      if (loginId == 0) loginId = decoded.deepIntAny(*loginKeys)
      if (userId == 0) userId = decoded.deepIntAny(*userKeys)
    }
    return loginId to userId
  }

  private fun JSONObject.deepBooleanAny(vararg keys: String): Boolean? {
    val direct = optBooleanAny(*keys)
    if (direct != null) return direct
    val objKeys = keys()
    while (objKeys.hasNext()) {
      val raw = opt(objKeys.next()) ?: continue
      when (raw) {
        is JSONObject -> raw.deepBooleanAny(*keys)?.let { return it }
        is JSONArray -> {
          for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item is JSONObject) item.deepBooleanAny(*keys)?.let { return it }
          }
        }
      }
    }
    return null
  }

  private fun parseNumberText(raw: String): Double? {
    val m = Regex("-?\\d+(?:\\.\\d+)?").find(raw.replace(",", ""))
    return m?.value?.toDoubleOrNull()
  }

  private fun cleanText(raw: String?): String {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return ""
    val n = v.lowercase()
    return if (n == "null" || n == "na" || n == "n/a" || n == "-") "" else v
  }

  private fun resolveOwnerAddress(vararg src: JSONObject?): String {
    val keys = arrayOf(
      "communicationAddress", "address", "addressLine1", "addressLine2", "address1", "address2",
      "fullAddress", "customerAddress", "ownerAddress", "mailingAddress", "residentialAddress",
      "streetAddress", "locationAddress", "cityAddress",
    )
    src.filterNotNull().forEach { obj ->
      val raw = obj.deepStringAny(*keys)
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isNotBlank()) return dec
    }
    return ""
  }

  private fun normalizePhoneForTelematics(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
      digits.length == 10 -> "+91$digits"
      digits.length == 12 && digits.startsWith("91") -> "+$digits"
      raw.startsWith("+") -> raw
      else -> if (digits.isNotBlank()) raw else ""
    }
  }

  private val regFormat = Regex("^[A-Z]{2}[ -]?[0-9]{1,2}[ -]?[A-Z]{0,3}[ -]?[0-9]{3,4}$")
  private val purchaseDateLike = Regex(
    "\\b\\d{1,2}[-/ ](?:[A-Za-z]{3,9}|\\d{1,2})[-/ ]\\d{2,4}\\b|\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b",
  )
  private val ownershipDateParsers = listOf(
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
    SimpleDateFormat("yyyy-MM-dd", Locale.US),
    SimpleDateFormat("yyyy/MM/dd", Locale.US),
    SimpleDateFormat("dd-MMM-yyyy", Locale.US),
    SimpleDateFormat("dd-MMM-yy", Locale.US),
    SimpleDateFormat("dd MMM yyyy", Locale.US),
    SimpleDateFormat("dd/MM/yyyy", Locale.US),
    SimpleDateFormat("dd/MM/yy", Locale.US),
    SimpleDateFormat("dd-MM-yyyy", Locale.US),
    SimpleDateFormat("dd-MM-yy", Locale.US),
  ).onEach { it.isLenient = false }
  private val ownershipOut = SimpleDateFormat("yyyy-MM-dd", Locale.US)

  private fun parseOwnershipDate(raw: String): Date? {
    val text = cleanText(raw)
    if (text.isBlank()) return null
    return ownershipDateParsers.firstNotNullOfOrNull { fmt ->
      runCatching { fmt.parse(text) }.getOrNull()
    }
  }

  private fun isReasonableOwnershipDate(d: Date): Boolean {
    val cal = Calendar.getInstance().apply { time = d }
    val year = cal.get(Calendar.YEAR)
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    return year in 1990..(thisYear + 1)
  }

  private fun normalizeOwnershipDate(raw: String): String {
    val d = parseOwnershipDate(raw) ?: return ""
    if (!isReasonableOwnershipDate(d)) return ""
    return ownershipOut.format(d)
  }

  private fun bestOwnershipDate(candidates: List<String>): String {
    val dates = candidates.mapNotNull { parseOwnershipDate(it)?.takeIf(::isReasonableOwnershipDate) }
    val best = dates.minByOrNull { it.time } ?: return ""
    return ownershipOut.format(best)
  }

  private fun looksLikeReg(value: String): Boolean {
    val v = value.uppercase(Locale.ENGLISH).replace(Regex("\\s+"), " ").trim()
    if (v.length < 6 || v.length > 16) return false
    if (v.contains("FACTORY:")) return false
    if (v.all { it.isDigit() }) return false
    return regFormat.matches(v)
  }

  private fun extractRegFromJson(root: Any?): String {
    val keyHints = listOf("registration", "vehicleReg", "regNo", "regNumber", "plate", "vehicleNumber")
    var hinted: String? = null
    val formatted = mutableListOf<String>()

    fun consider(raw: String, hintedKey: Boolean) {
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isBlank()) return
      val norm = dec.uppercase(Locale.ENGLISH).replace(Regex("\\s+"), " ").trim()
      if (looksLikeReg(norm)) {
        formatted += norm
        if (hintedKey) hinted = norm
      } else if (hintedKey && hinted.isNullOrBlank()) {
        hinted = norm
      }
    }

    fun walk(node: Any?) {
      when (node) {
        is JSONObject -> {
          val it = node.keys()
          while (it.hasNext()) {
            val k = it.next()
            val v = node.opt(k)
            if (v is String) {
              val isHint = keyHints.any { h -> k.contains(h, ignoreCase = true) }
              consider(v, isHint)
            }
            if (v is JSONObject || v is JSONArray) walk(v)
          }
        }
        is JSONArray -> {
          for (i in 0 until node.length()) walk(node.opt(i))
        }
      }
    }

    walk(root)
    return (hinted?.takeIf { looksLikeReg(it) } ?: formatted.firstOrNull() ?: hinted).orEmpty()
  }

  private fun extractOwnershipDateFromJson(root: Any?): String {
    val keyHints = listOf(
      "purchased", "purchase", "registration", "delivery", "invoice",
      "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
    )
    val keyBlock = listOf(
      "policy", "warranty", "expiry", "expiration", "expire",
      "insurance", "puc", "service", "subscription", "updated", "created",
    )
    val hintedDates = mutableListOf<Date>()

    fun normalizeDateCandidate(raw: String): String {
      val dec = cleanText(Crypto.decOrSelf(raw))
      if (dec.isBlank()) return ""
      val compact = dec.replace(Regex("\\s+"), " ").trim()
      val candidate = purchaseDateLike.find(compact)?.value ?: compact
      return normalizeOwnershipDate(candidate)
    }

    fun keyIsHint(key: String): Boolean {
      val norm = key.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")
      if (keyBlock.any { norm.contains(it) }) return false
      return keyHints.any { norm.contains(it) }
    }

    fun fromEpoch(raw: Number): String {
      val v = raw.toLong()
      if (v <= 0L) return ""
      val ms = if (v < 10_000_000_000L) v * 1000L else v
      val d = runCatching { Date(ms) }.getOrNull() ?: return ""
      if (!isReasonableOwnershipDate(d)) return ""
      return ownershipOut.format(d)
    }

    fun consider(value: Any?, hintedKey: Boolean) {
      when (value) {
        is String -> {
          if (!hintedKey) return
          val parsed = normalizeDateCandidate(value)
          if (parsed.isBlank()) return
          val d = parseOwnershipDate(parsed)?.takeIf(::isReasonableOwnershipDate) ?: return
          hintedDates += d
        }
        is Number -> {
          if (!hintedKey) return
          val parsed = fromEpoch(value)
          if (parsed.isBlank()) return
          val d = parseOwnershipDate(parsed)?.takeIf(::isReasonableOwnershipDate) ?: return
          hintedDates += d
        }
      }
    }

    fun walk(node: Any?) {
      when (node) {
        is JSONObject -> {
          val it = node.keys()
          while (it.hasNext()) {
            val k = it.next()
            val v = node.opt(k)
            consider(v, keyIsHint(k))
            if (v is JSONObject || v is JSONArray) walk(v)
          }
        }
        is JSONArray -> {
          for (i in 0 until node.length()) walk(node.opt(i))
        }
      }
    }

    walk(root)
    val best = hintedDates.minByOrNull { it.time } ?: return ""
    return ownershipOut.format(best)
  }

  private fun parseFlag(raw: Any?): Boolean? = when (raw) {
    is Boolean -> raw
    is Number -> raw.toInt() != 0
    is String -> when (raw.trim().lowercase()) {
      "true", "yes", "on", "active", "1" -> true
      "false", "no", "off", "inactive", "0" -> false
      else -> null
    }
    else -> null
  }

  private fun eventText(e: JSONObject) = dev.openvw.data.EventText.of(
    e.optString("type"), e.optString("event"), e.optString("name"), e.optString("code"),
    e.optDouble("actualSpeed").takeIf { !it.isNaN() }, e.optDouble("speedLimit").takeIf { !it.isNaN() }, ::prettifyEvent,
  )

  private fun prettifyEvent(raw: String): String {
    if (raw.isBlank()) return ""
    val normalized = raw
      .replace('_', ' ')
      .replace('-', ' ')
      .replace(Regex("([a-z])([A-Z])"), "$1 $2")
      .trim()
      .lowercase(Locale.ENGLISH)
    val overrides = mapOf(
      "sudden brake" to "Sudden brake",
      "sharp turn" to "Sharp turn",
      "speed limit breached" to "Speed limit breached",
      "overspeed" to "Speed limit breached",
      "low fuel" to "Low fuel",
      "battery low" to "Low battery",
      "tow alert" to "Tow alert",
      "towing" to "Tow alert",
      "puc expiry" to "PUC expiry",
      "geofence breached" to "Geo fence breached",
      "time fence breached" to "Time fence breached",
      "idle alert" to "Idle alert",
    )
    return overrides[normalized] ?: normalized.replaceFirstChar { it.uppercase() }
  }

  private suspend fun loadData() {
    // fresh bootstrap token (30-min TTL)
    val auth = Api.authenticate(session.installationID, 0)
    if (auth.optString("accessToken").isNotEmpty()) {
      session.token = auth.optString("accessToken"); session.refreshToken = auth.optString("refreshToken")
    }
    // CRM token (may override bearer; often null — bootstrap token still works)
    if (session.userId != 0) runCatching { Api.crmToken(session.userId, session.token) }.getOrNull()?.let { crm ->
      dump("savw crmToken", crm.toPlain())
      val sfdc = crm.optString("sfdcToken")
      if (sfdc.isNotEmpty() && sfdc != "null") session.token = sfdc
    }
    // profile + vehicle
    refreshProfileFromServer(force = true)
    // live telemetry now; trips/events/places follow in the background via poll()
    runCatching { loadLock.withLock { loadTelematics(full = false) } }
  }

  private suspend fun refreshProfileFromServer(force: Boolean = false, includeVehicleProfileDump: Boolean = false) {
    if (session.userId == 0 || session.token.isBlank()) return
    val now = System.currentTimeMillis()
    if (!force && now - lastProfileSyncAt < 90_000L) return
    val ui = Api.userInfo(session)
    dump("savw userInfo", ui.toPlain())
    if (!ui.optBoolean("_decryptFailed")) {
      lastUserInfoPayload = JSONObject(ui.toString())
      mapUserInfo(ui)
      lastProfileSyncAt = now
    }
    if (includeVehicleProfileDump || Store.vehicle.regNo.isBlank() || Store.connectedSince.isBlank()) {
      Api.vehicleProfileInfo(session)?.let { extra ->
        dump("savw vehicleProfile", extra.toPlain())
        lastVehicleProfilePayload = JSONObject(extra.toString())
        val reg = extractRegFromJson(extra)
        accountRegNo(reg)
        if (Store.connectedSince.isBlank()) {
          val purchased = extractOwnershipDateFromJson(extra)
          if (purchased.isNotBlank()) {
            session.connectedSince = purchased
            Store.connectedSince = purchased
          }
        }
      }
    }
  }

  private fun saveWidgets() {
    val tr = Store.trips.firstOrNull()
    val p = Store.parked
    val t = Store.telemetry
    val kmpl = Store.drive.realKmpl.takeIf { it > 0 } ?: FuelCalc.MILEAGE
    WidgetData.save(getApplication(), WidgetSnapshot(
      fuelPct = t.fuelPct,
      batteryV = t.batteryV,
      status = Store.vehicle.status,
      variant = Store.vehicle.variant,
      model = Store.vehicle.model,
      tripMin = tr?.durMin ?: 0,
      tripDate = tr?.date ?: "",
      tripStart = tr?.start ?: "",
      tripEnd = tr?.end ?: "",
      tripFrom = tr?.fromTitle ?: "",
      tripTo = tr?.toTitle ?: "",
      tripType = tr?.type ?: "Personal",
      tripKm = tr?.km ?: 0.0,
      tripScore = tr?.score ?: 0,
      parkTitle = if (Store.parkedKnown) p.title else "",
      parkSub = if (Store.parkedKnown) p.sub else "",
      parkEpoch = p.epoch,
      parkLat = p.lat,
      parkLon = p.lon,
      odometerKm = t.odometerKm ?: 0,
      rangeKm = (t.fuelPct / 100.0 * FuelCalc.TANK_L * kmpl).toInt(),
      coolantC = t.coolantC ?: 0,
      milOn = t.milOn,
      updatedAt = System.currentTimeMillis(),
      colour = CarLook.effective(Store.vehicle.colour).name, // widgets run without the app's Store
      brand = Store.brand.name,
    ))
    OpenVWWidgets.updateAll(getApplication())
  }

  private fun mapUserInfo(ui: JSONObject) {
    Store.plans = emptyList()
    val prof = ui.optJSONObject("userInfo")
    val arr = ui.optJSONArray("vehicle")
    val v = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
    if (v != null) {
      val vin = v.optString("vinNo").ifEmpty { v.optString("vin") }
      val variant = cleanText(Crypto.decOrSelf(v.optStringAny("variant", "telematics_Variant", "telematics_ModelVariant")))
        .ifEmpty { Store.brand.label }
      val model = cleanText(Crypto.decOrSelf(v.optStringAny("model", "vehicleModel", "telematics_Model", "telematics_ModelName", "telematics_ModelVariant")))
        .ifEmpty { v.optString("vehicleModel") }
        .ifEmpty { variant }
      val regNo = sequenceOf(
        cleanText(Crypto.decOrSelf(
          v.deepStringAny(
            "registrationNo", "registrationNumber", "vehicleRegNo", "vehicleRegNumber", "vehicleRegistrationNo",
            "telematics_RegistrationNumber", "regNo", "plateNo", "plateNumber", "licensePlate", "vehicleNumber",
          ),
        )),
        extractRegFromJson(v),
        extractRegFromJson(prof),
        extractRegFromJson(ui),
        cleanText(session.regNoOverride),
      ).firstOrNull { it.isNotBlank() } ?: ""
      val colour = cleanText(Crypto.decOrSelf(v.optStringAny("color", "colour", "vehicleColor", "paintColor", "exteriorColor")))
      val purchased = bestOwnershipDate(listOf(
        normalizeOwnershipDate(Crypto.decOrSelf(v.deepStringAny(
          "purchaseDate", "purchasedDate", "vehiclePurchaseDate", "dateOfPurchase", "registrationDate",
          "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
        ))),
        normalizeOwnershipDate(Crypto.decOrSelf(prof?.deepStringAny(
          "purchaseDate", "purchasedDate", "vehiclePurchaseDate", "dateOfPurchase", "registrationDate",
          "carPurchaseDate", "firstDeliveryDate", "initialRegistrationDate",
        ) ?: "")),
        extractOwnershipDateFromJson(v),
        extractOwnershipDateFromJson(prof),
        extractOwnershipDateFromJson(ui),
      ))
      if (purchased.isNotBlank()) {
        session.connectedSince = purchased
        Store.connectedSince = purchased
      } else {
        val nonPurchase = setOf(
          normalizeOwnershipDate(v.optStringAny("insuranceStartDate", "insuranceExpireDate", "puc_StartDate", "puc_ExpireDate")),
          normalizeOwnershipDate(v.optStringAny("serviceStartDate", "serviceEndDate", "standardWarrantyStartDate", "standardWarrantyEndDate")),
        ).filter { it.isNotBlank() }.toSet()
        val current = normalizeOwnershipDate(Store.connectedSince)
        if (current.isNotBlank() && current in nonPurchase) {
          session.connectedSince = ""
          Store.connectedSince = ""
        }
      }
      val odoFromProfile = v.deepIntAny(
        "odometer", "Odometer", "odometerReading", "OdometerReading",
        "vehicleOdometer", "VehicleOdometer", "totalDistance", "TotalDistance",
      )
      if (odoFromProfile > 0) {
        Store.odoSource = dev.openvw.data.OdoSource.CAR
        Store.telemetry = Store.telemetry.copy(odometerKm = odoFromProfile)
      }
      session.vin = vin
      val tele = Crypto.decOrSelf(v.optString("telematicsMobileNo"))
        .ifEmpty { session.mobile }
      session.telePhone = normalizePhoneForTelematics(tele)
      Store.vehicle = VehicleInfo(
        variant = variant,
        model = model,
        vehicleName = if (vin.isNotEmpty()) "Factory:$vin" else Store.vehicle.vehicleName,
        regNo = regNo,
        colour = colour,
        engineType = Crypto.decOrSelf(v.optStringAny("engineNo", "engineNumber")).ifEmpty { Store.vehicle.engineType },
        dongleImei = v.optStringAny("dongleImei", "dongleIMEI", "imei").ifEmpty { Store.vehicle.dongleImei },
        dongleFirmware = v.optStringAny("dongleFirmware", "firmwareVersion").ifEmpty { Store.vehicle.dongleFirmware },
        modelYear = Store.vehicle.modelYear, fuelType = Store.vehicle.fuelType,
        status = Store.vehicle.status,
      )

      fun pick(vararg keys: String): String {
        val sources = listOfNotNull(v, prof, ui)
        for (src in sources) {
          val raw = src.deepStringAny(*keys)
          val dec = Crypto.decOrSelf(raw).trim()
          if (dec.isNotEmpty()) return dec
        }
        return ""
      }
      fun pickBool(vararg keys: String): Boolean? {
        val sources = listOfNotNull(v, prof, ui)
        for (src in sources) {
          src.deepBooleanAny(*keys)?.let { return it }
        }
        return null
      }
      fun addPlan(
        target: MutableList<PlanInfo>,
        title: String,
        name: String,
        start: String,
        end: String,
        active: Boolean?,
      ) {
        val whenText = when {
          start.isNotBlank() && end.isNotBlank() -> "$start - $end"
          end.isNotBlank() -> "Valid until $end"
          start.isNotBlank() -> "From $start"
          else -> ""
        }
        val details = listOf(name, whenText).filter { it.isNotBlank() }.joinToString("\n")
        if (details.isBlank() && active == null) return
        target += PlanInfo(title = title, details = details, active = active)
      }

      val plans = mutableListOf<PlanInfo>()
      addPlan(
        plans,
        title = "Insurance",
        name = pick("insurancePlanName", "insuranceName", "policyName"),
        start = pick("insuranceStartDate", "insuranceValidFrom", "insuranceFromDate"),
        end = pick("insuranceEndDate", "insuranceExpiryDate", "insuranceValidTo", "policyEndDate"),
        active = pickBool("insuranceActive", "isInsuranceActive", "insuranceStatus"),
      )
      addPlan(
        plans,
        title = "Road Side Assistance",
        name = pick("rsaPlanName", "roadsideAssistanceName", "roadSideAssistanceName"),
        start = pick("rsaStartDate", "roadsideAssistanceStartDate", "roadSideAssistanceStartDate"),
        end = pick("rsaEndDate", "roadsideAssistanceEndDate", "roadSideAssistanceEndDate"),
        active = pickBool("rsaActive", "isRsaActive", "isRoadsideAssistanceActive"),
      )
      addPlan(
        plans,
        title = "Service & Maintenance Plan",
        name = pick("smpPlanName", "servicePlanName", "maintenancePlanName"),
        start = pick("smpStartDate", "servicePlanStartDate", "maintenancePlanStartDate"),
        end = pick("smpEndDate", "servicePlanEndDate", "maintenancePlanEndDate"),
        active = pickBool("smpActive", "isSmpActive", "isServicePlanActive"),
      )
      addPlan(
        plans,
        title = "Extended Warranty",
        name = pick("extendedWarrantyName", "ewPlanName", "warrantyPlanName"),
        start = pick("extendedWarrantyStartDate", "ewStartDate", "warrantyStartDate"),
        end = pick("extendedWarrantyEndDate", "ewEndDate", "warrantyEndDate"),
        active = pickBool("extendedWarrantyActive", "isExtendedWarrantyActive", "isWarrantyActive"),
      )
      Store.plans = plans
    }
    if (prof != null) {
      fun dec(o: JSONObject?, key: String) = cleanText(Crypto.decOrSelf(o?.optString(key)))
      // a phone-registered account leaves userInfo.name / primaryMailID empty; the car's telematics record has them
      val nm = dev.openvw.data.OwnerPick.name(
        dec(prof, "name"),
        listOf(dec(v, "telematics_FirstName"), dec(v, "telematics_LastName")).filter { it.isNotBlank() }.joinToString(" "),
        dec(v, "registeredOwner"),
        dec(v, "telematics_NickName"),
        session.ownerName,
      )
      val mail = dev.openvw.data.OwnerPick.email(
        dec(prof, "primaryMailID"), dec(v, "telematics_EmailId"), dec(v, "ownerEmailID"), dec(v, "communicationEmailID"),
        dec(prof, "secondaryMailID"), session.ownerEmail,
      )
      Log.d(TAG, "owner from profile: name=${if (nm.isBlank()) "missing" else "ok"} email=${if (mail.isBlank()) "missing" else "ok"}")
      if (nm.isNotBlank()) session.ownerName = nm
      if (mail.isNotBlank()) session.ownerEmail = mail
      val addr = resolveOwnerAddress(prof, v, ui).ifBlank { session.ownerAddress }
      setOwner(Owner(
        name = nm.ifEmpty { Repo.owner.name },
        email = mail,
        mobile = session.telePhone.ifEmpty { "+91" + session.mobile },
        address = addr,
      ))
    }
  }

  private suspend fun loadTelematics(full: Boolean) {
    // Cerence OTP login: renew the end-user token with its refresh token, once it has expired or (like the
    // official app) within a day of expiring, while the refresh token is still accepted
    val now = System.currentTimeMillis()
    val teleExp = session.teleExpiresAt.takeIf { it > 0 && session.teleToken.isNotEmpty() }
    val renewDue = session.teleToken.isEmpty() || (teleExp != null && teleExp - now < 24 * 3600_000L)
    if (renewDue && session.teleRefreshToken.isNotEmpty() && now >= refreshRetryAt) {
      val r = Api.cerenceRefresh(session.teleRefreshToken)
      val ti = r.optJSONObject("loginInfo")?.optJSONObject("tokenInfo") ?: r.optJSONObject("tokenInfo") ?: r
      val access = ti.optString("accessToken")
      val status = r.optInt("_status")
      Log.d(TAG, "cerence refresh: status=$status accessToken=${if (access.isBlank()) "missing" else "ok"}" +
        (if (teleExp != null) " oldExp=${Date(teleExp)}" else "") +
        if (access.isBlank()) " error=${r.cerenceError()} failures=${refreshFailures + 1}" else "")
      if (access.isNotBlank()) {
        saveTeleTokens(ti)
        refreshFailures = 0
      } else {
        // The server answered and refused. Cerence answers an unknown/expired refresh token with a bare
        // 500 (errorCode 1999), the same as a garbage one, so after a few refusals the login is gone for good:
        // ask for a new OTP instead of silently showing empty data forever. Network errors (-1) don't count.
        if (status in 400..599) refreshFailures++
        // don't hammer the identity server: 30 s, 1 min, 2 min … up to 30 min between attempts
        refreshRetryAt = now + (30_000L shl (refreshFailures - 1).coerceIn(0, 6)).coerceAtMost(30 * 60_000L)
        if (refreshFailures >= 3 && session.teleToken.isEmpty()) { sessionExpired(); return }
      }
    }
    // Cerence login has no savwconnect profile to name the car: read it from the inventory once per app start
    if (session.teleToken.isNotEmpty() && session.teleRefreshToken.isNotEmpty() &&
      (session.vin.isEmpty() || Store.vehicle.vehicleName == Repo.vehicle.vehicleName)) { loadUserCar(); carsLoaded = true }
    if (session.vin.isEmpty()) return
    // savwconnect login: mint the end-user token from the CMS cerenceToken
    if (session.teleToken.isEmpty() && session.cerenceToken.isEmpty() && session.userId != 0) {
      val ct = Api.cerenceToken(session.userId)
      session.cerenceToken = ct.optString("cerenceToken").ifEmpty { ct.optString("CerenceToken") }
    }
    if (session.teleToken.isEmpty() && session.cerenceToken.isNotEmpty()) {
      val phone = normalizePhoneForTelematics(session.telePhone.ifEmpty { "+91" + session.mobile })
      val tt = Api.telematicsToken(session.cerenceToken, phone, session.vin)
      val tok = tt.optJSONObject("loginInfo")?.optJSONObject("tokenInfo")?.optString("accessToken")
        ?: tt.optString("accessToken")
      if (!tok.isNullOrEmpty()) session.teleToken = tok
      else session.cerenceToken = "" // stale CMS token: re-fetch it on the next poll
      val teleAddr = resolveOwnerAddress(tt.optJSONObject("userInfo"), tt)
      Log.d(TAG, "telematics token: status=${tt.opt("_status")} userInfo keys=${tt.optJSONObject("userInfo")?.keys()?.asSequence()?.toList()} " +
        "address=${if (teleAddr.isBlank()) "missing" else "ok"}")
      if (teleAddr.isNotBlank()) {
        session.ownerAddress = teleAddr // the savwconnect profile's own address is often empty: keep this one
        setOwner(accountOwner.copy(address = teleAddr))
      }
      // "connected since" = the telematics account creation date
      tt.optJSONObject("userInfo")?.optString("createDate")?.takeIf { it.isNotEmpty() && session.connectedSince.isBlank() }?.let {
        val normalized = normalizeOwnershipDate(it)
        if (normalized.isNotBlank()) {
          session.connectedSince = normalized
          Store.connectedSince = normalized
        }
      }
    }
    if (session.teleToken.isEmpty()) return
    // savwconnect sign-in: the telematics car list (dongle IMEI / firmware, model year, fuel) once per start
    if (!carsLoaded && session.userId != 0) { carsLoaded = true; runCatching { loadUserCar() } }
    if (session.carId.isEmpty()) {
      val rc = Api.resolveCar(session.vin, session.teleToken)
      rc.optJSONArray("cars")?.optJSONObject(0)?.let {
        session.carId = it.optString("_id").ifEmpty { it.optString("id") }
      }
    }
    if (session.carId.isEmpty()) return
    val t = Api.telematics(session.carId, session.teleToken)
    if (t.optInt("_status") == 401) { session.teleToken = ""; return }
    applyCar(t, live = false)
    if (full) { runCatching { loadActivity() }; lastFull = System.currentTimeMillis() }
    if (full && widgetsDue) { widgetsDue = false; runCatching { saveWidgets() } }
  }

  /** Car JSON (REST GET /car/{id} or a pushed "carStatus") -> Store. Missing fields keep their last value. */
  private suspend fun applyCar(t: JSONObject, live: Boolean) {
    dump(if (live) "carStatus push" else "car", t.toPlain())
    val tj = t.optJSONObject("telemetry")
      ?: t.optJSONObject("carStatus")?.optJSONObject("telemetry")
      ?: t.optJSONObject("data")?.optJSONObject("telemetry")
      ?: t
    val old = Store.telemetry
    fun metricNumber(o: JSONObject): Double? {
      for (vk in arrayOf("value", "Value")) {
        if (!o.has(vk)) continue
        when (val raw = o.opt(vk)) {
          is Number -> return raw.toDouble()
          is String -> parseNumberText(raw)?.let { return it }
        }
      }
      return null
    }
    fun metricObject(name: String): JSONObject? {
      tj.optJSONObject(name)?.let { return it }
      val it = tj.keys()
      while (it.hasNext()) {
        val k = it.next()
        if (k.equals(name, ignoreCase = true)) return tj.optJSONObject(k)
      }
      return null
    }
    fun readKeyAsNumber(key: String): Double? {
      metricObject(key)?.let { metricNumber(it)?.let { v -> return v } }
      when (val raw = tj.opt(key)) {
        is Number -> return raw.toDouble()
        is String -> parseNumberText(raw)?.let { return it }
      }
      return null
    }
    fun gi(vararg keys: String): Int? {
      for (key in keys) {
        readKeyAsNumber(key)?.toInt()?.let { return it }
      }
      return null
    }
    fun gd(vararg keys: String): Double? {
      for (key in keys) {
        readKeyAsNumber(key)?.let { return it }
      }
      return null
    }
    fun findByFragment(fragments: List<String>, excludes: List<String> = emptyList(), validate: (Int) -> Boolean = { true }): Int? {
      val fs = fragments.map { it.lowercase() }
      val xs = excludes.map { it.lowercase() }
      val it = tj.keys()
      while (it.hasNext()) {
        val k = it.next()
        val lk = k.lowercase()
        if (fs.none { lk.contains(it) }) continue
        if (xs.any { lk.contains(it) }) continue
        val n = readKeyAsNumber(k)?.toInt() ?: continue
        if (validate(n)) return n
      }
      return null
    }
    fun gb(vararg keys: String): Boolean? {
      for (key in keys) {
        metricObject(key)?.let { o ->
          if (o.has("value")) return o.optBoolean("value")
          if (o.has("Value")) return o.optBoolean("Value")
        }
        when (val raw = tj.opt(key)) {
          is Boolean -> return raw
          is String -> {
            when (raw.trim().lowercase()) {
              "true" -> return true
              "false" -> return false
            }
          }
        }
      }
      return null
    }
    val rpm = gi("EngineSpeed", "RPM", "EngineRPM", "engineRpm", "engineRPM")
      ?: findByFragment(listOf("rpm", "enginespeed", "engine_speed"))
    val fuelRaw = gd(
      "FuelLevel", "fuelLevel", "Fuel", "FuelPercentage", "fuelPercentage",
      "fuelPercent", "fuelPct", "fuel_level", "fuel_percent", "fuel_pct",
      "fuelLevelPercentage", "stateOfFuel", "remainingFuel",
    ) ?: findByFragment(
      fragments = listOf("fuellevel", "fuellevel", "fuelpercent", "fuel_percentage", "fuelpct"),
      excludes = listOf("distance", "range", "trip", "avg", "average", "used", "consumption", "economy", "tank", "capacity"),
      validate = { it in 0..100 },
    )?.toDouble()
    val fuelPct = fuelRaw?.let { n ->
      val pct = if (n in 0.0..1.0) (n * 100.0) else n
      pct.toInt().coerceIn(0, 100)
    } ?: old.fuelPct
    val liveOdo = (gi(
      "Odometer", "odometer", "VehicleOdometer", "OdometerReading", "Mileage", "TotalDistance",
      "odometerKm", "totalOdometer", "totalMileage", "tripOdometer", "totalDistanceKm", "vehicleOdo", "odo",
    ) ?: findByFragment(
      fragments = listOf("odometer", "mileage"),
      excludes = listOf("fuel", "trip", "since", "dtc", "mil", "engine", "avg", "average"),
    ))?.takeIf { it > 0 } // 0 = the dongle can't read it on this car
    if (liveOdo != null) Store.odoSource = dev.openvw.data.OdoSource.CAR
    val odometer = liveOdo ?: old.odometerKm
    // Engine ON/OFF (telemetry.engine), as the official app reads it (CarStatusParser): OFF = parked, whatever the
    // telemetry still says — Speed/EngineSpeed keep their last value from the end of the drive.
    val carStatus = sequenceOf(tj, t, t.optJSONObject("carStatus"), t.optJSONObject("data"))
      .filterNotNull()
      .flatMap { o -> sequenceOf(o.optString("engine"), o.optString("status")) }
      .map { it.trim().uppercase() }
      .firstOrNull { it == "ON" || it == "OFF" }
    val engineState = tj.optStringAny("engine", "engineStatus").trim()
    val engineOn = when {
      carStatus != null -> carStatus == "ON"
      engineState.equals("on", ignoreCase = true) -> true
      engineState.equals("off", ignoreCase = true) -> false
      else -> gb("IsEngineOn", "engineOn", "ignitionOn") ?: old.engineOn
    }
    val parked = carStatus == "OFF"
    Store.telemetry = Telemetry(
      speedKmh = if (parked) null else gi("Speed") ?: old.speedKmh,
      rpm = if (parked) null else rpm ?: old.rpm,
      fuelPct = fuelPct,
      batteryV = gd("ControlModuleVoltage") ?: old.batteryV,
      coolantC = gi("CoolantTemperature") ?: old.coolantC,
      intakeC = gi("IntakeAirTemperature") ?: old.intakeC,
      engineLoadPct = gi("EngineLoad") ?: old.engineLoadPct,
      odometerKm = odometer,
      milOn = gb("IsMILOn") ?: old.milOn,
      distanceWithMil = gi("DistanceWithMIL") ?: old.distanceWithMil,
      distanceSinceDtc = gi("DistanceSinceDTCClear") ?: old.distanceSinceDtc,
      engineOn = engineOn,
    )
    val regNo = sequenceOf(
      cleanText(Crypto.decOrSelf(
        t.deepStringAny(
          "registrationNo", "registrationNumber", "vehicleRegNo", "vehicleRegNumber", "vehicleRegistrationNo",
          "telematics_RegistrationNumber", "regNo", "plateNo", "plateNumber", "licensePlate", "registration", "vehicleNumber",
        ),
      )).trim(),
      extractRegFromJson(t),
    ).firstOrNull { it.isNotBlank() } ?: ""
    if (regNo.isNotEmpty()) {
      accountRegNo(regNo)
    } else if (Store.vehicle.regNo.isBlank() && session.regNoOverride.isNotBlank()) {
      Store.vehicle = Store.vehicle.copy(regNo = session.regNoOverride)
    }
    val moving = (Store.telemetry.speedKmh ?: 0) > 0
    Store.vehicle = Store.vehicle.copy(status = when {
      carStatus == "OFF" -> "Parked"
      carStatus == "ON" -> if (moving) "Driving" else "Idling"
      moving || Store.telemetry.engineOn -> "Driving"
      else -> "Parked"
    })
    // dongle health
    t.optJSONArray("devices")?.optJSONObject(0)?.let { d ->
      Store.dongle = Dongle(d.optInt("netSignal"), d.optString("status"), d.optJSONObject("firmware")?.optString("version") ?: "")
      // devices[].id is the dongle IMEI: a fallback for accounts whose car list doesn't carry it
      if (Store.vehicle.dongleImei.isBlank()) d.optString("id").takeIf { it.length == 15 && it.all(Char::isDigit) }
        ?.let { Store.vehicle = Store.vehicle.copy(dongleImei = it) }
    }
    // pushes carry the live position: follow the car on the maps
    // ... and draw the drive as it happens (Locate map), with the direction of travel when the car sends one
    if (live) t.optJSONObject("location")?.toPlain()?.let { l ->
      Telematics.point(l)?.let { pt ->
        val raw = Telematics.num(l["timestamp"])?.toLong()?.takeIf { it > 0 } ?: t.optLong("timestamp").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val ts = if (raw > 1_000_000_000_000L) raw / 1000 else raw
        Store.parked = Store.parked.copy(lat = pt.lat, lon = pt.lon, epoch = ts, heading = Telematics.heading(l))
        Store.trail = Telematics.extendTrail(Store.trail, pt, if (trailAt > 0) ts - trailAt else 0)
        trailAt = ts
        renamePlace(pt.lat, pt.lon)
      }
    }
    Log.d(TAG, "telemetry (${if (live) "push" else "rest"}): status=${Store.vehicle.status} engine=${carStatus ?: "?"} " +
      "fuel=${Store.telemetry.fuelPct}% speed=${Store.telemetry.speedKmh} odo=${Store.telemetry.odometerKm} " +
      "location=${if (t.optJSONObject("location") != null) "yes" else "no"} " +
      "reportedAt=${tj.optLong("timestamp").takeIf { it > 0 }?.let { Date(if (it > 1_000_000_000_000L) it else it * 1000) } ?: "?"} " +
      "trail=${Store.trail.size} heading=${Store.parked.heading ?: "-"} keys=${tj.keys().asSequence().toList()}")
    tj.optLong("timestamp").takeIf { it > 0 }?.let { Store.reportedAt = if (it > 1_000_000_000_000L) it else it * 1000 }
    Store.updatedAt = System.currentTimeMillis()
    Store.live = true
  }

  /** Geocode the car's spot only after it moved ~150 m, so a drive doesn't spam the geocoder. */
  private suspend fun renamePlace(lat: Double, lon: Double) {
    val prev = lastGeo
    if (prev != null && abs(prev.first - lat) < 0.0014 && abs(prev.second - lon) < 0.0014) return
    lastGeo = lat to lon
    val (title, sub) = withContext(Dispatchers.IO) { Places.lookup(getApplication(), lat, lon) }
    Store.parked = Store.parked.copy(title = title, sub = sub)
  }

  /** Pushed "events" message (engine on/off, alarms) -> top of Updates. */
  private fun applyEvent(e: JSONObject) {
    dump("event push ${e.optString("type")}", e.toPlain())
    val (kind, title, detail) = eventText(e) ?: return
    val p = Store.parked
    val loc = e.optJSONObject("location")
    val ev = CarEvent(e.optString("id").ifEmpty { "live-${e.optLong("timestamp")}" }, kind, title,
      e.optLong("timestamp").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000),
      loc?.optDouble("latitude")?.takeIf { it != 0.0 && !it.isNaN() } ?: p.lat,
      loc?.optDouble("longitude")?.takeIf { it != 0.0 && !it.isNaN() } ?: p.lon, detail)
    Store.events = listOf(ev) + Store.events.filter { it.id != ev.id }
    notifyEvents(listOf(ev))
    if (kind == "engine") {
      val on = title == "Engine on"
      Store.telemetry = Store.telemetry.copy(engineOn = on)
      Store.vehicle = Store.vehicle.copy(status = if (on) "Driving" else "Parked")
      if (on) {
        // a new drive: start its trail where the engine came on
        Store.trail = listOfNotNull(Telematics.point(loc?.toPlain())); trailAt = if (Store.trail.isEmpty()) 0L else ev.epoch
      } else Store.parked = Store.parked.copy(heading = null)
      // a finished drive becomes a trip on the server shortly after engine off: reload trips then
      if (!on) viewModelScope.launch { delay(90_000); lastFull = 0L; poll() }
    }
  }

  /** Trips, events, alert policies and the parked spot (Cerence). */
  private suspend fun loadActivity() {
    val ctx = getApplication<Application>()
    val now = System.currentTimeMillis() / 1000
    val weekAgo = now - 7 * 86400

    // ---- trips ----
    Api.trips(session.carId, session.teleToken).optJSONArray("trips")?.takeIf { it.length() > 0 }?.let { arr ->
      dump("trip", arr.optJSONObject(0)?.toPlain())
      dev.openvw.data.Diag.payload("trips (newest 5 of ${arr.length()})", (0 until minOf(5, arr.length())).map { jsonPlain(arr.opt(it)) })
      val alarmKeys = sortedSetOf<String>(); val tagSet = sortedSetOf<String>()
      for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { tr ->
        tr.optJSONObject("eventCount")?.keys()?.forEach { alarmKeys += it }
        tagSet += Telematics.tags(jsonPlain(tr.opt("tags")))
      }
      Log.d(TAG, "trips: ${arr.length()} trip(s), eventCount keys=$alarmKeys tags=$tagSet")
      var count = 0; var scoreSum = 0; var maxSp = 0; var idle = 0; var brakes = 0; var sumKm = 0.0; var sumFuelL = 0.0
      var totKm = 0.0; var totMin = 0; var wTrips = 0; var wKm = 0.0; var wMin = 0
      val list = ArrayList<Trip>()
      for (i in 0 until arr.length()) {
        val tr = arr.optJSONObject(i) ?: continue
        count++
        scoreSum += tr.optInt("driverScore")
        maxSp = maxOf(maxSp, tr.optInt("maxSpeed"))
        idle += tr.optInt("idleTime")
        brakes += tr.optJSONObject("eventCount")?.optInt("sudden brake") ?: 0
        val odoKm = (tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0) - (tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0)
        val sf = tr.optJSONObject("startFuelLevel")?.optDouble("value") ?: 0.0
        val ef = tr.optJSONObject("endFuelLevel")?.optDouble("value") ?: 0.0
        val fuelL = (sf - ef) / 100.0 * FuelCalc.TANK_L
        if (odoKm > 0 && fuelL > 0) { sumKm += odoKm; sumFuelL += fuelL }

        val st = tr.optLong("startTime"); val et = tr.optLong("endTime")
        val km = tr.optDouble("distance", 0.0).let { if (it > 0) it / 1000.0 else odoKm }
        val min = (((et - st) + 30) / 60).toInt().coerceAtLeast(0)
        totKm += km; totMin += min
        if (st >= weekAgo) { wTrips++; wKm += km; wMin += min }

        // geocode the most recent trips; older ones use the cache or raw coordinates
        list += toTrip(tr, geocode = list.size < 10)
      }
      list.sortByDescending { it.epoch }
      Store.trips = list
      // Some cars send no odometer in their live data, but each trip carries the reading at its end: show the newest
      // one, marked as such, until (if ever) a live reading arrives.
      if (Store.odoSource != dev.openvw.data.OdoSource.CAR)
        list.firstOrNull { it.endOdo > 0 }?.let { last ->
          Store.telemetry = Store.telemetry.copy(odometerKm = last.endOdo.toInt()); Store.odoSource = dev.openvw.data.OdoSource.LAST_TRIP
          Log.d(TAG, "odometer: none in live data, using the last trip's end reading")
        }
      // Others (seen on a 2026 VW Virtus) send none anywhere: the owner's reading plus the trips since
      applyOdometerReading()
      val real = if (sumFuelL > 0) sumKm / sumFuelL else 0.0
      Store.drive = DriveStats(count, if (count > 0) scoreSum / count else 0, maxSp, idle, brakes, real, totKm, totMin, wTrips, wKm, wMin)
    }

    // ---- events (engine on/off, alarms) + parked spot ----
    Api.events(session.carId, session.teleToken).optJSONArray("events")?.let { arr ->
      val list = ArrayList<CarEvent>()
      for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        dump("event ${e.optString("type")}", e.toPlain())
        val loc = e.optJSONObject("location")
        val (kind, title, detail) = eventText(e) ?: continue
        list += CarEvent(e.optString("id"), kind, title, e.optLong("timestamp"), loc?.optDouble("latitude") ?: 0.0, loc?.optDouble("longitude") ?: 0.0, detail)
      }
      list.sortByDescending { it.epoch }
      Store.events = list
      // name the places of the latest events (most hit the cache from trip endpoints)
      withContext(Dispatchers.IO) { list.take(15).forEach { Places.lookup(ctx, it.lat, it.lon) } }
      Store.events = list.toList()
      notifyEvents(list) // anything the live feed missed (or everything, when only the 15-minute check runs)
    }
    // the telemetry endpoint has no position; the newest event (usually "engine off") marks where the car is
    Log.d(TAG, "activity: trips=${Store.trips.size} events=${Store.events.size} withLocation=${Store.events.count { it.lat != 0.0 }} " +
      "newest=${Store.events.firstOrNull()?.let { "${it.title}@${Date(it.epoch * 1000)}" } ?: "none"}")
    Store.events.firstOrNull { it.lat != 0.0 }?.let { ev ->
      val (title, sub) = withContext(Dispatchers.IO) { Places.lookup(ctx, ev.lat, ev.lon) }
      Store.parked = Parked(ev.lat, ev.lon, ev.epoch, title, sub)
      withContext(Dispatchers.IO) { runCatching { MapSnapshot.update(ctx, ev.lat, ev.lon) } }
    }

    // ---- alert policies (geofences + enabled triggers) ----
    runCatching {
      val p = Api.policies(session.carId, session.teleToken)
      dump("policies", p.toPlain())
      val fences = p.optJSONArray("fences")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name")?.ifEmpty { null } ?: "Geofence ${it + 1}" } } ?: emptyList()
      val shapes = p.optJSONArray("fences")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Telematics.fence(it.toPlain(), i) } } } ?: emptyList()
      Log.d(TAG, "policies: ${fences.size} geofence(s), ${shapes.count { it.drawable }} with a shape we can draw")
      val states = linkedMapOf<String, Boolean>()
      p.optJSONObject("firebaseNotificationTrigger")?.let { o ->
        val it = o.keys()
        while (it.hasNext()) {
          val k = it.next()
          parseFlag(o.opt(k))?.let { on -> states[k] = on }
        }
      }
      val trig = states.filterValues { it }.keys.sortedBy { it.lowercase(Locale.ENGLISH) }
      val sortedStates = states.entries.sortedBy { it.key.lowercase(Locale.ENGLISH) }.associate { it.toPair() }
      Store.alertSetup = AlertSetup(fences, trig, sortedStates, shapes.filter { it.drawable })
    }

    runCatching { recomputePending() }
    // no event with a position yet (new account, or every recent event lacked one): ask where the car is now
    if (!Store.parkedKnown) runCatching {
      val l = Api.carLocation(session.carId, session.teleToken).optJSONObject("location")?.toPlain()
      Telematics.point(l)?.let { pt ->
        val raw = Telematics.num(l?.get("timestamp"))?.toLong()?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val ts = minOf(if (raw > 1_000_000_000_000L) raw / 1000 else raw, System.currentTimeMillis() / 1000)
        val (title, sub) = withContext(Dispatchers.IO) { Places.lookup(ctx, pt.lat, pt.lon) }
        Store.parked = Parked(pt.lat, pt.lon, ts, title, sub)
        Log.d(TAG, "parked spot from /location (no event had one)")
      }
    }
  }

  /** Trip details: fetch the trip's real GPS path (snapped to the road) and its events, once per trip. */
  fun loadRoute(t: Trip) {
    if (t.pending || t.id.isBlank() || Store.routes.containsKey(t.id) || Store.routeLoading == t.id) return
    if (demo) { Store.routes = Store.routes + (t.id to dev.openvw.data.Demo.route(t)); return }
    viewModelScope.launch {
      Store.routeLoading = t.id
      try {
        var r = Api.tripDetail(t.id, session.teleToken)
        if (r.optInt("_status") == 401) { // token expired: re-mint once
          session.teleToken = ""; loadLock.withLock { loadTelematics(full = false) }
          r = Api.tripDetail(t.id, session.teleToken)
        }
        dump("trip detail", r.toPlain())
        if (r.optInt("_status") !in 200..299) { Log.d(TAG, "trip detail: status=${r.opt("_status")}"); return@launch } // retried next open
        val path = r.optJSONArray("itinerary")?.toPlain()?.let { Telematics.path(it) } ?: emptyList()
        val a = r.optJSONArray("events")
        val events = (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let(::toEvent) }.sortedBy { it.epoch }
        Log.d(TAG, "trip detail: ${path.size} points, ${events.size} events (${events.count { it.kind == "alarm" }} alerts)")
        Store.routes = Store.routes + (t.id to TripRoute(path, events))
      } finally {
        if (Store.routeLoading == t.id) Store.routeLoading = null
      }
    }
  }

  private fun toEvent(e: JSONObject): CarEvent? {
    val (kind, title, detail) = eventText(e) ?: return null
    val p = Telematics.point(e.optJSONObject("location")?.toPlain())
    return CarEvent(e.optString("id"), kind, title, e.optLong("timestamp"), p?.lat ?: 0.0, p?.lon ?: 0.0, detail)
  }

  /** A drive that has ended (latest engine-off) but isn't in trip history yet — built from events. */
  private suspend fun recomputePending() {
    val ctx = getApplication<Application>()
    val off = Store.events.filter { it.kind == "engine" && it.title == "Engine off" && it.lat != 0.0 }.maxByOrNull { it.epoch }
    // compare with when the newest trip ENDED (its engine-off), not when it started — a single
    // trip can also span several engine on/off pairs (short stops), so allow a little slack
    val newestEnd = Store.trips.maxOfOrNull { maxOf(it.endEpoch, it.epoch) } ?: 0L
    if (off == null || off.epoch <= newestEnd + 120) { Store.pendingDrive = null; return }
    val on = Store.events.filter { it.kind == "engine" && it.title == "Engine on" && it.epoch <= off.epoch && it.lat != 0.0 }.maxByOrNull { it.epoch }
    val (tT, tS) = withContext(Dispatchers.IO) { Places.lookup(ctx, off.lat, off.lon) }
    val (fT, fS) = on?.let { withContext(Dispatchers.IO) { Places.lookup(ctx, it.lat, it.lon) } } ?: ("" to "")
    val startE = on?.epoch ?: off.epoch
    Store.pendingDrive = Trip(
      id = "pending-${off.epoch}", start = clock(startE), end = clock(off.epoch),
      fromTitle = fT, fromSub = fS, toTitle = tT, toSub = tS,
      durMin = (((off.epoch - startE) + 30) / 60).toInt().coerceAtLeast(0),
      type = "Personal", date = day(startE), epoch = startE, pending = true,
    )
  }

  /** One Cerence trip JSON -> Trip. [geocode] = look up place names; otherwise cache or raw coordinates. */
  private suspend fun toTrip(tr: JSONObject, geocode: Boolean): Trip {
    val ctx = getApplication<Application>()
    val odoKm = (tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0) - (tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0)
    val sf = tr.optJSONObject("startFuelLevel")?.optDouble("value") ?: 0.0
    val ef = tr.optJSONObject("endFuelLevel")?.optDouble("value") ?: 0.0
    val st = tr.optLong("startTime"); val et = tr.optLong("endTime")
    val km = tr.optDouble("distance", 0.0).let { if (it > 0) it / 1000.0 else odoKm }
    val min = (((et - st) + 30) / 60).toInt().coerceAtLeast(0)
    val sl = tr.optJSONObject("startLocation"); val el = tr.optJSONObject("endLocation")
    suspend fun place(l: JSONObject?): Pair<String, String> {
      if (l == null) return "Unknown" to ""
      val lat = l.optDouble("latitude"); val lon = l.optDouble("longitude")
      return if (geocode) withContext(Dispatchers.IO) { Places.lookup(ctx, lat, lon) }
      else Places.cached(ctx, lat, lon) ?: (Places.coords(lat, lon) to "")
    }
    val (fT, fS) = place(sl); val (tT, tS) = place(el)
    val tags = Telematics.tags(jsonPlain(tr.opt("tags")))
    return Trip(
      id = tr.optString("id"), start = clock(st), end = clock(et),
      fromTitle = fT, fromSub = fS, toTitle = tT, toSub = tS,
      durMin = min, type = tags.firstOrNull() ?: "Personal", tags = tags,
      // the server leaves eventCount out of trips that had no alarms (seen on a real Slavia), so absent = none
      alarms = Telematics.alarms(tr.optJSONObject("eventCount")?.toPlain() ?: emptyMap()),
      date = day(st), km = km, score = tr.optInt("driverScore"), epoch = st, endEpoch = et,
      drivingMin = tr.optInt("drivingTime"), idleMin = tr.optInt("idleTime"), haltMin = tr.optInt("haltTime"),
      maxSpeed = tr.optInt("maxSpeed"), avgSpeed = tr.optInt("speed"),
      startOdo = tr.optJSONObject("startOdometer")?.optDouble("value") ?: 0.0,
      endOdo = tr.optJSONObject("endOdometer")?.optDouble("value") ?: 0.0,
      startFuelPct = sf.toInt(), endFuelPct = ef.toInt(), tankL = FuelCalc.TANK_L,
      brakes = tr.optJSONObject("eventCount")?.optInt("sudden brake") ?: 0,
      startLat = sl?.optDouble("latitude") ?: 0.0, startLon = sl?.optDouble("longitude") ?: 0.0,
      endLat = el?.optDouble("latitude") ?: 0.0, endLon = el?.optDouble("longitude") ?: 0.0,
    )
  }

  /**
   * Trips between two picked days. [startMs]/[endMs] are the date picker's values (UTC midnight of
   * each day); the whole local days are searched. Cerence allows max 30 days per query, so longer
   * ranges are fetched window by window and merged.
   */
  fun searchTrips(startMs: Long, endMs: Long) {
    val range = startMs to endMs
    searchJob?.cancel()
    Store.tripRange = range; Store.tripResults = emptyList(); Store.tripSearchError = null; Store.tripSearching = true
    if (demo) {
      Store.tripResults = dev.openvw.data.Demo.tripsBetween(localDayStart(startMs) * 1000, (localDayStart(endMs) + 86_399) * 1000)
      Store.tripSearching = false
      return
    }
    searchJob = viewModelScope.launch {
      try {
        if (session.carId.isEmpty() || session.teleToken.isEmpty()) loadLock.withLock { loadTelematics(full = false) }
        if (session.carId.isEmpty() || session.teleToken.isEmpty()) throw Exception("Couldn't reach your car right now")
        val from = localDayStart(startMs); val to = localDayStart(endMs) + 86_399
        val found = LinkedHashMap<String, JSONObject>()
        var a = from
        while (a <= to) {
          val b = minOf(a + MAX_WINDOW - 1, to)
          var r = Api.trips(session.carId, session.teleToken, a, b)
          if (r.optInt("_status") == 401) { // token expired: re-mint once
            session.teleToken = ""; loadLock.withLock { loadTelematics(full = false) }
            r = Api.trips(session.carId, session.teleToken, a, b)
          }
          val arr = r.optJSONArray("trips")
          if (arr == null && r.has("message")) throw Exception(r.optString("message"))
          if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { found[it.optString("id").ifEmpty { "t${it.optLong("startTime")}" }] = it }
          a = b + 1
        }
        val sorted = found.values.filter { it.optLong("startTime") in from..to }.sortedByDescending { it.optLong("startTime") }
        // show right away with cached names, then fill in real place names
        Store.tripResults = withReading(sorted.map { toTrip(it, geocode = false) })
        Store.tripSearching = false
        if (sorted.isNotEmpty()) Store.tripResults = withReading(sorted.mapIndexed { i, j -> toTrip(j, geocode = i < 40) })
      } catch (e: kotlinx.coroutines.CancellationException) { throw e
      } catch (e: Exception) {
        Store.tripSearchError = e.message ?: "Couldn't load trips"
      } finally {
        if (Store.tripRange == range) Store.tripSearching = false
      }
    }
  }

  fun clearTripSearch() {
    searchJob?.cancel(); searchJob = null
    Store.tripRange = null; Store.tripResults = emptyList(); Store.tripSearching = false; Store.tripSearchError = null
  }

  /** Date-picker value (UTC midnight of a calendar day) -> that day's local midnight, epoch seconds. */
  private fun localDayStart(utcMs: Long): Long {
    val u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    val l = java.util.Calendar.getInstance().apply {
      clear(); set(u.get(java.util.Calendar.YEAR), u.get(java.util.Calendar.MONTH), u.get(java.util.Calendar.DAY_OF_MONTH))
    }
    return l.timeInMillis / 1000
  }

  private fun clock(epoch: Long) = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(epoch * 1000))
  private fun day(epoch: Long) = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(epoch * 1000))

  companion object {
    private const val MAX_WINDOW = 2_592_000L // Cerence: "The max query time range is 2592000" (30 days)
    const val HOLDER_PHONE = "phone"
    const val HOLDER_CAR = "android-auto"
    const val HOLDER_ALERTS = "instant-alerts"
  }
}
