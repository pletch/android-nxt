# Design Brief: Activity-Triggered Adaptive Monitoring for OwnTracks Android

## Purpose

Add an **opt-in** feature to the OwnTracks Android app that automatically switches
the location monitoring mode based on detected physical activity:

- When the user starts walking/running (on-foot activity) → elevate to **move mode**
  (high-frequency, GPS-accurate tracking) so walks/hikes are captured in detail.
- When the user becomes stationary or starts driving → revert to the prior mode
  (typically **significant changes**, low power) to conserve battery.

This solves a real, long-standing usability problem: move mode tracks walks well but
drains battery, while significant-changes mode conserves battery but under-records
walks (the OS doesn't wake the app often enough during slow/local movement). Today
users must manually toggle modes or rig up Tasker automation. This feature makes the
app do it automatically using Android's low-power Activity Recognition.

There are existing feature requests for exactly this:
- owntracks/android#877 ("Change monitoring mode based on location" — notes Tasker
  works today but an in-app solution would be better)
- The pattern is well-precedented: fitness apps and Google Location History already
  do activity-driven adaptive sampling.

## IMPORTANT: status of the claims in this brief

This brief was developed through research and reasoning, NOT by reading the current
source. Several architectural claims below are **inferences marked [VERIFY]**. The
FIRST task is to confirm them against the actual repo (owntracks/android). Treat the
real source as authoritative; correct this brief where it's wrong before building.

---

## Phase 0: Source investigation (DO THIS FIRST)

Before writing any feature code, confirm the architecture by reading the real source.
Clone https://github.com/owntracks/android and answer these questions:

### Q1. How does the `CHANGE_MONITORING` intent change the mode?
- Search the source for `CHANGE_MONITORING` (registered in AndroidManifest.xml).
- Find the handler (likely in a background service class).
- Trace what method it calls when it receives the `monitoring` integer extra
  (values: move/significant/manual/quiet — confirm the int mapping;
  `monitoring 2` == move and `1` == significant per external docs [VERIFY]).
- Identify the concrete "apply this monitoring mode now" method it ends up calling
  (candidate names: setMonitoring(), setupLocationRequest(),
  reconfigureLocationProvider(), or a reaction to a Preferences change). Record the
  exact method + class.

### Q2. How does `setConfiguration` change the mode?
- Search for `setConfiguration` / the message class carrying it (likely
  MessageConfiguration, processed in MessageProcessor, or an importKeyValue path in
  the Preferences/Parser code).
- Trace how it applies a config: does it write multiple Preferences fields in bulk
  then trigger a reconfigure?
- Identify the point where it (re)applies the location request.

### Q3. Do the two paths share the "apply monitoring mode" code?
- Determine whether the `CHANGE_MONITORING` path and the `setConfiguration` path
  converge on the SAME mode-apply method, or each has its own.
- This is the pivotal question for this feature's design (see Q4).

### Q4. Where is the setConfiguration crash (issue #1838)?
- Read owntracks/android#1838 — "setConfiguration causes crash" — for a stack trace.
  The reporter changes BOTH `monitoring` and `moveModeLocatorInterval` via
  setConfiguration and it crashes.
- Determine whether the crash is in:
  (a) the bulk multi-field config-write/reconfigure path (setConfiguration-specific), OR
  (b) the shared mode-apply code that CHANGE_MONITORING also uses.
- **[INFERENCE — VERIFY]:** The evidence suggests the crash is in the bulk-config path
  (a), not the shared mode-apply, because CHANGE_MONITORING (a narrow single-preference
  intent) has been used reliably for years via Tasker/Home Assistant, while the crash
  is specifically tied to setConfiguration changing multiple fields at once. IF this
  holds, this feature should change mode via the SAME narrow path CHANGE_MONITORING
  uses (single monitoring-preference change), NOT by building a config object and
  calling setConfiguration. That sidesteps the crash.
- IF instead the crash is in shared code (b): fixing that crash becomes a prerequisite
  to this feature (and a valuable upstream contribution in its own right), because this
  feature will exercise the mode-switch path frequently (every walk).

### Deliverable from Phase 0
A short note recording: the exact mode-apply method to call, whether to route through
the CHANGE_MONITORING-style path (preferred) vs setConfiguration (avoid if it's the
crash path), and the int values for each monitoring mode. Update this brief.

---

