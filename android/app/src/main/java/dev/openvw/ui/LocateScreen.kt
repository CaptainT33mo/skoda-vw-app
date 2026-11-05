package dev.openvw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import dev.openvw.data.GoogleMaps
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openvw.data.Store
import dev.openvw.data.Workshop
import dev.openvw.theme.Accent
import dev.openvw.theme.Border
import dev.openvw.theme.Card
import dev.openvw.theme.Field
import dev.openvw.theme.Ink
import dev.openvw.theme.Muted

@Composable
fun LocateScreen(onBack: () -> Unit) {
  var mode by remember { mutableStateOf(MapMode.STREET) }
  var carCard by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var editing by remember { mutableStateOf<Workshop?>(null) } // workshop being added / edited
  val ctx = LocalContext.current
  val brand = Store.brand.label
  // searches are centred on the parked car when we know where it is; otherwise Maps uses your location
  fun search(q: String) {
    if (q.isBlank()) return
    GoogleMaps.searchNear(ctx, q.trim(), Store.parked.lat.takeIf { Store.parkedKnown }, Store.parked.lon.takeIf { Store.parkedKnown })
  }
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    BackBar(onBack, showBack = false)
    Text("Locate", style = H1, modifier = Modifier.padding(start = 20.dp, top = 4.dp))

    // search: opens Google Maps around the car
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      VIcon(Ic.search + " M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z", size = 18.dp, color = Muted, stroke = 2f)
      Spacer(Modifier.width(10.dp))
      androidx.compose.foundation.text.BasicTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search(query) }),
        modifier = Modifier.weight(1f),
        decorationBox = { inner ->
          if (query.isEmpty()) Text(if (Store.parkedKnown) "Search near your car" else "Search places", style = TextStyle(color = Muted, fontSize = 14.sp))
          inner()
        },
      )
      if (query.isNotBlank()) Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(Ink).clickable { search(query) }, contentAlignment = Alignment.Center) {
        VIcon(Ic.chevronR, size = 16.dp, color = Card, stroke = 2.4f)
      }
    }

    // one-tap searches (open Google Maps)
    androidx.compose.foundation.layout.FlowRow(
      Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      listOf("$brand service centre", "$brand showroom", "Petrol pump", "Parking", "Car wash", "Tyre shop").forEach { q ->
        Seg(q, false) { search(q) }
      }
    }

    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, dev.openvw.theme.Hairline, RoundedCornerShape(24.dp))) {
      OsmMap(
        Store.parked.lat, Store.parked.lon, Modifier.matchParentSize(), zoom = if (Store.parkedKnown) 15.0 else 10.0, interactive = true, showMarker = Store.parkedKnown, mode = mode,
        onMarkerClick = { carCard = !carCard },
      )
      MapModeToggle(mode, Modifier.align(Alignment.TopEnd).padding(10.dp)) { mode = it }
      // tapping the car pin offers a hand-off to Google Maps
      if (carCard && Store.parkedKnown) Row(
        Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp, end = 60.dp)
          .shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Card).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f, fill = false)) {
          Text("Your ${dev.openvw.data.CarLook.name()}", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold))
          Text(Store.parked.title, maxLines = 1, style = TextStyle(color = Muted, fontSize = 11.sp))
        }
        Spacer(Modifier.width(10.dp))
        SmallDark("Google Maps", Ic.navArrow) { GoogleMaps.pin(ctx, Store.parked.lat, Store.parked.lon) }
      }
    }

    // where the car is
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).card(24.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Ink), contentAlignment = Alignment.Center) {
        VIcon(Ic.car, size = 20.dp, color = Card, stroke = 1.9f)
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text("YOUR CAR · ${Store.vehicle.status.uppercase()}", style = label())
        Spacer(Modifier.height(2.dp))
        Text(if (Store.parkedKnown) Store.parked.title else "Location not available yet", maxLines = 1, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
        Text(
          if (Store.parkedKnown) listOf(Store.parked.sub, sinceLabel(Store.parked.epoch)).filter { it.isNotEmpty() }.joinToString(" · ")
          else "Shows after your next drive, once the engine is switched off",
          maxLines = 2, style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 16.sp),
        )
      }
      if (Store.parkedKnown) {
        Spacer(Modifier.width(10.dp))
        CircleButton(Ic.navArrow, bg = Field, border = Field) { GoogleMaps.pin(ctx, Store.parked.lat, Store.parked.lon) }
      }
    }

    // saved workshops: the owner's own (Skoda/VW servers have no dealer list)
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Saved workshops", style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp))
        Text("Your service centre, showroom or garage", style = TextStyle(color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
      }
      CircleButton("M12 6v12M6 12h12") { editing = Workshop("w${System.currentTimeMillis()}", "", "", "") }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (Store.savedWorkshops.isEmpty()) Column(Modifier.fillMaxWidth().card(24.dp).padding(18.dp)) {
        Text("No saved workshops yet", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text("Search for your $brand service centre above, then tap + to save it here with its phone number for one-tap directions and calls.",
          style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 17.sp))
      }
      Store.savedWorkshops.forEach { w ->
        Column(Modifier.card(24.dp).clickable { editing = w }.padding(16.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
              Text(w.name, style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
              if (w.address.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(w.address, style = TextStyle(color = Muted, fontSize = 12.sp)) }
            }
            // filled heart = saved; tap to remove
            CircleButton(Ic.heart, bg = Accent, fg = androidx.compose.ui.graphics.Color.White, border = Accent) {
              dev.openvw.data.SavedWorkshops.remove(ctx, w.id)
            }
          }
          Spacer(Modifier.height(12.dp))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val dest = listOf(w.name, w.address).filter { it.isNotBlank() }.joinToString(", ")
            SmallDark("Directions", Ic.navArrow, Modifier.weight(1f)) { GoogleMaps.directions(ctx, dest) }
            if (w.phone.isNotBlank()) Row(
              Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(99.dp)).background(Card)
                .border(1.dp, dev.openvw.theme.Hairline, RoundedCornerShape(99.dp)).clickable { GoogleMaps.dial(ctx, w.phone) },
              verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
              VIcon("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", size = 14.dp, color = Ink, stroke = 1.9f)
              Spacer(Modifier.width(7.dp))
              Text("Call", style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
          }
        }
      }
    }
  }

  editing?.let { w -> WorkshopEditor(w, onDismiss = { editing = null }) { saved -> dev.openvw.data.SavedWorkshops.put(ctx, saved); editing = null } }
}

