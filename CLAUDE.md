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

### The audio pipeline

`DoubleClapDetector` owns no signal processing itself. It wires four pieces, each
replaceable and each testable alone:

```
AudioInput -> ClapFeatureExtractor -> ClapCandidateDetector -> DoubleClapStateMachine
   PCM            measurements           "that was a clap"        "that was two"
```

| Type | Where | Notes |
| --- | --- | --- |
| `AudioInput` | `core/audio/AudioInput.kt` | Cold `Flow<AudioFrame>`. Interface, so tests feed synthetic PCM |
| `AudioRecordInput` | `platform/audio/` | **The only class that opens the microphone** |
| `ClapFeatureExtractor` | `trigger/audio/` | PCM to scalars; tracks the noise floor. Pure |
| `ClapCandidateDetector` | `trigger/audio/` | Onset gates, then decay verification. Pure |
| `DoubleClapStateMachine` | `trigger/audio/` | Gesture timing. Pure, no clock inside |
| `ClapDiagnostics` | `trigger/audio/` | Audio-only live view for the test screen |
| `ClapCalibration` | `trigger/audio/` | Measurements from a calibration run, and the derivation that turns them into a `ClapProfile` |
| `ClapCalibrator` | `trigger/audio/` | Runs the guided measurement over `AudioInput`. Cold flow of `CalibrationStage` |
| `EventLog` | `core/diagnostics/` | Bounded, in-memory, metadata-only history |

### Sensor Mode

Long-running microphone monitoring, so the screen does not have to stay on.

| Type | Where | Responsibility |
| --- | --- | --- |
| `SensorModeService` | `service/` | The foreground service. `foregroundServiceType="microphone"` |
| `SensorModeController` | `service/` | **The only way anything turns listening on or off.** Persists the desire, then asks the service |
| `SensorModeNotifications` | `service/` | Status notification with Pause/Resume/Open App, plus the attention channel |
| `SensorModeBootReceiver` | `service/` | Reacts to boot and upgrade. Deliberately does *not* start the service |
| `DetectionSupervisor` | `core/sensormode/` | Restarts capture with a backoff when the microphone is lost. Pure |
| `ListeningMode` | `core/sensormode/` | The *desired* state: OFF / LISTENING / PAUSED. Persisted |
| `SensorHealth` | `core/sensormode/` | Everything the health screen needs. Pure, so the derivations are tested |
| `SensorModeStore` | `data/` | Operational state: desired mode, timestamps, last error |
| `SensorEnvironment` | `platform/` | Permissions and the battery exemption, read live |

**Desired state and actual state are separate, and that separation is the design.**
Android can refuse to run a microphone foreground service, and the gap between "the
user wants this on" and "it is on" is what `SensorHealth.resumeRequired` reports. Never
collapse the two.

**Nothing starts listening except through `SensorModeController`,** called from
something the user did while the app was visible. Starting a microphone foreground
service requires while-in-use microphone access, which a visible activity grants and a
background context does not.

**The boot receiver must never start the service.** Android does not permit launching a
microphone foreground service from a background receiver on current versions. The
receiver posts a one-tap resume notification instead. Do not try to work around this.

**No wake lock.** The device is plugged in, so Doze does not engage; `AudioRecord` plus
the foreground service keeps the audio path alive. Adding a wake lock would add a
resource to leak for no benefit.

Detection runs on the **capture timeline** — timestamps derived from sample counts,
not a clock — so it is immune to scheduling jitter and deterministic under test.
`MonotonicClock` is used only to stamp the emitted `TriggerEvent`, because the rule
layer compares that against other triggers.

All per-session state lives inside the `events()` flow rather than in detector
fields, so two collections never share a state machine.

Every detection threshold lives in `DoubleClapConfiguration` (with `ClapProfile` and
`DoubleClapTiming`). **Do not introduce a detection constant anywhere else** —
retuning has to be possible from one file. `sensitivity` scales only the loudness
gates; the character gates define what a clap *is* and loosening them just admits
doors and speech.

### Where a threshold comes from

Precedence, lowest first:

1. `ClapProfile()` defaults — generic, and wrong for most devices.
2. `ClapCalibration.toProfile()` — replaces them with values measured on this device
   in this room, when the user has calibrated.
3. `SensitivityLevel` (Low/Normal/High) — scales the loudness gates on top.
4. `ClapProfile.adaptiveMinPeak()` — raises the absolute peak gate at runtime as the
   room gets busier, bounded by `adaptiveRangeUp`.

`SettingsRepository.triggerConfigurations()` assembles 1–3; the detector applies 4 per
frame. The developer screen displays the result read-only: the way to change a
threshold is to recalibrate or move sensitivity, not to edit derived values behind the
model's back.

**Calibration stores measurements, not thresholds.** `ClapCalibration.toProfile()` is
the single definition of how a room becomes a configuration, so improving the
derivation later benefits everyone who has already calibrated. Do not persist the
derived profile alongside the measurements.

### Continuous-processing cost

The per-sample arithmetic is already negligible — roughly 16,000 sample iterations a
second, a rounding error on any phone. What costs battery on a device left running for
weeks is **how often a thread wakes up**, so `AudioCaptureConfig.readBatchFrames` fetches
several analysis frames per microphone read (64 ms by default) and slices the batch into
frames sharing one buffer at different offsets. Analysis resolution is unchanged.

