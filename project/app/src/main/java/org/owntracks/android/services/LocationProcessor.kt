package org.owntracks.android.services

import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.WaypointModel
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.ApplicationScope
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.kalman.KalmanFix
import org.owntracks.android.kalman.LocationKalmanFilter
import org.owntracks.android.location.geofencing.Geofence
import org.owntracks.android.model.messages.AddMessageStatus
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.model.messages.MessageLocation.Companion.fromLocation
import org.owntracks.android.model.messages.MessageStatus
import org.owntracks.android.model.messages.MessageTransition
import org.owntracks.android.model.messages.MessageWaypoint
import org.owntracks.android.model.messages.MessageWaypoints
import org.owntracks.android.model.messages.addWifi
import org.owntracks.android.net.WifiInfoProvider
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.support.DeviceMetricsProvider
import org.owntracks.android.support.MessageWaypointCollection
import org.owntracks.android.test.SimpleIdlingResource
import timber.log.Timber

/**
 * A waypoint transition candidate that has been observed but not yet committed, because it hasn't
 * persisted for long enough to rule out a fix landing right on the waypoint boundary.
 */
internal data class PendingWaypointTransition(val transition: Int, val since: Instant)

/**
 * Debounces a waypoint transition candidate against previously-pending state. A candidate is only
 * committed once it has been observed consistently for at least [dwell]; a candidate that differs
 * from the currently-pending one resets the dwell timer.
 *
 * @return a pair of (transition to commit, if any) to (new pending state, if any)
 */
internal fun resolveTransitionDebounce(
    pending: PendingWaypointTransition?,
    candidate: Int,
    now: Instant,
    dwell: Duration
): Pair<Int?, PendingWaypointTransition?> =
    if (pending == null || pending.transition != candidate) {
      null to PendingWaypointTransition(candidate, now)
    } else if (Duration.between(pending.since, now) >= dwell) {
      candidate to null
    } else {
      null to pending
    }

