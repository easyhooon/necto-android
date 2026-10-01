# Verification

How necto-android was checked against the real Necto Mac app. Use it as the Test Plan
of a pull request, and update it when behavior changes.

## Environment

| | |
| --- | --- |
| Necto (Mac) | 0.2.0 (`Necto-0.2.0.dmg`, sha256 verified) |
| Device | Samsung Galaxy S25+ (SM-S936N), Android 17 (API 37), USB |
| Connection | `adb forward tcp:9979 tcp:9979` |
| Apps | `sample` (Views and a Compose screen) and a production Jetpack Compose app |

## Automated tests

`./gradlew build` runs them all.

| Module | Tests | What they cover |
| --- | --- | --- |
| necto-core | 19 | Protocol models and JSON, schema validation, SDK runtime, plugin behavior |
| necto-okhttp | 7 | Request and response capture, capped large bodies, failures and `RuntimeException`s, streams that are not held back, bodies closed unread, large uploads |
| necto-android | 7 (Robolectric) | UI Control on Views and Compose: listing with labels, test tags, values and secure fields; tap, text input (replace and append) and swipe on Compose; a touch-transparent full-window overlay does not hide content, while a clickable one does |

The overlay test fails when the hit test treats every visible view as a blocker,
which was the regression found on the production app.

## Manual checks on a device

Driven through `necto` CLI (`plugin send`) and the Mac app's panels.

| Plugin | Check | Result |
| --- | --- | --- |
| Connection | App appears in the sidebar; device row shows `<device name> · Android 17` | ✅ |
| Connection | App restarted five times; reconnects with all six plugins | ✅ |
| Events | `events.list` shows info and warn events reported by the app | ✅ |
| Network | OkHttp requests listed with status, duration, size, headers, body and cURL | ✅ |
| Network | Production app: login and data requests listed live in the panel | ✅ |
| Preferences | Every DataStore listed by name; `preferences.set` updates the running app's UI | ✅ |
| Files | `files.roots`, `files.list` on the app's data directory | ✅ |
| Performance | CPU, memory, FPS (≈120 on a 120 Hz display while scrolling), threads, Java and native heap | ✅ |
| Control (Views) | Targets listed; tap, text input (Korean text) | ✅ |
| Control (Compose) | Button, text field, switch and lazy list listed with test tags | ✅ |
| Control (Compose) | Tap ×2 → counter 2; input replace then append; switch 0 → 1; swipe scrolls the list | ✅ |
| Control (Compose) | Production app login screen: buttons listed under androidx.core's `ProtectionLayout` | ✅ |

## Known limitations

- Dialogs, popups and bottom sheets live in other windows and are not reached by UI Control.
- The Mac app labels `osVersion` as iOS, so the SDK sends it empty and puts the Android version in the device name.
- An app that is not in the foreground has no UI Control targets.