The batch buffer is allocated fresh per read and never reused, so frames sharing it
cannot be corrupted however far ahead the producer runs. Do not "optimise" that into a
reused buffer: it crosses a dispatcher boundary.

`ClapDiagnostics` publishing is gated on `subscriptionCount`, so a phone with no UI
attached does no UI work at all.

### Adaptive behaviour

Two mechanisms, both about a room that changes rather than a sound in isolation:

- **Adaptive peak gate.** Rises with the tracked ambient *peak*, bounded above by
  `adaptiveRangeUp` and never falling below the calibrated value. It only ever rises:
  a quieter room is already handled by `minAmbientRatio`, which becomes easier to
  satisfy as the noise floor drops, so lowering the absolute gate too would buy
  sensitivity nobody asked for and pay for it in false positives at 3am.
- **Burst suppression.** More than `maxTransientsPerWindow` *clap-shaped* onsets
  inside `transientWindowMillis` and onsets stop being trusted until the room settles.
  Only clap-shaped onsets are counted — speech and music are already rejected by the
  character gates, and letting them inflate the counter would make the reported
  rejection reason less useful without changing the outcome.

The allowance must stay above three: a triple clap is three onsets and must never be
read as a burst.

`ClapDiagnostics` is a deliberate exception to the trigger abstraction: an
audio-only side channel for the test screen. Do not widen `TriggerState` or
`TriggerEvent` with sensor-specific fields to serve one screen.

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
- **Calibration saves derived values only** — ambient level, clap peak levels,
  transient durations, recommended thresholds. `EventLog` is metadata only and lives
  in memory, never on disk, so there is no retention policy to get wrong.

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

Audio is tested without a microphone. `SyntheticAudio.kt` generates seeded waveforms
for claps, speech, music, door thuds and table knocks, and replays them through the
`AudioInput` interface. Two levels matter:

- `ClapDiscriminationTest` asserts **which gate** rejects each everyday sound. An
  end-to-end "emits nothing" assertion would also pass if detection were broken, so
  add cases here when changing a threshold.
- `DoubleClapStateMachineTest` covers gesture timing with synthetic claps at exact
  instants — echoes, expiry, triples, bursts, cooldown.
- `AdaptiveDetectionTest` uses `SyntheticSignal.room()` to change the environment
  partway through a signal, and `impulseTrain()` for hammering and rattles.
- `ClapCalibratorTest` round-trips: calibrate on one signal, then detect with the
  derived profile. That property cannot be established by checking the arithmetic, so
  add a round-trip case when changing the derivation.

Synthetic waveforms validate the logic, not real-world accuracy. Anything about
sensitivity in a real room has to be measured on a device.

## Requires user intervention

Some things Android will not let the app fix by itself. These are product behaviour, not
bugs, and the health screen exists to make each one a single tap:

| Situation | Why | Recovery |
| --- | --- | --- |
| Device rebooted | A microphone foreground service cannot be started from a boot receiver | One tap on the resume notification |
| App upgraded | The process is killed; same restriction applies | One tap |
| Process killed | Sticky restart is attempted, but Android may refuse to re-promote | One tap, or automatic if the restart is allowed |
| Microphone permission revoked | Retrying cannot grant a permission | Grant it, then Resume |
| Another app takes the microphone | Transient | **Automatic**, with exponential backoff |
| Notifications disabled | Service still runs, but Pause/Resume are unreachable | Health screen offers the request or settings |
| OEM battery killer | Not exposed by any public API | Health screen guides to the exemption list |

## Current state

Stage 3. Sensor Mode runs double clap detection in a foreground service, so the screen
can be off. Detection works end to end on device: microphone to `TriggerEvent` to rule to
a vibration. `VibrateAction` is local feedback and the
only action that exists; Google Home, motion and gesture recognition are later
stages with extension points but no implementations.

Screens: dashboard, settings, `ui/health` — setup and health checks — `ui/calibration`
— the guided flow — and `ui/claplab`,
a developer screen showing live level, the tracked background, the adaptive gate, the
thresholds in force, rejection reasons and the event log.

Sensitivity is exposed to users as **Low / Normal / High**; numeric thresholds are
read-only on the developer screen. Do not put raw scalars in the settings screen —
"0.63" tells nobody whether their claps will register.

**Known limitation.** A dry, broadband impact — a hard strike on a table — clears
every feature gate and is accepted as a clap. This is pinned by a test in
`ClapDiscriminationTest` rather than hidden. Separating the two needs timbre
modelling, which is where a classifier would earn its place.

**Known limitation.** Two impulses in clap timing are two impulses in clap timing.
Regular hammering at roughly two to four strikes a second will fire once before burst
suppression engages. Eliminating that would mean waiting to see whether a third
impulse follows, which adds latency to every legitimate detection — a trade-off worth
making deliberately, not by accident.

**Known limitation.** In a room whose noise floor approaches clap level, no threshold
works. Calibration reports this as poor headroom, or fails outright with advice,
rather than shipping a configuration that cannot succeed.

Do not build ahead of the current stage. Extension points, yes; speculative
features, no.