@Singleton
class LocationProcessor
@Inject
constructor(
  private val messageProcessor: MessageProcessor,
  private val preferences: Preferences,
  private val locationRepo: LocationRepo,
  private val waypointsRepo: WaypointsRepo,
  private val deviceMetricsProvider: DeviceMetricsProvider,
  private val wifiInfoProvider: WifiInfoProvider,
  @param:ApplicationScope private val scope: CoroutineScope,
  @param:CoroutineScopes.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
  @param:Named("publishResponseMessageIdlingResource")
    private val publishResponseMessageIdlingResource: SimpleIdlingResource,
  @param:Named("mockLocationIdlingResource")
    private val mockLocationIdlingResource: SimpleIdlingResource,
  @param:Named("nativeGeofencingAvailable") private val nativeGeofencingAvailable: Boolean
) : Preferences.OnPreferenceChangeListener {

  // Cached: maybeSmooth runs on every continuous DEFAULT fix, and each experimentalFeatures read
  // rebuilds a sorted set from the preference store just to answer one boolean.
  @Volatile private var smoothingEnabled = isSmoothingEnabled()

  init {
    preferences.registerOnPreferenceChangedListener(this)
  }

  private fun isSmoothingEnabled() =
      preferences.experimentalFeatures.contains(Preferences.EXPERIMENTAL_FEATURE_SMOOTH_LOCATIONS)

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::experimentalFeatures.name)) {
      smoothingEnabled = isSmoothingEnabled()
    }
  }

  private fun locationIsWithAccuracyThreshold(l: Location): Boolean =
      preferences.ignoreInaccurateLocations
          .run { preferences.ignoreInaccurateLocations == 0 || l.accuracy < this }
          .also {
            if (!it) {
              Timber.d(
                  "Location accuracy ${l.accuracy} is outside accuracy threshold of ${preferences.ignoreInaccurateLocations}")
            }
          }

  // The most recent DEFAULT fix the jump gate withheld from publishing — either rejected as an
  // implausible jump, or quarantined as a suspicious post-gap jump. A subsequent fix that agrees
  // with it corroborates that the new position is real (see below).
  private var lastWithheldLocation: Location? = null

  // Fires when the gate starts withholding, so the service can request a fresh fix to corroborate
  // (or refute) it in seconds instead of waiting for the next scheduled fix — for a stationary
  // device in significant mode that could otherwise be arbitrarily far off. Emitted only on the
  // first withhold of an episode and rate-limited, so a flapping location environment can't turn
  // the gate into a high-accuracy-request loop.
  private val mutableCorroborationFixRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val corroborationFixRequests: SharedFlow<Unit> = mutableCorroborationFixRequests
  private var lastCorroborationRequestElapsedMs = 0L

  /**
   * Gates the continuous DEFAULT stream against network-location "teleport" artifacts (a
   * mislocated cell ID, or a wifi AP whose database entry went stale when it moved). Explicit
   * USER/CIRCULAR/etc. samples are always trusted; 0 disables the gate entirely.
   *
   * Two failure modes, one shared principle — a bounce is transient but a real move persists, so
   * two consecutive fixes that agree with each other are believed over the published anchor:
   * - **Implausible jump**: the speed implied from the last published fix exceeds
   *   [Preferences.maxImplausibleSpeedKmh]. Withheld — unless the previous withheld fix
   *   corroborates it, which means the *anchor* is the outlier (a bounce that reached the wire)
   *   and waiting out the implied-speed window would lock genuine fixes out.
   * - **Suspicious post-gap jump**: a long publish gap makes any implied speed look plausible (dt
   *   is the denominator), so the speed check is structurally blind right after a gap — exactly
   *   when a stationary device's displacement-triggered publish is most likely to *be* a bounce.
   *   A plausible fix that still jumped more than [QUARANTINE_DISTANCE_METRES] is therefore
   *   withheld until the next fix corroborates it: a genuine relocation costs one fix of latency,
   *   a bounce never reaches the wire.
   */
  private fun shouldPublishLocation(
      location: Location,
      reportType: MessageLocation.ReportType
  ): Boolean {
    if (reportType != MessageLocation.ReportType.DEFAULT) return true
    val last = locationRepo.currentPublishedLocation.value ?: return true
    val withheld = lastWithheldLocation
    val decision =
        evaluateJumpGate(
            distanceToAnchorMetres = last.distanceTo(location),
            dtToAnchorSeconds = (location.time - last.time) / 1000.0,
            distanceToWithheldMetres = withheld?.distanceTo(location),
            dtToWithheldSeconds = withheld?.let { (location.time - it.time) / 1000.0 },
            maxSpeedKmh = preferences.maxImplausibleSpeedKmh)
    return when (decision) {
      JumpGateDecision.PUBLISH -> {
        lastWithheldLocation = null
        true
      }
      JumpGateDecision.PUBLISH_CORROBORATED -> {
        Timber.i("Jump corroborated by the previously withheld fix; accepting $location")
        lastWithheldLocation = null
        true
      }
      JumpGateDecision.WITHHOLD -> {
        val firstWithholdOfEpisode = withheld == null
        lastWithheldLocation = location
        Timber.w(
            "Withholding suspicious location jump: ${last.distanceTo(location).roundToInt()}m " +
                "in ${"%.1f".format((location.time - last.time) / 1000.0)}s from $last to " +
                "$location (awaiting corroboration)")
        val nowMs = SystemClock.elapsedRealtime()
        if (firstWithholdOfEpisode &&
            nowMs - lastCorroborationRequestElapsedMs >= CORROBORATION_REQUEST_COOLDOWN_MS) {
          lastCorroborationRequestElapsedMs = nowMs
          mutableCorroborationFixRequests.tryEmit(Unit)
        }
        false
      }
    }
  }

  // Experimental (see Preferences.EXPERIMENTAL_FEATURE_SMOOTH_LOCATIONS): smooths GPS jitter on
  // the continuous DEFAULT stream. Kept as a single instance for the process lifetime so it can
  // build up a running estimate across fixes.
  private val kalmanFilter = LocationKalmanFilter()

  private fun maybeSmooth(location: Location, reportType: MessageLocation.ReportType): Location {
    if (reportType != MessageLocation.ReportType.DEFAULT || !smoothingEnabled) {
      return location
    }
    val smoothed =
        kalmanFilter.filter(
            KalmanFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMetres = location.accuracy,
                timestampMillis = location.time,
                speedMetresPerSecond = location.speed))
    // Only the position is smoothed. The sensor-reported accuracy must survive untouched: the
    // ignoreInaccurateLocations gate, the waypoint-transition tolerance (geofenceRadius +
    // accuracy), and the published `acc` all consume it, and the filter's own confidence
    // (sqrt(variance), floored at 1 m) would let a genuinely poor fix pass those gates looking
    // precise.
    return Location(location).apply {
      latitude = smoothed.latitude
      longitude = smoothed.longitude
    }
  }

  suspend fun publishLocationMessage(trigger: MessageLocation.ReportType) =
      locationRepo.currentPublishedLocation.value?.run { publishLocationMessage(trigger, this) }

  private val highAccuracyProviders = setOf("gps", "fused")

  // Matches the notificationResponsiveness used for native GMS geofencing, so both flavors have
  // comparable real-world hysteresis around a waypoint boundary.
  private val transitionDebounceDwell: Duration = Duration.ofMinutes(2)
  private val pendingWaypointTransitions = mutableMapOf<Long, PendingWaypointTransition>()

  private suspend fun publishLocationMessage(
      trigger: MessageLocation.ReportType,
      location: Location
  ): Result<Unit> {
    Timber.v("Maybe publishing $location with trigger $trigger")
    if (!locationIsWithAccuracyThreshold(location))
        return Result.failure(Exception("location accuracy too low"))

    // If this location has come from the network *and* the most recent location was both recent and
    // high-accuracy, then it's probably not usefully accurate. Drop it.
    locationRepo.currentPublishedLocation.value?.let { lastLocation ->
      if (location.provider == "network" &&
          highAccuracyProviders.contains(lastLocation.provider) &&
          location.time - lastLocation.time <
              preferences.discardNetworkLocationThresholdSeconds * 1000) {
        Timber.d(
            "Ignoring location from ${location.provider}, last was from ${lastLocation.provider} within ${preferences.discardNetworkLocationThresholdSeconds}s")
        return Result.failure(
            Exception(
                "Ignoring location from ${location.provider}, last was recent and high-accuracy"))
      }
    }

    // NB: the implausible-speed check upstream added here (#2034, later refined by #2289's sibling
    // to exempt `responseMessageTypes`) is deliberately absent — this fork runs the superset jump
    // gate in onLocationChanged/shouldPublishLocation instead, so checking again against the same
    // anchor would be redundant. The RESPONSE exemption upstream added is already implied here: the
    // gate only ever runs on DEFAULT, so every response-type trigger passes untouched.

    val loadedWaypoints = withContext(ioDispatcher) { waypointsRepo.getAll() }
    Timber.d("publishLocationMessage for $location triggered by $trigger")

    // Check if publish would trigger a region if fusedRegionDetection is enabled. Skipped entirely
    // where native OS geofencing is available (e.g. gms) - that's a purpose-built mechanism with its
    // own hysteresis, and running this alongside it causes the two to race and flip-flop on the same
    // waypoint state. Where it isn't available (e.g. oss), a transition candidate must instead be
    // observed consistently for transitionDebounceDwell before being committed, for the same reason.
    Timber.d(
        "Checking if location triggers waypoint transitions. waypoints: $loadedWaypoints, trigger=$trigger, fusedRegionDetection: ${preferences.fusedRegionDetection}, nativeGeofencingAvailable: $nativeGeofencingAvailable")
    if (loadedWaypoints.isNotEmpty() &&
        preferences.fusedRegionDetection &&
        !nativeGeofencingAvailable &&
        trigger != MessageLocation.ReportType.CIRCULAR) {
      pendingWaypointTransitions.keys.retainAll(loadedWaypoints.map { it.id }.toSet())
      loadedWaypoints.forEach { waypoint ->
        val candidate =
            if (location.distanceTo(waypoint.getLocation()) <=
                waypoint.geofenceRadius + location.accuracy) {
              Geofence.GEOFENCE_TRANSITION_ENTER
            } else {
              Geofence.GEOFENCE_TRANSITION_EXIT
            }
        if (candidate == waypoint.lastTransition) {
          pendingWaypointTransitions.remove(waypoint.id)
          Timber.d("onWaypointTransition triggered by location waypoint intersection event")
          onWaypointTransition(waypoint, location, candidate, MessageTransition.TRIGGER_LOCATION)
        } else {
          val (transitionToCommit, newPending) =
              resolveTransitionDebounce(
                  pendingWaypointTransitions[waypoint.id],
                  candidate,
                  Instant.ofEpochMilli(location.time),
                  transitionDebounceDwell)
          if (newPending == null) {
            pendingWaypointTransitions.remove(waypoint.id)
          } else {
            pendingWaypointTransitions[waypoint.id] = newPending
          }
          if (transitionToCommit != null) {
            Timber.d("onWaypointTransition triggered by location waypoint intersection event")
            onWaypointTransition(
                waypoint, location, transitionToCommit, MessageTransition.TRIGGER_LOCATION)
          }
        }
      }
    }
    if (preferences.monitoring === MonitoringMode.Quiet &&
        MessageLocation.ReportType.USER != trigger) {
      Timber.d("message suppressed by monitoring settings: quiet")
      return Result.failure(Exception("message suppressed by monitoring settings: quiet"))
    }
    if (preferences.monitoring === MonitoringMode.Manual &&
        MessageLocation.ReportType.USER != trigger &&
        MessageLocation.ReportType.CIRCULAR != trigger) {
      Timber.d("message suppressed by monitoring settings: manual")
      return Result.failure(Exception("message suppressed by monitoring settings: manual"))
    }

    val message =
        if (preferences.extendedData) {
              fromLocation(location, Build.VERSION.SDK_INT).apply {
                addWifi(wifiInfoProvider)
                battery = deviceMetricsProvider.batteryLevel
                batteryStatus = deviceMetricsProvider.batteryStatus
                conn = deviceMetricsProvider.connectionType.value
                monitoringMode = preferences.monitoring
                source = location.provider
                motionActivities = locationRepo.currentMotionActivities
              }
            } else {
              fromLocation(location, Build.VERSION.SDK_INT)
            }
            .apply {
              this.trigger = trigger
              trackerId = preferences.tid.toString()
              inregions = calculateInRegions(loadedWaypoints)
            }
    Timber.v("Actually publishing location $location triggered by $trigger as message=$message")
    messageProcessor.queueMessageForSending(message)
    if (responseMessageTypes.contains(trigger)) {
      publishResponseMessageIdlingResource.setIdleState(true)
    }
    return Result.success(Unit)
  }

  private val responseMessageTypes =
      listOf(
          MessageLocation.ReportType.RESPONSE,
          MessageLocation.ReportType.USER,
          MessageLocation.ReportType.CIRCULAR)

  private fun calculateInRegions(loadedWaypoints: List<WaypointModel>): List<String> =
      loadedWaypoints
          .filter { it.lastTransition == Geofence.GEOFENCE_TRANSITION_ENTER }
          .map { it.description }
          .toList()

  /**
   * Called when a new location is received from the device, or directly from the user via the map
   *
   * @param location received from the device
   * @param reportType type of report that
   */
  suspend fun onLocationChanged(location: Location, reportType: MessageLocation.ReportType) {
    Timber.v("OnLocationChanged $location $reportType")
    if (location.time > locationRepo.currentLocationTime ||
        reportType != MessageLocation.ReportType.DEFAULT) {
      if (!shouldPublishLocation(location, reportType)) return
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || location.isMock) {
        Timber.v("Idling location")
        mockLocationIdlingResource.setIdleState(true)
      }
      val locationToPublish = maybeSmooth(location, reportType)
      publishLocationMessage(reportType, locationToPublish).run {
        if (isSuccess) {
          locationRepo.setCurrentPublishedLocation(locationToPublish)
        } else {
          Timber.d("Not publishing location: ${exceptionOrNull()?.message}")
        }
      }
    } else {
      Timber.v("Not re-sending message with same timestamp as last")
    }
  }

  fun onWaypointTransition(
      waypointModel: WaypointModel,
      location: Location,
      transition: Int,
      trigger: String
  ) {
    if (!locationIsWithAccuracyThreshold(location)) {
      Timber.d(
          "ignoring transition for $location, transition=$transition, trigger=$trigger: low accuracy")
      return
    }
    Timber.d("OnWaypointTransition $waypointModel $location $transition $trigger")
    scope.launch {
      // If the transition hasn't changed, or has moved from unknown to exit, don't notify.
      if (transition == waypointModel.lastTransition ||
          (waypointModel.isUnknown() && transition == Geofence.GEOFENCE_TRANSITION_EXIT)) {
        waypointModel.lastTransition = transition
        waypointsRepo.update(waypointModel, false)
      } else {
        waypointModel.lastTransition = transition
        waypointModel.lastTriggered = Instant.now()
        waypointsRepo.update(waypointModel, false)
        if (preferences.monitoring === MonitoringMode.Quiet) {
          Timber.d("message suppressed by monitoring settings: ${preferences.monitoring}")
        } else {
          publishTransitionMessage(waypointModel, location, transition, trigger)
          if (trigger == MessageTransition.TRIGGER_CIRCULAR) {
            publishLocationMessage(MessageLocation.ReportType.CIRCULAR, location)
          }
        }
      }
    }
  }

  fun publishWaypointMessage(e: WaypointModel) {
    messageProcessor.queueMessageForSending(waypointsRepo.fromDaoObject(e))
  }

  private fun publishTransitionMessage(
      waypointModel: WaypointModel,
      triggeringLocation: Location,
      transition: Int,
      trigger: String
  ) {
    messageProcessor.queueMessageForSending(
        MessageTransition().apply {
          setTransition(transition)
          this.trigger = trigger
          trackerId = preferences.tid.toString()
          latitude = triggeringLocation.latitude
          longitude = triggeringLocation.longitude
          accuracy = triggeringLocation.accuracy.roundToInt()
          timestamp = TimeUnit.MILLISECONDS.toSeconds(triggeringLocation.time)
          waypointTimestamp = waypointModel.tst.epochSecond
          description = waypointModel.description
        })
  }

  suspend fun publishWaypointsMessage() {
    messageProcessor.queueMessageForSending(
        MessageWaypoints().apply {
          waypoints =
              MessageWaypointCollection().apply {
                withContext(ioDispatcher) {
                  addAll(
                      waypointsRepo.getAll().map {
                        MessageWaypoint().apply {
                          description = it.description
                          latitude = it.geofenceLatitude.value
                          longitude = it.geofenceLongitude.value
                          radius = it.geofenceRadius
                          timestamp = it.tst.epochSecond
                        }
                      })
                }
              }
        })
    publishResponseMessageIdlingResource.setIdleState(true)
  }

  fun publishStatusMessage() {
    // Getting appHibernation takes a while, so lets not block the main thread
    scope.launch(ioDispatcher) {
      messageProcessor.queueMessageForSending(
          MessageStatus().apply {
            android =
                AddMessageStatus().apply {
                  wifistate = wifiInfoProvider.isWiFiEnabled()
                  powerSave = deviceMetricsProvider.powerSave
                  batteryOptimizations = deviceMetricsProvider.batteryOptimizations
                  appHibernation = deviceMetricsProvider.appHibernation
                  locationPermission = deviceMetricsProvider.locationPermission
                }
          })
      publishResponseMessageIdlingResource.setIdleState(true)
    }
  }
}

