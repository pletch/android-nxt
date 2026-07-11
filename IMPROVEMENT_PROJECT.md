# OwnTracks Android — Personal Improvement Project

A rolling project to improve the OwnTracks Android client for personal use, with
the explicit goal of keeping each change **independently upstreamable**. Every
work item is its own branch off `master` and its own PR, so anything the upstream
project wants can be cherry-picked piecemeal without dragging in the rest.

## Working principles

- **One concern per branch/PR.** Branch off `master`, not off another feature
  branch, unless there is a hard dependency (noted per item below).
- **Preserve public contracts.** Keep `MessageProcessorEndpoint`,
  `ContactsRepo`, preference keys, and message schemas stable unless the item is
  specifically about changing one.
- **Match upstream style.** ktfmt (100-col), upstream comment density, existing
  patterns (e.g. reuse `LenientIntSerializer`-style helpers rather than inventing
  new ones).
- **Gate every item** on: `:app:ktfmtFormat`, both flavors compiling
  (`gms` + `oss`), unit tests, and any relevant `androidTest`. Clean build for
  final verification (the `kaptOssDebugKotlin` databinding flake needs `:app:clean`).
- **Label upstream-worthiness** per item: clean bug fixes and broadly-useful
  features are upstream candidates; opinionated/personal behavior may stay local.
- Requires **JDK 21** (`-Dorg.gradle.java.home=/home/tim/jdk21/jdk-21.0.11+10`).

## Status legend

✅ done · 🔃 in progress · 🔜 queued · 🔎 needs investigation first

---

## 1. ✅ Activity-triggered adaptive monitoring — `activity-triggered-monitoring`

**Done**, committed `4b1e3b16`. Opt-in Google Play Services Activity Recognition
that boosts locator settings on-foot and reverts when stationary
("boosted-Significant", manual-override backoff, precise-location handling).
gms-only (oss gets a no-op). Upstream candidate (behind a preference).

## 2. ✅ HiveMQ MQTT migration — `hivemq-mqtt-migration`

**Migrated and validated on-device (2026-06-07).** Replaces unmaintained Eclipse
Paho with HiveMQ behind the same endpoint contract. Committed; full unit suite
passes. Own-reconnect (HiveMQ auto-reconnect off), all HiveMQ futures bounded by
a force-completing `await(timeout)`, network-loss recovery, and a send-loop
reconnect backstop. **On-device gates all confirmed** (see status below):
credential change, network-flap, network-loss, and Doze/long-idle overnight
recovery. Decision (2026-06): continued the migration (option B) rather than
patch Paho — EOL-library + reconnect-robustness. Foundational for #7.
**Still deferred:** wss-through-caddy-l4 (the original transport goal), and a
soak/observability pass (see "verification" notes the user is running).

## 3. ✅ Lenient timestamp parsing — `fix-lenient-timestamp-parsing`

**Done**, committed `6f2d5c7e` on its own branch off `master` (split out of the
HiveMQ working tree). Friends' location messages with fractional `tst` /
`created_at` (e.g. `1765163519.5254116`) were rejected by strict `Long` decoding,
falling back to `MessageUnknown` ("Unknown message type received") and dropping
those contacts from the map. Fix mirrors the existing `LenientIntSerializer`:
- `InstantEpochSecondsSerializer` decodes via `decodeDouble().toLong()`
  (`MessageWithCreatedAt.kt`).
- New `LenientLongSerializer` on the `tst` field (`MessageLocation.kt`).
- Zero-`tst` validation tolerates fractional values (`MessageLocationDeserializer.kt`).
- Regression test in `ParserTest`.

**Strong upstream candidate** — the Recorder already tolerates fractional `tst`,
so the strict Android parser is arguably the outlier. (Note: the user is also
fixing the offending sender to emit integer `tst`; the lenient receiver fix stays
as defensive interop.) Files are cleanly separable from the MQTT work.

## 4. ✅ Friend map pin goes stale vs Friends view — `fix-stale-contact-marker`

**Done**, committed `e722379d` (off `master`). Two fixes: (a) moved the full
marker reconcile to the top of `repeatOnLifecycle(STARTED)` in `MapFragment.kt`
so markers re-sync on every resume (the core bug, both flavors); (b) fixed a
crash-prone marker insertion index in `OSMMapFragment.kt` where
`filterIsInstance<MyLocationNewOverlay>().indexOfFirst` returned -1 with no
location overlay → `overlays.add(-1, …)` threw and halted updates (oss only).
Left the repo's `SharedFlow` as-is — reconcile-on-resume is the robust minimal
fix. On-device confirmation pending. Confirmed root cause below.

**Root-cause (confirmed in code):** dropped repo events while the map is
backgrounded.
- `MemoryContactsRepo` publishes changes via a `MutableSharedFlow` with **no
  replay and no buffer** (`MemoryContactsRepo.kt`).
