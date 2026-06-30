package org.owntracks.android.services

import android.Manifest
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.graphics.Typeface
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import javax.inject.Inject
import javax.inject.Named
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_CHANNEL_EVENTS
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_GROUP_EVENTS
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_ID_EVENT_GROUP
import org.owntracks.android.BaseApp.Companion.NOTIFICATION_ID_ONGOING
import org.owntracks.android.R
import org.owntracks.android.data.repos.ContactsRepo
import org.owntracks.android.data.repos.EndpointStateRepo
import org.owntracks.android.data.repos.LocationRepo
import org.owntracks.android.data.waypoints.WaypointsRepo
import org.owntracks.android.di.CoroutineScopes
import org.owntracks.android.geocoding.GeocoderProvider
import org.owntracks.android.location.ActivityRecognitionClient
import org.owntracks.android.location.LatLng
import org.owntracks.android.location.LocationAvailability
import org.owntracks.android.location.LocationCallback
import org.owntracks.android.location.LocationProviderClient
import org.owntracks.android.location.LocationRequest
import org.owntracks.android.location.LocationResult
import org.owntracks.android.location.geofencing.Geofence
import org.owntracks.android.location.geofencing.GeofencingClient
import org.owntracks.android.location.geofencing.GeofencingEvent
import org.owntracks.android.location.geofencing.GeofencingEvent.Companion.fromIntent
import org.owntracks.android.location.geofencing.GeofencingRequest
import org.owntracks.android.location.toLatLng
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.model.messages.MessageTransition
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.Preferences.Companion.PREFERENCES_THAT_WIPE_QUEUE_AND_CONTACTS
import org.owntracks.android.preferences.types.ConnectionMode
import org.owntracks.android.preferences.types.MonitoringMode.Companion.getByValue
import org.owntracks.android.services.worker.Scheduler
import org.owntracks.android.support.DateFormatter.formatDate
import org.owntracks.android.support.RequirementsChecker
import org.owntracks.android.support.RunThingsOnOtherThreads
import org.owntracks.android.test.SimpleIdlingResource
import org.owntracks.android.ui.map.MapActivity
import timber.log.Timber

@AndroidEntryPoint
class BackgroundService : LifecycleService(), Preferences.OnPreferenceChangeListener {
  private var lastLocation: Location? = null
  private val activeNotifications = mutableListOf<Spannable>()
  private var hasBeenStartedExplicitly = false

  @Inject lateinit var preferences: Preferences

  @Inject lateinit var scheduler: Scheduler

  @Inject lateinit var locationProcessor: LocationProcessor

  @Inject lateinit var geocoderProvider: GeocoderProvider

  @Inject lateinit var contactsRepo: ContactsRepo

  @Inject lateinit var locationRepo: LocationRepo

  @Inject lateinit var runThingsOnOtherThreads: RunThingsOnOtherThreads

  @Inject lateinit var waypointsRepo: WaypointsRepo

  @Inject lateinit var messageProcessor: MessageProcessor

  @Inject lateinit var endpointStateRepo: EndpointStateRepo

  @Inject lateinit var geofencingClient: GeofencingClient

  @Inject lateinit var activityRecognitionClient: ActivityRecognitionClient

  @Inject lateinit var locationProviderClient: LocationProviderClient

  @Inject lateinit var requirementsChecker: RequirementsChecker

  @Inject
  @Named("contactsClearedIdlingResource")
  lateinit var contactsClearedIdlingResource: SimpleIdlingResource

  @Inject @CoroutineScopes.IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

  // The interval currently in effect for the driving boost (speed-tiered; see DrivingSpeedTier).
  private var currentDrivingIntervalSeconds = DrivingSpeedTier.DEFAULT_INTERVAL_SECONDS

  // Whether GPS speed currently indicates driving. Edge-tracked so we only feed the controller on
  // the enter/exit crossing (not every fix), otherwise a repeated STILL would perpetually re-arm
  // its revert timer. Backs up Activity Recognition, which is blind to smooth constant-velocity
  // cruising (it reports STILL); see DrivingSpeedTier.DRIVING_ENTER_KMH / DRIVING_EXIT_KMH.
  private var speedIndicatesDriving = false

