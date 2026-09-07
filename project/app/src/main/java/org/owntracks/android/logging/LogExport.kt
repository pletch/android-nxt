package org.owntracks.android.logging

import java.io.OutputStream

/**
 * Serialisation of the in-memory log buffer for export.
 *
 * Both functions stream, formatting one entry at a time: at the buffer's
 * [TimberInMemoryLogTree.MAX_LOG_ENTRIES] the materialised payload runs to several MB, and joining
 * it into a single String (then copying that to a ByteArray) allocated it in a burst — in a process
 * whose foreground service needs to survive memory pressure.
 *
 * [exportedSizeBytes] and [writeEntriesTo] must agree exactly: the size is reported to the reader
 * via `OpenableColumns.SIZE` before any bytes are written, and a reader that trusts it can truncate
 * the upload if it disagrees. LogExportTest pins that equivalence.
 */
internal const val LOG_ENTRY_SEPARATOR = "\n"

/** Byte length of the payload [writeEntriesTo] produces for [preamble] and [entries]. */
internal fun exportedSizeBytes(entries: List<LogEntry>, preamble: String = ""): Long {
  val separatorSize = LOG_ENTRY_SEPARATOR.toByteArray().size
  var total = preamble.toByteArray().size.toLong()
  var anythingWritten = preamble.isNotEmpty()
  entries.forEach { entry ->
    if (anythingWritten) total += separatorSize
    total += entry.toExportedString().toByteArray().size
    anythingWritten = true
  }
  return total
}

/**
 * Writes [preamble] (crash reports, when there are any) followed by [entries] to [outputStream],
 * separated by [LOG_ENTRY_SEPARATOR], with no trailing separator. Flushes but does not close: the
 * caller owns the stream's lifetime, and for the export pipe it's the close that signals EOF to the
 * reader.
 */
internal fun writeEntriesTo(
    outputStream: OutputStream,
    entries: List<LogEntry>,
    preamble: String = "",
) {
  val writer = outputStream.bufferedWriter()
  var anythingWritten = preamble.isNotEmpty()
  if (anythingWritten) writer.write(preamble)
  entries.forEach { entry ->
    if (anythingWritten) writer.write(LOG_ENTRY_SEPARATOR)
    writer.write(entry.toExportedString())
    anythingWritten = true
  }
  writer.flush()
}
