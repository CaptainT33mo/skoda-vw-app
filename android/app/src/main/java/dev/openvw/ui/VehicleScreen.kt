package dev.openvw.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import dev.openvw.data.Mask
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openvw.R
import dev.openvw.data.CarLook
import dev.openvw.data.PlanInfo
import dev.openvw.data.Repo
import dev.openvw.data.Store
import dev.openvw.theme.Border
import dev.openvw.theme.Card
import dev.openvw.theme.Ink
import dev.openvw.theme.Line
import dev.openvw.theme.LineSoft
import dev.openvw.theme.Muted
import dev.openvw.theme.OnDarkMuted
import dev.openvw.theme.Green
import dev.openvw.theme.GreenSoftBg

@Composable
fun VehicleScreen(
  initialTab: VehicleTab,
  onBack: () -> Unit,
  privacy: Boolean = false,
  onPrivacy: (Boolean) -> Unit = {},
  onLogout: () -> Unit = {},
  onOwner: (name: String, email: String, address: String) -> Unit = { _, _, _ -> },
  onPaint: (String) -> Unit = {},
  onPrice: (Double) -> Unit = {},
  onDark: (Boolean) -> Unit = {},
  onSystemTheme: (Boolean) -> Unit = {},
  onAlerts: (Boolean) -> Unit = {},
  onAlertType: (dev.openvw.data.Alerts.Type, Boolean) -> Unit = { _, _ -> },
) {
  var tab by remember { mutableStateOf(initialTab) }
  var ownerEditor by remember { mutableStateOf(false) }
  var nameDraft by remember { mutableStateOf("") }
  var emailDraft by remember { mutableStateOf("") }
  var addressDraft by remember { mutableStateOf("") }
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    BackBar(onBack, showBack = false, trailing = {
      // quick light/dark toggle (same setting as Owner info → Dark mode)
      CircleButton(if (Store.dark) Ic.sun else Ic.moon) { onDark(!Store.dark) }
    })
    Text(when (tab) { VehicleTab.OWNER -> "Owner"; VehicleTab.SETTINGS -> "Settings"; else -> "Your car" }, style = H1, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    Row(Modifier.padding(start = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Seg("Vehicle info", tab == VehicleTab.VEHICLE) { tab = VehicleTab.VEHICLE }
      Seg("Owner info", tab == VehicleTab.OWNER) { tab = VehicleTab.OWNER }
      Seg("Settings", tab == VehicleTab.SETTINGS) { tab = VehicleTab.SETTINGS }
    }

    if (tab == VehicleTab.VEHICLE) {
      Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().height(200.dp).card(28.dp)) {
        CarPicture(
          Store.vehicle.colour, "${Store.vehicle.variant} ${Store.vehicle.model}",
          Modifier.align(Alignment.Center).fillMaxWidth(0.88f).fillMaxHeight(0.86f),
        )
      }
      // car colour: follow the account, or pick manually
      ColourPicker(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), onPaint)

      // name + reg rows
      Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).card(24.dp).padding(horizontal = 18.dp)) {
        InfoRow("Vehicle name", Mask.vehicleName(Store.vehicle.vehicleName), mono = true)
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        val cleanReg = Store.vehicle.regNo.trim().takeUnless {
          it.isBlank() || it.equals("null", ignoreCase = true) || it.equals("n/a", ignoreCase = true) || it == "-"
        } ?: ""
        // read-only, as in the official app: the registration comes from the car's account
        if (cleanReg.isBlank()) InfoRow("Vehicle reg. no.", "Not reported by your account", muted = true)
        else InfoRow("Vehicle reg. no.", if (privacy) Mask.reg(cleanReg) else cleanReg)
      }
      // specs grid
      Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(horizontal = 18.dp, vertical = 4.dp)) {
        Store.specs.chunked(2).forEach { pair ->
          Row(Modifier.fillMaxWidth()) {
            pair.forEach { s ->
              Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                Text(s.label.uppercase(), style = label())
                Spacer(Modifier.height(4.dp))
                Text(s.value, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp, fontFamily = if (s.mono) FontFamily.Monospace else FontFamily.Default))
              }
              Spacer(Modifier.width(14.dp))
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
          }
        }

      }
      HealthCard(Store.telemetry, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))

      OwnershipCard(
        Store.telemetry.odometerKm,
        Store.connectedSince,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
      )
      if (Store.plans.isNotEmpty()) {
        PlansCard(
          Store.plans,
          Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
      }

      FuelSpendCard(
        Store.telemetry.odometerKm,
        Store.connectedSince,
        Store.drive.realKmpl,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        onPrice = onPrice,
      )

    } else if (tab == VehicleTab.OWNER) {
      // owner
      Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).background(Card), contentAlignment = Alignment.Center) {
          Text(Mask.initials(Store.owner.initials), style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
          Text(Mask.name(Store.owner.proper), style = TextStyle(color = Card, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp))
          Text("PRIMARY OWNER · ${CarLook.name().uppercase()}", style = TextStyle(color = OnDarkMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp))
        }
        Spacer(Modifier.width(12.dp))
        // edit owner info (name / email / address kept on this phone)
        Box(
          Modifier.size(42.dp).clip(RoundedCornerShape(21.dp)).background(Card.copy(alpha = 0.14f))
            .clickable(onClickLabel = "Edit owner info") {
              nameDraft = Store.owner.name.takeIf { it != Repo.owner.name }.orEmpty()
              emailDraft = Store.owner.email
              addressDraft = Store.owner.address.trim()
              ownerEditor = true
            },
          contentAlignment = Alignment.Center,
        ) { VIcon(Ic.edit, size = 18.dp, color = Card, stroke = 1.9f) }
      }
      Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(horizontal = 18.dp)) {
        InfoRow("Name", Mask.name(Store.owner.proper))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        InfoRow("Email ID", Store.owner.email.ifEmpty { "Add your email" }.let { Mask.email(it) }, muted = !privacy && Store.owner.email.isEmpty())
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        InfoRow("Mobile number", Mask.mobile(Store.owner.mobile))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        val ownerAddress = Store.owner.address.trim()
        InfoRow("Address", if (ownerAddress.isNotEmpty()) ownerAddress else "Add your address", muted = ownerAddress.isEmpty())
      }

      if (ownerEditor) {
        AlertDialog(
          onDismissRequest = { ownerEditor = false },
          title = { Text("Owner info") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it }, singleLine = true, label = { Text("Name") })
              OutlinedTextField(value = emailDraft, onValueChange = { emailDraft = it }, singleLine = true, label = { Text("Email ID") })
              OutlinedTextField(value = addressDraft, onValueChange = { addressDraft = it }, label = { Text("Address") })
              Text(
                "Saved on this phone only. Leave a field empty to use your account's details.",
                style = TextStyle(color = Muted, fontSize = 12.sp),
              )
            }
          },
          confirmButton = {
            TextButton(onClick = {
              onOwner(nameDraft, emailDraft, addressDraft)
              ownerEditor = false
            }) { Text("Save") }
          },
          dismissButton = {
            TextButton(onClick = { ownerEditor = false }) { Text("Cancel") }
          },
        )
      }

    } else {
      // settings
      SettingsHeader("Appearance")
      // Theme: follow the phone's setting, or force dark / light
      SettingToggle("Match system theme", "Light or dark, following your phone's setting", Store.themeMode.isEmpty(), onSystemTheme)
      SettingToggle("Dark mode", if (Store.themeMode.isEmpty()) "Turning this on or off stops matching the system" else "Easier on the eyes at night", Store.dark, onDark)

      SettingsHeader("Car alerts")
      // Car alerts (background check every ~15 min); turning them on asks for notification permission first
      val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
      ) { granted -> onAlerts(granted) }
      val ctx = androidx.compose.ui.platform.LocalContext.current
      SettingToggle(
        "Car alerts",
        if (Store.alertsOn) "Checks your car about every 15 minutes, even when the app is closed"
        else "Battery drain, check-engine light, night-time starts and low fuel",
        Store.alertsOn,
      ) { on ->
        if (on && !dev.openvw.work.CarNotifications.canPost(ctx) && android.os.Build.VERSION.SDK_INT >= 33)
          askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else onAlerts(on)
      }
      if (Store.alertsOn) dev.openvw.data.Alerts.Type.entries.forEach { t ->
        SettingToggle(t.title, t.description, t in Store.alertTypes) { onAlertType(t, it) }
      }

      SettingsHeader("Privacy & security")
      // App lock: confirm with a biometric / screen lock before turning it on (or off)
      var lockOn by remember { mutableStateOf(AppLock.isOn(ctx)) }
      SettingToggle(
        "App lock",
        if (AppLock.available(ctx)) "Fingerprint, face or screen lock to open CarPulse" else "Set up a screen lock on your phone to use this",
        lockOn,
      ) { on ->
        val act = ctx as? androidx.fragment.app.FragmentActivity
        if (act != null && AppLock.available(ctx))
          AppLock.authenticate(act, if (on) "Turn on App lock" else "Turn off App lock") { AppLock.setOn(act, on); lockOn = on }
      }

      // Privacy mode toggle
      SettingToggle("Privacy mode", "Mask VIN, reg & personal info with mimic data", privacy, onPrivacy)

      // settings above save as soon as they change, so sign-out is the only action here
      Box(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(99.dp)).background(Card)
          .border(1.dp, dev.openvw.theme.Hairline, RoundedCornerShape(99.dp)).clickable(onClick = onLogout),
        contentAlignment = Alignment.Center,
      ) {
        Text("Sign out", style = TextStyle(color = dev.openvw.theme.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold))
      }
    }
  }
}

