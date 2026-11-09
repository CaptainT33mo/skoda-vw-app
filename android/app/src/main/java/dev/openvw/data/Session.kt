package dev.openvw.data

import android.content.Context
import java.util.UUID

/** Persistent session: tokens + ids, survives app restarts. */
class Session(context: Context) {
  private val sp = context.getSharedPreferences("openvw", Context.MODE_PRIVATE)

  private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
  private fun i(k: String, d: Int = 0) = sp.getInt(k, d)
  private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
  private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()

  var mobile: String get() = s("mobile"); set(v) = put("mobile", v)
  var deviceID: String
    get() {
      var d = s("deviceID")
      if (d.isEmpty()) { d = UUID.randomUUID().toString(); put("deviceID", d) }
      return d
    }
    set(v) = put("deviceID", v)
  var installationID: Int get() = i("installationID"); set(v) = put("installationID", v)
  var token: String get() = s("token"); set(v) = put("token", v)
  var refreshToken: String get() = s("refreshToken"); set(v) = put("refreshToken", v)
  var signStamp: Long get() = sp.getLong("signStamp", 0); set(v) = sp.edit().putLong("signStamp", v).apply()
  var loginId: Int get() = i("loginId"); set(v) = put("loginId", v)
  var userId: Int get() = i("userId"); set(v) = put("userId", v)
  var crmToken: String get() = s("crmToken"); set(v) = put("crmToken", v)
  var vin: String get() = s("vin"); set(v) = put("vin", v)
  var carId: String get() = s("carId"); set(v) = put("carId", v)
  var cerenceToken: String get() = s("cerenceToken"); set(v) = put("cerenceToken", v)
  var teleToken: String get() = s("teleToken"); set(v) = put("teleToken", v)
  // Cerence end-user login: OTP session between send and verify, and the refresh token for teleToken
  var otpSessionId: String get() = s("otpSessionId"); set(v) = put("otpSessionId", v)
  var teleRefreshToken: String get() = s("teleRefreshToken"); set(v) = put("teleRefreshToken", v)
  var teleExpiresAt: Long get() = sp.getLong("teleExpiresAt", 0); set(v) = sp.edit().putLong("teleExpiresAt", v).apply() // ms; 0 = unknown
  // owner from the Cerence login (no profile endpoint to re-read it later)
  var ownerName: String get() = s("ownerName"); set(v) = put("ownerName", v)
  var ownerEmail: String get() = s("ownerEmail"); set(v) = put("ownerEmail", v)
  var ownerAddress: String get() = s("ownerAddress"); set(v) = put("ownerAddress", v) // as the account reports it
  // owner details typed in on this phone (no server API to change them); "" = use the account's
  var nameOverride: String get() = s("nameOverride"); set(v) = put("nameOverride", v)
  var emailOverride: String get() = s("emailOverride"); set(v) = put("emailOverride", v)
  var addressOverride: String get() = s("addressOverride"); set(v) = put("addressOverride", v)
  var telePhone: String get() = s("telePhone"); set(v) = put("telePhone", v)
  var connectedSince: String get() = s("connectedSince", ""); set(v) = put("connectedSince", v)
  // per-car settings, keyed by the selected car's VIN (an account can have several cars)
  private fun carKey(k: String): String {
    val vin = s("vin"); if (vin.isBlank()) return k
    val key = "$k@$vin"
    // values saved before per-car keys existed belong to the first car that reads them
    if (!sp.contains(key) && sp.contains(k)) sp.edit().apply {
      when (val old = sp.all[k]) { is Boolean -> putBoolean(key, old); is String -> putString(key, old) }
      remove(k)
    }.apply()
    return key
  }
  var regNoOverride: String get() = s(carKey("regNoOverride"), ""); set(v) = put(carKey("regNoOverride"), v)
  var regNoManual: Boolean get() = sp.getBoolean(carKey("regNoManual"), false); set(v) = sp.edit().putBoolean(carKey("regNoManual"), v).apply() // owner typed it: servers don't overwrite
  /** The account's car list (JSON array), so the switcher shows before the server answers. */
  var cars: String get() = s("cars"); set(v) = put("cars", v)
  var verified: Boolean get() = sp.getBoolean("verified", false); set(v) = sp.edit().putBoolean("verified", v).apply()
  var paintPick: String get() = s(carKey("paintPick")); set(v) = put(carKey("paintPick"), v)
  var demo: Boolean get() = sp.getBoolean("demo", false); set(v) = sp.edit().putBoolean("demo", v).apply() // showing the sample car
  var brand: String get() = s("brand"); set(v) = put("brand", v) // Brand name; "" = not chosen yet
  var fuelPrice: Float get() = sp.getFloat("fuelPrice", 0f); set(v) = sp.edit().putFloat("fuelPrice", v).apply()
  /** "" = follow the system theme (default), "dark" / "light" = the owner's override. */
  var themeMode: String get() = s("themeMode"); set(v) = put("themeMode", v)
  var shakeToReport: Boolean get() = sp.getBoolean("shakeToReport", true); set(v) = sp.edit().putBoolean("shakeToReport", v).apply() // shake the phone: "Report a problem?"
  var privacy: Boolean get() = sp.getBoolean("privacy", false); set(v) = sp.edit().putBoolean("privacy", v).apply()

  // Not gated on loginId: the Cerence login has none.
  val loggedIn: Boolean get() = verified && (teleRefreshToken.isNotEmpty() || installationID != 0)

  fun clearAuth() {
    verified = false; token = ""; crmToken = ""; teleToken = ""; cerenceToken = ""; teleRefreshToken = ""; teleExpiresAt = 0; otpSessionId = ""; ownerName = ""; ownerEmail = ""; ownerAddress = ""
    nameOverride = ""; emailOverride = ""; addressOverride = ""
    cars = ""
    loginId = 0; userId = 0; signStamp = 0 // savwconnect sign-in ids
  }
}
