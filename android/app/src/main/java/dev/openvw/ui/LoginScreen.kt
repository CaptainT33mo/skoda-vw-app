package dev.openvw.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openvw.R
import dev.openvw.data.Brand
import dev.openvw.data.Store
import dev.openvw.theme.Bg
import dev.openvw.theme.Border
import dev.openvw.theme.Card
import dev.openvw.theme.Ink
import dev.openvw.theme.Muted
import dev.openvw.theme.Red

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(vm: AppViewModel) {
  // The page keeps its full-screen layout when the keyboard opens: instead of squeezing everything into the space
  // above the keyboard, the keyboard's height is added below the content and the page scrolls just far enough
  // that the form's bottom sits on the keyboard. The header scrolls only as far as needed; nothing is resized.
  val scroll = rememberScrollState()
  val imeOpen = WindowInsets.isImeVisible
  val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
  var formBottom by remember { mutableIntStateOf(0) } // px from the top of the page to the form's bottom edge
  BoxWithConstraints(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
    val pageHeight = maxHeight // measured without the keyboard: the layout never shrinks
    val viewport = constraints.maxHeight
    LaunchedEffect(imeOpen, imeBottom, formBottom, scroll.maxValue) {
      if (imeOpen) scroll.animateScrollTo((formBottom - (viewport - imeBottom)).coerceIn(0, scroll.maxValue))
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).imePadding()) {
      // Header and form stay together, centred in the page: with the form pinned to the bottom, tall screens (a
      // foldable's cover screen) left a large empty gap between the welcome text and the form.
      Column(
        Modifier.fillMaxWidth().heightIn(min = pageHeight).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Column {
          Spacer(Modifier.height(32.dp))
          BrandBadge()
          Spacer(Modifier.height(20.dp))
          Text("Welcome to\nCarPulse", style = TextStyle(color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp, lineHeight = 44.sp))
          Spacer(Modifier.height(10.dp))
          Text(
            if (vm.phase == Phase.OTP) "Enter the 6-digit code sent to your phone by SMS."
            else "Sign in with the mobile number registered on your car's ${Store.brand.officialApp} account.",
            style = TextStyle(color = Muted, fontSize = 15.sp, lineHeight = 21.sp),
          )
          Spacer(Modifier.height(32.dp))
        }
        Column(Modifier.onGloballyPositioned { formBottom = (it.positionInParent().y + it.size.height).toInt() }) {
          BackHandler(enabled = vm.phase == Phase.OTP) { vm.backToPhone() }
          if (vm.phase == Phase.OTP) OtpStep(vm) else PhoneStep(vm)
          vm.message?.let { Text(it, style = TextStyle(color = if (it == "Code re-sent") Muted else Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 12.dp)) }
          Spacer(Modifier.height(28.dp))
        }
      }
    }
  }
}

@Composable
private fun BrandBadge() {
  Box(
    Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF121214)),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(R.drawable.ic_carpulse_mark),
      contentDescription = "CarPulse",
      modifier = Modifier.size(52.dp),
      contentScale = ContentScale.Fit,
    )
  }
}

/** Skoda / Volkswagen: which brand's servers the sign-in goes to. Confirmed from the car list after sign-in. */
@Composable
private fun BrandPicker(vm: AppViewModel) {
  Text("YOUR CAR", style = label())
  Spacer(Modifier.height(8.dp))
  Row(
    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Brand.entries.forEach { b ->
      val on = Store.brand == b
      Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(if (on) Ink else Card)
          .clickable(enabled = !vm.busy) { vm.setBrand(b) },
        contentAlignment = Alignment.Center,
      ) {
        Text(b.label, style = TextStyle(color = if (on) Card else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold))
      }
    }
  }
  Spacer(Modifier.height(20.dp))
}

@Composable
private fun PhoneStep(vm: AppViewModel) {
  var p by remember { mutableStateOf(vm.phone) }
  BrandPicker(vm)
  Text("MOBILE NUMBER", style = label())
  Spacer(Modifier.height(8.dp))
  Row(
    Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("+91", style = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.width(12.dp))
    BasicTextField(
      value = p,
      onValueChange = { p = it.filter { c -> c.isDigit() }.take(10) },
      textStyle = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      modifier = Modifier.weight(1f),
      decorationBox = { inner -> if (p.isEmpty()) Text("00000 00000", style = TextStyle(color = Muted, fontSize = 20.sp)); inner() },
    )
  }
  Spacer(Modifier.height(20.dp))
  CtaButton(if (vm.busy) "Sending OTP…" else "Send OTP", enabled = !vm.busy && p.length == 10) { vm.startLogin(p) }
  Spacer(Modifier.height(8.dp))
  // no account handy (or reviewing the app): a sample car, clearly marked as such, with no sign-in
  Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !vm.busy) { vm.startDemo() }.padding(12.dp), contentAlignment = Alignment.Center) {
    Text("No account? Try a demo ${if (Store.brand == Brand.VW) "Taigun" else "Slavia"}", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
  }
}

@Composable
private fun OtpStep(vm: AppViewModel) {
  var code by remember { mutableStateOf("") }
  BasicTextField(
    value = code,
    onValueChange = {
      code = it.filter { c -> c.isDigit() }.take(6)
      if (code.length == 6) vm.verify(code)
    },
    // plain digits: the boxes show them anyway, and NumberPassword opens OnePlus/Oppo's full-screen "secure keyboard"
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = Modifier.fillMaxWidth(),
    decorationBox = {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(6) { i ->
          val ch = code.getOrNull(i)?.toString() ?: ""
          Box(
            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp)).background(Card)
              .border(if (code.length == i) 2.dp else 1.dp, if (code.length == i) Ink else Border, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
          ) { Text(ch, style = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)) }
        }
      }
    },
  )
  Spacer(Modifier.height(20.dp))
  CtaButton(if (vm.busy) "Verifying…" else "Verify & continue", enabled = !vm.busy && code.length == 6) { vm.verify(code) }
  Spacer(Modifier.height(8.dp))
  Box(Modifier.fillMaxWidth().clickable { vm.resend() }.padding(12.dp), contentAlignment = Alignment.Center) {
    Text("Resend code", style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
  }
}

@Composable
private fun CtaButton(text: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
      .background(if (enabled) Ink else Border).let { if (enabled) it.clickable(onClick = onClick) else it },
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = TextStyle(color = if (enabled) Card else Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold))
  }
}
