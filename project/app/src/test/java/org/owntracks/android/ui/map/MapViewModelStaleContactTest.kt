package org.owntracks.android.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewModelStaleContactTest {
  private val now = 1_000_000_000L // arbitrary "now" in epoch seconds
  private val twoDays = 2 * 86_400L

  @Test
  fun `not stale when the feature is disabled`() {
    assertFalse(
        MapViewModel.isLocationStale(
            locationTimestampSeconds = now - 10 * 86_400L,
            nowSeconds = now,
            hideEnabled = false,
            thresholdDays = 2))
  }

  @Test
  fun `not stale when within the threshold`() {
    assertFalse(
        MapViewModel.isLocationStale(
            locationTimestampSeconds = now - (twoDays - 60),
            nowSeconds = now,
            hideEnabled = true,
            thresholdDays = 2))
  }

  @Test
  fun `stale when older than the threshold`() {
    assertTrue(
        MapViewModel.isLocationStale(
            locationTimestampSeconds = now - (twoDays + 60),
            nowSeconds = now,
            hideEnabled = true,
            thresholdDays = 2))
  }

  @Test
  fun `not stale when timestamp is unknown or threshold is non-positive`() {
    assertFalse(
        MapViewModel.isLocationStale(
            locationTimestampSeconds = 0, nowSeconds = now, hideEnabled = true, thresholdDays = 2))
    assertFalse(
        MapViewModel.isLocationStale(
            locationTimestampSeconds = now - 10 * 86_400L,
            nowSeconds = now,
            hideEnabled = true,
            thresholdDays = 0))
  }
}