/**
 * Whether [distanceMetres] covered in [dtSeconds] implies a plausible speed given
 * [maxSpeedKmh] (0 disables the check). Split out as a top-level pure function so the
 * teleport-jump logic is unit-testable without instantiating [LocationProcessor].
 */
internal fun isPlausibleSpeed(distanceMetres: Float, dtSeconds: Double, maxSpeedKmh: Int): Boolean {
  if (maxSpeedKmh <= 0 || dtSeconds <= 0) return true
  val impliedSpeedKmh = DrivingSpeedTier.mpsToKmh((distanceMetres / dtSeconds).toFloat())
  return impliedSpeedKmh <= maxSpeedKmh
}

// A plausible-speed jump larger than this is still withheld pending corroboration: after a long
// publish gap the speed check is blind (huge dt), and the "miles away" network-bounce artifacts
// this guards against are km-scale. Small enough to catch them, large enough that ordinary
// between-fix movement never pays the one-fix corroboration latency.
internal const val QUARANTINE_DISTANCE_METRES = 5_000f

// Floor between on-demand corroboration fix requests. Generous enough for GPS acquisition plus
// slack; a genuine relocation resolves on the first request, so a repeat inside this window only
// happens in a flapping/GPS-denied environment where more requests wouldn't help anyway.
private const val CORROBORATION_REQUEST_COOLDOWN_MS = 60_000L

