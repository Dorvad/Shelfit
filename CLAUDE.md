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
| ActionCatalogue | `core/action/ActionCatalogue.kt` | The actions this build offers, and type→action for persistence |
| ActionExecutor | `core/action/ActionExecutor.kt` | Performs one family of actions |
| ActionDispatcher | `core/action/ActionDispatcher.kt` | Routes an action to the executor that accepts it |
| RuleRepository | `data/RuleRepository.kt` | Persists the user's rules. The only thing the rule layer reads |
| SmartHomeClient | `core/smarthome/SmartHomeClient.kt` | **The provider seam.** A vendor SDK appears in one implementation and nowhere else |

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

### The trigger/action boundary

**A detector must never reference the action layer.** It reports a `TriggerEvent`; what
happens next is not its business. This is the property that will let a smart-home action
be added without touching a single line of audio code, and it is enforced by
`PipelineBoundaryTest` rather than by comments — the moment an `ActionDispatcher` is
passed into a detector "just to make the notification easier", that test fails.

The asymmetry is deliberate and worth stating: `AutomationRule` references both a
`TriggerId` and an `Action`; neither references the rule. If you find yourself wanting a
detector to know about a rule, the thing you actually want belongs in
`AutomationCoordinator`.

`TriggerEvent` carries identity, timing, confidence and a small `detail` map — never an
`Action`, and never sensor content.

### Adding an action

1. Add the `Action` data type in `core/action/Action.kt`.
2. Implement an `ActionExecutor` for it in `platform/`.
3. Register the executor in `AppContainer`'s dispatcher.
4. Add an `ActionKind` to the catalogue so the rule editor offers it.

`AppContainer`'s initialiser asserts that every catalogued action has an executor, so
forgetting step 3 is a crash on your machine rather than a rule that silently does
nothing on a user's.

An action that needs per-rule configuration implements `Action.parameters` and gives its
`ActionKind` a factory, as `SmartHomeDeviceAction` does. **The action owns both halves of its
own encoding** — `parameters` and `fromParameters` — so neither `RuleCodec` nor the rule
editor knows its keys. Set `requiresConfiguration` so the editor refuses to save a rule that
would fire and do nothing.

`RuleCodec` is at `v2`. It still reads `v1` records, and any future field must keep doing the
equivalent: a user who has tuned their automations should never lose them to an app update.

### The smart-home layer

One interface, `core/smarthome/SmartHomeClient.kt`, is the entire contract a provider has
to satisfy. **A vendor SDK may appear in exactly one implementation of it and nowhere
else.** That is what keeps the audio pipeline, the rule engine and the UI free of vendor
types, and it is enforced by `PipelineBoundaryTest`.

| Type | Where | Responsibility |
| --- | --- | --- |
| `SmartHome.kt` | `core/smarthome/` | The app's own vocabulary: structure, device, command, failure, state |
| `SmartHomeClient` | `core/smarthome/` | The seam. Every method returns a value; expected conditions are never exceptions |
| `SmartHomeDirectory` | `core/smarthome/` | The chosen home's device list, shared by the connect screen and the rule editor |
| `SmartHomeDeviceAction` | `core/action/Action.kt` | Device ids plus a verb. No provider type, no network |
| `SmartHomeActionExecutor` | `core/action/` | Turns the action into commands. In `core/` because it needs no Android |
| `TuyaCloudClient` | `platform/smarthome/tuya/` | **The working provider.** Tuya Cloud API over `HttpsURLConnection` |
| `TuyaCloudApi` / `TuyaSignature` | `platform/smarthome/tuya/` | Signed HTTP and the HMAC-SHA256 signing, which is pure and unit-tested |
| `GoogleHomeClient` | `platform/smarthome/` | Where the Home APIs SDK goes. **Currently reports `NotConfigured`** |
| `SimulatedSmartHomeClient` | `platform/smarthome/` | A pretend home for development |
| `SelectableSmartHomeClient` | `platform/smarthome/` | Routes to the provider named by `SmartHomeProvider` |

**Tuya is the provider that works.** It needs no hub and no SDK: three REST endpoints over
`HttpsURLConnection`, signed with `javax.crypto`, and `org.json` for parsing — all of it already
in the platform, so the integration added **zero dependencies**. Tuya's own app SDK
(`com.thingclips.smart:thingsmart`) was rejected deliberately: it brings fastjson, okhttp, native
libraries for two ABIs and an embedded V8 engine into an app whose premise is running quietly for
weeks. See `docs/tuya-setup.md`.

`TuyaSignature` is pure Kotlin and heavily tested, because a signing bug arrives as a generic
authorisation error indistinguishable from a mistyped secret. **Do not "simplify" it** — the blank
Signature-Headers line, the uppercase hex and the sorted query string are all load-bearing.

