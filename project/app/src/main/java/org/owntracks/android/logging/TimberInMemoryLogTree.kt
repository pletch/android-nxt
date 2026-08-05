package org.owntracks.android.logging

import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.owntracks.android.BuildConfig
import timber.log.Timber
import timber.log.Timber.DebugTree

class TimberInMemoryLogTree(private val debugBuild: Boolean) : DebugTree() {
  companion object {
    const val LOG_PREFIX = "FARTSHOES"
    // A publish cycle costs ~13 entries, so at a driving locator interval (6-9s) the buffer fills
    // at ~70 entries/minute: 500 held barely 7 minutes, and a typical commute had already rolled
    // out of the buffer by the time the log was exported. 5000 covers ~75 minutes of driving for
    // ~1.5MB of retained heap (~300 bytes/entry, measured).
    private const val MAX_LOG_ENTRIES = 5000
  }

  private val mutableLogFlow =
      MutableSharedFlow<LogEntry>(
          replay = MAX_LOG_ENTRIES, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val liveLogs: SharedFlow<LogEntry> = mutableLogFlow

  override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
    // In release builds, suppress DEBUG and VERBOSE from Logcat to avoid leaking PII
    // (e.g. coordinates) to the system log (CWE-532). The in-memory buffer is unaffected.
    if (BuildConfig.DEBUG || priority >= Log.INFO) {
      val prefix = if (BuildConfig.DEBUG) LOG_PREFIX else ""
      super.log(priority, "${prefix}_$tag", message, t)
    }
    // Verbose messages are excluded from the buffer; DEBUG and above are kept for the
    // in-app log viewer.
    if (priority >= Log.DEBUG) {
      LogEntry(priority, tag, message, Thread.currentThread().name).run {
        mutableLogFlow.tryEmit(this)
      }
    }
  }

  override fun createStackElementTag(element: StackTraceElement): String? {
    return if (debugBuild) {
      "${super.createStackElementTag(element)}/${element.methodName}/${element.lineNumber}"
    } else {
      super.createStackElementTag(element)
    }
  }

  fun logLines(): List<LogEntry> {
    return mutableLogFlow.replayCache
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun clear() {
    mutableLogFlow.resetReplayCache()
    Timber.i("Logs cleared")
  }
}
