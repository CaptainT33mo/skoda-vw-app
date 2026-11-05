package dev.openvw.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { HOME, TRIPS, LOCATE, ALERTS, VEHICLE, TRIP_DETAIL }

enum class VehicleTab { VEHICLE, OWNER, SETTINGS }

/** A screen to open from outside the app (a widget tap); the main UI consumes it once it's showing. */
object NavRequest {
  const val EXTRA = "dev.openvw.OPEN_SCREEN"
  var screen by mutableStateOf<Screen?>(null)

  fun from(intent: android.content.Intent?) {
    intent?.getStringExtra(EXTRA)?.let { name -> screen = Screen.entries.firstOrNull { it.name == name } }
  }
}
