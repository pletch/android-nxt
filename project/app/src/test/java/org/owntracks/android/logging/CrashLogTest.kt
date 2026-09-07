package org.owntracks.android.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val noBackupDir: File
    get() = temporaryFolder.root

  private val crashLog: CrashLog
    get() = CrashLog(noBackupDir.resolve(CrashLog.DIRECTORY_NAME))

  private fun boom(message: String = "boom") = IllegalStateException(message)

  @Test
  fun `a recorded crash names the thread and the exception`() {
    crashLog.record("backgroundHandlerThread", boom("no fix interval"))

    val report = crashLog.pending().single().readText()
    assertTrue(report, report.contains("Thread: backgroundHandlerThread"))
    assertTrue(report, report.contains("java.lang.IllegalStateException: no fix interval"))
    assertTrue(report, report.contains("Stacktrace:"))
  }

  /**
   * The point of the per-file store: a crash loop leaves evidence of the loop, where a single
   * overwritten file left only the last sample.
   */
  @Test
  fun `successive crashes are all retained`() {
    crashLog.record("main", boom("first"), at = 1000)
    crashLog.record("main", boom("second"), at = 2000)

    assertEquals(2, crashLog.pending().size)
    assertTrue(crashLog.pendingText()!!.contains("first"))
    assertTrue(crashLog.pendingText()!!.contains("second"))
  }

  @Test
  fun `crashes within the same millisecond do not overwrite each other`() {
    crashLog.record("main", boom("first"), at = 1000)
    crashLog.record("main", boom("second"), at = 1000)

    assertEquals(2, crashLog.pending().size)
  }

  @Test
  fun `only the newest reports are kept`() {
    (1..CrashLog.MAX_RETAINED + 3).forEach {
      crashLog.record("main", boom("crash $it"), at = it * 1000L)
    }

    val reports = crashLog.pending()
    assertEquals(CrashLog.MAX_RETAINED, reports.size)
    assertTrue(reports.first().readText().contains("crash 4"))
    assertTrue(reports.last().readText().contains("crash ${CrashLog.MAX_RETAINED + 3}"))
  }

  /** Reports are oldest-first so the export reads in the order the crashes happened. */
  @Test
  fun `pending reports are ordered oldest first`() {
    crashLog.record("main", boom("older"), at = 1000)
    crashLog.record("main", boom("newer"), at = 9000)

    assertTrue(crashLog.pending().first().readText().contains("older"))
    assertTrue(crashLog.pending().last().readText().contains("newer"))
  }

  @Test
  fun `nothing is pending before the first crash`() {
    assertEquals(emptyList<File>(), crashLog.pending())
    assertNull(crashLog.pendingText())
  }

  /**
   * Reading a report must not consume it: a background process start replays the crash into a log
   * buffer nobody is watching, and the user still has to be able to find it.
   */
  @Test
  fun `reading pending reports leaves them on disk`() {
    crashLog.record("main", boom())

    crashLog.pendingText()

    assertEquals(1, crashLog.pending().size)
  }

  @Test
  fun `acknowledging discards every report`() {
    crashLog.record("main", boom("first"), at = 1000)
    crashLog.record("main", boom("second"), at = 2000)

    crashLog.acknowledge()

    assertEquals(emptyList<File>(), crashLog.pending())
    assertNull(crashLog.pendingText())
  }

  @Test
  fun `a system exit report carries the trace the system kept`() {
    crashLog.recordSystemExit(
        at = 1000,
        description = "user request after error: Input dispatching timed out",
        reason = 6,
        status = 0,
        trace = "main (state=BLOCKED)\n  at org.owntracks.android.Boom.hang".byteInputStream(),
    )

    val report = crashLog.pending().single().readText()
    assertTrue(report, report.contains("Input dispatching timed out"))
    assertTrue(report, report.contains("main (state=BLOCKED)"))
  }

  @Test
  fun `a system exit with no retained trace still produces a report`() {
    crashLog.recordSystemExit(
        at = 1000,
        description = "native crash",
        reason = 5,
        status = 0,
        trace = null,
    )

    assertTrue(crashLog.pending().single().readText().contains("(no trace retained by the system)"))
  }

  /** An ANR dumps every thread; the whole thing would swamp the export it's attached to. */
  @Test
  fun `an oversized trace is truncated`() {
    val trace = "x".repeat(CrashLog.MAX_TRACE_CHARS * 2)

    crashLog.recordSystemExit(1000, "anr", 6, 0, trace.byteInputStream())

    val report = crashLog.pending().single().readText()
    assertTrue(report.contains("(trace truncated at ${CrashLog.MAX_TRACE_CHARS} characters)"))
    assertTrue(report.length < trace.length)
  }

  @Test
  fun `a trace that exactly fills the cap is not marked truncated`() {
    val trace = "x".repeat(CrashLog.MAX_TRACE_CHARS)

    crashLog.recordSystemExit(1000, "anr", 6, 0, trace.byteInputStream())

    val report = crashLog.pending().single().readText()
    assertFalse(report.contains("truncated"))
    assertTrue(report.contains(trace))
  }

  /**
   * The watermark is what stops an old ANR being re-reported on every one of the many background
   * process starts that can see it, and it has to outlive the reports being acknowledged.
   */
  @Test
  fun `the exit watermark survives acknowledgement`() {
    crashLog.markExitsImportedUpTo(4000)
    crashLog.recordSystemExit(4000, "anr", 6, 0, null)

    crashLog.acknowledge()

    assertEquals(4000L, crashLog.lastImportedExitTimestamp())
    assertEquals(emptyList<File>(), crashLog.pending())
  }

  @Test
  fun `the exit watermark starts at zero`() {
    assertEquals(0L, crashLog.lastImportedExitTimestamp())
  }

  @Test
  fun `a crash written by an older version is imported`() {
    val legacy =
        noBackupDir.resolve("crash.log").apply { writeText("Thread: main\nException: old") }

    crashLog.importLegacy()

    assertFalse(legacy.exists())
    assertTrue(crashLog.pending().single().readText().contains("Exception: old"))
  }

  @Test
  fun `importing does nothing when there is no legacy crash`() {
    crashLog.importLegacy()

    assertEquals(emptyList<File>(), crashLog.pending())
  }
}