internal enum class JumpGateDecision {
  PUBLISH,
  PUBLISH_CORROBORATED,
  WITHHOLD
}

/**
 * Pure decision core of [LocationProcessor]'s jump gate (see [shouldPublishLocation] for the
 * rationale). [distanceToWithheldMetres]/[dtToWithheldSeconds] describe the previously withheld
 * fix, if any. [maxSpeedKmh] <= 0 disables the gate.
 */
internal fun evaluateJumpGate(
    distanceToAnchorMetres: Float,
    dtToAnchorSeconds: Double,
    distanceToWithheldMetres: Float?,
    dtToWithheldSeconds: Double?,
    maxSpeedKmh: Int
): JumpGateDecision {
  if (maxSpeedKmh <= 0) return JumpGateDecision.PUBLISH
  if (isPlausibleSpeed(distanceToAnchorMetres, dtToAnchorSeconds, maxSpeedKmh) &&
      distanceToAnchorMetres < QUARANTINE_DISTANCE_METRES) {
    return JumpGateDecision.PUBLISH
  }
  // Implausible jump, or plausible only thanks to a long gap: believe it once two consecutive
  // fixes agree with each other.
  if (distanceToWithheldMetres != null &&
      dtToWithheldSeconds != null &&
      isPlausibleSpeed(distanceToWithheldMetres, dtToWithheldSeconds, maxSpeedKmh)) {
    return JumpGateDecision.PUBLISH_CORROBORATED
  }
  return JumpGateDecision.WITHHOLD
}
