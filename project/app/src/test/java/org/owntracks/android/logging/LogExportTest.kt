package org.owntracks.android.logging

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogExportTest {

  private fun entry(message: String, priority: Int = Log.DEBUG) =
      LogEntry(
          priority = priority,
          tag = "SomeClass/someMethod/42",
          message = message,
          threadName = "backgroundHandlerThread",
          time = Date(1785929165000))

  private fun written(entries: List<LogEntry>): ByteArray =
      ByteArrayOutputStream().also { writeEntriesTo(it, entries) }.toByteArray()

  /**
   * The contract that matters: `query()` reports [exportedSizeBytes] via OpenableColumns.SIZE
   * before a single byte is written, so a reader that trusts it truncates the upload if the two
   * ever disagree.
   */
  private fun assertSizeMatchesPayload(entries: List<LogEntry>) {
    assertEquals(written(entries).size.toLong(), exportedSizeBytes(entries))
  }

  @Test
  fun `reported size matches the written payload for a typical buffer`() {
    assertSizeMatchesPayload(
        (1..500).map { entry("Location result received: lat=39.7564529900493,long=-86.155795787") })
  }

  @Test
  fun `reported size matches the written payload when empty`() {
    assertSizeMatchesPayload(emptyList())
  }

  @Test
  fun `reported size matches the written payload for a single entry`() {
    assertSizeMatchesPayload(listOf(entry("only one")))
  }

  /** Byte length diverges from char count here, so a naive length-based size would be wrong. */
  @Test
  fun `reported size matches the written payload for multi-byte characters`() {
    assertSizeMatchesPayload(
        listOf(
            entry("region entered: Café ☕"),
            entry("contact: 日本語のトラッカー"),
            entry("emoji in a tid: 🚗💨")))
  }

  /** Messages already containing newlines must not be miscounted as separators. */
  @Test
  fun `reported size matches the written payload for embedded newlines`() {
    assertSizeMatchesPayload(listOf(entry("line one\nline two\n"), entry("trailing\n")))
  }

  @Test
  fun `entries are separated by a single newline with none trailing`() {
    val text = written(listOf(entry("first"), entry("second"), entry("third")))
        .toString(Charsets.UTF_8)
    assertEquals(2, text.count { it == '\n' })
    assertFalse(text.endsWith("\n"))
    assertEquals(3, text.lines().size)
  }

  @Test
  fun `each written line is the entry's exported form`() {
    val entries = listOf(entry("first"), entry("second", priority = Log.ERROR))
    assertEquals(entries.map { it.toExportedString() }, written(entries).toString(Charsets.UTF_8).lines())
  }
}
