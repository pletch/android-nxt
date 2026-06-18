# OwnTracks for Android (unofficial fork)

> [!IMPORTANT]
> **This is an unofficial fork of [owntracks/android](https://github.com/owntracks/android).**
> It is not built, signed, or distributed by the OwnTracks project, and it is not available on the Google Play Store, F-Droid, or IzzyOnDroid. It tracks upstream but carries a number of additional, unreleased changes (see [Changes in this fork](#changes-in-this-fork) below). For the official app, please use [owntracks/android](https://github.com/owntracks/android).
>
> The default branch of this repository is [`integration-260`](https://github.com/pletch/android-nxt/tree/integration-260), which integrates all of the changes listed below.

This is the OwnTracks Android app. See the upstream [booklet](http://owntracks.org/booklet/features/android/) for details on how to get started with OwnTracks, as well some details about behaviour specific to the Android app.

## Changes in this fork

These are the individual changes carried on top of upstream `master`. Each is developed on its own feature branch and integrated into [`integration-260`](https://github.com/pletch/android-nxt/tree/integration-260).

### Messaging / MQTT
* **Migrate the MQTT client from Eclipse Paho to HiveMQ.** Replaces the legacy Paho client with the HiveMQ MQTT client (Netty/RxJava under the hood).
* **Own the MQTT reconnect logic** instead of relying on HiveMQ auto-reconnect, and **recover — rather than just disconnect — when the active network is lost.**
* **Reliability hardening:** bound the publish, disconnect, and subscribe awaits so a stuck operation can't stall the outbound loop, and time out publishes at the future level rather than via `withTimeout`.

### Activity-triggered monitoring
* **Activity-triggered adaptive monitoring** (upstream PR [#877](https://github.com/owntracks/android/pull/877)): adjust monitoring based on detected motion activity.
* **Speed-tiered driving boost** that raises sampling while driving, with an **optional entry dwell to suppress flapping** at the start of an activity, and **tightened driving sampling bands (~10%)** for finer tracks.

### Map & contacts
* **Badge contact markers with inferred activity** (walking / driving), **detect and badge cycling as a distinct activity**, and **seat the badge on the marker edge** so it clears the contact initials.
* **Consume and emit the OwnTracks `motionactivities` field.**
* **Show the reported time (`created_at`) on the contact details sheet.**
* **Locale-aware units** for contact speed, altitude, and distance.
* **Optionally hide markers for inactive (stale) contacts,** and **keep friend markers in sync with contact state.**

### Location power
* **Dial back the live blue-dot locator to balanced power,** and **release the blue-dot location request when the map is backgrounded** to save battery.

### Message parsing
* **More lenient parsing:** accept fractional epoch seconds in `tst`/`created_at`, tolerate a fractional `batt` in location messages, and tolerate a fractional/unknown battery status (`bs`).

![GitHub License](https://img.shields.io/github/license/pletch/android-nxt) ![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/pletch/android-nxt/trunk-build.yaml?branch=integration-260)



## Build flavours

There are two build flavours for OwnTracks:

* `gms`: This is the build published to the [Google Play Store](https://play.google.com/store/apps/details?id=org.owntracks.android). It links to and requires the Google Play Services libraries for location (using the Google location APIs), as well as the Google Maps SDK for drawing the main map.
* `oss`: This is an "un-Googled" build, which does not require or depend on Google Play Services. It uses the built-in android location capabilities and defaults to [OpenStreetMap](https://www.openstreetmap.org/) for the main map. Available via [F-Droid](https://f-droid.org/packages/org.owntracks.android/).

Both flavours are published as an APK to Github releases.

### Signing keys

* Google Play store-distributed builds are signed with Google's App signing key: `02:FD:16:4A:95:46:17:F0:B7:94:57:97:37:C9:7A:07:B8:31:83:1D:0A:05:90:C3:8D:07:2B:FE:29:01:08:F1`
* APKs attached to Github Releases are signed with our own key: `1F:C4:DE:52:D0:DA:A3:3A:9C:0E:3D:67:21:7A:77:C8:95:B4:62:66:EF:02:0F:AD:0D:48:21:6A:6A:D6:CB:70`
* F-Droid builds are signed with their own key, details at <https://f-droid.org/en/docs/Release_Channels_and_Signing_Keys/>

## Contributing

Pull requests welcome! Please see [CONTRIBUTING.md](https://github.com/owntracks/android/blob/master/CONTRIBUTING.md) for details on how to build the project locally.

If you spot a translation issue or want to help contribute translating the app into other languages, you can visit [POEditor](https://poeditor.com/join/project?hash=xe6LPP0Jnx) and help out.
