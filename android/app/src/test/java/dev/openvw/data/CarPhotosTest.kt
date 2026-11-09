package dev.openvw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPhotosTest {
  // the real bundled list (tests run from the app module directory)
  private val list = org.json.JSONArray(java.io.File("src/main/assets/cars/index.json").readText()).let { a ->
    (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
      CarPhotos.Entry(o.getString("model"), if (o.isNull("year")) null else o.getInt("year"), o.getString("variant"), o.getString("colour"), o.getString("file"))
    }
  }

  @Test fun everyFileExists() = list.forEach { assertTrue(it.file, java.io.File("src/main/assets/cars/" + it.file).exists()) }

  @Test fun preFaceliftSlaviaInItsOwnColour() {
    val e = CarPhotos.choose(list, "Slavia Sportline 1.0 AT", "Tornado Red Dual Tone", 2024, null)!!
    assertEquals("Slavia", e.model); assertTrue(e.colour, "tornado" in e.colour.lowercase()); assertTrue("${e.year}", (e.year ?: 0) <= 2025)
  }

  @Test fun newSlaviaGetsTheFacelift() {
    val e = CarPhotos.choose(list, "Slavia Prestige", "Candy White", 2026, null)!!
    assertEquals(2026, e.year); assertTrue(e.colour, "white" in e.colour.lowercase())
  }

  @Test fun variantSpecificPictureOnlyForThatVariant() {
    val mc = CarPhotos.choose(list, "Slavia Monte Carlo", "Lava Blue", 2026, null)!!
    assertEquals("Monte Carlo", mc.variant)
    val plain = CarPhotos.choose(list, "Slavia Classic", "Lava Blue", 2026, null)!!
    assertTrue(plain.variant, plain.variant != "Monte Carlo")
  }

  @Test fun mostSpecificModelName() = assertEquals("Tiguan Allspace", CarPhotos.choose(list, "Tiguan Allspace 2.0 TSI", "Pure White", 2021, null)?.model)

  @Test fun manualPaintPickWins() {
    val e = CarPhotos.choose(list, "Kylaq Signature", "Candy White", 2025, CarLook.Paint.RED)!!
    assertEquals(CarLook.Paint.RED, CarLook.paintOf(e.colour))
  }

  @Test fun unknownColourFallsBackToPaintFamilyOrNothing() {
    assertNotNull(CarPhotos.choose(list, "Taigun GT", "Some Red", 2023, null))
    assertNull(CarPhotos.choose(list, "Taigun GT", "Unobtainium", 2023, null))
  }

  @Test fun archivedModelsAreNotBundled() = assertNull(CarPhotos.choose(list, "Skoda Yeti", "Candy White", 2016, null))
}