- `MapFragment` collects `contactUpdatedEvent` **inside
  `repeatOnLifecycle(STARTED)`** (`MapFragment.kt:106-132`). Below STARTED
  (screen off / backgrounded) there is no subscriber, so `emit` drops the event.
- The one-shot full sync `updateAllMarkers(allContacts.values.toSet())` runs at
  `onCreateView` (`MapFragment.kt:104`), **outside** the `repeatOnLifecycle`
  block, so it does **not** re-run when the map returns to STARTED.
- Net: location updates that arrive while stopped mutate the in-memory `Contact`
  (so the Friends view, which reads `contactsRepo.all` live, is correct) but the
  marker is never refreshed → stale pin. Exactly the reported mismatch.

**Fix direction:** reconcile markers against authoritative state on every
re-entry to STARTED — move/duplicate `updateAllMarkers(contactsRepo.all)` to the
top of the `repeatOnLifecycle(STARTED)` block, and/or back the repo with a
replay/`StateFlow` so (re)subscribers always reconcile current state, then apply
incremental events. Prefer reconcile-on-resume (robust even if events are still
dropped).

**Key files:** `MapFragment.kt`, `MemoryContactsRepo.kt`, `MapViewModel.kt`.
**Risk:** low. **Tests:** a repo test asserting state-after-events; manual
background/foreground on-device check. **Strong upstream candidate.**

## 5. ✅ Friend marker icons with driving/walking overlay — `feature-contact-activity-icons`

**Done**, committed `ea7b3266` (off `master`). Map markers and the bottom-sheet
avatar show a corner badge (walk / car glyph) when a contact's reported velocity
implies walking or driving; stationary/unknown shows no badge.

**As built (chosen scope: velocity-inference only, walking/driving only):**
- `ContactActivity.fromVelocity` → NONE/WALKING/DRIVING from `vel` (km/h):
  `< 3` none, `3–11` walking, `≥ 12` driving (named constants). Unit-tested.
- `ContactImageBindingAdapter` composes the badge onto the cached base
  face/initials bitmap and caches the composite keyed by
  `identityHashCode(base) + activity` — so unchanged states reuse the bitmap
  (no per-update Canvas churn) and a rebuilt base self-invalidates without extra
  plumbing.
- No `Contact`/repo change and no new event needed: `vel` arrives inside location
  messages, so the existing `ContactLocationUpdated` redraw refreshes the badge.
- Walking/driving vector drawables; badge colours in `colors.xml`.

**Why no AR coupling:** the local Activity Recognition (#1) detects *this* device,
not friends, so it can't drive friend badges. The coherent tie-in is publish-side
— see #8. **Upstream candidate** (universal, no protocol change).

## 6. ✅ Locale-aware units of measure (mph/ft) — `feature-locale-units`

**Done**, committed `a22cb28e` (off `master`). A "Units of measure" Map
preference (Automatic / Metric / Imperial) renders contact speed, altitude,
accuracy and distance in the chosen system; "Automatic" follows the device
locale via the ICU measurement system.

**As built:**
- `UnitOfMeasure` string-enum preference (mirrors `ReverseGeocodeProvider`),
  wired through `Preferences`, `DefaultsProvider`, **both** the persistence
  `PreferencesStore` (read+write) *and* the UI `PreferenceDataStoreShim` — a new
  enum preference needs both serialization sites, which the wholesale unit-test
  failure caught the first time round.
- `UnitFormatter` converts the protocol's metric values and formats them; the map
  bottom sheet (`ui_map.xml`) calls it from data binding for the four fields.
- `ListPreference` in `preferences_map.xml` + strings/arrays; conversion +
  resolution unit tests (the `android.icu` locale branch is on-device only).
- **Display only** — published/stored values stay metric, so it's interoperable.
  **Strong upstream candidate.**

## 7. 🔜 Evaluate MQTT 5 features (message expiry for stale updates) — `feature-mqtt5-expiry`

**Goal:** use MQTT 5 message-expiry to avoid processing stale position updates
(notably retained last-positions delivered on reconnect that are already old).

**Design notes:**
- **Depends on #2** — only feasible because HiveMQ supports MQTT 5
  (`useMqttVersion5()`); Paho v3 cannot. The current config calls
  `useMqttVersion3()`.
- Message expiry is set by the **publisher** and enforced by a **MQTT5 broker**
  (including for retained messages), so the win requires: broker MQTT5 support +
  publishers setting an expiry interval. Evaluate the user's broker (caddy-l4
  fronting which broker?) and senders first.
