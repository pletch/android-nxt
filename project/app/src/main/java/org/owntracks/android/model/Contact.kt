@file:OptIn(ExperimentalTime::class)

package org.owntracks.android.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.owntracks.android.BR
import org.owntracks.android.geocoding.GeocoderProvider
import org.owntracks.android.location.LatLng
import org.owntracks.android.location.toLatLng
import org.owntracks.android.model.messages.MessageCard
import org.owntracks.android.model.messages.MessageLocation
import org.owntracks.android.model.messages.MessageTransition
import timber.log.Timber

class Contact(id: String) : BaseObservable() {
  @get:Bindable val id: String = id.ifEmpty { "NOID" }

  @get:Bindable
  val displayName: String
    get() = name?.ifEmpty { trackerId } ?: trackerId

  // Set from a MessageCard
  private var name: String? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.displayName)
    }

  @get:Bindable
  var latLng: LatLng? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.latLng)
    }

  @get:Bindable
  var locationTimestamp: Long = 0
    private set(value) {
      field = value
      notifyPropertyChanged(BR.locationTimestamp)
    }

  // When the message was created/sent by the contact (epoch seconds), vs locationTimestamp which is
  // when the fix was measured. These differ when a device re-sends a stale fix (e.g. a periodic
  // ping while stationary).
  @get:Bindable
  var createdAtTimestamp: Long = 0
    private set(value) {
      field = value
      notifyPropertyChanged(BR.createdAtTimestamp)
    }

  @get:Bindable
  var face: String? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.face)
    }

  fun setMessageCard(messageCard: MessageCard) {
    name = messageCard.name
    face = messageCard.face
  }

  fun setLocationFromMessageLocation(messageLocation: MessageLocation): Boolean {
    if (locationTimestamp > messageLocation.timestamp) return false
    Timber.v("update contact:$id, tst:${messageLocation.timestamp}", id, messageLocation.timestamp)
    locationTimestamp = messageLocation.timestamp
    createdAtTimestamp = messageLocation.createdAt.epochSeconds
    if (latLng != messageLocation.toLatLng()) {
      Timber.v("Contact ${this.id} has moved to $latLng")
      latLng = messageLocation.toLatLng()
    }
    trackerId = messageLocation.trackerId?.take(2) ?: messageLocation.topic.takeLast(2)
    locationAccuracy = messageLocation.accuracy
    altitude = messageLocation.altitude
    velocity = messageLocation.velocity
    battery = messageLocation.battery
    motionActivities = messageLocation.motionActivities
    return true
  }

  fun setLocationFromMessageTransition(messageLocation: MessageTransition): Boolean {
    if (locationAccuracy > messageLocation.timestamp) return false
    locationTimestamp = messageLocation.timestamp
    if (latLng != messageLocation.toLatLng()) {
      Timber.v("Contact ${this.id} has moved to $latLng")
      latLng = messageLocation.toLatLng()
    }
    locationAccuracy = messageLocation.accuracy
    return true
  }

  @get:Bindable
  var locationAccuracy: Int = 0
    private set(value) {
      field = value
      notifyPropertyChanged(BR.locationAccuracy)
    }

  @get:Bindable
  var altitude: Int = 0
    private set(value) {
      field = value
      notifyPropertyChanged(BR.altitude)
    }

  @get:Bindable
  var velocity: Int = 0
    private set(value) {
      field = value
      notifyPropertyChanged(BR.velocity)
    }

  // The contact's reported motionactivities (OwnTracks field), preferred over velocity for the
  // activity badge when present.
  @get:Bindable
  var motionActivities: List<String>? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.motionActivities)
    }

  @get:Bindable
  var battery: Int? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.battery)
    }

  @get:Bindable
  var geocodedLocation: String? = null
    private set(value) {
      field = value
      notifyPropertyChanged(BR.geocodedLocation)
    }

  @get:Bindable
  var trackerId: String = id.takeLast(2)
    private set(value) {
      field = value
      notifyPropertyChanged(BR.trackerId)
      notifyPropertyChanged(BR.displayName)
    }

  fun geocodeLocation(geocoderProvider: GeocoderProvider, scope: CoroutineScope) {
    latLng?.let { scope.launch { geocodedLocation = geocoderProvider.resolve(it) } }
  }

  override fun toString(): String {
    return "Contact $id ($name)"
  }
}