@Composable
private fun WorkshopEditor(w: Workshop, onDismiss: () -> Unit, onSave: (Workshop) -> Unit) {
  var name by remember { mutableStateOf(w.name) }
  var address by remember { mutableStateOf(w.address) }
  var phone by remember { mutableStateOf(w.phone) }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (w.name.isEmpty()) "Save a workshop" else "Edit workshop") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
        androidx.compose.material3.OutlinedTextField(address, { address = it }, label = { Text("Address (optional)") })
        androidx.compose.material3.OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c in "+ -" } }, singleLine = true, label = { Text("Phone (optional)") },
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
        Text("Saved on this phone only.", style = TextStyle(color = Muted, fontSize = 12.sp))
      }
    },
    confirmButton = {
      androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(w.copy(name = name.trim(), address = address.trim(), phone = phone.trim())) }) { Text("Save") }
    },
    dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** Compact dark pill with a small accent arrow badge. */
@Composable
private fun SmallDark(text: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Row(
    modifier.height(40.dp).clip(RoundedCornerShape(99.dp))
      .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(dev.openvw.theme.Ink2, Ink)))
      .clickable(onClick = onClick).padding(start = 6.dp, end = 14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(dev.openvw.theme.Accent), contentAlignment = Alignment.Center) {
      VIcon(icon, size = 12.dp, color = Card, stroke = 2.4f)
    }
    Spacer(Modifier.width(7.dp))
    Text(text, maxLines = 1, style = TextStyle(color = Card, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
  }
}
