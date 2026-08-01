package org.owntracks.android.services.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.owntracks.android.services.BackgroundService
import timber.log.Timber

/**
 * Delivers an activity change that could not be delivered by starting the service.
 *
 * Activity transitions arrive by broadcast at arbitrary times, including while the app is in the
 * background with [BackgroundService] not running — at which point Android refuses the foreground
 * service start and the change would otherwise be dropped, losing (most importantly) the driving
 * boost for the whole trip. Binding is not subject to the same restriction, so this worker binds
 * with [Context.BIND_AUTO_CREATE] to bring the service up and hands the change over directly.
 */
@HiltWorker
class ActivityChangeWorker
@AssistedInject
constructor(@Assisted private val context: Context, @Assisted workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
  override suspend fun doWork(): Result {
    val ordinals = inputData.getIntArray(INPUT_ACTIVITY_CHANGE_ORDINALS)
    if (ordinals == null || ordinals.isEmpty()) {
      Timber.w("ActivityChangeWorker started without any activity change ordinals")
      return Result.failure()
    }
    Timber.d("ActivityChangeWorker delivering ${ordinals.size} deferred activity change(s)")

    val mutex = Mutex()
    var backgroundService: BackgroundService? = null
    val serviceConnection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.d("${this@ActivityChangeWorker::class.simpleName} has connected to $name")
            backgroundService = (service as BackgroundService.LocalBinder).service
            mutex.unlock()
          }

          override fun onServiceDisconnected(name: ComponentName) {
            Timber.w("${this@ActivityChangeWorker::class.simpleName} has disconnected from $name")
            backgroundService = null
          }
        }

    mutex.lock()
    val bound =
        context.bindService(
            Intent(context, BackgroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE)
    if (!bound) {
      Timber.w("Unable to bind to service to deliver activity change")
      return Result.failure()
    }
    mutex.withLock {
      backgroundService?.onActivityChangeOrdinals(ordinals)
          ?: Timber.w("No service bound, unable to deliver activity change")
      context.unbindService(serviceConnection)
      return Result.success()
    }
  }

  companion object {
    const val INPUT_ACTIVITY_CHANGE_ORDINALS = "activityChangeOrdinals"
  }
}
