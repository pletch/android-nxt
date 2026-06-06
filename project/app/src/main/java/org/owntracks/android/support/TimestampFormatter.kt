package org.owntracks.android.support

import android.text.format.DateUtils
import java.text.DateFormat

/**
 * Formats an epoch-seconds timestamp for the contact details sheet. Matches the contact-row time
 * style: a short time if it's today, otherwise a short date+time. Returns "" when unset (0).
 */
object TimestampFormatter {
  @JvmStatic
  fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val millis = epochSeconds * 1000
    return if (DateUtils.isToday(millis)) {
      DateFormat.getTimeInstance(DateFormat.SHORT).format(millis)
    } else {
      DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(millis)
    }
  }
}
