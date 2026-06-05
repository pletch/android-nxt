package org.owntracks.android.support

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import kotlin.math.roundToInt
import org.owntracks.android.R
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.UnitOfMeasure

enum class UnitSystem {
  METRIC,
  IMPERIAL
}

/**
 * Formats the contact-detail quantities (speed, altitude, accuracy, distance) for display,
 * converting from the metric values carried in the OwnTracks protocol to the user's chosen
 * [UnitOfMeasure]. The `format*` methods are called directly from the map layout's data binding, so
 * they take a [Context] and resolve the preference themselves.
 */
object UnitFormatter {
  private const val KM_PER_MILE = 1.609344
  private const val METERS_PER_FOOT = 0.3048
  private const val METERS_PER_MILE = 1609.344

  /** Resolves the effective system, mapping [UnitOfMeasure.Default] to the locale's convention. */
  fun resolveSystem(units: UnitOfMeasure, locale: Locale): UnitSystem =
      when (units) {
        UnitOfMeasure.Metric -> UnitSystem.METRIC
        UnitOfMeasure.Imperial -> UnitSystem.IMPERIAL
        UnitOfMeasure.Default ->
            if (LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) ==
                LocaleData.MeasurementSystem.SI) {
              UnitSystem.METRIC
            } else {
              UnitSystem.IMPERIAL
            }
      }

  fun kmhToMph(kmh: Int): Int = (kmh / KM_PER_MILE).roundToInt()

  fun metersToFeet(meters: Int): Int = (meters / METERS_PER_FOOT).roundToInt()

  @JvmStatic
  fun formatSpeed(context: Context, kmh: Int): String =
      when (systemFor(context)) {
        UnitSystem.METRIC -> context.getString(R.string.unitsSpeedMetric, kmh)
        UnitSystem.IMPERIAL -> context.getString(R.string.unitsSpeedImperial, kmhToMph(kmh))
      }

  /** Used for both altitude and accuracy (both are a length in metres). */
  @JvmStatic
  fun formatLength(context: Context, meters: Int): String =
      when (systemFor(context)) {
        UnitSystem.METRIC -> context.getString(R.string.unitsLengthMetric, meters)
        UnitSystem.IMPERIAL -> context.getString(R.string.unitsLengthImperial, metersToFeet(meters))
      }

  @JvmStatic
  fun formatDistance(context: Context, meters: Float): String =
      when (systemFor(context)) {
        UnitSystem.METRIC ->
            if (meters >= 1000f) {
              context.getString(R.string.unitsDistanceKilometers, meters / 1000.0)
            } else {
              context.getString(R.string.unitsDistanceMeters, meters.toDouble())
            }
        UnitSystem.IMPERIAL ->
            if (meters >= METERS_PER_MILE) {
              context.getString(R.string.unitsDistanceMiles, meters / METERS_PER_MILE)
            } else {
              context.getString(R.string.unitsDistanceFeet, meters / METERS_PER_FOOT)
            }
      }

  private fun systemFor(context: Context): UnitSystem {
    val preferences =
        EntryPointAccessors.fromApplication(
                context.applicationContext, UnitFormatterEntryPoint::class.java)
            .preferences()
    return resolveSystem(preferences.units, Locale.getDefault())
  }

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  internal interface UnitFormatterEntryPoint {
    fun preferences(): Preferences
  }
}
