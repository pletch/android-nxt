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

/** Byte length of the payload [writeEntriesTo] produces for [entries]. */
internal fun exportedSizeBytes(entries: List<LogEntry>): Long =
    entries.foldIndexed(0L) { index, total, entry ->
      total +
          (if (index > 0) LOG_ENTRY_SEPARATOR.toByteArray().size else 0) +
          entry.toExportedString().toByteArray().size
    }

/**
 * Writes [entries] to [outputStream] separated by [LOG_ENTRY_SEPARATOR], with no trailing
 * separator. Flushes but does not close: the caller owns the stream's lifetime, and for the export
 * pipe it's the close that signals EOF to the reader.
 */
internal fun writeEntriesTo(outputStream: OutputStream, entries: List<LogEntry>) {
  val writer = outputStream.bufferedWriter()
  entries.forEachIndexed { index, entry ->
    if (index > 0) writer.write(LOG_ENTRY_SEPARATOR)
    writer.write(entry.toExportedString())
  }
  writer.flush()
}
