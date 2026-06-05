package org.owntracks.android.support

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.owntracks.android.preferences.types.UnitOfMeasure

class UnitFormatterTest {
  @Test
  fun `kmh converts to mph rounded to nearest integer`() {
    assertEquals(0, UnitFormatter.kmhToMph(0))
    assertEquals(31, UnitFormatter.kmhToMph(50))
    assertEquals(62, UnitFormatter.kmhToMph(100))
    assertEquals(100, UnitFormatter.kmhToMph(161))
  }

  @Test
  fun `metres convert to feet rounded to nearest integer`() {
    assertEquals(0, UnitFormatter.metersToFeet(0))
    assertEquals(328, UnitFormatter.metersToFeet(100))
    assertEquals(3281, UnitFormatter.metersToFeet(1000))
  }

  @Test
  fun `explicit unit preferences resolve to their system regardless of locale`() {
    assertEquals(UnitSystem.METRIC, UnitFormatter.resolveSystem(UnitOfMeasure.Metric, Locale.US))
    assertEquals(
        UnitSystem.IMPERIAL, UnitFormatter.resolveSystem(UnitOfMeasure.Imperial, Locale.UK))
  }
}
