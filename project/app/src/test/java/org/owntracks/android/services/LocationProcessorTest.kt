package org.owntracks.android.services

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.InMemoryWaypointsRepo
import org.owntracks.android.model.messages.MessageBase
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.net.WifiInfoProvider
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.preferences.types.StringMaxTwoAlphaNumericChars
import org.owntracks.android.support.DeviceMetricsProvider
import org.owntracks.android.test.SimpleIdlingResource

class LocationProcessorTest {

  @Test
  fun `normal driving speed is plausible`() {
    // 1000m in 60s = 60 km/h
    assertTrue(isPlausibleSpeed(1000f, 60.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `commercial flight speed is plausible`() {
    // 150km in 900s (15 min) = 600 km/h
    assertTrue(isPlausibleSpeed(150_000f, 900.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `a teleport jump is not plausible`() {
    // 50km in 5s implies 36000 km/h - a cell-tower bounce, not real movement
    assertFalse(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `zero maxSpeedKmh disables the check`() {
    assertTrue(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = 0))
  }

  @Test
  fun `negative maxSpeedKmh disables the check`() {
    assertTrue(isPlausibleSpeed(50_000f, 5.0, maxSpeedKmh = -1))
  }

  @Test
  fun `zero or negative dtSeconds does not false-positive`() {
    assertTrue(isPlausibleSpeed(50_000f, 0.0, maxSpeedKmh = 1000))
    assertTrue(isPlausibleSpeed(50_000f, -5.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `speed right at the threshold is plausible`() {
    // 1000km in 3600s = exactly 1000 km/h
    assertTrue(isPlausibleSpeed(1_000_000f, 3600.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `speed just over the threshold is not plausible`() {
    assertFalse(isPlausibleSpeed(1_000_001f, 3600.0, maxSpeedKmh = 1000))
  }

  // Jump gate: implausible jumps and suspicious post-gap jumps are withheld until corroborated.

  @Test
  fun `an ordinary small move publishes immediately`() {
    // 800m in 60s = 48 km/h, well under the quarantine distance
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(800f, 60.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a teleport jump is withheld`() {
    // 50km in 5s: implausible at any setting
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(50_000f, 5.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a plausible-speed jump after a long gap is quarantined, not published`() {
    // The gap-blindness case: 50km after an hour implies only 50 km/h, but a stationary device's
    // first post-gap fix is disproportionately likely to be a network bounce — hold it.
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(50_000f, 3600.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a plausible-speed jump under the quarantine distance publishes immediately`() {
    // 4km after an hour: under QUARANTINE_DISTANCE_METRES, no corroboration needed
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(4_000f, 3600.0, null, null, maxSpeedKmh = 1000))
  }

  @Test
  fun `a second fix agreeing with the withheld one corroborates a real relocation`() {
    // Still ~50km from the anchor, but only 100m in 30s from the withheld fix: the move is real.
    assertEquals(
        JumpGateDecision.PUBLISH_CORROBORATED,
        evaluateJumpGate(50_000f, 3630.0, 100f, 30.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `a second fix back at the anchor discards the withheld bounce`() {
    // The next fix is back near the published anchor: the withheld fix was a transient bounce.
    // It publishes normally and the withheld state is dropped by the caller.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(120f, 3630.0, 50_000f, 30.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `an implausible jump corroborated by the previous withheld fix escapes a bad anchor`() {
    // The anchor itself is a bounce that reached the wire: real fixes look implausible against
    // it, but agree with each other — accept instead of locking on.
    assertEquals(
        JumpGateDecision.PUBLISH_CORROBORATED,
        evaluateJumpGate(50_000f, 65.0, 90f, 60.0, maxSpeedKmh = 1000))
  }

  @Test
  fun `zero maxSpeedKmh disables the jump gate entirely`() {
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(50_000f, 5.0, null, null, maxSpeedKmh = 0))
  }

  // On-foot tightening. Values below are taken from a real walk that produced ~190m spikes
  // between fixes 25s apart, which the 1000 km/h ceiling cannot see (~29 km/h implied) and which
  // reported better-than-median accuracy, so the accuracy gate could not see them either.

  @Test
  fun `a walking-scale spike is withheld while the on-foot boost is active`() {
    // 190m in 25s = 27.4 km/h: plausible against the teleport ceiling, not against 15 km/h.
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(
            190f, 25.0, null, null, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `the same spike publishes when the on-foot boost is not active`() {
    // Driving, or any non-boosted mode: onFootMaxSpeedKmh is passed as 0 and nothing changes.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(190f, 25.0, null, null, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 0))
  }

  @Test
  fun `ordinary walking is unaffected by the tight threshold`() {
    // The largest genuine segment observed on that walk: 88m in 28s = 11.3 km/h, and under the
    // displacement floor regardless.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(
            88f, 28.0, null, null, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `GPS scatter between closely spaced fixes cannot trip the tight threshold`() {
    // 40m in 3s implies 48 km/h, but it's under the floor: a corroboration fix arriving seconds
    // after the last one must not be rejected for ordinary jitter.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(
            40f, 3.0, null, null, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `a long publish gap stays safe under the tight threshold`() {
    // The Doze case: 500m of real movement across a 4 minute gap implies 7.5 km/h. It clears the
    // floor, so it is judged — and passes on speed, which is exactly why the floor alone isn't
    // the whole gate.
    assertEquals(
        JumpGateDecision.PUBLISH,
        evaluateJumpGate(
            500f, 240.0, null, null, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `a second bad fix near the first cannot corroborate it while on foot`() {
    // Both fixes are out at the spike: 190m from the anchor, and 150m in 25s from the withheld
    // one. Under the loose ceiling that pair would corroborate; the tight threshold refuses.
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(
            190f, 25.0, 150f, 25.0, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `a genuine on-foot relocation still corroborates`() {
    // The anchor turned out to be the bad fix: the new fix is far from it but only 30m in 25s
    // from the previously withheld one, i.e. walking pace. Believe the pair.
    assertEquals(
        JumpGateDecision.PUBLISH_CORROBORATED,
        evaluateJumpGate(
            190f, 25.0, 30f, 25.0, maxSpeedKmh = 1000, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  @Test
  fun `the tight threshold works with the teleport ceiling disabled`() {
    assertEquals(
        JumpGateDecision.WITHHOLD,
        evaluateJumpGate(
            190f, 25.0, null, null, maxSpeedKmh = 0, onFootMaxSpeedKmh = 15,
            onFootMinJumpMetres = 100f))
  }

  /** Regression test for https://github.com/owntracks/android/issues/2034 review follow-up. */
  @Test
  fun `RESPONSE trigger is not dropped by the implausible speed filter`() = runTest {
    val preferences =
        mock<Preferences> {
          on { maxImplausibleSpeedKmh } doReturn 50
          on { ignoreInaccurateLocations } doReturn 0
          on { discardNetworkLocationThresholdSeconds } doReturn 0
          on { fusedRegionDetection } doReturn false
          on { monitoring } doReturn MonitoringMode.Significant
          on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
        }
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor =
        LocationProcessor(
            messageProcessor,
            preferences,
            LocationRepo(),
            InMemoryWaypointsRepo(this, mock<Context>(), Dispatchers.Unconfined),
            mock<DeviceMetricsProvider>(),
            mock<WifiInfoProvider>(),
            this,
            Dispatchers.Unconfined,
            SimpleIdlingResource("publishResponseMessageIdlingResource", false),
            SimpleIdlingResource("mockLocationIdlingResource", false),
            false)

    val firstLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(firstLocation, MessageLocation.ReportType.DEFAULT)

    // ~50km in 5 seconds - far beyond the 50km/h mocked ceiling - but a RESPONSE to an explicit
    // "reportLocation" request must still be published, never silently dropped.
    val secondLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
          on { distanceTo(firstLocation) } doReturn 50_000f
        }
    locationProcessor.onLocationChanged(secondLocation, MessageLocation.ReportType.RESPONSE)

    val captor = argumentCaptor<MessageBase>()
    verify(messageProcessor, times(2)).queueMessageForSending(captor.capture())
    assertEquals(
        MessageLocation.ReportType.RESPONSE, (captor.secondValue as MessageLocation).trigger)
  }

  private fun TestScope.buildLocationProcessor(
      messageProcessor: MessageProcessor,
      discardThresholdSeconds: Int
  ): LocationProcessor {
    val preferences =
        mock<Preferences> {
          on { maxImplausibleSpeedKmh } doReturn 0
          on { ignoreInaccurateLocations } doReturn 0
          on { discardNetworkLocationThresholdSeconds } doReturn discardThresholdSeconds
          on { fusedRegionDetection } doReturn false
          on { monitoring } doReturn MonitoringMode.Significant
          on { tid } doReturn StringMaxTwoAlphaNumericChars("AB")
        }
    return LocationProcessor(
        messageProcessor,
        preferences,
        LocationRepo(),
        InMemoryWaypointsRepo(this, mock<Context>(), Dispatchers.Unconfined),
        mock<DeviceMetricsProvider>(),
        mock<WifiInfoProvider>(),
        this,
        Dispatchers.Unconfined,
        SimpleIdlingResource("publishResponseMessageIdlingResource", false),
        SimpleIdlingResource("mockLocationIdlingResource", false),
        false)
  }

  /**
   * A network fix landing shortly after a high-accuracy one is the case
   * `discardNetworkLocationThresholdSeconds` exists to suppress.
   */
  @Test
  fun `given a recent gps fix, a following network fix is discarded`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor = buildLocationProcessor(messageProcessor, 30)

    val gpsLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(gpsLocation, MessageLocation.ReportType.DEFAULT)

    val networkLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "network"
          on { accuracy } doReturn 500f
        }
    locationProcessor.onLocationChanged(networkLocation, MessageLocation.ReportType.DEFAULT)

    verify(messageProcessor, times(1)).queueMessageForSending(any())
  }

  /**
   * The inverse must not happen: a gps fix converging shortly after a poor network fix is exactly
   * the better location we want to publish. Regression test for
   * https://github.com/owntracks/android/issues/2289
   */
  @Test
  fun `given a recent network fix, a following gps fix is still published`() = runTest {
    val messageProcessor = mock<MessageProcessor>()
    val locationProcessor = buildLocationProcessor(messageProcessor, 30)

    val networkLocation =
        mock<Location> {
          on { time } doReturn 1_000_000L
          on { provider } doReturn "network"
          on { accuracy } doReturn 500f
        }
    locationProcessor.onLocationChanged(networkLocation, MessageLocation.ReportType.DEFAULT)

    val gpsLocation =
        mock<Location> {
          on { time } doReturn 1_005_000L
          on { provider } doReturn "gps"
          on { accuracy } doReturn 5f
        }
    locationProcessor.onLocationChanged(gpsLocation, MessageLocation.ReportType.DEFAULT)

    verify(messageProcessor, times(2)).queueMessageForSending(any())
  }
}
