package dev.openvw.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import dev.openvw.theme.Bg
import dev.openvw.theme.Card
import dev.openvw.theme.Ink
import dev.openvw.theme.Line
import dev.openvw.theme.Muted

@Composable
fun OpenVWApp(vm: AppViewModel = dev.openvw.AppGraph.vm(LocalContext.current.applicationContext as android.app.Application)) {
  when (vm.phase) {
    Phase.LOGIN, Phase.OTP -> Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.widthIn(max = 520.dp)) { LoginScreen(vm) }
    }
    Phase.BOOTING, Phase.LOADING -> LoadingScreen()
    Phase.READY -> MainApp(vm)
  }
}

@Composable
private fun LoadingScreen() {
  Column(Modifier.fillMaxSize().background(Bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
    // the app icon itself (same tile as the launcher icon), so the splash hands over without a jump
    Box(
      Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF121214))
        .border(1.dp, dev.openvw.theme.Line, RoundedCornerShape(24.dp)),
      contentAlignment = Alignment.Center,
    ) {
      androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(dev.openvw.R.drawable.ic_carpulse_mark), contentDescription = "CarPulse",
        modifier = Modifier.size(52.dp),
      )
    }
    Spacer(Modifier.height(20.dp))
    CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
    Spacer(Modifier.height(14.dp))
    Text("Connecting to your ${dev.openvw.data.Store.brand.label}…", style = TextStyle(color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
  }
}

@Composable
private fun MainApp(vm: AppViewModel) {
  // back stack of (screen, My-car tab); system back pops it, and only exits the app from Home
  val stack = remember { mutableStateListOf(Screen.HOME to VehicleTab.VEHICLE) }
  val (screen, vehTab) = stack.last()
  var forward by remember { mutableStateOf(true) }
  var playKey by remember { mutableIntStateOf(0) }

  // while the app is in the foreground: one full refresh (+ widgets), then live pushes from the car hub
  val owner = LocalLifecycleOwner.current
  LaunchedEffect(owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      vm.onOpened()
      try { awaitCancellation() } finally { vm.onBackground() }
    }
  }

  fun open(s: Screen, tab: VehicleTab = VehicleTab.VEHICLE) {
    if (stack.last() == s to tab) return
    forward = true
    if (s == Screen.HOME) {
      playKey++
      stack.clear(); stack.add(Screen.HOME to VehicleTab.VEHICLE)
      return
    }
    stack.removeAll { it.first == s } // revisiting a screen moves it to the top instead of duplicating it
    stack.add(s to tab)
  }

  fun go(s: Screen) = open(s)

  // a widget asked for a screen: go there (after App lock, since this UI only exists once unlocked)
  val requested = NavRequest.screen
  LaunchedEffect(requested) { requested?.let { NavRequest.screen = null; open(it) } }

  fun back() {
    if (stack.size <= 1) return
    forward = false
    stack.removeAt(stack.lastIndex)
    if (stack.last().first == Screen.HOME) playKey++
  }

  fun openTrip(t: dev.openvw.data.Trip) { dev.openvw.data.Store.detailTrip = t; dev.openvw.data.Store.detailFocus = null; open(Screen.TRIP_DETAIL) }

  BackHandler(enabled = stack.size > 1) { back() }

  // wide windows (foldable inner screen, tablets, landscape): side rail + width-limited content
  BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
    val wide = maxWidth >= 600.dp
    // Home goes two-column only when each column still gets phone-card width (~380dp)
    val twoCol = wide && maxWidth - 89.dp >= 760.dp
    val content: @Composable (Modifier) -> Unit = { mod ->
      Column(mod.statusBarsPadding()) {
      if (vm.demo) DemoBar { vm.exitDemo() }
      Box(Modifier.weight(1f)) {
        AnimatedContent(
          targetState = screen,
          transitionSpec = {
            val dir = if (forward) 1 else -1
            (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { dir * it / 16 })
              .togetherWith(fadeOut(tween(140)))
          },
          label = "screen",
        ) { s ->
          // pull down to reload everything from the car (every main tab; the trip detail page is a fixed snapshot)
          PullToRefreshBox(
            isRefreshing = vm.refreshing && s != Screen.TRIP_DETAIL,
            onRefresh = { if (s != Screen.TRIP_DETAIL) vm.pullRefresh() },
            modifier = Modifier.fillMaxSize(),
          ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
              // phones: full width; wide: Home two-column up to 1100dp, otherwise a 640–720dp centred column
              Column(Modifier.widthIn(max = if (!wide) Dp.Infinity else if (s == Screen.HOME && twoCol) 1100.dp else if (s == Screen.HOME) 640.dp else 720.dp)) {
                when (s) {
                  Screen.HOME -> HomeScreen(
                    onGo = ::go,
                    onOwner = { open(Screen.VEHICLE, VehicleTab.OWNER) },
                    playKey = playKey,
                    onShown = { vm.refreshTelematics() },
                    wide = twoCol,
                    onOpenTrip = ::openTrip,
                    onSelectCar = { vm.selectCar(it) },
                  )
                  Screen.TRIPS -> TripsScreen(
                    onBack = ::back, onOpenTrip = ::openTrip,
                    onSearch = { a, b -> vm.searchTrips(a, b) },
                    onClearSearch = { vm.clearTripSearch() },
                  )
                  Screen.LOCATE -> LocateScreen(onBack = ::back)
                  Screen.ALERTS -> AlertsScreen(onBack = ::back, onOpenTrip = { t, e -> openTrip(t); dev.openvw.data.Store.detailFocus = e })
                  Screen.VEHICLE -> VehicleScreen(
                    vehTab, onBack = ::back,
                    privacy = dev.openvw.data.Store.privacy,
                    onPrivacy = { vm.setPrivacy(it) },
                    onLogout = { vm.logout() },
                    onOwner = { name, email, address -> vm.setOwnerDetails(name, email, address) },
                    onPaint = { vm.setPaint(it) },
                    onPrice = { vm.setFuelPrice(it) },
                    onDark = { vm.setDark(it) },
                    onAlerts = { vm.setAlertsOn(it) },
                    onAlertType = { t, on -> vm.setAlertType(t, on) },
                    onSystemTheme = { follow -> vm.setThemeMode(if (follow) "" else if (dev.openvw.data.Store.dark) "dark" else "light") },
                  )
                  Screen.TRIP_DETAIL -> dev.openvw.data.Store.detailTrip.let { t ->
                    TripDetailScreen(
                      t, route = t?.let { dev.openvw.data.Store.routes[it.id] },
                      loadingRoute = t != null && dev.openvw.data.Store.routeLoading == t.id,
                      focus = dev.openvw.data.Store.detailFocus,
                      onLoadRoute = { vm.loadRoute(it) }, onBack = ::back,
                    )
                  }
                }
              }
            }
          }
        }
      }
      }
    }
    if (wide) {
      Row(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxHeight().background(Card).statusBarsPadding().navigationBarsPadding()) {
          NavRail(screen) { go(it) }
          Box(Modifier.fillMaxHeight().width(1.dp).background(Line))
        }
        content(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding())
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        content(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(Card).navigationBarsPadding()) {
          Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
          BottomNav(screen) { go(it) }
        }
      }
    }
  }
}

/** Shown on every screen in demo mode, so a sample car is never mistaken for a real one. */
@Composable
private fun DemoBar(onExit: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().background(dev.openvw.theme.Accent).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Demo · sample car, not real data", modifier = Modifier.weight(1f),
      style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    Text("Exit", modifier = Modifier.clip(RoundedCornerShape(99.dp)).clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 4.dp),
      style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
  }
}