@Composable
private fun PlansCard(plans: List<PlanInfo>, modifier: Modifier = Modifier) {
  Column(modifier.card(24.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
    Text("SUBSCRIPTION & BENEFITS", style = label())
    Spacer(Modifier.height(10.dp))
    plans.forEachIndexed { i, p ->
      PlanRow(p)
      if (i != plans.lastIndex) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        Spacer(Modifier.height(10.dp))
      }
    }
  }
}

@Composable
private fun PlanRow(plan: PlanInfo) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
    Column(Modifier.weight(1f)) {
      Text(plan.title, style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
      if (plan.details.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(plan.details, style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 17.sp))
      }
    }
    if (plan.active != null) {
      Spacer(Modifier.width(10.dp))
      Box(
        Modifier.clip(RoundedCornerShape(99.dp))
          .background(if (plan.active) GreenSoftBg else Card)
          .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          if (plan.active) "Active" else "Inactive",
          style = TextStyle(color = if (plan.active) Green else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
      }
    }
  }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, muted: Boolean = false) {
  Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
    Text(label.uppercase(), style = label())
    Spacer(Modifier.height(4.dp))
    Text(
      value,
      style = TextStyle(
        color = if (muted) Muted else Ink, fontSize = if (mono) 13.sp else 15.sp,
        fontWeight = if (muted) FontWeight.Normal else FontWeight.Bold,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
      ),
    )
  }
}

