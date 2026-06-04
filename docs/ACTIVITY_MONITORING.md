# Activity-Triggered Adaptive Monitoring

An **opt-in** feature that automatically increases location accuracy and frequency while you are
walking or running, then returns to your normal settings once you stop. It captures walks and
hikes in detail without leaving the battery-hungry **Move** mode on permanently.

## Why

`Significant` monitoring mode is battery-efficient but under-records walks: it uses balanced
(non-GPS) accuracy and a large displacement filter, so the OS rarely wakes the app during slow,
local movement. `Move` mode records walks well but keeps GPS firing on a fixed interval the whole
time it's on, which drains the battery. This feature gives you `Move`-like detail **only while you
are actually on foot**, then backs off — at a lower battery cost than `Move`.

## What it does

While on-foot activity is detected, the app **boosts the locator** (it does **not** change your
monitoring mode):

| Setting | While on foot |
|---|---|
| Priority | High accuracy (GPS) |
| Interval | [`activityOnFootLocatorInterval`](PREFERENCES.md) (default `25` s) |
| Displacement | [`activityOnFootLocatorDisplacement`](PREFERENCES.md) (default `30` m) |

This is applied as a **runtime override** — your stored `locatorPriority`, `locatorInterval`, and
`locatorDisplacement` are left untouched and simply take effect again once you become stationary.
Because the boost keeps a displacement filter, you get a clean, distance-based track (a point
roughly every N metres) rather than `Move` mode's unconditional, time-based fixes.

> The defaults above are conservative starting points and are expected to be refined after
> real-world field testing.

## Requirements

- The **Google Play Services (gms)** build. Activity Recognition is a Play Services API; in the
  `oss` / F-Droid build the feature is hidden and does nothing.
- The **Physical Activity** runtime permission (`ACTIVITY_RECOGNITION`, Android 10+). Enabling the
  toggle requests it.
- **Precise** location. With only **Approximate** location, high-accuracy GPS is silently
  downgraded to coarse, so the boost would gain nothing — the app therefore **skips the boost** and
  shows a tappable warning in settings to grant Precise location.

## Enabling it

**Settings → Advanced → Locator → "Adapt monitoring to activity"**

When you turn it on, the app requests the Physical Activity permission. If location is set to
Approximate, a warning appears beneath the toggle — tap it to grant Precise location (on Android 12+
this offers the Precise/Approximate upgrade dialog; if the system won't re-prompt, it offers app
settings).

## Behaviour details

- **Fast-in:** the moment on-foot activity is detected, the boost is applied.
- **Slow-out (hysteresis):** when you become still or start driving, the boost is **not** removed
  immediately. A timer of [`activityRevertDelaySeconds`](PREFERENCES.md) (default `180` s) is armed;
  only if you stay stationary until it fires is the boost reverted. A renewed on-foot detection
  (e.g. a pause at a crosswalk) cancels the pending revert.
- **Detection latency:** Activity Recognition takes roughly 30–60 s to confidently classify
  walking, so the first minute or so of a walk may still be under-sampled.
- **Already in Move mode:** the boost is skipped, since Move already tracks densely at high
  accuracy.
- **Manual override:** if you change the monitoring mode yourself (via the UI, the
  `CHANGE_MONITORING` intent, or a remote command) while the feature is active, your choice wins —
  the boost is cleared (so your new mode runs with your own locator settings) and auto-boosting is
  suspended until a clean *still → on-foot* cycle, so the feature won't fight you.
- **Process death:** if the app's process is killed mid-walk, any leftover boost is cleared when the
  service restarts (so it can't drain the battery); if you're still walking, it re-boosts shortly.

## Limitations

- Background-execution reliability (doze mode, OEM battery optimisation) is device-specific, as with
  all background location features; disabling battery optimisation for OwnTracks is recommended.

## Configuration keys

See the [preferences reference](PREFERENCES.md): `autoMonitoringByActivity`,
`activityOnFootLocatorInterval`, `activityOnFootLocatorDisplacement`, `activityRevertDelaySeconds`.
All are exportable, so the feature can also be enabled and tuned via a configuration file or a
remote `setConfiguration` command.
