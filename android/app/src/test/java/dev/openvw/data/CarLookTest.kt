package dev.openvw.data

import dev.openvw.data.CarLook.Paint
import org.junit.Assert.assertEquals
import org.junit.Test

class CarLookTest {
  private fun p(c: String) = CarLook.paint(c)

  @Test fun whiteWithBlackRoofIsWhite() = assertEquals(Paint.WHITE, p("Candy White Deep/Black Pearlescent"))
  @Test fun plainWhiteIsWhite() = assertEquals(Paint.WHITE, p("Candy White"))
  @Test fun silverIsWhite() = assertEquals(Paint.WHITE, p("Reflex Silver"))
  @Test fun blackIsBlack() = assertEquals(Paint.BLACK, p("Deep Black Pearl"))
  @Test fun blackFirstThenWhiteIsBlack() = assertEquals(Paint.BLACK, p("Deep Black/Candy White"))
  @Test fun carbonSteelIsGrey() = assertEquals(Paint.GREY, p("Carbon Steel Grey Matte"))
  @Test fun cherryIsRed() = assertEquals(Paint.RED, p("Wild Cherry Red"))
  @Test fun kingsRedIsRed() = assertEquals(Paint.RED, p("Kings Red Metallic"))
  @Test fun redWithBlackRoofIsRed() = assertEquals(Paint.RED, p("Wild Cherry Red/Deep Black"))
  @Test fun risingBlueIsBlue() = assertEquals(Paint.BLUE, p("Rising Blue Metallic"))
  @Test fun curcumaIsYellow() = assertEquals(Paint.YELLOW, p("Curcuma Yellow"))
  // "Lava Blue" is a Skoda paint, so "lava" means blue; an orange nobody renders falls back to white
  @Test fun lavaBlueIsBlue() = assertEquals(Paint.BLUE, p("Lava Blue Metallic"))
  @Test fun unknownColourUsesWhite() = assertEquals(Paint.WHITE, p("Sunset Orange"))
  @Test fun emptyUsesWhite() = assertEquals(Paint.WHITE, p(""))
  // the art is drawn, not photographed: every paint works on every model (OpenVW's Virtus photos were W/B/R only)
  @Test fun virtusRedIsRed() = assertEquals(Paint.RED, CarLook.paint("Wild Cherry Red", "Virtus GT"))
  @Test fun virtusBlueIsBlue() = assertEquals(Paint.BLUE, CarLook.paint("Rising Blue", "Virtus Topline"))
  @Test fun bodyStyles() {
    assertEquals(CarArt.Body.SEDAN, CarLook.body("Slavia Sportline 1.0 AT"))
    assertEquals(CarArt.Body.SEDAN, CarLook.body("VIRTUS GT"))
    assertEquals(CarArt.Body.SUV, CarLook.body("Kushaq Style"))
    assertEquals(CarArt.Body.SUV, CarLook.body("TAIGUN"))
    assertEquals(CarArt.Body.HATCH, CarLook.body("Fabia"))
    assertEquals(CarArt.Body.SUV, CarLook.body("Something new")) // unknown: the common shape
  }
  @Test fun paintColours() {
    assertEquals(0xB21228, CarLook.rgb("Tornado Red", "Slavia"))
    assertEquals(0xECECE9, CarLook.rgb("", "Slavia")) // unknown colour draws white
  }
  @Test fun taigunKeepsAllPaints() = assertEquals(Paint.BLUE, CarLook.paint("Rising Blue", "Taigun GT"))
  @Test fun modelNames() {
    assertEquals("Slavia", CarLook.name("Slavia Sportline 1.0 TSI AT"))
    assertEquals("Virtus", CarLook.name("VIRTUS GT PLUS"))
    assertEquals("T-Roc", CarLook.name("T-ROC"))
  }

  @Test fun manualPickWins() {
    Store.paintPick = "BLUE"
    try { assertEquals(Paint.BLUE, CarLook.effective("Candy White")) } finally { Store.paintPick = "" }
  }
}