## Phase 0 FINDINGS — verified against source 2026-06-02 (commit 9b4364cd)

The architecture is **reactive**: nobody calls an "apply mode" method directly. Setting
the `monitoring` preference fires a preference-change listener that re-applies the
location request. Both the `CHANGE_MONITORING` intent and `setConfiguration` converge on
this same listener.

### The mode-apply path (the thing to reuse)
1. Something sets `preferences.monitoring = <MonitoringMode>`.
2. `PreferencesStore.setValue` writes it and — only if the value actually changed —
   calls `preferences.notifyChanged(...)`
   ([PreferencesStore.kt:163-169](project/app/src/main/java/org/owntracks/android/preferences/PreferencesStore.kt#L163-L169)).
3. `notifyChanged` invokes every registered `OnPreferenceChangeListener.onPreferenceChanged(propertyNames)`
   ([Preferences.kt:419-426](project/app/src/main/java/org/owntracks/android/preferences/Preferences.kt#L419-L426)).
4. `BackgroundService` is that listener
   ([BackgroundService.kt:88](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L88),
   registered at [:176](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L176)).
   In `onPreferenceChanged`, `if (properties.contains("monitoring"))` it calls
   **`setupLocationRequest()`** and `ongoingNotification.setMonitoringMode(...)`
   ([BackgroundService.kt:641-644](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L641-L644)).
5. `setupLocationRequest()`
   ([BackgroundService.kt:530-575](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L530-L575))
   is the concrete "apply this monitoring mode now" method — it maps the mode to an
   interval/priority and calls `locationProviderClient.requestLocationUpdates(...)`.

**=> This feature should just do `preferences.monitoring = MonitoringMode.Move` (and
restore the saved mode the same way). No intent, no MessageConfiguration, no direct call
to `setupLocationRequest`.** The new module will have `Preferences` via DI (same as
`SignificantMotionSensor`). Setting the preference is the entire mechanism.

> ⚠️ SUPERSEDED: the shipped implementation does **not** force Move mode (it was too
> battery-heavy). It keeps the user's mode and instead boosts the locator at request time. See
> "IMPLEMENTATION — as built" below for what actually shipped.

### Q1 — CHANGE_MONITORING
[BackgroundService.kt:303-314](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L303-L314).
With a `monitoring` int extra it does `preferences.monitoring = getByValue(extra)`;
with no extra it calls `preferences.setMonitoringNext()`
([Preferences.kt:393-395](project/app/src/main/java/org/owntracks/android/preferences/Preferences.kt#L393-L395)).
It does NOT call any apply method itself — it relies entirely on the reactive listener
above. So CHANGE_MONITORING == "set the `monitoring` preference."

### Q2 — setConfiguration
Two entry points, both end at `Preferences.importConfiguration(MessageConfiguration)`
([Preferences.kt:93-136](project/app/src/main/java/org/owntracks/android/preferences/Preferences.kt#L93-L136)):
remote cmd via `MessageProcessor` ([:540](project/app/src/main/java/org/owntracks/android/services/MessageProcessor.kt#L540))
and local file load via `LoadViewModel` ([:60/:92](project/app/src/main/java/org/owntracks/android/ui/preferences/load/LoadViewModel.kt#L60)).
`importConfiguration` wraps all field writes in a `PreferencesStore.Transaction`
([PreferencesStore.kt:171-200](project/app/src/main/java/org/owntracks/android/preferences/PreferencesStore.kt#L171-L200))
that **batches** the changed-property notifications and fires a SINGLE
`notifyChanged(allChangedProps)` on `close()`. That one notification still lands in the
same `onPreferenceChanged`, so it still calls `setupLocationRequest()`.

### Q3 — do the paths share the apply code? YES.
Both `CHANGE_MONITORING` and `setConfiguration` converge on
`onPreferenceChanged` → `setupLocationRequest()`. The only difference is notification
timing: CHANGE_MONITORING notifies immediately per single set; setConfiguration batches
all changed props into one notification via the Transaction. The mode-apply code itself
is identical.

### Q4 — the #1838 crash
Could NOT reproduce from the current source by reading; the preference layer has clearly
been reworked since the brief was written (the batching `Transaction`, the reactive
listener, and commit `e050ee7c` "exception thrown during editing the Connection
preferences ... doesn't crash the whole app" all postdate the old design). Changing
`monitoring` + `moveModeLocatorInterval` together now flows cleanly:
`importConfiguration` sets both inside the Transaction → one `notifyChanged` →
`onPreferenceChanged` matches both `moveModeLocatorInterval`
([BackgroundService.kt:629](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L629))
and `monitoring` → `setupLocationRequest()`, which reads `moveModeLocatorInterval.toLong()`
for Move mode with no obvious fault.
**Conclusion: #1838 is not a blocker for this feature, AND it is moot regardless** —
because we set the single `monitoring` preference directly (the narrow path), we never
exercise the bulk-config route at all. Verify #1838's current status on GitHub before
claiming it's fixed upstream; for THIS feature it's out of scope.

### Monitoring mode int values — CONFIRMED
`enum MonitoringMode` ([MonitoringMode.kt:7-11](project/app/src/main/java/org/owntracks/android/preferences/types/MonitoringMode.kt#L7-L11)):
`Quiet(-1)`, `Manual(0)`, `Significant(1)`, `Move(2)`. Default is `Significant`
([DefaultsProvider.kt:45](project/app/src/main/java/org/owntracks/android/preferences/DefaultsProvider.kt#L45)).
Use the named enum constants (`MonitoringMode.Move`, etc.), never the ints.
(Brief's move=2 / significant=1 were right; manual=0, quiet=-1 now confirmed.)

### NEW finding the brief missed: gms vs oss flavor split (IMPORTANT)
The app builds two flavors on a `locationProvider` dimension — `gms` and `oss`
([build.gradle.kts:191-200](project/app/build.gradle.kts#L191-L200)). The Activity
Recognition **Transition API** (`com.google.android.gms.location.ActivityRecognition`)
is a Play Services API and only works in the `gms` flavor (`play-services-location` is
already a `gmsImplementation` dependency). Play-Services-only functionality is abstracted
behind an interface in `main` with separate `gms`/`oss` implementations + Hilt modules —
see `GeofencingClient` / `NoopGeofencingClient`
([gms ServiceModule](project/app/src/gms/java/org/owntracks/android/di/ServiceModule.kt)
vs [oss ServiceModule](project/app/src/oss/java/org/owntracks/android/di/ServiceModule.kt)).
**This feature must follow that pattern: a real AR registration manager in `src/gms`, a
no-op in `src/oss`, bound via the flavor `ServiceModule`.** (Note: the unrelated
`SignificantMotionSensor` lives in `main` only because it uses the AOSP `SensorManager`,
not Play Services — do not put AR there.)

### Best existing template to copy
[`SignificantMotionSensor`](project/app/src/main/java/org/owntracks/android/services/SignificantMotionSensor.kt)
is the closest precedent: a self-contained motion-triggered helper, constructor-injected
with `Preferences` + `RequirementsChecker`, gated behind an `experimentalFeatures` flag,
with `setup()`/`cancel()` driven by the `BackgroundService` lifecycle
([instantiated :179-180](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L179-L180),
`setup()` at [:368](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L368),
`cancel()` at [:250](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L250),
re-toggled in `onPreferenceChanged` [:648-656](project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L648-L656)).
Model the AR manager's lifecycle and opt-in gating on this. Note its rate-limiting and
re-registration logic too.

### Manual-override hook (component 5)
Manual mode changes ALSO go through `preferences.monitoring = ...`, so the AR module can
observe them by registering its own `OnPreferenceChangeListener` and comparing the new
`monitoring` value against the one it last set itself; a `monitoring` change it didn't
originate == a manual override. No separate hook point needed.

---

## IMPLEMENTATION — as built (supersedes the original design below where they differ)

The feature was implemented following Phase 0. The sections below this one are the
*original* design brief and are kept for history; where they describe forcing **Move mode**,
that was deliberately changed — see "Strategy change" below.

### Strategy change: boosted-Significant instead of forcing Move
The original design elevated to **Move mode** on-foot. Move mode tracks at high accuracy but
fires GPS unconditionally every `moveModeLocatorInterval` seconds (no displacement filter),
which is battery-heavy and keeps polling for the whole slow-out window after you stop. To get
most of the tracking benefit at lower battery cost, the implementation instead keeps the
user's current monitoring mode and **boosts the locator settings** while on-foot:
- `locatorPriority` → `HighAccuracy` (GPS)
- `locatorDisplacement` → `activityOnFootLocatorDisplacement` (default 30 m)
- `locatorInterval` → `activityOnFootLocatorInterval` (default 25 s)

This yields a distance-gated GPS track during walks. The boost is applied as a **runtime
override** computed in `effectiveLocatorSettings(...)` and read by `setupLocationRequest()`; the
controller only flips the `locatorBoostedByActivity` flag and the user's **stored** locator
preferences are never mutated. It is skipped when already in Move mode (the boost doesn't apply
there). Mode/monitoring itself is never changed by the feature.

### Components (files)
- **main** `services/ActivityMonitoringModeController.kt` — the decision engine (fast-in /
  slow-out hysteresis, manual-override backoff, precise-location skip). It only flips the
  `locatorBoostedByActivity` flag; the actual settings come from `EffectiveLocatorSettings.kt`.
  Pure Kotlin, unit-tested.
- **main** `location/ActivityRecognitionClient.kt` — interface; **oss**
  `location/NoopActivityRecognitionClient.kt`; **gms**
  `gms/location/GMSActivityRecognitionClient.kt` (+ `ActivityRecognitionReceiver.kt`).
  Bound per-flavor in `di/ServiceModule.kt`. Activity Recognition is a Play Services API, so
  it is **gms-flavor only**; oss is a no-op (mirrors the `GeofencingClient` split).
- **gms receiver** parses `ActivityTransitionResult`, logs each transition, and forwards a
  boolean per ENTER event (on-foot vs not) to `BackgroundService` via `ServiceStarter`
  (`INTENT_ACTION_ACTIVITY_TRANSITION` / `EXTRA_ACTIVITY_ON_FOOT_FLAGS`), keeping GMS types
  out of `main`.
- **BackgroundService** owns the controller (created before the pref listener is registered),
  registers/deregisters AR in `setupActivityRecognition()`, routes transition intents to the
  controller, and forwards monitoring changes for override detection.
- **UI** Settings → Advanced → Locator: a `SwitchPreferenceCompat` (`autoMonitoringByActivity`)
  plus `EditIntegerPreference`s for on-foot interval/displacement and the revert delay, all
  gms-gated (`AdvancedFragment`). Enabling requests `ACTIVITY_RECOGNITION` at runtime
  (`ActivityRecognitionPermissionRequester`).

### Preferences added
- `autoMonitoringByActivity: Boolean` (default false, opt-in) — exported.
- `activityOnFootLocatorInterval: Int` (default 25) — exported, tunable.
- `activityOnFootLocatorDisplacement: Int` (default 30) — exported, tunable.
- `activityRevertDelaySeconds: Int` (default 180) — exported, slow-out hysteresis.
- Non-exported runtime flag: `locatorBoostedByActivity` (read by `effectiveLocatorSettings`).
  It is cleared on service start so a process death mid-walk can't leave the boost stuck.

### Hysteresis & manual override (as built)
- **Fast-in:** first on-foot → set `locatorBoostedByActivity = true` (a reconfigure applies the
  boost via `effectiveLocatorSettings`).
- **Slow-out:** not-on-foot arms a coroutine timer of `activityRevertDelaySeconds`; a renewed
  on-foot cancels it. On fire, the boost flag is cleared.
- **Manual override:** because the controller never writes `monitoring`, *any* monitoring
  change is the user's — it clears the boost (so the new mode uses the user's own locator
  settings) and suppresses auto-boosting until a clean still→on-foot cycle. Controller entry
  points are `@Synchronized` (remote `setConfiguration` can arrive on a background thread).
- **Process death:** `BackgroundService.onCreate` calls `controller.onServiceStart()`, which
  clears any stale boost; if the user is still on foot, AR re-boosts within ~30–60 s.

### Permissions / precise location (beyond the original brief)
- Requires the `ACTIVITY_RECOGNITION` runtime permission (declared in the **gms** manifest).
- `RequirementsChecker.hasPreciseLocationPermission()` checks `ACCESS_FINE_LOCATION`
  specifically (the app's `hasLocationPermissions()` accepts coarse too). If Precise isn't
  granted, the boost is **skipped** (high accuracy would be silently downgraded to coarse), and
  the Advanced screen shows a tappable warning that re-requests fine location (Android 12+
  Precise upgrade dialog), falling back to app settings when the system won't re-prompt.

### #1838 (setConfiguration crash) — not exercised
The feature changes single preferences directly and never builds a `MessageConfiguration`, so
it does not exercise the bulk-config path implicated in #1838. No fix was needed.

### Boost application (effective-vs-configured)
The boost is a **runtime override**, not a preference swap: `effectiveLocatorSettings(...)` (pure,
unit-tested) computes the locator parameters from the configured mode/prefs plus the boost flag,
and `setupLocationRequest()` uses them. The user's stored locator preferences are never mutated, so
editing them mid-walk is safe and they take effect as soon as the boost ends. (This addressed the
Gemini Code Assist review's "effective vs configured" and "stale boost on process death" points;
the original implementation swapped the user-facing prefs, which is no longer the case.)

### Test coverage
- `test/.../services/ActivityMonitoringModeControllerTest.kt` — 10 unit tests (boost-on without
  mutating stored prefs, delayed revert, anti-bounce, skip-in-Move, skip-when-Precise-denied,
  feature-disable clears, onServiceStart clears a stale boost, three manual-override cases).
- `test/.../services/EffectiveLocatorSettingsTest.kt` — 7 unit tests (per-mode mapping,
  explicit-priority override, boost override, boost ignored in Move).
- `androidTest/.../ui/ActivityMonitoringPreferenceTests.kt` — instrumented (gms-gated via
  `Assume`); **needs a device/emulator run** — not yet validated on-device.

### Still outstanding
- On-device field validation & tuning of the defaults (walks/drives/stationary; doze/OEM
  background behaviour). This is the empirical part and has **not** been done yet.
- `docs/` user documentation.

---

## Architecture (ORIGINAL DESIGN — see "IMPLEMENTATION — as built" above for what shipped)

The feature is a self-contained module that OBSERVES activity transitions and CALLS
the app's EXISTING mode-setter. It must NOT touch the location-processing core, the
publish/queue path, or the message pipeline.

```
Google Play Services: ActivityRecognitionClient
   → ActivityTransitionRequest (WALKING / RUNNING / ON_FOOT / STILL / IN_VEHICLE)
        │  transition events delivered via PendingIntent
        ▼
[NEW] ActivityRecognitionReceiver (BroadcastReceiver)
   - parse ActivityTransitionResult
   - apply debounce / hysteresis (fast-in, slow-out)
   - decide target monitoring mode
        │  calls the EXISTING mode-apply method (from Phase 0 / Q1)
        ▼
[EXISTING] mode-apply path (the one CHANGE_MONITORING uses)
   - sets the `monitoring` preference and re-applies the location request
```

Key principle: REUSE the existing, battle-tested mode-switch path. We add a new
*trigger source*, not new mode-switching mechanics.

---

## Components to build

### 1. Preferences (opt-in + tuning knobs)
- `autoMonitoringByActivity: Boolean` (default false — MUST be opt-in; it changes
  battery behavior).
- `activityRevertDelaySeconds: Int` (default ~180s) — how long "still" must persist
  before reverting out of move mode (the slow-out hysteresis).
- (Optional advanced) confidence threshold for accepting a transition.
- Follow the app's existing Preferences pattern/class for adding these.

### 2. ActivityRecognition registration manager
- When the feature is enabled AND permissions granted, register an
  ActivityTransitionRequest for ENTER/EXIT on WALKING, RUNNING, ON_FOOT, STILL,
  IN_VEHICLE via ActivityRecognition.getClient(context)
  .requestActivityTransitionUpdates(request, pendingIntent).
- Deregister when the feature is disabled.
- Tie registration lifecycle to the app's existing background service lifecycle.

### 3. ActivityRecognitionReceiver (BroadcastReceiver)
- Guard with ActivityTransitionResult.hasResult(intent).
- Map transitions:
  - ENTER walking/running/on_foot → onFootDetected()
  - ENTER still/in_vehicle → notOnFootDetected()
- Register in AndroidManifest.xml (or context-registered, per app convention).

### 4. Decision logic with hysteresis (the part needing real care)
- **Fast-in:** on first on-foot detection, immediately switch to move mode (catch the
  walk start). Before switching, SAVE the current mode to `savedPreActivityMode`.
- **Slow-out:** on "still"/"in_vehicle", do NOT revert immediately. Start (or reset) a
  revert timer of `activityRevertDelaySeconds`. Only if still-stationary when it fires,
  revert to `savedPreActivityMode` (NOT hardcoded significant — restore what the user
  was actually in).
- Cancel the revert timer if on-foot is re-detected before it fires (so a pause at a
  crosswalk / tying a shoe doesn't bounce out of move mode).

### 5. Manual-override backoff
- If the user manually changes mode (via UI / existing CHANGE_MONITORING intent / a
  remote command) while auto-mode is active, treat that as authoritative: set a
  `userManuallyOverrode` flag and suppress auto-switching until the next clean
  STILL→ON_FOOT cycle. Prevents the feature from fighting the user.
- [VERIFY] Find where manual mode changes happen so this hook can observe them.

### 6. Permissions
- Requires the `ACTIVITY_RECOGNITION` runtime permission (Android 10+/API 29+),
  in addition to existing location permissions.
- The feature's settings toggle must request it and gracefully disable the feature
  if denied.
- Add to AndroidManifest.xml.

---

## Design decisions (rationale captured so they aren't re-litigated)

1. **Opt-in, not default.** Changes battery behavior; users must enable deliberately.
2. **Fast-in / slow-out hysteresis is the single most important tuning choice.** Enter
   move mode promptly; leave it only after a sustained still period. Asymmetric by design.
3. **Restore the prior mode, not a hardcoded one.** Save `savedPreActivityMode` and
   restore it, so the feature composes with manual settings and other automation.
4. **Detection latency is accepted in v1.** Activity Recognition takes ~30–60s to
   confidently classify walking, so the first ~minute of a walk is still under-sampled.
   A v2 enhancement could use a faster early hint (step counter / significant-motion
   sensor) to trigger move mode sooner, confirmed by Activity Recognition. Out of scope
   for v1.
5. **Activity Recognition itself is low-power** (uses the motion coprocessor, not GPS).
   The only meaningful battery cost is being in move mode during actual walks — which
   is the intended behavior and is bounded to activity periods. That's the whole win:
   move-mode granularity ONLY during walks.
6. **Reuse the existing mode-apply path** (see Phase 0). Do not reimplement mode
   switching. Prefer the narrow CHANGE_MONITORING-style single-preference change over
   the bulk setConfiguration route (which has a known crash, #1838).

---

## Monitoring mode int values [VERIFY in source]
External docs indicate (confirm against the Preferences enum/constants in source):
- move = 2
- significant changes = 1
- manual = (confirm)
- quiet = (confirm)
Use the source's own named constants, not magic numbers.

---

## Build order

1. **Phase 0 source investigation** (above). Update this brief with findings.
2. Get the upstream app building unmodified; sideload to a device; confirm it runs.
3. Add the opt-in preference(s) + ACTIVITY_RECOGNITION permission request flow.
4. Wire ActivityTransition registration + the receiver, LOGGING detected transitions
   only (do NOT change mode yet). Go for real walks/drives and observe detection
   accuracy and latency. This validates the detection layer before coupling it to
   behavior.
5. Connect the receiver to the existing mode-apply method with the fast-in/slow-out
   hysteresis and `savedPreActivityMode` restore.
6. Add manual-override backoff.
7. Field-test across walks, drives, and stationary periods; tune confidence threshold
   and `activityRevertDelaySeconds`.
8. If the feature's mode-switch path triggers the #1838 crash, investigate/fix it
   (likely a prerequisite + separate upstream contribution).
9. Prepare as an upstream PR (this is a broadly useful feature; the project has open
   requests for it).

---

## Testing notes (these are inherently physical / on-device)

- Detection accuracy and latency can only be validated by actually walking/driving with
  the phone. Expect to iterate on real walks.
- Background-execution reliability (doze mode, battery optimization, OEM-specific
  background kills) is the perennial hard part of location apps and is empirical —
  device- and OEM-specific. Budget iteration time here.
- Verify the feature interacts correctly with the existing publish/queue path: a walk
  in move mode should produce a dense, gap-free track at the configured endpoint
  (check the server-side access log for steady POSTs throughout the walk).
- Confirm reverting to the prior mode actually reduces sampling/battery when stationary.

## Scope guardrails

- DO NOT modify the location-processing core, the message/publish pipeline, or the
  HTTP/MQTT queue. This feature only adds a trigger that calls the existing mode-apply
  method.
- DO NOT introduce a new mode-switching mechanism; reuse the existing one.
- Keep the feature fully disabled (no ActivityRecognition registration, zero behavior
  change) when the opt-in preference is off.