  // Consecutive vehicular-speed fixes, used to override an active on-foot/cycling boost only once
  // speed is sustained (see DrivingSpeedTier.DRIVING_OVERRIDE_CONFIRMATION_FIXES).
  private var consecutiveDrivingSpeedFixes = 0

  private val callbackForReportType =
      mutableMapOf<MessageLocation.ReportType, Lazy<LocationCallbackWithReportType>>().apply {
        MessageLocation.ReportType.entries.forEach {
          this[it] = lazy {
            LocationCallbackWithReportType(
                it,
                locationProcessor,
                lifecycleScope,
                // Only the continuous DEFAULT stream feeds the driving speed-tiering.
                onLocation =
                    if (it == MessageLocation.ReportType.DEFAULT) ::onDrivingLocationForTuning
                    else { _ -> })
          }
        }
      }

  /**
   * Speed handling on the continuous DEFAULT location stream, two jobs:
   * 1. While the driving boost is active, re-tune the sampling interval from the fix's own speed (a
   *    longer interval at higher speed lets the GPS duty-cycle). Only re-issues the request on a
   *    band change (DrivingSpeedTier applies hysteresis), so steady driving doesn't churn it.
   * 2. Engage/disengage the driving boost from GPS speed when Activity Recognition misses it. AR is
   *    accelerometer-based and reports STILL during smooth constant-velocity cruising, so speed is
   *    the only reliable vehicle signal there. Only the enter/exit edge is fed to the controller
   *    (so its revert timer isn't perpetually re-armed). With no on-foot/cycling boost active a
   *    single vehicular-speed fix engages; to override an on-foot/cycling boost AR established it
   *    takes sustained speed (DRIVING_OVERRIDE_CONFIRMATION_FIXES), and because a boost is already
   *    active the controller switches profiles immediately — no entry dwell, no baseline dip.
   */
  private fun onDrivingLocationForTuning(location: Location) {
    if (!location.hasSpeed()) return
    val speedKmh = DrivingSpeedTier.mpsToKmh(location.speed)

    if (preferences.locatorBoostedByDriving) {
      val newInterval =
          DrivingSpeedTier.intervalSecondsForSpeed(speedKmh, currentDrivingIntervalSeconds)
      if (newInterval != currentDrivingIntervalSeconds) {
        Timber.d(
            "Driving speed ${"%.0f".format(speedKmh)} km/h; " +
                "re-tuning interval ${currentDrivingIntervalSeconds}s -> ${newInterval}s")
        currentDrivingIntervalSeconds = newInterval
        setupLocationRequest()
      }
    }

    if (!preferences.autoMonitoringByActivity || !preferences.boostLocatorWhileDriving) {
      consecutiveDrivingSpeedFixes = 0
      return
    }

    if (speedKmh >= DrivingSpeedTier.DRIVING_ENTER_KMH) {
      consecutiveDrivingSpeedFixes++
    } else {
      consecutiveDrivingSpeedFixes = 0
    }

    // Overriding an on-foot/cycling boost AR set demands sustained speed; engaging from no boost
    // needs only one fix (the cold-start driving case).
    val mayEngageDriving =
        if (preferences.locatorBoostedByActivity) {
          consecutiveDrivingSpeedFixes >= DrivingSpeedTier.DRIVING_OVERRIDE_CONFIRMATION_FIXES
        } else {
          consecutiveDrivingSpeedFixes >= 1
        }

    if (!speedIndicatesDriving && mayEngageDriving) {
      val overridingOnFoot = preferences.locatorBoostedByActivity
      Timber.i(
          "GPS speed ${"%.0f".format(speedKmh)} km/h indicates driving; engaging driving boost " +
              if (overridingOnFoot) "(overriding on-foot boost)"
              else "(Activity Recognition reported no vehicle transition)")
      speedIndicatesDriving = true
      currentDrivingIntervalSeconds =
          DrivingSpeedTier.intervalSecondsForSpeed(speedKmh, currentDrivingIntervalSeconds)
      locationRepo.currentMotionActivities = DetectedActivityChange.IN_VEHICLE.toMotionActivities()
      activityMonitoringModeController.onActivityChange(DetectedActivityChange.IN_VEHICLE)
    } else if (speedIndicatesDriving && speedKmh < DrivingSpeedTier.DRIVING_EXIT_KMH) {
      speedIndicatesDriving = false
      // Only revert the boost we engaged from speed; if AR has since switched us to an on-foot /
      // cycling boost, leave that alone (AR owns it).
      if (preferences.locatorBoostedByDriving) {
        Timber.i(
            "GPS speed ${"%.0f".format(speedKmh)} km/h indicates a stop; arming driving-boost revert")
        // Clear the speed-engaged "automotive" so published motionactivities doesn't go stale: we
        // engaged driving from GPS speed because AR never saw us enter the vehicle, so AR will emit
        // no exit transition to overwrite it — without this the last fix keeps re-publishing
        // "automotive" for hours after we've parked.
        locationRepo.currentMotionActivities = DetectedActivityChange.STILL.toMotionActivities()
        activityMonitoringModeController.onActivityChange(DetectedActivityChange.STILL)
      }
    }
  }