**The Google Home APIs Android SDK is not in this repository and did not resolve from
Google's Maven or Maven Central** — it is a ZIP behind a signed-in developer account, and the
Home APIs additionally require a Nest hub. `GoogleHomeClient` is therefore deliberately empty,
with `TODO(home-sdk)` on each member describing what it must produce. Do not fill it in with
guessed coordinates, classes or method names. See `docs/google-home-setup.md`.

Rules a provider implementation must keep:

- **Never throw for an expected condition.** No network, withdrawn consent, an unplugged
  lamp — all of those are `SmartHomeFailure` values. An exception here reaches a foreground
  service that has to stay alive.
- **One result per requested device**, whatever happened. A missing entry is counted as a
  success by the executor's arithmetic.
- **Only lights and outlets are switchable.** Anything else is `DeviceKind.UNSUPPORTED` —
  listed so the user can see it was found, never operated.
- **Toggle reads before it writes.** Fail the device with `DEVICE_STATE_UNKNOWN` rather than
  guessing a direction.

`ActionResult.Partial` exists because one action can span several devices: "2 of 3 lamps
switched" is neither success nor failure, and collapsing it into either would hide a problem
or overstate one.

If the Home APIs later offer server-side automations for groups of actions,
`SmartHomeClient.execute` is already shaped for it — it takes the whole target list and
returns one report, so that becomes a change inside one file. Direct per-device control is
the right first implementation because its failures are individually attributable.

### Non-negotiables

- **New sensors go through `TriggerDetector`.** Never open a microphone, camera, or
  `SensorManager` from an Activity, a ViewModel, or a Composable. If sensor code
  appears outside a detector, that is a defect.
- **Detectors emit conclusions, not data.** Audio buffers and camera frames stay
  inside the detector.
- **`events()` must be cold and cancellation-safe.** Cancelling the flow has to
  release the hardware; that is the app's only battery brake.
- **Detectors know nothing about actions.** Enforced by test. See above.
- **The coordinator must be collecting before events flow.** `TriggerEngine.events` has
  no replay, so an event emitted with nothing listening is dropped. `AppContainer` starts
  the coordinator in its initialiser for exactly this reason — do not make it lazy.
- **A vendor smart-home SDK lives in one file.** Behind `SmartHomeClient`, in
  `platform/smarthome/`. Nothing in `core/`, `trigger/`, `ui/` or `service/` may import one.
  Enforced by test.

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

The smart-home integration is the worked example: HTTP is `HttpsURLConnection`, JSON is
`org.json`, signing is `javax.crypto` — all already in the platform. A vendor app SDK was
available and was turned down for what it would have cost in size, native libraries and battery.

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

## Rules

`RuleRepository` persists rules in one string preference via `RuleCodec`: one line per
rule, `|`-separated, text fields percent-encoded. A hand-written codec because there are
seven fields and the dependency list is deliberately short.

Two decoding behaviours are deliberate and tested:

- **A blob always opens with a version line,** so an empty rule list is still a non-empty
  string. That is what distinguishes "the user deleted every rule" from "never
  configured", and stops defaults being re-seeded over a deliberate choice.
- **Unknown action types and unknown trigger ids keep the rule,** with that part
  unresolved. A downgrade, or a rule written by a later version, must not silently delete
  the user's configuration.

The editor offers what the `TriggerRegistry` and `ActionCatalogue` contain, so it can only
ever offer things that exist. Reserved future `TriggerId`s are declared in
`core/trigger/Trigger.kt` and deliberately have no detector, so they never appear as
options.

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

Stage 6, complete. Clap twice and a real light switches: the pipeline runs from microphone to
smart-home device, in a foreground service, with the screen off.

Four actions: vibrate, show a notification, write to the log — all entirely local — and switch
smart-home devices. Three providers sit behind `SmartHomeClient`: **Tuya works**, Google reports
`NotConfigured` until its SDK is available, and a simulator covers every failure path without
hardware. Camera, gesture, light and movement triggers remain later stages with extension points
and no implementations.

Screens: dashboard, settings, `ui/rules` — the rule editor, including the device picker —
`ui/smarthome` — connect, choose a home, see what can be switched — `ui/health` — setup and
health checks — `ui/calibration` — the guided flow — and `ui/claplab`, a developer screen
showing live level, the tracked background, the adaptive gate, the thresholds in force,
rejection reasons and the event log.

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

**Known limitation.** Tuya's IoT Core connection service is a one-month free trial, extendable
free on request. When it lapses the API stops answering and automations stop switching — the app
reports it, but it has still stopped. This is the strongest argument for adding local LAN control,
which would also remove the cloud round trip. Local control needs a per-device key that only the
cloud API supplies, so the cloud setup is a prerequisite either way.

**Known limitation.** Google Home cannot be reached at all. `GoogleHomeClient` reports
`NotConfigured`; the SDK is not obtainable from this repository and the Home APIs need a Nest hub.
The user-facing text says so rather than failing mysteriously.

Do not build ahead of the current stage. Extension points, yes; speculative
features, no.
