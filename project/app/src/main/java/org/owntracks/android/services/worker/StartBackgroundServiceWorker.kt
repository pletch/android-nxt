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
 * Brings [BackgroundService] up when a wake-up could not start it directly.
 *
 * The location wake-up broadcast arrives with the process dead — that is the entire point of it —
 * and Android refuses a foreground service start from the background unless the app is exempt.
 * Binding is not subject to that restriction, so this worker binds with [Context.BIND_AUTO_CREATE]
 * to create the service and then asks it, over its local binder, to start itself: a service that is
 * only bound would be destroyed again the moment this worker unbinds.
 */
@HiltWorker
class StartBackgroundServiceWorker
@AssistedInject
constructor(@Assisted private val context: Context, @Assisted workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
  override suspend fun doWork(): Result {
    Timber.d("StartBackgroundServiceWorker bringing the service up")
    val mutex = Mutex()
    var backgroundService: BackgroundService? = null
    val serviceConnection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.d("${this@StartBackgroundServiceWorker::class.simpleName} connected to $name")
            backgroundService = (service as BackgroundService.LocalBinder).service
            mutex.unlock()
          }

          override fun onServiceDisconnected(name: ComponentName) {
            Timber.w("${this@StartBackgroundServiceWorker::class.simpleName} disconnected")
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
      Timber.w("Unable to bind to the service to bring it up")
      return Result.failure()
    }
    mutex.withLock {
      backgroundService?.startFromWakeup()
          ?: Timber.w("No service bound, unable to bring the service up")
      context.unbindService(serviceConnection)
      return Result.success()
    }
  }
}
