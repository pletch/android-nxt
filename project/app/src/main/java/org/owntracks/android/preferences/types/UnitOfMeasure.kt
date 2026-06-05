package org.owntracks.android.preferences.types

import kotlinx.serialization.Serializable

/**
 * Which system of units to display speeds, altitudes and distances in. [Default] follows the device
 * locale; [Metric] and [Imperial] force a choice.
 */
@Serializable
enum class UnitOfMeasure {
  Default,
  Metric,
  Imperial;

  companion object {
    @JvmStatic
    @FromConfiguration
    fun getByValue(value: String): UnitOfMeasure =
        entries.firstOrNull { it.name.equals(value, true) } ?: Default
  }
}
