# CLAUDE.md

Guidance for working in this repository.

## Product purpose

Shelfit Sentinel turns a spare Android phone into an always-on smart-home sensor.
The device sits plugged in, watches or listens for a physical cue, and performs a
configured action. The first cue is a **double clap** heard through the microphone;
later stages add camera gestures, ambient light, and movement.

Because the device is expected to run continuously, battery and thermal behaviour
are product requirements, not optimisations.

## Architecture

One pipeline, five layers, each depending only on the one before it:

```
SENSOR  ->  TRIGGER DETECTOR  ->  TRIGGER EVENT  ->  RULE  ->  ACTION EXECUTOR
```

| Concept | Type | Responsibility |
| --- | --- | --- |
| Trigger | `core/trigger/Trigger.kt` | Metadata only: id, name, required sensors and permissions, default config |
| TriggerConfiguration | `core/trigger/TriggerConfiguration.kt` | Per-trigger user tuning; each trigger defines its own |
| TriggerDetector | `core/trigger/TriggerDetector.kt` | The **only** layer that touches a sensor. Returns a cold `Flow<TriggerEvent>` |
| TriggerEvent | `core/trigger/TriggerEvent.kt` | A confirmed detection. Carries a conclusion, never raw sensor data |
| TriggerState | `core/trigger/TriggerState.kt` | Detector lifecycle, surfaced in the dashboard |
| TriggerEngine | `core/trigger/TriggerEngine.kt` | Starts/stops enabled detectors, merges their events |
| AutomationRule | `core/rule/AutomationRule.kt` | "When trigger X fires, run action Y", plus confidence floor and cooldown |
| AutomationCoordinator | `core/rule/AutomationCoordinator.kt` | Matches events to rules and dispatches. The whole rule layer |
| Action | `core/action/Action.kt` | Data describing what to do |
| ActionExecutor | `core/action/ActionExecutor.kt` | Performs one family of actions |
| ActionDispatcher | `core/action/ActionDispatcher.kt` | Routes an action to the executor that accepts it |

Everything under `core/` is plain Kotlin with no Android imports, so it is fully
unit-testable on the JVM. Android-specific implementations live in `platform/`.

Wiring is hand-rolled in `AppContainer.kt` — no DI framework. **`AppContainer` is
the extension point**: register a detector in `triggerRegistry`, register an
executor in `actionDispatcher`. Nothing else changes.

### Adding a new trigger

1. Add a `Trigger` (metadata) and a `TriggerConfiguration` for its knobs.
2. Implement `TriggerDetector`. Return a cold flow; release the sensor when the
   flow is cancelled.
3. Register the detector in `AppContainer.triggerRegistry`.
4. Map settings to its configuration in `SentinelSettings.triggerConfigurations()`.
5. Add its `SensorKind` to `core/sensor/SensorKind.kt` if the hardware is new, and
   handle it in `AndroidSensorStatusProvider`.

The rule, action, and UI layers should need no changes. If a change forces them
to, the abstraction is wrong — fix the abstraction rather than reaching around it.

### Non-negotiables

- **New sensors go through `TriggerDetector`.** Never open a microphone, camera, or
  `SensorManager` from an Activity, a ViewModel, or a Composable. If sensor code
  appears outside a detector, that is a defect.
- **Detectors emit conclusions, not data.** Audio buffers and camera frames stay
  inside the detector.
- **`events()` must be cold and cancellation-safe.** Cancelling the flow has to
  release the hardware; that is the app's only battery brake.

## Privacy principles

These are product commitments, not preferences:

- **Sensor processing happens on the device.** No sensor data leaves the phone for
  interpretation. If a future feature genuinely needs off-device inference, it is
  opt-in, disclosed, and never the default.
- **Audio is never stored.** No recording to disk, no cache files, no upload —
  the microphone buffer is analysed in memory and discarded. This holds by default
  and there is no setting that turns it off.
- **Only requested sensors are opened**, only while listening is active.
- **Logs and `TriggerEvent.detail` carry no sensor content** — trigger identity and
  confidence only.

## SDK levels

| | |
| --- | --- |
| `minSdk` | 29 (Android 10) |
| `targetSdk` | 36 (Android 16) |
| `compileSdk` | 37 |

`compileSdk` is ahead of `targetSdk` on purpose: current AndroidX requires
compiling against 37, while `targetSdk` 36 keeps runtime behaviour on Android 16.
Lint's `OldTargetApi` warning is the expected consequence.

## Toolchain

AGP 9.3.1, Gradle 9.7, Kotlin 2.4.10, JDK 17 bytecode. AGP 9 compiles Kotlin
itself — **there is no `kotlin-android` plugin**, but the Compose compiler plugin
is still applied separately and must track the Kotlin version.

Dependencies are deliberately few: Compose + Material 3, Navigation Compose,
Lifecycle, DataStore Preferences, coroutines. Prefer a local vector drawable or a
few lines of Kotlin over a new dependency.

## Build and test commands

`ANDROID_HOME` must be set, or `sdk.dir` present in `local.properties`.

```bash
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleRelease        # R8-minified release build
./gradlew :app:installDebug           # install on a connected device
./gradlew build                       # everything
```

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`
Lint report: `app/build/reports/lint-results-debug.html`

Unit tests use `kotlinx-coroutines-test`; fakes live in
`app/src/test/kotlin/com/shelfit/sentinel/core/Fakes.kt`. There are no
instrumented tests yet.

## Current state

Stage 1: architecture, app shell, dashboard, settings, navigation.
`DoubleClapDetector` implements the full lifecycle but captures no audio — it
reports `TriggerState.Reason.NOT_IMPLEMENTED` and emits nothing. The dashboard
says so rather than pretending to listen. See `README.md` for the stage plan.

Do not build ahead of the current stage. Extension points, yes; speculative
features, no.