@Composable
private fun ColourPicker(modifier: Modifier, onPaint: (String) -> Unit) {
  val pick = Store.paintPick
  val auto = CarLook.paint(Store.vehicle.colour)
  val reported = Store.vehicle.colour.isNotBlank()
  Column(modifier.card(24.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text("CAR COLOUR", style = label())
      Text(
        when {
          pick.isNotEmpty() -> CarLook.effective(Store.vehicle.colour).label
          reported -> "Auto · ${auto.label}"
          else -> "Not reported · pick yours"
        },
        style = TextStyle(color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
      )
    }
    Spacer(Modifier.height(12.dp))
    val few = CarLook.paints().size < 4
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (few) Arrangement.spacedBy(12.dp) else Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      // "Auto" follows the colour on the account
      Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(99.dp)).background(if (pick.isEmpty()) Ink else Card)
          .border(1.dp, if (pick.isEmpty()) Ink else Border, RoundedCornerShape(99.dp)).clickable { onPaint("") }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
      ) { Text("Auto", style = TextStyle(color = if (pick.isEmpty()) Card else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
      CarLook.paints().forEach { p ->
        val sel = pick == p.name
        Box(
          Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).border(2.dp, if (sel) Ink else Color.Transparent, RoundedCornerShape(17.dp))
            .clickable { onPaint(p.name) }.padding(4.dp).clip(RoundedCornerShape(13.dp)).background(Color(p.swatch))
            .border(1.dp, Color(0x22000000), RoundedCornerShape(13.dp)),
        )
      }
    }
  }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    // the whole row toggles (not just the switch), and reads as one switch to TalkBack
    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp)
      .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
      .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
      Spacer(Modifier.height(2.dp))
      Text(subtitle, style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 16.sp))
    }
    Spacer(Modifier.width(12.dp))
    Switch(
      checked = checked, onCheckedChange = null,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Card, checkedTrackColor = Ink,
        // "off" has to read as off, not disabled: a visible grey thumb and outline in both themes
        uncheckedThumbColor = Muted, uncheckedTrackColor = Card, uncheckedBorderColor = Muted,
      ),
    )
  }
}

@Composable
private fun SettingsHeader(text: String) {
  Text(text.uppercase(), style = label(), modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 2.dp))
}
