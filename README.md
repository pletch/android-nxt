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
* **Migrate the MQTT client from Eclipse Paho to HiveMQ.** Replaces the unmaintained Eclipse Paho v3 client with the HiveMQ MQTT client (Netty/RxJava under the hood), keeping TCP/TLS with client-cert support and adding first-class WebSocket transport (configurable `wsPath`; TLS + WebSocket = `wss`). A key motivation is more reliable WebSocket (`wss`) connections, which were a weak spot of the Paho client.
* **Own the MQTT reconnect logic** instead of relying on HiveMQ auto-reconnect, and **recover — rather than just disconnect — when the active network is lost.**
* **Reliability hardening:** bound the publish, disconnect, and subscribe awaits so a stuck operation can't stall the outbound loop, and time out publishes at the future level rather than via `withTimeout`.

### Activity-triggered monitoring
* **Activity-triggered adaptive monitoring:** an opt-in feature that boosts the locator to high accuracy while on-foot activity (walking/running) is detected, then reverts to your normal settings once stationary — capturing walks/hikes in detail without leaving Move mode on permanently. Uses Google Play Services Activity Recognition, so it is `gms`-flavour only (the `oss` flavour binds a no-op).
* **Speed-tiered driving boost** that raises sampling while driving, with an **optional entry dwell to suppress flapping** at the start of an activity, and **tightened driving sampling bands (~10%)** for finer tracks.

### Map & contacts
* **Badge contact markers with inferred activity** (walking / driving), **detect and badge cycling as a distinct activity**, and **seat the badge on the marker edge** so it clears the contact initials.
* **Consume and emit the OwnTracks `motionactivities` field.**
* **Show the reported time (`created_at`) on the contact details sheet.**
* **Locale-aware units** for contact speed, altitude, and distance.
* **Optionally hide markers for inactive (stale) contacts,** and **keep friend markers in sync with contact state.**

### Location power
* **Fix the map keeping a location request active while backgrounded.** The map's current-location source collected its location flow in a bare lifecycle scope, so the collector kept running while the map fragment was merely stopped (backgrounded), not destroyed. That held the fused-location request open, so it kept firing — and kept the system location indicator (drawn as the map's blue dot) lit — the entire time the app was in the background, until the view was destroyed. The collect is now scoped to the started lifecycle, so the request is released when the map is backgrounded and restored on resume.
* **Dial back the in-use map locator to balanced power.** While the map is open, the current-location request now uses balanced-power accuracy at a 5s/5m cadence instead of high accuracy every 2s, letting the location providers duty-cycle. The foreground tracking service still drives the actual recorded fixes, so the accuracy of logged locations is unaffected.

### Message parsing
* **More lenient parsing:** accept fractional epoch seconds in `tst`/`created_at`, tolerate a fractional `batt` in location messages, and tolerate a fractional/unknown battery status (`bs`).

![GitHub License](https://img.shields.io/github/license/pletch/android-nxt) ![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/pletch/android-nxt/trunk-build.yaml?branch=integration-260)



## Build flavours

There are two build flavours for OwnTracks:

* `gms`: This is the build published to the [Google Play Store](https://play.google.com/store/apps/details?id=org.owntracks.android). It links to and requires the Google Play Services libraries for location (using the Google location APIs), as well as the Google Maps SDK for drawing the main map.
* `oss`: This is an "un-Googled" build, which does not require or depend on Google Play Services. It uses the built-in android location capabilities and defaults to [OpenStreetMap](https://www.openstreetmap.org/) for the main map. Available via [F-Droid](https://f-droid.org/packages/org.owntracks.android/).

Both flavours are published as an APK to Github releases.

### Signing keys

This fork is not distributed through the Google Play Store or F-Droid and is not signed with the official OwnTracks signing keys. Builds you produce from this repository are signed with your own key. The official app's signing keys are documented in the [upstream README](https://github.com/owntracks/android#signing-keys).

## Contributing

Pull requests welcome! Please see [CONTRIBUTING.md](https://github.com/owntracks/android/blob/master/CONTRIBUTING.md) for details on how to build the project locally.

If you spot a translation issue or want to help contribute translating the app into other languages, you can visit [POEditor](https://poeditor.com/join/project?hash=xe6LPP0Jnx) and help out.
