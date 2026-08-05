package org.owntracks.android.logging

import android.content.Context
import java.io.File
import java.io.InputStream
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

    /** An ANR dumps every thread in the process; a whole one would swamp the exported log. */
    internal const val MAX_TRACE_CHARS = 64 * 1024

    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".log"
    private const val LEGACY_FILE_NAME = "crash.log"
    private const val WATERMARK_FILE_NAME = "last-imported-exit"

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

  /**
   * Writes a report for a death the uncaught exception handler never saw — an ANR or a native
   * crash — from the record the system kept. [trace] is the system's own dump (every thread's stack
   * for an ANR, the tombstone for a native crash); it's read and closed here, truncated at
   * [MAX_TRACE_CHARS] so that one ANR can't dominate an export.
   */
  fun recordSystemExit(
      at: Long,
      description: String?,
      reason: Int,
      status: Int,
      trace: InputStream?
  ) {
    directory.mkdirs()
    val traceText =
        trace?.let { stream -> runCatching { stream.use { readCapped(it) } }.getOrNull() }
            ?: "(no trace retained by the system)"
    fileFor(at)
        .writeText(
            """
        |Killed at: ${timestampFormat.format(Date(at))}
        |Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        |System exit: $description (reason $reason, status $status)
        |Stacktrace:
        |$traceText
        """
                .trimMargin())
    prune()
  }

  /**
   * The timestamp of the newest system exit already turned into a report. Persisted separately from
   * the reports themselves: [acknowledge] must not make the system's list look new again, or an old
   * ANR would be re-imported and re-notified on every process start forever.
   */
  fun lastImportedExitTimestamp(): Long =
      runCatching { watermarkFile.readText().trim().toLong() }.getOrDefault(0L)

  fun markExitsImportedUpTo(timestamp: Long) {
    runCatching {
      directory.mkdirs()
      watermarkFile.writeText(timestamp.toString())
    }
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

  /**
   * The watermark shares the directory with the reports but not their name pattern, so [pending],
   * [prune] and [acknowledge] leave it alone.
   */
  private val watermarkFile: File
    get() = directory.resolve(WATERMARK_FILE_NAME)

  private fun readCapped(stream: InputStream): String {
    val buffer = CharArray(MAX_TRACE_CHARS)
    val reader = stream.bufferedReader()
    var read = 0
    while (read < MAX_TRACE_CHARS) {
      val count = reader.read(buffer, read, MAX_TRACE_CHARS - read)
      if (count < 0) break
      read += count
    }
    val text = String(buffer, 0, read)
    return if (read == MAX_TRACE_CHARS && reader.read() >= 0) {
      "$text\n(trace truncated at $MAX_TRACE_CHARS characters)"
    } else {
      text
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
