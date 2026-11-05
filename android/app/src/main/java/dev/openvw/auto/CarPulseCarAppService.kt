package dev.openvw.auto

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.openvw.AppGraph
import dev.openvw.ui.AppViewModel

/**
 * Android Auto entry point. The car screen shares the app's single [AppViewModel] (see [AppGraph]), so it
 * reuses the phone's sign-in and the same live SignalR feed. The feed is held open while the car screen is
 * visible — even with the phone locked in a pocket, since Android Auto keeps this process in the foreground
 * while it's bound — and is released a minute after the screen goes away (a quick hop to Maps keeps it).
 */
class CarPulseCarAppService : CarAppService() {
  /**
   * This exported service hands out live location and telemetry, so release builds only talk to the real
   * Android Auto hosts (Google's signed allowlist). Debug builds accept any host, e.g. the Desktop Head Unit.
   */
  override fun createHostValidator(): HostValidator =
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    else HostValidator.Builder(applicationContext).addAllowedHosts(dev.openvw.R.array.car_hosts_allowlist).build()

  override fun onCreateSession(): Session = CarPulseCarSession()
}

private class CarPulseCarSession : Session(), DefaultLifecycleObserver {
  private var hu: HeadUnit? = null

  init { lifecycle.addObserver(this) }

  private val vm get() = AppGraph.vm(carContext.applicationContext as Application)

  private fun headUnit() = hu ?: HeadUnit(carContext).also { hu = it }

  override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext, headUnit())

  override fun onStart(owner: LifecycleOwner) {
    vm.acquireLive(AppViewModel.HOLDER_CAR)
    headUnit().start()
  }

  override fun onStop(owner: LifecycleOwner) {
    hu?.stop()
    vm.releaseLive(AppViewModel.HOLDER_CAR, graceMs = 60_000L)
  }

  override fun onDestroy(owner: LifecycleOwner) {
    hu?.stop()
    vm.releaseLive(AppViewModel.HOLDER_CAR)
  }
}
