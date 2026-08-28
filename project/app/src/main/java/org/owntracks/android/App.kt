package org.owntracks.android

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StrictMode
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.databinding.DataBindingUtil
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.EarlyEntryPoint
import dagger.hilt.android.EarlyEntryPoints
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import java.security.Security
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant
import org.conscrypt.Conscrypt
import org.owntracks.android.di.CustomBindingComponentBuilder
import org.owntracks.android.di.CustomBindingEntryPoint
import org.owntracks.android.geocoding.GeocoderProvider
import org.owntracks.android.logging.CrashLog
import org.owntracks.android.logging.TimberInMemoryLogTree
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.AppTheme
import org.owntracks.android.services.MessageProcessor
import org.owntracks.android.support.RunThingsOnOtherThreads
import org.owntracks.android.support.receiver.StartBackgroundServiceReceiver
import org.owntracks.android.ui.status.logs.LogViewerActivity
import timber.log.Timber
import kotlin.time.ExperimentalTime

@HiltAndroidApp
class App : BaseApp() {
  override fun onCreate() {
    super.onCreate()
    StartBackgroundServiceReceiver.enable(this)
  }
}

@OptIn(ExperimentalTime::class)
open class BaseApp :
    Application(),
    Configuration.Provider,
    Preferences.OnPreferenceChangeListener,
    ComponentCallbacks2 {

  @EarlyEntryPoint
  @InstallIn(SingletonComponent::class)
  internal interface ApplicationEntrypoint {
    fun preferences(): Preferences

    fun workerFactory(): HiltWorkerFactory

    fun bindingComponentProvider(): Provider<CustomBindingComponentBuilder>

    fun messageProcessor(): MessageProcessor

    fun notificationManager(): NotificationManagerCompat

    fun runThingsOnOtherThreads(): RunThingsOnOtherThreads
  }

  private val preferences by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).preferences()
  }

  private val workerFactory: HiltWorkerFactory by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).workerFactory()
  }

  private val bindingComponentProvider: Provider<CustomBindingComponentBuilder> by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).bindingComponentProvider()
  }

  private val notificationManager: NotificationManagerCompat by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).notificationManager()
  }

  private val runThingsOnOtherThreads: RunThingsOnOtherThreads by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).runThingsOnOtherThreads()
  }

  private val crashLog by lazy { CrashLog.forContext(this) }

  private val _workManagerFailedToInitialize = MutableStateFlow(false)
  val workManagerFailedToInitialize: StateFlow<Boolean> = _workManagerFailedToInitialize

  override fun onCreate() {
    // Make sure we use Conscrypt for advanced TLS features on all devices.
    Security.insertProviderAt(Conscrypt.newProviderBuilder().provideTrustManager(true).build(), 1)

    super.onCreate()

    setGlobalExceptionHandler()

    val dataBindingComponent = bindingComponentProvider.get().build()
    val dataBindingEntryPoint =
        EntryPoints.get(dataBindingComponent, CustomBindingEntryPoint::class.java)

    DataBindingUtil.setDefaultComponent(dataBindingEntryPoint)

    /*
    Deliberately does not cancel any scheduled work here. WorkManager starts the process itself to
    run a job, which means this runs *before* that job does: cancelling here threw away the pending
    MQTT reconnect, and the periodic connection watchdog along with it, every time the app was woken
    up to recover a dead connection. Since the watchdog is only re-scheduled when the endpoint is
    activated, and nothing activates it in a process started for a worker, a single process death
    was enough to lose it permanently. Stale work is handled where it is scheduled instead: the
    reconnect and the watchdog are unique work, and the location ping is cancelled by tag before it
    is re-enqueued.
     */
    Timber.plant(TimberInMemoryLogTree(BuildConfig.DEBUG))

    if (BuildConfig.DEBUG) {

      Timber.e("StrictMode enabled in DEBUG build")
      StrictMode.setThreadPolicy(
          StrictMode.ThreadPolicy.Builder()
              .detectNetwork()
              .penaltyFlashScreen()
              .penaltyDialog()
              .build())
      StrictMode.setVmPolicy(
          StrictMode.VmPolicy.Builder()
              .detectLeakedSqlLiteObjects()
              .detectLeakedClosableObjects()
              .detectFileUriExposure()
              .penaltyLog()
              .build())
    }

    preferences.registerOnPreferenceChangedListener(this)

    setThemeFromPreferences()

    // Notifications can be sent from multiple places, so let's make sure we've got the channels in
    // place
    createNotificationChannels()

    logHistoricalProcessExits()
    reportPendingCrashes()
  }

  /**
   * Everything the system killed us for, not just the most recent: a crash loop, a run of ANRs or a
   * low-memory kill is a pattern across entries, and these are the only trace of the deaths the
   * uncaught exception handler never sees (native crashes, ANRs, and being killed outright).
   */
  private fun logHistoricalProcessExits() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return
    }
    val abnormalReasons =
        setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY)
    val exits =
        (this.getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(this.packageName, 0, 10)
    exits.forEach {
      val message =
          "Historical process exited at ${Instant.fromEpochMilliseconds(it.timestamp)}. reason: ${it.description}, status: ${it.status}, reason: ${it.reason}"
      if (it.reason in abnormalReasons) {
        Timber.e(message)
      } else {
        Timber.i(message)
      }
    }
    importSystemExitTraces(exits)
  }

  /**
   * Turns the ANRs and native crashes in [exits] into crash reports, carrying the trace the system
   * kept for them: neither ever reaches the uncaught exception handler, so this is the only place
   * their stacks can come from. REASON_CRASH is left out — the handler already recorded that one,
   * with the exception attached.
   *
   * Only exits newer than the stored watermark are imported, and the watermark advances past every
   * exit seen, so an old ANR isn't re-reported on each of the many process starts that see it.
   */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun importSystemExitTraces(exits: List<ApplicationExitInfo>) {
    val tracedReasons =
        setOf(ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH_NATIVE)
    val importedUpTo = crashLog.lastImportedExitTimestamp()
    exits
        .filter { it.reason in tracedReasons && it.timestamp > importedUpTo }
        .sortedBy { it.timestamp }
        .forEach {
          runCatching {
                crashLog.recordSystemExit(
                    it.timestamp,
                    it.description,
                    it.reason,
                    it.status,
                    runCatching { it.traceInputStream }.getOrNull())
              }
              .onFailure { failure -> Timber.e(failure, "Unable to record system exit trace") }
        }
    exits.maxOfOrNull { it.timestamp }?.let { crashLog.markExitsImportedUpTo(it) }
  }

  /**
   * Replays any crash reports into the log and raises a notification for them. The reports are left
   * on disk: this runs on every process start, including the background wakeups that nobody is
   * watching, and consuming the report there would leave nothing for the user to find. [CrashLog]
   * is cleared when the log viewer's clear action acknowledges them.
   */
  private fun reportPendingCrashes() {
    crashLog.importLegacy()
    val pending = crashLog.pending()
    if (pending.isEmpty()) {
      return
    }
    pending.forEach { file ->
      runCatching { file.readText() }
          .onSuccess { Timber.e("Previous crash (${file.name}): $it") }
          .onFailure { Timber.e(it, "Unable to read crash report ${file.name}") }
    }
    notifyOfCrashes(pending.size)
  }

  private fun notifyOfCrashes(count: Int) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      return
    }
    val text = resources.getQuantityString(R.plurals.crashNotificationText, count, count)
    NotificationCompat.Builder(this, GeocoderProvider.ERROR_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.crashNotificationTitle))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(R.drawable.ic_owntracks_80)
        .setAutoCancel(true)
        // Re-posted on every process start until the reports are acknowledged, so alert once and
        // then update the existing notification silently.
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, LogViewerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
        .run { notificationManager.notify(NOTIFICATION_TAG_CRASH, NOTIFICATION_ID_CRASH, this) }
  }

  private fun setGlobalExceptionHandler() {
    val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        crashLog.record(t.name, e)
      } catch (e: Exception) {
        Timber.e(e, "Error writing crash log")
      }
      currentHandler?.uncaughtException(t, e)
    }
  }

  @MainThread
  private fun setThemeFromPreferences() {
    when (preferences.theme) {
      AppTheme.Auto -> AppCompatDelegate.setDefaultNightMode(Preferences.SYSTEM_NIGHT_AUTO_MODE)
      AppTheme.Dark -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
      AppTheme.Light -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
  }

  private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // Importance min will show normal priority notification for foreground service. See
      // https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_MIN
      // User has to actively configure this in the notification channel settings.
      val ongoingNotificationChannelName =
          if (getString(R.string.notificationChannelOngoing).trim().isNotEmpty()) {
            getString(R.string.notificationChannelOngoing)
          } else {
            "Ongoing"
          }
      NotificationChannel(
              NOTIFICATION_CHANNEL_ONGOING,
              ongoingNotificationChannelName,
              NotificationManager.IMPORTANCE_LOW)
          .apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = getString(R.string.notificationChannelOngoingDescription)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
          }
          .run { notificationManager.createNotificationChannel(this) }

      val eventsNotificationChannelName =
          if (getString(R.string.events).trim().isNotEmpty()) {
            getString(R.string.events)
          } else {
            "Events"
          }
      NotificationChannel(
              NOTIFICATION_CHANNEL_EVENTS,
              eventsNotificationChannelName,
              NotificationManager.IMPORTANCE_HIGH)
          .apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = getString(R.string.notificationChannelEventsDescription)
            enableLights(false)
            enableVibration(false)
            setShowBadge(true)
            setSound(null, null)
          }
          .run { notificationManager.createNotificationChannel(this) }

      val errorNotificationChannelName =
          if (getString(R.string.notificationChannelErrors).trim().isNotEmpty()) {
            getString(R.string.notificationChannelErrors)
          } else {
            "Errors"
          }
      NotificationChannel(
              GeocoderProvider.ERROR_NOTIFICATION_CHANNEL_ID,
              errorNotificationChannelName,
              NotificationManager.IMPORTANCE_LOW)
          .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
          .run { notificationManager.createNotificationChannel(this) }
    }
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::theme.name)) {
      Timber.d("Theme changed. Setting theme to ${preferences.theme}")
      // Can only call setThemeFromPreferences on the main thread
      runThingsOnOtherThreads.postOnMainHandlerDelayed(::setThemeFromPreferences, 0)
    }
    Timber.v("Idling preferenceSetIdlingResource because of $properties")
  }

  override fun onTrimMemory(level: Int) {
    Timber.w(
        "onTrimMemory notified ${getAvailableMemory().run { "isLowMemory: $lowMemory availMem: ${android.text.format.Formatter.formatShortFileSize(applicationContext,availMem)}, threshold: ${android.text.format.Formatter.formatShortFileSize(applicationContext,threshold)} totalMemory: ${android.text.format.Formatter.formatShortFileSize(applicationContext,totalMem)} " }}")
    super.onTrimMemory(level)
  }

  override fun onLowMemory() {
    Timber.w(
        "onLowMemory notified ${getAvailableMemory().run { "isLowMemory: $lowMemory availMem: ${android.text.format.Formatter.formatShortFileSize(applicationContext,availMem)}, threshold: ${android.text.format.Formatter.formatShortFileSize(applicationContext,threshold)} totalMemory: ${android.text.format.Formatter.formatShortFileSize(applicationContext,totalMem)} " }}")
    super.onLowMemory()
  }

  private fun getAvailableMemory(): ActivityManager.MemoryInfo {
    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().also { memoryInfo ->
      activityManager.getMemoryInfo(memoryInfo)
    }
  }

  companion object {
    const val NOTIFICATION_CHANNEL_ONGOING = "O"
    const val NOTIFICATION_CHANNEL_EVENTS = "E"
    const val NOTIFICATION_ID_ONGOING = 1
    const val NOTIFICATION_ID_EVENT_GROUP = 2
    const val NOTIFICATION_ID_CRASH = 3
    const val NOTIFICATION_TAG_CRASH = "Crash"
    const val NOTIFICATION_GROUP_EVENTS = "events"
  }

  override val workManagerConfiguration: Configuration
    get() =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setInitializationExceptionHandler { throwable ->
              Timber.e(throwable, "Exception thrown when initializing WorkManager")
              _workManagerFailedToInitialize.value = true
            }
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
