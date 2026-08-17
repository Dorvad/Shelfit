# Shelfit Sentinel

Turn an unused Android phone into an always-on smart-home sensor.

A spare handset is left plugged in somewhere useful. It watches or listens for a
physical cue — the first one being a **double clap** — and runs a configured
action. All sensor interpretation happens on the device.

## Status: stage 1 of 6 — architecture and shell

This build compiles, installs, and runs. What it does **not** yet do is detect
anything: there is no microphone capture in this stage. Pressing **Start
listening** exercises the real detector lifecycle, and the dashboard reports the
double clap detector as not yet implemented rather than claiming to listen.

What exists:

- The full trigger → event → rule → action pipeline, with unit tests
- A dashboard showing sensor status, the double clap trigger, its unconfigured
  action, and a start/stop control
- A settings screen backed by DataStore (clap enable, sensitivity, keep screen on)
- Navigation between the two screens
- A release build that passes R8 minification (~2 MB APK)

## How it fits together

```
SENSOR  ->  TRIGGER DETECTOR  ->  TRIGGER EVENT  ->  RULE  ->  ACTION EXECUTOR
```

Today:

```
Microphone -> DoubleClapDetector -> DoubleClapDetected -> rule -> (no action yet)
```

Later, without touching the rule or action layers:

```
Camera -> GestureDetector -> OpenPalmDetected -> rule -> GoogleHomeAction
```

Each layer knows only the one before it. A detector is the only thing allowed to
touch a sensor, and it hands on a conclusion rather than raw data. Rules match on a
trigger id, so swapping how a cue is detected does not disturb what happens next.

See [CLAUDE.md](CLAUDE.md) for the architecture in detail and for the rules that
keep it that way.

## Development stages

| Stage | Scope | State |
| --- | --- | --- |
| **1. Architecture and shell** | Trigger/rule/action abstractions, Compose shell, dashboard, settings, navigation | **Done** |
| **2. Double clap detection** | Real microphone capture in `DoubleClapDetector`: runtime permission request, RMS onset detection, two-peak timing, sensitivity tuning | Next |
| **3. Always-on operation** | Foreground service so detection survives the UI closing, notification, boot restart, battery measurement over multi-day runs | Planned |
| **4. Actions** | `ActionExecutor` implementations and a rule editor so a trigger can be bound to a real action. Google Home integration belongs here | Planned |
| **5. Additional triggers** | Ambient light and accelerometer first (cheap, no camera permission), then camera motion, then hand gestures | Planned |
| **6. Reliability** | Multi-week soak testing, false-positive tuning, thermal behaviour, recovery from revoked permissions | Planned |

Stages 2 and 3 are independent of stage 4: detection can be proven with the
built-in log action before any smart-home integration exists.

Deliberately **not** in scope yet: Google Home, motion detection, gesture
recognition. The architecture has extension points for all three; none of them
have speculative implementations.

## Building

Requires JDK 17+ and the Android SDK (platform 37, build-tools 36+). Point the
build at your SDK with `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/android-sdk`.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # 23 unit tests, JVM only
./gradlew :app:lintDebug            # lint
./gradlew :app:installDebug         # install on a connected device
```

## Technical choices

Native Android, Kotlin, Jetpack Compose with Material 3. AGP 9.3.1 / Gradle 9.7 /
Kotlin 2.4.10. `minSdk` 29 (Android 10), `targetSdk` 36 (Android 16), `compileSdk`
37.

Dependencies are kept short on purpose — Compose, Navigation, Lifecycle, DataStore,
coroutines, and nothing else. There is no DI framework; the object graph is a dozen
objects wired by hand in `AppContainer.kt`. Detectors expose cold `Flow`s so that
cancelling collection releases the sensor, which is the main lever the app has over
battery drain.

## Privacy

Sensor data is interpreted on the device. **Audio is never written to storage** —
buffers are analysed in memory and discarded, with no setting that changes this.
Sensors are opened only while listening is active, and logs record trigger identity
and confidence, never sensor content.
