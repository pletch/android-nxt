package org.owntracks.android.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.owntracks.android.BuildConfig

/**
 * On-disk record of uncaught exceptions, surviving the process death that produced them.
 *
 * Crashes are kept as one file each rather than a single overwritten `crash.log`, and are not
 * deleted when they're read: the process is routinely restarted in the background by location
 * updates, so whichever start happens to run first would otherwise consume the only copy of the
 * crash and drop it into a log buffer that a driving locator interval rolls over within the hour.
 * Reports stay on disk — replayed into the log and attached to every export — until the user
 * explicitly clears the log, which is the acknowledgement that they've been seen.
 */
class CrashLog(private val directory: File) {

  companion object {
    internal const val DIRECTORY_NAME = "crashes"

    /** Enough to show a crash loop's shape without letting a fast loop fill the data dir. */
    internal const val MAX_RETAINED = 5

    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".log"
    private const val LEGACY_FILE_NAME = "crash.log"

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun forContext(context: Context): CrashLog =
        CrashLog(context.applicationContext.noBackupFilesDir.resolve(DIRECTORY_NAME))
  }

  /**
   * Writes a report for [throwable]. Called from the default uncaught exception handler, on a
   * thread that is already dying: it must not throw, and it must not depend on anything the crash
   * may have left broken.
   */
  fun record(threadName: String, throwable: Throwable, at: Long = System.currentTimeMillis()) {
    directory.mkdirs()
    fileFor(at)
        .writeText(
            """
        |Crashed at: ${timestampFormat.format(Date(at))}
        |Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        |Thread: $threadName
        |Exception: ${throwable.javaClass.name}: ${throwable.message}
        |Stacktrace:
        |${throwable.stackTraceToString()}
        """
                .trimMargin())
    prune()
  }

  /** Unacknowledged crash reports, oldest first. */
  fun pending(): List<File> =
      directory
          .listFiles { file -> file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }
          ?.sortedBy(File::getName)
          .orEmpty()

  /**
   * The pending reports as one block of text, or null if there are none. Suitable for prepending to
   * an exported log.
   */
  fun pendingText(): String? =
      pending()
          .mapNotNull { file ->
            runCatching { "=== Crash report ${file.name} ===\n${file.readText()}" }.getOrNull()
          }
          .takeIf { it.isNotEmpty() }
          ?.joinToString("\n")

  /** Discards every pending report. */
  fun acknowledge() {
    pending().forEach { it.delete() }
  }

  /**
   * Moves a report written by a pre-[CrashLog] version of the app into the directory, so an upgrade
   * doesn't silently swallow the crash that prompted it.
   */
  fun importLegacy(legacy: File = directory.parentFile!!.resolve(LEGACY_FILE_NAME)) {
    if (!legacy.exists()) return
    directory.mkdirs()
    if (!legacy.renameTo(fileFor(legacy.lastModified()))) {
      legacy.delete()
    }
  }

  /** A file named for [at], with a uniquifier for the crash loop that fits inside a millisecond. */
  private fun fileFor(at: Long): File {
    val candidate = directory.resolve("$FILE_PREFIX$at$FILE_SUFFIX")
    if (!candidate.exists()) return candidate
    return generateSequence(1) { it + 1 }
        .map { directory.resolve("$FILE_PREFIX$at-$it$FILE_SUFFIX") }
        .first { !it.exists() }
  }

  private fun prune() {
    pending().dropLast(MAX_RETAINED).forEach { it.delete() }
  }
}