  private val ongoingNotification by lazy { OngoingNotification(this, preferences.monitoring) }
  private val notificationManagerCompat by lazy { NotificationManagerCompat.from(this) }
  private val activityManager by lazy {
    this.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  }
  private val powerStateLogger by lazy { PowerStateLogger(this.applicationContext) }
  private val powerBroadcastReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          intent.action?.run(powerStateLogger::logPowerState)
        }
      }

  // Significant motion sensor for triggering location requests when device movement is detected
  private lateinit var significantMotionSensor: SignificantMotionSensor

  // Decision engine for activity-triggered adaptive monitoring (opt-in autoMonitoringByActivity)
  private lateinit var activityMonitoringModeController: ActivityMonitoringModeController

  // Whether we currently hold an activity-transition registration. Tracked so repeated
  // setupAndStartService() calls within one process don't churn (remove+re-register) the GMS
  // updates, which resets its transition detector and can drop transitions entirely.
  private var activityUpdatesRegistered = false

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  internal interface ServiceEntrypoint {
    fun preferences(): Preferences

    fun endpointStateRepo(): EndpointStateRepo
  }

  override fun onCreate() {
    Timber.v("Backgroundservice onCreate")
    val entrypoint = EntryPoints.get(applicationContext, ServiceEntrypoint::class.java)
    preferences = entrypoint.preferences()
    endpointStateRepo = entrypoint.endpointStateRepo()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Timber.i(
          "Permissions. ACCESS_BACKGROUND_LOCATION: ${ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PERMISSION_GRANTED}")
    }
    Timber.i(
        "Permissions. ACCESS_COARSE_LOCATION: ${ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)==PERMISSION_GRANTED}")
    Timber.i(
        "Permissions. ACCESS_FINE_LOCATION: ${ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)==PERMISSION_GRANTED}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Timber.i(
          "Permissions. POST_NOTIFICATIONS: ${ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)==PERMISSION_GRANTED}")
    }

    super.onCreate()

    // Created before the pref listener is registered so it can't miss a monitoring change.
    activityMonitoringModeController =
        ActivityMonitoringModeController(preferences, lifecycleScope) {
          requirementsChecker.hasPreciseLocationPermission()
        }
    activityMonitoringModeController.onServiceStart()

    preferences.registerOnPreferenceChangedListener(this)

    // Initialize significant motion sensor
    significantMotionSensor =
        SignificantMotionSensor(
            this,
            preferences,
            locationProviderClient,
            requirementsChecker,
            callbackForReportType[MessageLocation.ReportType.SIGNIFICANT_MOTION]!!.value,
            runThingsOnOtherThreads.getBackgroundLooper())

    registerReceiver(
        powerBroadcastReceiver,
        IntentFilter().apply {
          addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
          addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
          }
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_SCREEN_OFF)
        })
    powerStateLogger.logPowerState("serviceOnCreate")

    lifecycleScope.launch {
      // Every time a waypoint is inserted, updated or deleted, we need to update the geofences, and
      // maybe publish that waypoint
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          waypointsRepo.migrationCompleteFlow.collect {
            if (it) {
              waypointsRepo.repoChangedEvent.collect { waypointOperation ->
                when (waypointOperation) {
                  is WaypointsRepo.WaypointOperation.Insert ->
                      locationProcessor.publishWaypointMessage(waypointOperation.waypoint)
                  is WaypointsRepo.WaypointOperation.Update ->
                      locationProcessor.publishWaypointMessage(waypointOperation.waypoint)
                  else -> {}
                }
                lifecycleScope.launch { setupGeofences() }
              }
            }
          }
        }
        launch { setupGeofences() }
        launch {
          locationRepo.currentPublishedLocation.collect { location ->
            location?.run {
              if (lastLocation == null || lastLocation!!.time < location.time) {
                lastLocation = location
                Timber.v("New published location: $location. Doing a geocode reverse")
                geocoderProvider.resolve(location.toLatLng(), this@BackgroundService)
              }
            }
          }
        }
        launch {
          endpointStateRepo.endpointState.collect {
            ongoingNotification.setEndpointState(
                it,
                if (preferences.mode == ConnectionMode.MQTT) preferences.host
                else preferences.url.toHttpUrlOrNull()?.host ?: "")
          }
        }
        endpointStateRepo.setServiceStartedNow()
      }
    }
  }

  override fun onDestroy() {
    Timber.v("Backgroundservice onDestroy")
    stopForeground(STOP_FOREGROUND_REMOVE)
    unregisterReceiver(powerBroadcastReceiver)
    significantMotionSensor.cancel()
    if (requirementsChecker.hasActivityRecognitionPermission()) {
      activityRecognitionClient.removeActivityUpdates()
    }
    activityUpdatesRegistered = false
    speedIndicatesDriving = false
    consecutiveDrivingSpeedFixes = 0
    preferences.unregisterOnPreferenceChangedListener(this)
    messageProcessor.stopSendingMessages()
    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Timber.v("Backgroundservice onStartCommand intent=$intent")
    super.onStartCommand(intent, flags, startId)
    handleIntent(intent)
    startForegroundService()
    return START_STICKY
  }

  /**
   * We've been sent a start command with an intent, which usually means we've got to do something
   * depending on the action
   *
   * @param intent that was passed to the service start command
   */
  private fun handleIntent(intent: Intent?) {
    if (intent?.action != null) {
      Timber.v("intent received with action:${intent.action}")
      when (intent.action) {
        INTENT_ACTION_SEND_LOCATION_USER -> {
          lifecycleScope.launch {
            if (requirementsChecker.hasLocationPermissions()) {
              locationProviderClient.singleHighAccuracyLocation(
                  callbackForReportType[MessageLocation.ReportType.USER]!!.value,
                  runThingsOnOtherThreads.getBackgroundLooper())
            }
          }
          return
        }
        // This comes from the [GeofencingBroadcastReceiver]
        INTENT_ACTION_SEND_EVENT_CIRCULAR -> {
          val event = fromIntent(intent)
          if (!event.hasError() && !event.triggeringGeofences.isNullOrEmpty()) {
            lifecycleScope.launch { onGeofencingEvent(fromIntent(intent)) }
          }
          return
        }
        // This comes from the gms ActivityRecognitionReceiver
        INTENT_ACTION_ACTIVITY_TRANSITION -> {
          if (preferences.autoMonitoringByActivity) {
            intent.getIntArrayExtra(EXTRA_ACTIVITY_CHANGE_ORDINALS)?.forEach { ordinal ->
              val change = DetectedActivityChange.entries[ordinal]
              // Record the actual detected activity (independent of the driving-boost gating below)
              // so it can be published as `motionactivities`. Last ordinal wins = current activity.
              locationRepo.currentMotionActivities = change.toMotionActivities()
              // When driving boost is off, treat getting in a vehicle like becoming still (revert).
              val effective =
                  if (change == DetectedActivityChange.IN_VEHICLE &&
                      !preferences.boostLocatorWhileDriving) {
                    DetectedActivityChange.STILL
                  } else {
                    change
                  }
              activityMonitoringModeController.onActivityChange(effective)
            }
          }
          return
        }

        // Called when the events are cancelled
        INTENT_ACTION_CLEAR_NOTIFICATIONS -> {
          clearEventStackNotification()
          return
        }
        // Clears all contacts from the repo
        INTENT_ACTION_CLEAR_CONTACTS -> {
          lifecycleScope.launch {
            contactsRepo.clearAll()
            contactsClearedIdlingResource.setIdleState(true)
          }
          return
        }
        INTENT_ACTION_CHANGE_MONITORING -> {
          if (intent.hasExtra("monitoring")) {
            val newMode = getByValue(intent.getIntExtra("monitoring", preferences.monitoring.value))
            preferences.monitoring = newMode
          } else {
            // Step monitoring mode if no mode is specified
            preferences.setMonitoringNext()
          }
          hasBeenStartedExplicitly = true
          notificationManagerCompat.cancel(BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0)
          return
        }
        INTENT_ACTION_BOOT_COMPLETED,
        INTENT_ACTION_PACKAGE_REPLACED -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!requirementsChecker.hasBackgroundLocationPermission() &&
                !hasBeenStartedExplicitly) {
              notifyUserOfBackgroundLocationRestriction()
            }
          }
          setupAndStartService()
          return
        }
        else -> {}
      }
    } else {
      Timber.d(
          "no intent or action provided, setting up location request and scheduling location ping.")
      hasBeenStartedExplicitly = true
      setupAndStartService()
    }
  }

  private fun startForegroundService() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        startForeground(
            NOTIFICATION_ID_ONGOING,
            ongoingNotification.getNotification(),
            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
      } catch (e: ForegroundServiceStartNotAllowedException) {
        Timber.e(
            e,
            "Foreground service start not allowed. backgroundRestricted=${activityManager.isBackgroundRestricted}")
        return
      }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID_ONGOING,
          ongoingNotification.getNotification(),
          FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID_ONGOING, ongoingNotification.getNotification())
    }
  }

  private fun setupAndStartService() {
    Timber.v("setupAndStartService")
    startForegroundService()
    setupLocationRequest()
    scheduler.scheduleLocationPing()
    significantMotionSensor.setup()
    setupActivityRecognition()
    messageProcessor.initialize()
  }

  /**
   * Registers/deregisters activity-transition updates per the opt-in preference and permission.
   * Idempotent within a process: only (de)registers on an actual state change, so the repeated
   * setupAndStartService() calls a foreground app triggers don't churn the GMS registration.
   */
  private fun setupActivityRecognition() {
    if (!requirementsChecker.hasActivityRecognitionPermission()) {
      if (preferences.autoMonitoringByActivity) {
        Timber.i(
            "Activity-based adaptive monitoring is enabled but the ACTIVITY_RECOGNITION permission is not granted")
      }
      return
    }
    if (preferences.autoMonitoringByActivity) {
      if (activityUpdatesRegistered) {
        Timber.d("Activity transition updates already registered; skipping")
      } else {
        Timber.d(
            "Activity-based adaptive monitoring enabled; requesting activity transition updates")
        activityRecognitionClient.requestActivityUpdates()
        activityUpdatesRegistered = true
      }
    } else if (activityUpdatesRegistered) {
      activityRecognitionClient.removeActivityUpdates()
      activityUpdatesRegistered = false
      // Stop attaching a now-stale activity to outgoing locations.
      locationRepo.currentMotionActivities = null
    }
  }

  private fun notifyUserOfBackgroundLocationRestriction() {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      return
    }
    val activityLaunchIntent =
        Intent(applicationContext, MapActivity::class.java)
            .setAction("android.intent.action.MAIN")
            .addCategory("android.intent.category.LAUNCHER")
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val notificationText = getString(R.string.backgroundLocationRestrictionNotificationText)
    val notificationTitle = getString(R.string.backgroundLocationRestrictionNotificationTitle)
    val notification =
        NotificationCompat.Builder(
                applicationContext, GeocoderProvider.ERROR_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_owntracks_80)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, 0, activityLaunchIntent, UPDATE_CURRENT_INTENT_FLAGS))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    notificationManagerCompat.notify(
        BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0, notification)
  }

  fun sendEventNotification(message: MessageTransition) {
    Timber.d("Sending event notification for $message")
    if (!preferences.notificationEvents ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      return
    }
    val contact = contactsRepo.getById(message.getContactId())
    val timestampInMs = TimeUnit.SECONDS.toMillis(message.timestamp)
    val location = message.description ?: getString(R.string.aLocation)
    val title = contact?.displayName ?: message.topic
    val transitionText =
        getString(
            if (message.getTransition() == Geofence.GEOFENCE_TRANSITION_ENTER) {
              R.string.transitionEntering
            } else {
              R.string.transitionLeaving
            })
    val eventText = "$transitionText $location"
    val whenStr = formatDate(timestampInMs)
    // Need to lock to prevent "clear()" being called while we're adding to it
    val summaryAndInbox =
        synchronized(activeNotifications) {
          activeNotifications.add(
              SpannableString("$whenStr $title $eventText").apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    whenStr.length + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
              })
          Timber.v("groupedNotifications: ${activeNotifications.size}")
          val summary =
              resources.getQuantityString(
                  R.plurals.notificationEventsTitle,
                  activeNotifications.size,
                  activeNotifications.size)
          val inbox = NotificationCompat.InboxStyle().setSummaryText(summary)
          activeNotifications.forEach { inbox.addLine(it) }
          Pair(summary, inbox)
        }
    val summary = summaryAndInbox.first
    val inbox = summaryAndInbox.second

    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EVENTS)
        .setContentTitle(getString(R.string.events))
        .setContentText(summary)
        .setGroup(NOTIFICATION_GROUP_EVENTS) // same as group of single notifications
        .setGroupSummary(true)
        .setColor(getColor(R.color.OTPrimaryBlue))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setSmallIcon(R.drawable.ic_owntracks_80)
        .setLocalOnly(true)
        .setDefaults(Notification.DEFAULT_ALL)
        .setNumber(activeNotifications.size)
        .setStyle(inbox)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt() / 1000,
                Intent(this, MapActivity::class.java),
                UPDATE_CURRENT_INTENT_FLAGS))
        .setDeleteIntent(
            PendingIntent.getService(
                this,
                1,
                Intent(this, BackgroundService::class.java)
                    .setAction(INTENT_ACTION_CLEAR_NOTIFICATIONS),
                UPDATE_CURRENT_INTENT_FLAGS))
        .build()
        .run {
          notificationManagerCompat
              .notify(NOTIFICATION_GROUP_EVENTS, NOTIFICATION_ID_EVENT_GROUP, this)
              .also { Timber.v("Event notification sent: $it") }
        }
  }

  fun clearEventStackNotification() {
    Timber.v("clearing notification stack")
    synchronized(activeNotifications) { activeNotifications.clear() }
  }

  private suspend fun onGeofencingEvent(event: GeofencingEvent) {
    if (event.hasError()) {
      Timber.e("geofencingEvent hasError: ${event.errorCode}")
      return
    }
    if (event.geofenceTransition == null ||
        event.triggeringGeofences == null ||
        event.triggeringLocation == null) {
      Timber.e("geofencingEvent has no transition or trigger")
      return
    }
    val transition: Int = event.geofenceTransition
    event.triggeringGeofences.forEach { triggeringGeofence ->
      val requestId = triggeringGeofence.requestId
      if (requestId != null) {
        try {
          waypointsRepo.get(requestId.toLong())?.run {
            Timber.d("onWaypointTransition triggered by geofencing event")
            locationProcessor.onWaypointTransition(
                this, event.triggeringLocation, transition, MessageTransition.TRIGGER_CIRCULAR)
          } ?: run { Timber.e("waypoint id $requestId not found for geofence event") }
        } catch (e: NumberFormatException) {
          Timber.e("$requestId from Geofencing event is not a valid request id")
        }
      }
    }
  }

  fun requestOnDemandLocationUpdate(reportType: MessageLocation.ReportType) {
    if (requirementsChecker.hasLocationPermissions()) {
      Timber.d("On demand location request")
      locationProviderClient.singleHighAccuracyLocation(
          callbackForReportType[reportType]!!.value, runThingsOnOtherThreads.getBackgroundLooper())
    } else {
      Timber.e("missing location permission")
    }
  }

  private fun setupLocationRequest(): Result<Unit> {
    Timber.v("setupLocationRequest")
    if (requirementsChecker.hasLocationPermissions()) {
      val settings =
          effectiveLocatorSettings(
              preferences.monitoring,
              preferences.locatorPriority,
              preferences.locatorInterval,
              preferences.locatorDisplacement,
              preferences.moveModeLocatorInterval,
              preferences.locatorBoostedByActivity,
              preferences.activityOnFootLocatorInterval,
              preferences.activityOnFootLocatorDisplacement,
              preferences.locatorBoostedByDriving,
              currentDrivingIntervalSeconds)
      val interval = Duration.ofSeconds(settings.intervalSeconds.toLong())
      val smallestDisplacement = settings.smallestDisplacement?.toFloat()
      val priority = settings.priority
      // Peg the fastest interval to the interval while driving so the GPS can duty-cycle rather
      // than
      // sampling continuously to evaluate displacement.
      val fastestInterval =
          if (preferences.pegLocatorFastestIntervalToInterval ||
              preferences.locatorBoostedByDriving) {
            interval
          } else {
            Duration.ofSeconds(1)
          }
      val request =
          LocationRequest(
              fastestInterval, smallestDisplacement, null, null, priority, interval, null)
      Timber.d("location update request params: $request")
      locationProviderClient.flushLocations()
      locationProviderClient.requestLocationUpdates(
          request,
          callbackForReportType[MessageLocation.ReportType.DEFAULT]!!.value,
          runThingsOnOtherThreads.getBackgroundLooper())
      return Result.success(Unit)
    } else {
      return Result.failure(Exception("Missing location permission"))
    }
  }

  private suspend fun setupGeofences() {
    if (requirementsChecker.hasLocationPermissions()) {

      withContext(ioDispatcher) {
        val waypoints = waypointsRepo.getAll()
        Timber.i("Setting up geofences for ${waypoints.size} waypoints")
        val geofences =
            waypoints
                .map {
                  Geofence(
                      it.id.toString(),
                      Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
                      2.minutes.inWholeMilliseconds.toInt(),
                      it.geofenceLatitude,
                      it.geofenceLongitude,
                      it.geofenceRadius.toFloat(),
                      Geofence.NEVER_EXPIRE,
                      null)
                }
                .toList()
        geofencingClient.removeGeofences(this@BackgroundService)
        if (geofences.isNotEmpty()) {
          val request = GeofencingRequest(Geofence.GEOFENCE_TRANSITION_ENTER, geofences)
          geofencingClient.addGeofences(request, this@BackgroundService)
        }
      }
    } else {
      Timber.e("Missing location permission")
    }
  }

  fun onGeocodingProviderResult(latLng: LatLng, reverseGeocodedText: String) {
    if (latLng == lastLocation?.toLatLng()) {
      Timber.v("New reverse geocode for $latLng: $reverseGeocodedText")

      if (lastLocation != null && preferences.notificationLocation) {
            reverseGeocodedText.ifBlank { lastLocation!!.toLatLng().toDisplayString() }
          } else {
            getString(R.string.app_name)
          }
          .run(ongoingNotification::setTitle)
    } else {
      Timber.v(
          "Ignoring reverse geocode for $latLng: $reverseGeocodedText, because my lastPublished location is ${lastLocation?.toLatLng()}")
    }
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    val propertiesWeCareAbout =
        listOf(
            Preferences::locatorInterval.name,
            Preferences::locatorDisplacement.name,
            Preferences::moveModeLocatorInterval.name,
            Preferences::pegLocatorFastestIntervalToInterval.name,
            Preferences::notificationHigherPriority.name,
            Preferences::locatorPriority.name,
            Preferences::locatorBoostedByActivity.name,
            Preferences::locatorBoostedByDriving.name,
            Preferences::activityOnFootLocatorInterval.name,
            Preferences::activityOnFootLocatorDisplacement.name)
    if (propertiesWeCareAbout
        .stream()
        .filter { o: String -> properties.contains(o) }
        .collect(Collectors.toSet())
        .isNotEmpty()) {
      Timber.d("locator preferences changed. Resetting location request.")
      setupLocationRequest()
    }
    if (properties.contains("monitoring")) {
      setupLocationRequest()
      ongoingNotification.setMonitoringMode(preferences.monitoring)
      activityMonitoringModeController.onMonitoringModeChangedExternally(preferences.monitoring)
    }
    if (properties.contains(Preferences::autoMonitoringByActivity.name)) {
      setupActivityRecognition()
      if (!preferences.autoMonitoringByActivity) {
        activityMonitoringModeController.onFeatureDisabled()
        speedIndicatesDriving = false
        consecutiveDrivingSpeedFixes = 0
      }
    }
    if (properties.intersect(PREFERENCES_THAT_WIPE_QUEUE_AND_CONTACTS).isNotEmpty()) {
      lifecycleScope.launch { contactsRepo.clearAll() }
    }
    if (properties.contains(Preferences::experimentalFeatures.name)) {
      // Handle significant motion sensor based on experimental feature toggle
      if (preferences.experimentalFeatures.contains(
          Preferences.EXPERIMENTAL_FEATURE_REQUEST_LOCATION_ON_SIGNIFICANT_MOTION)) {
        Timber.d("Significant motion feature enabled, setting up sensor")
        significantMotionSensor.setup()
      } else {
        Timber.d("Significant motion feature disabled, cancelling sensor")
        significantMotionSensor.cancel()
      }
    }
  }

  fun reInitializeLocationRequests() {
    Timber.v("Reinitializing location requests")
    runThingsOnOtherThreads.postOnServiceHandlerDelayed(
        {
          if (setupLocationRequest().isSuccess) {
            Timber.d("Getting last location")
            locationProviderClient.getLastLocation()?.run {
              lifecycleScope.launch {
                locationProcessor.onLocationChanged(this@run, MessageLocation.ReportType.DEFAULT)
              }
            }
          }
        },
        0)
  }

  private val localServiceBinder: IBinder = LocalBinder()

  inner class LocalBinder : Binder() {
    val service: BackgroundService
      get() = this@BackgroundService
  }

  override fun onBind(intent: Intent): IBinder {
    super.onBind(intent)
    Timber.d("Background service bound intent=$intent")
    return localServiceBinder
  }

  companion object {
    const val BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG = "backgroundRestrictionNotification"

    // NEW ACTIONS ALSO HAVE TO BE ADDED TO THE SERVICE INTENT FILTER
    const val INTENT_ACTION_SEND_LOCATION_USER = "org.owntracks.android.SEND_LOCATION_USER"
    const val INTENT_ACTION_SEND_EVENT_CIRCULAR = "org.owntracks.android.SEND_EVENT_CIRCULAR"
    const val INTENT_ACTION_ACTIVITY_TRANSITION = "org.owntracks.android.ACTIVITY_TRANSITION"
    // IntArray extra on INTENT_ACTION_ACTIVITY_TRANSITION: one DetectedActivityChange ordinal per
    // detected ENTER transition (on-foot / in-vehicle / still).
    const val EXTRA_ACTIVITY_CHANGE_ORDINALS = "activityChangeOrdinals"
    private const val INTENT_ACTION_CLEAR_NOTIFICATIONS =
        "org.owntracks.android.CLEAR_EVENT_NOTIFICATIONS"
    private const val INTENT_ACTION_CLEAR_CONTACTS = "org.owntracks.android.CLEAR_CONTACTS"
    const val INTENT_ACTION_CHANGE_MONITORING = "org.owntracks.android.CHANGE_MONITORING"
    private const val INTENT_ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val INTENT_ACTION_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    const val UPDATE_CURRENT_INTENT_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
  }

  class LocationCallbackWithReportType(
      private val reportType: MessageLocation.ReportType,
      private val locationProcessor: LocationProcessor,
      private val lifecycleCoroutineScope: LifecycleCoroutineScope,
      private val onLocation: (Location) -> Unit = {}
  ) : LocationCallback {

    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
      Timber.v("Location availability $locationAvailability")
    }

    override fun onLocationResult(locationResult: LocationResult) {
      Timber.d("Location result received: $locationResult")
      onLocation(locationResult.lastLocation)
      onLocationChanged(locationResult.lastLocation, reportType)
    }

    override fun onLocationError() {
      Timber.v("Callback fired with no location received")
    }

    private fun onLocationChanged(location: Location, reportType: MessageLocation.ReportType) {
      Timber.v("backgroundservice location update received: $location, report type $reportType")
      lifecycleCoroutineScope.launch { locationProcessor.onLocationChanged(location, reportType) }
    }

    override fun toString(): String {
      return "Backgroundservice callback[$reportType] "
    }
  }

  class PowerStateLogger(private val applicationContext: Context) {
    private val powerManager =
        applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun logPowerState(action: String) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Timber.d(
            "%snull", "triggeringAction=$action " +
                "isPowerSaveMode=${powerManager.isPowerSaveMode} " +
                "locationPowerSaveMode=${powerManager.locationPowerSaveMode} " +
                "isDeviceIdleMode=${powerManager.isDeviceIdleMode} " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  "isDeviceLightIdleMode=${powerManager.isDeviceLightIdleMode} "
                } else {
                  ""
                } +
                "isInteractive=${powerManager.isInteractive} isIgnoringBatteryOptimizations=${powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)}")
      }
    }
  }
}