- Scope as an **evaluation spike**: a 5-vs-3 switch is a non-trivial API change
  (`Mqtt5*` connect/publish/auth types, different LWT/properties). Decide whether
  to (a) move the whole client to MQTT5, or (b) stay MQTT3 and instead drop stale
  updates **client-side** by comparing `tst`/`created_at` against a freshness
  window on receive — which needs no broker/sender changes and may capture most
  of the benefit. Compare cost/benefit before committing.
- Note: `Contact.setLocationFromMessageLocation` already rejects out-of-order
  updates (`locationTimestamp > tst`); a freshness window would extend that to
  reject *absolutely* stale ones.

**Key files (if MQTT5):** `MQTTConnectionConfiguration.kt`,
`MQTTMessageProcessorEndpoint.kt`, `EndpointState.kt`. **Risk:** medium–high
(protocol surface). **Upstream:** the client-side freshness-window variant is a
clean upstream candidate; a full MQTT5 switch is a larger architectural ask.

## 8. ✅ Consume + emit the `motionactivities` field — `feature-activity-motionactivities`

**Done (2026-06-07).** Implemented on `feature-activity-motionactivities` (off
`integration-260`, since it needs both #1 and #5). Consume: badge prefers a
contact's `motionactivities` over velocity inference (`ContactActivity.from
MotionActivities`); `Contact`/`MessageLocation` carry it — iOS contacts benefit
immediately. Emit: Android attaches its AR state as `motionactivities` on
extended-data locations (`LocationRepo.currentMotionActivities` ←
`DetectedActivityChange.toMotionActivities`, gms-only). Unit tests for both
mappings + parser round-trip. Both halves are clean upstream candidates (consume
= spec compliance; emit = extending a documented field to Android).

<details><summary>original plan (superseded)</summary>

**Goal:** close the loop between the local Activity Recognition (#1) and the
contact badges (#5) by putting the device's detected activity on the wire, so
other OwnTracks clients can badge *this* user accurately instead of guessing from
velocity — and, symmetrically, prefer a contact's explicit activity over
inference when present.

**Design notes (revised 2026-06 — use the documented `motionactivities` field,
NOT a custom `act`):**
- **Correction:** an earlier version of this item claimed activity "is not part of
  the OwnTracks protocol" and proposed a custom `act`. **Wrong.**
  `motionactivities` is a *documented* OwnTracks location field
  (owntracks.org/booklet/tech/json): a list of strings from {stationary, walking,
  running, automotive, cycling, unknown}. It's **iOS-only on the publish side**
  (CoreMotion); Android emits nothing today. So iOS contacts (jack/matt/emily) are
  **already sending real activity on the wire** — we just weren't reading it.
- **Consume side (do this first — real data exists today):** in #5's activity
  derivation (`ContactActivity` / `ContactImageBindingAdapter`), prefer a contact's
  `motionactivities` over velocity inference. It's an array → pick by priority
  (automotive→driving, cycling/running/walking→on-foot, stationary→none). Carry it
  on `MessageLocation` (deserializer) + `Contact`. Fully spec-compliant, benefits
  every iOS contact immediately, clean upstream PR.
- **Publish side (Android):** emit `motionactivities` from the gms
  `ActivityRecognitionClient`, mapping Google `DetectedActivity` → the spec
  vocabulary (STILL→stationary, WALKING→walking, RUNNING→running,
  IN_VEHICLE→automotive, ON_BICYCLE→cycling, else unknown). gms-only; oss no-op.
  This **extends an existing spec field to Android** (making it cross-platform)
  rather than inventing one — a much better upstream story than custom `act`.

**Key files:** `MessageLocation.kt` (+ deserializer), `LocationProcessor` /
publish path, `ActivityMonitoringModeController.kt`, `Contact.kt`,
`ContactImageBindingAdapter.kt` / `ContactActivity.kt`. **Depends on #1 + #5.**
**Risk:** medium. **Tests:** mapping + precedence (explicit over inferred) unit
tests.

</details>

## 9. ✅ Hide markers for inactive (stale) contacts — `feature-hide-stale-contacts`

**Done**, committed `b952fe95` (off `master`). Opt-in option to hide a contact's
map marker once its last reported location is older than a threshold — for
friends who only publish occasionally (e.g. a phone that publishes just while on
the home network), whose retained last-position otherwise lingers on the map for
days after they've left.

**As built:**
- New Map preferences: `hideStaleContacts` (Boolean, off by default) +
  `staleContactThresholdDays` (Int, default 2). Both plain Boolean/Int, so no
  `PreferencesStore`/shim changes were needed (unlike #6's enum).
- `MapViewModel.isContactStale` + a pure `isLocationStale` helper (unit-tested)
  computing age from `Contact.locationTimestamp`.
- `MapFragment.updateMarkerForContact` removes a stale contact's marker; the
  retained stale position is dropped right on connect.
- **Composes with #4:** markers re-evaluate on every reconcile (incl. the
  resume-time reconcile), so a contact that ages past the threshold while the map
  is open is hidden on the next pass, and reappears on a fresh publish.

Display-only — contacts stay in the Friends list, nothing changes on the wire.
This is the map-scoped slice of #7's client-side "freshness" idea. **Upstream
candidate** (opt-in). Note: edits `preferences_map.xml`, which #6 also touches.
✅ Confirmed working on-device 2026-06-05.

## 10. ✅ Speed-tiered driving locator boost — `feature-driving-boost`

**Done**, committed `0d5eb665` (off `activity-triggered-monitoring`, since it
extends #1). Activity monitoring previously boosted only on foot and treated
`IN_VEHICLE` as "stop boosting". Now getting in a vehicle also boosts the locator
to high accuracy, with a **sampling interval tuned to the vehicle's current
speed** (free from every `Location.getSpeed()`): longer interval at high speed so
the GPS can duty-cycle (battery), tighter at city speed to capture turns. On by
default; opt-out via a "Boost while driving" switch (depends on the AR master
toggle). gms-only.

**As built:**
- `DrivingSpeedTier` — pure speed→interval bands (8/13/18/22 s) with hysteresis so
  steady driving near a boundary doesn't churn the request. Unit-tested.
- Activity classification is now **tri-state** (`ON_FOOT` / `IN_VEHICLE` /
  `STILL`); the gms receiver forwards `DetectedActivityChange` ordinals. The
  controller drives two mutually-exclusive boost flags (`locatorBoostedByActivity`
  fixed on-foot interval vs `locatorBoostedByDriving` speed-tiered), sharing the
  slow-out revert + manual-override back-off. `EffectiveLocatorSettings` gained a
  driving branch (HighAccuracy, tiered interval, 50 m displacement floor so a
  parked vehicle stops emitting points).
- `BackgroundService` feeds the DEFAULT location stream's speed into the tier and
  re-issues the request on a band change; it pegs the fastest interval to the
  interval while driving so the GPS sleeps between fixes.

**Rationale captured during design:** displacement is already speed-adaptive in
time, so the speed-tiering's real win is GPS duty-cycling (relaxed interval at
speed) — confirmed worthwhile because the speed is free in every fix. Bands are
constants, easy to tune after road testing. **Depends on #1.** On-device
validation (the duty-cycling battery win, band feel) still pending.

## 11. ❄️ Passive location while driving — likely unnecessary (kept for the record)

**Conclusion: probably not worth building.** This started as "leverage the car's
GPS to save battery", but on closer analysis the premise is already handled by
the platform, so the explicit work mostly solves a problem we don't have.

**Why the fused provider already covers the goal:** `FusedLocationProviderClient`
satisfies our request (accuracy + rate) from the **cheapest available source that
meets it**, powering the GNSS hardware only to the union of all apps' demands. So
if a higher-quality/cheaper source exists — a car location feed, or a GNSS
session another app is already running — the fused provider serves our existing
active `HIGH_ACCURACY` request from it and lets the phone's GNSS chip idle,
**transparently, with no code**. The "car GPS → phone GPS rests" win, if the car
feed reaches the system fused provider at all, needs nothing from us. (Whether
that feed is present is car-dependent and undocumented for background apps — but
that's about the *source existing*, not about us needing to opt in.)

**What `PRIORITY_PASSIVE` would actually add — and why it's marginal:** passive is
a *different* mechanism: contribute **zero** power demand and only receive fixes
that *other* apps' active requests already trigger. It is **not** how a car feed
is consumed (that's automatic above). It only helps in one narrow corner: another
app (nav) is actively running the GPS, **and** we're **off charger**, **and** we
accept going blind the moment that app stops. On a typical Android Auto drive the
phone is charging, so the battery upside is negligible, while the cost is real: a
new `LocatorPriority.Passive`, a staleness-watchdog fallback to the active
speed-tiered request (#10) on the critical location path, and track quality made
dependent on another app.

**Verdict:** rely on the fused provider's automatic source-selection (already in
effect via #10's active request); don't build explicit passive. Revisit only if
real off-charger driving without nav shows a battery problem that the platform
isn't already solving. **Depends on #1 + #10** if ever revived.

---

## Suggested sequencing

Done: **#3** (`6f2d5c7e`), **#4** (`e722379d`), **#5** (`ea7b3266`),
**#6** (`a22cb28e`), **#9** (`b952fe95`), **#10** (`0d5eb665`, off #1) — all on
their own branches off
`master`. **#2 (HiveMQ)** is committed on `hivemq-mqtt-migration` (`8615b72a` +
reconnect-ownership fix `8295f72e`) but still needs **on-device validation**. A
combined `integration-testing` branch merges everything for device testing.
On-device validation status:
- ✅ **Credential change reconnects without a Force Stop** — confirmed 2026-06-05
  (the deadlock fixed by `8295f72e`).
- ✅ **Doze / long-idle longevity — confirmed 2026-06-07.** Phones re-establish
  updates reliably after overnight sleep with no manual intervention, despite the
  exact-alarm pinger being gone (HiveMQ keepalive + WorkManager reconnect + the
  await/reconnect fixes below cover it).
- ✅ **Network-flap recovery (wifi↔cell) — confirmed 2026-06-07.** A
  quick flap recovered (the `withTimeout` bound from `4663166c`/`50aa6517`), but a
  longer off-Wi-Fi walk wedged the outbound loop for 10+ min across reconnects:
  a QoS-1 publish future was **never settled** and the coroutine `withTimeout`
  couldn't unwind an await parked on it (a known HiveMQ async-client class —
  hivemq-mqtt-client #554, #612). Refixed in `0c48f386`: `await(timeout)` now
  **force-completes the future** via a scheduled task (applied to publish /
  connect / disconnect / subscribe), and a publish timeout forces a reconnect so
  the retry uses a fresh client instead of the wedged one. Confirmed on-device.
- ✅ **Network-loss recovery (`2b805043`) — confirmed 2026-06-07.**
  Driving off home wifi, the client went DISCONNECTED and never reconnected
  (queued for the whole drive) until a manual Reconnect, despite cellular being
  fine. During the wifi↔cell handoff the callback's `onLost` for the active
  network just `disconnect()`ed (a USER disconnect, which doesn't self-schedule a
  reconnect), and the settled network never fired another `onAvailable`. Fix:
  `onLost(currentNetwork)` now reconnects (failed attempt self-schedules a retry);
  plus a backstop — `sendMessage` schedules a reconnect whenever it has queued
  work but isn't CONNECTED. Note this is independent of the publish-timeout fix
  (that path needs a *timeout*; here it fast-failed NotConnected).
- ⏳ wss-through-caddy-l4 (deferred by the user for now).
- 📌 Optional tuning (not required — `0c48f386` prevents the wedge regardless):
  a **moderate keepalive (~120–300 s)** detects a dead socket in minutes instead
  of an hour. Cheap now that the Paho exact-alarm pinger is gone — HiveMQ pings via
  an in-process Netty timer that can't wake the device from Doze, so it adds no
  Doze wakeups; going *too* short instead risks reconnect churn (the broker drops
  the connection during Doze when the timer can't fire). 3600 s stays safe too.

Remaining:

1. **Finish validating #2 (HiveMQ)** on-device — Doze longevity + network-flap
   recovery (credential-change already ✅). Unblocks #7.
2. **#7 (MQTT5 / freshness)** — after #2, starting with the spike.
3. **#8 (explicit activity field)** — after #2 ships; builds on #1 + #5. Lower
   priority since it only helps this user's own clients.
#11 (passive driving location) was **shelved** — the fused provider already serves
our request from the cheapest available source transparently, so explicit passive
solves a problem we mostly don't have (see its section).

## 12. ✅ Map blue-dot locator: leak fix + power dial-back — `fix-map-locator-power`

Two commits off `master`:
- `c41f63b7` **(the impactful one):** the map's current-location ("blue dot")
  source collected `currentLocation` in a bare `lifecycleScope.launch` with no
  `repeatOnLifecycle`, so the request kept running while the fragment was merely
  STOPPED — i.e. whenever the app had ever been opened, a HighAccuracy locator
  ran in the **background** until the process died. Scoped the collect to
  `repeatOnLifecycle(STARTED)` in `GoogleMapFragment` + `OSMMapFragment`.
- `8b8c1a85`: dial the blue-dot request back from `HighAccuracy@2s/1m` to
  `BalancedPowerAccuracy@5s/5m`.

**Upstream bug, not ours** — `git blame` puts the leaking pattern at `d601a6550`
(Andrew Rowson, 2026-03-13, "migrate remaining MapViewModel LiveData to
StateFlow"): the classic LiveData→StateFlow pitfall (LiveData auto-pauses below
STARTED; a bare `collect` does not). **Confirmed major battery improvement
on-device 2026-06-06.** Affects every OwnTracks user → **top upstream-PR
candidate** (clean lifecycle bug fix, large real-world battery impact).

## Review findings (2026-07-11)

Full review pass over (a) the uncommitted working tree (Kalman smoothing,
plausible-speed gate, `tst` future bound, MQTT generation guards, driving-boost
watchdog, Scheduler KEEP, AR settings dialog), (b) the committed fork-vs-upstream
divergence, and (c) an upstream pattern hunt. Verdicts: **CONFIRMED** = traced
end-to-end in code; **PLAUSIBLE** = realistic scenario, not exhaustively proven.
Baseline: `:app:testGmsDebugUnitTest` + `:location-kalman:test` green before review.

### Correctness — working tree (fix before committing)

Items 1–4 **fixed in the working tree (2026-07-11)**: the entry-dwell now
re-checks `boostLocatorWhileDriving` when it elapses (+ regression tests);
`maybeSmooth` no longer overwrites the sensor accuracy (fixes both the
`ignoreInaccurateLocations` bypass and the geofence-tolerance collapse; the
residual smoothed-position lag in geofence checks is accepted and documented);
and both forced driving-boost reverts share a `revertDrivingBoost()` helper
that clears a speed-engaged "automotive" (watchdog: always; toggle-off: only
when speed-engaged, since AR owns the truth otherwise).

Items 5–8 **fixed in the working tree (2026-07-11, second pass)**:
- **#5**: `scheduleMqttReconnect(expedite: Boolean)` — failure paths keep KEEP
  (backoff growth preserved), while the two genuine nudges (`sendMessage`
  queued-work backstop, publish-timeout) use REPLACE so a grown backoff can't
  starve them.
- **#6**: the Kalman filter's process noise is now also floored by the
  fix-implied displacement speed, so speedless (network) fixes can't collapse
  the gain and lag the anchor (regression test added); and the plausibility
  gate keeps the last *rejected* fix — a new fix implausible against the anchor
  but plausible against the previous rejection corroborates a real relocation
  and is accepted, ending bad-anchor lock-in after one extra fix.
- **#7**: the disconnected listener re-checks its generation *inside* the
  `scope.launch` before setting DISCONNECTED (mirrors the connected listener).
- **#8**: the `publishes(ALL)` callback is generation-tagged, so a zombie
  superseded client can no longer feed incoming messages into the pipeline.
  (No force-teardown added: HiveMQ's mqtt3 API has no forcible disconnect, and
  every superseded client already gets a `disconnect()` request.)

Also done in the second pass: **cleanups** 1 (shared `promptOpenAppSettings`
helper), 4 (`DrivingSpeedTier.mpsToKmh` reuse + single `distanceTo`), 5 (cached
`smoothingEnabled` flag via `OnPreferenceChangeListener`), 7 (filter returns a
dedicated `SmoothedPosition` instead of echoing timestamp/speed through a
`KalmanFix`); the **ContactsActivity upstream fix** (collect wrapped in
`repeatOnLifecycle(STARTED)` with a `setContactList` reconcile on every
re-entry — upstream PR candidate); **docs** (`PREFERENCES.md` experimental-
features list refreshed); and all three **hygiene** items (`/bin` ignored in
`location-kalman/.gitignore`, `*.code-workspace` ignored at the root,
`location-kalman:test` added to the CI test task).

**Declined**: cleanup 3 (replace generation counters with client-identity
checks — the listeners are constructed *before* the client exists, so a captured
`thisClient` is a chicken-and-egg; the generation scheme stays) and cleanup 6
(watchdog job churn — one Job allocation per ~10 s fix while driving is
negligible, and the cancel+relaunch form is clearer than a self-rechecking
timer). `maxImplausibleSpeedKmh` stays config-editor-only for now (deliberate —
it's a defensive limit most users should never touch).

1. **CONFIRMED · Driving boost can engage after the toggle is switched off**
   (`ActivityMonitoringModeController.kt:196`). `onDrivingBoostFeatureDisabled()`
   only acts when `locatorBoostedByDriving` is already true and never
   `cancelPendingEntry()`. With `activityEntryDelaySeconds > 0`: IN_VEHICLE
   arrives → entry dwell armed → user toggles `boostLocatorWhileDriving` off →
   dwell elapses → `applyBoost(onFoot=false)` sets the driving boost anyway.
   `onEntryDwellElapsed` re-checks mode/permission but not the driving toggle.
   Self-heals only on a later STILL + revert cycle. Fix: cancel the pending entry
   (or re-check the toggle in `onEntryDwellElapsed`).

2. **CONFIRMED · Kalman smoothing bypasses `ignoreInaccurateLocations` and
   misreports `acc` on the wire** (`LocationProcessor.kt`). `maybeSmooth()` runs
   *before* `publishLocationMessage`, whose `locationIsWithAccuracyThreshold`
   then sees the filter's synthetic accuracy (`sqrt(variance)`, floored at 1 m,
   ~8–15 m even when raw fixes are 50 m). A run of poor network fixes the user
   asked to drop is published with a confident-looking `acc`. Fix direction:
   gate on the *raw* accuracy first, and/or publish
   `max(rawAccuracy, filterAccuracy)`.

3. **CONFIRMED · Geofence transitions evaluated on the smoothed fix**
   (`LocationProcessor.kt:156`). Waypoint ENTER/EXIT uses
   `distanceTo(waypoint) <= geofenceRadius + location.accuracy` — with smoothing
   on, both the lagged position and the collapsed synthetic accuracy shrink the
   enter tolerance, so boundary sits/entries that the raw fix would classify
   ENTER get EXIT (missed region events). Same root as #2: smooth only what gets
   published, not what feeds region logic — or smooth at the source and keep raw
   values for gating.

4. **CONFIRMED · Forced driving-boost reverts leave `motionactivities` stuck on
   "automotive"** (`BackgroundService.kt`, watchdog fire + toggle-off handler).
   The normal speed-exit path (line ~256) resets
   `locationRepo.currentMotionActivities` to stationary precisely because AR
   never saw the vehicle; both new revert paths skip that reset, so extended-data
   publishes keep reporting `automotive` for hours after parking. Fix: extract
   one `revertDrivingBoost()` helper that does all four steps (also see
   Cleanup #2).

5. **PLAUSIBLE · Scheduler KEEP starves recovery nudges during grown backoff**
   (`Scheduler.kt:84`). `MQTTReconnectWorker` returns `Result.retry()`, so
   WorkManager already owns the growing backoff (10 s doubling → hours). With
   KEEP, the `sendMessage`/network nudges (`scheduleMqttReconnect`) no-op while
   the backed-off job is pending: after a long broker outage on a stable network,
   reconnection waits out the full remaining backoff instead of the 10 s the
   nudge intends. KEEP is right for the redundant in-worker reschedule, but the
   nudge paths lose their purpose. Fix direction: since the worker retries
   itself, drop the redundant `scheduleMqttReconnect()` from the endpoint's
   connect-failure path and let genuine nudges use REPLACE (or cancel+enqueue
   when state has been DISCONNECTED with queued work for a while).

6. **PLAUSIBLE · Plausible-speed gate vs. smoothing interactions**
   (`LocationProcessor.kt`):
   - `maybeSmooth` feeds `location.speed` without checking `hasSpeed()`; a
     speedless (e.g. network) fix while driving gives the filter a 1 m/s process
     -noise floor → the smoothed anchor lags far behind → the gate (raw fix vs.
     anchor) inflates implied speed → cascading rejections if
     `maxImplausibleSpeedKmh` was lowered. At the 1000 default this won't trip.
   - Bad-anchor lock-in: an *accepted* cell bounce becomes the anchor and real
     fixes get dropped until dt grows (≈3 min for a 50 km bounce at 1000 km/h;
     ~1 h if the user set 50 km/h). A manual USER publish recovers (bypasses the
     gate, resets the anchor) — worth documenting.
   - When publishes fail (Quiet/Manual/accuracy), the anchor goes stale and dt
     inflation makes the gate vacuous — reduced protection only, not a bug.

7. **PLAUSIBLE · MQTT endpoint-state clobber from a superseded client's
   disconnect callback** (`MQTTMessageProcessorEndpoint.kt:158`).
   `disconnectedListenerFor` checks the generation *synchronously* but sets
   DISCONNECTED inside `scope.launch` (ApplicationScope = `Dispatchers.Default`,
   multi-threaded, no ordering). The old client's USER-disconnect callback fires
   during the next `connect()` while its generation is still current; its queued
   `setState(DISCONNECTED)` can land *after* the new client's CONNECTED →
   endpoint stuck reporting DISCONNECTED while connected → `sendMessage` churns
   NotConnected + reconnect until a full reconnect resets it. Fix: move the
   generation check inside the launch (mirroring `connectedListenerFor`).
   *(Refuted while verifying: the generation-assignment ordering itself is fine —
   `currentClientGeneration` is set before `connect()` is invoked, and
   `buildClient` doesn't connect.)*

8. **PLAUSIBLE · Zombie superseded MQTT client** (`MQTTMessageProcessorEndpoint.kt`).
   The `publishes(ALL) { onIncomingPublish(it) }` callback is *not*
   generation-tagged, and an abandoned client (5 s disconnect timeout, or a
   connect that completes after being abandoned) is never force-disconnected.
   Same-clientId broker takeover usually kills the zombie — but if the zombie
   completes its connect *last*, it kicks the **current** client instead
   (reconnect churn), and abandoned Netty resources linger. Fix direction: keep a
   reference to the superseded client and `disconnect()` it fire-and-forget (no
   await) when replacing, and tag the publishes callback with the generation too.

### Behavior checks (confirm intended)

- **Experimental screen is now always visible** (`PreferencesFragment.kt`), and
  `docs/PREFERENCES.md` still documents the removed
  `showExperimentalPreferenceUI` key (importing a config carrying it is now a
  silent no-op). If intentional, update the doc.
- **`maxImplausibleSpeedKmh` has no preferences UI** — settable only via the
  config editor. Plumbing itself is correct (plain Int needs no store/shim work;
  defaults + export verified).
- **Watchdog can revert mid-gridlock**: 20 min below 9 km/h (severe jam,
  drive-through queues) with no AR transition fires the watchdog; re-engage then
  needs ≥ 36 km/h. Self-heals, but worth remembering when road-testing.
- **Kalman linear-dt process noise** (`variance += dt·v²`) matches the
  widely-copied reference filter; with real speeds present the gain stays high
  (driving: q≈7200 vs R≈100 → gain ≈ 0.99). The weak spot is the missing-speed
  case above, not the dt exponent. The cos(latitude) concern was **refuted**: a
  shared scalar gain is a per-axis linear interpolation, which commutes with the
  degrees→metres scaling, so no anisotropy error.

### Cleanup (working tree)

1. `AdvancedFragment.kt`: `promptOpenAppSettingsForActivityRecognition()` is a
   near-verbatim copy of the precise-location settings prompt — extract
   `promptOpenAppSettings(titleRes, messageRes)`.
2. `BackgroundService.kt`: the 3-line driving revert is copy-pasted at the
   watchdog and preference-change sites — extract `revertDrivingBoost()` (and
   fold in the `currentMotionActivities` reset from Correctness #4).
3. `MQTTMessageProcessorEndpoint.kt`: the AtomicLong + @Volatile generation pair
   can collapse to capturing `val thisClient = newClient` in the listeners and
   checking `client !== thisClient` — one moving part instead of three.
4. `LocationProcessor.kt`: `isPlausibleSpeed` re-derives m/s→km/h with a bare
   `* 3.6` (use `DrivingSpeedTier.mpsToKmh`); `last.distanceTo(location)` is
   computed twice on the rejection path.
5. `LocationProcessor.maybeSmooth` reads `preferences.experimentalFeatures` on
   every DEFAULT fix — each read allocates/sorts a fresh set via the store.
   Cache a boolean via the existing `OnPreferenceChangeListener`.
6. `BackgroundService.armDrivingBoostWatchdog` cancels + relaunches a coroutine
   on every qualifying fix; storing a last-confirmation timestamp and letting one
   long-lived job re-check it on fire is cheaper (minor).
7. `LocationKalmanFilter.filter` echoes timestamp/speed through the returned
   `KalmanFix` unchanged — return only position+accuracy so nobody trusts the
   echoes.
8. Formatting: `:app:ktfmtCheck` passes on the working tree (a suspected
   101-column violation in `Scheduler.kt` was refuted). Note the
   `location-kalman` module has no ktfmt task wired at all.

### Upstream findings (Scope 3)

- **`ContactsActivity` collects `contactUpdatedEvent` in a bare
  `lifecycleScope.launch`** (no `repeatOnLifecycle`, `ContactsActivity.kt:71`) —
  same family as the blue-dot leak (#12): while the activity is backgrounded it
  keeps consuming repo events and calling `viewModel.refreshGeocode` (network
  reverse-geocoding) on every contact update for as long as the activity exists.
  Every other collector in the app (MapActivity, WaypointsActivity) uses
  `repeatOnLifecycle`. Fix needs the #4 lesson: wrap in
  `repeatOnLifecycle(STARTED)` *plus* re-sync `setContactList` on re-entry, since
  the repo flow has no replay. **Upstream PR candidate.**
- Checked and clean: MapActivity/WaypointsActivity collectors all lifecycle-
  aware; `host`/`port`/`username`/`clientId` changes *do* reconnect (via
  `PREFERENCES_THAT_WIPE_QUEUE_AND_CONTACTS` → endpoint reload — an earlier
  suspicion refuted); `KeyStore.getInstance("PKCS12", "BC")` in `MqttSslConfig`
  is upstream parity (same call in upstream `SocketFactory`); remaining strict
  message fields (`MessageTransition.tst` etc.) are receive-tolerant enough in
  practice; stale-contact math (`isLocationStale`) and `ContactActivity`
  precedence are sound.

### Repo hygiene

- `project/location-kalman/bin/` (IDE build output) is untracked and **not**
  gitignored (only `project/bin/` is) — add `bin/` to the module or root ignore.
- **`:location-kalman:test` never runs in CI** — `build-test-lint.yaml` only runs
  `app:createGmsDebugUnitTestCoverageReport`. Add the module test task.
- `android.code-workspace` untracked — ignore or commit deliberately.

## Cross-cutting upstream notes

- Keep a short note per PR on whether it's behind a preference (lower friction to
  upstream) vs always-on behavior change.
- The lenient-parsing fix (#3) and stale-pin fix (#4) are the two cleanest
  "obvious bug" PRs and the best first contributions to gauge upstream interest.
- **The blue-dot leak fix (#12, `c41f63b7`) is now the highest-value upstream
  PR**: confirmed large battery win, affects all users, isolated one-line-per-file
  lifecycle fix with clear LiveData-migration root cause in the description.
