# Shelfit Sentinel

Turn an unused Android phone into an always-on smart-home sensor.

A spare handset is left plugged in somewhere useful. It watches or listens for a
physical cue — the first one being a **double clap** — and runs a configured
action. All sensor interpretation happens on the device.

## Status: stage 2 of 6 — double clap detection

Clapping twice now works end to end on a real device: the microphone hears it, the
detector confirms it, a rule matches it, and the phone buzzes. Nothing leaves the
device and no audio is ever stored.

What exists:

- The full trigger → event → rule → action pipeline, with unit tests
- Real microphone capture via `AudioRecord`, and a deterministic clap detector built
  from peak, background level, attack, crest factor, spectral tilt, transient
  duration and the quiet either side of the sound
- A dashboard with microphone permission handling and a start/stop control
- A developer screen for tuning: live level, tracked background, accepted claps,
  why a loud sound was rejected, and the gap between the two claps
- A settings screen backed by DataStore
- 83 unit tests, including synthetic speech, music, doors and table knocks
- A release build that passes R8 minification (~2 MB APK)

**Detection runs only while the app is in the foreground.** Android suspends
microphone access for backgrounded apps, so unattended operation needs the
foreground service in stage 3.

## How it fits together

```
SENSOR  ->  TRIGGER DETECTOR  ->  TRIGGER EVENT  ->  RULE  ->  ACTION EXECUTOR
```

Today:

```
Microphone -> DoubleClapDetector -> DoubleClapDetected -> rule -> VibrateAction
```

Inside the detector, four replaceable pieces:

```
AudioInput -> ClapFeatureExtractor -> ClapCandidateDetector -> DoubleClapStateMachine
   PCM            measurements           "that was a clap"        "that was two"
```

`AudioInput` is an interface, so the entire analysis chain is tested against
synthetic waveforms with no microphone involved.

Later, without touching the rule or action layers:

```
Camera -> GestureDetector -> OpenPalmDetected -> rule -> GoogleHomeAction
```

Each layer knows only the one before it. A detector is the only thing allowed to
touch a sensor, and it hands on a conclusion rather than raw data. Rules match on a
trigger id, so swapping how a cue is detected does not disturb what happens next.

See [CLAUDE.md](CLAUDE.md) for the architecture in detail and for the rules that
keep it that way.

## How a clap is recognised

Audio arrives as 16 ms frames of 16 kHz mono PCM. Each frame is reduced to a few
numbers — peak, RMS, crest factor, zero-crossing rate, and a high-frequency ratio
computed from the sample-to-sample difference, which stands in for an FFT at a
fraction of the cost. A background level is tracked alongside, falling quickly and
rising slowly, so a brief clap barely moves it while music playing for several
seconds does.

A frame becomes a clap **onset** only if it is both loud and shaped like an impact:

| Test | Rejects |
| --- | --- |
| Peak above an absolute floor | Microphone self-noise in a silent room |
| RMS well above the tracked background | Anything that is merely part of the room |
| Sharp rise versus the previous frame | Voices and instruments, which ramp up |
| High crest factor | Sustained tones |
| High-frequency ratio | Doors, footsteps, low-pitched bangs |
| Quiet run before the onset | Peaks riding on top of speech or music |

The sound then has to *prove* it was a clap by collapsing back to the background
within about 120 ms and staying there. Speech, music and the ring of a slammed door
fail here even when their onset looks percussive. Only then is a clap confirmed —
timestamped at its onset, so the lag never affects timing.

Two confirmed claps 120–900 ms apart make the gesture. Anything closer is treated as
a room reflection and ignored without disturbing the pending first clap; anything
later becomes the first clap of a new pair. A detection is followed by a cooldown, so
a burst of clapping produces one event rather than a stream.

Everything above is tunable from `DoubleClapConfiguration`; the **Clap detector test**
screen shows the live level, the tracked background, and which test rejected the last
loud sound.

## Development stages

| Stage | Scope | State |
| --- | --- | --- |
| **1. Architecture and shell** | Trigger/rule/action abstractions, Compose shell, dashboard, settings, navigation | **Done** |
| **2. Double clap detection** | `AudioRecord` capture, feature-based clap detection, gesture timing, permission handling, developer tuning screen, local haptic feedback | **Done** |
| **3. Always-on operation** | Foreground service so detection survives the UI closing, notification, boot restart, battery measurement over multi-day runs | Next |
| **4. Actions** | `ActionExecutor` implementations and a rule editor so a trigger can be bound to a real action. Google Home integration belongs here | Planned |
| **5. Additional triggers** | Ambient light and accelerometer first (cheap, no camera permission), then camera motion, then hand gestures | Planned |
| **6. Reliability** | Multi-week soak testing, false-positive tuning, thermal behaviour, recovery from revoked permissions | Planned |

Stage 3 is independent of stage 4: detection is already proven by the built-in
vibrate action, so always-on operation can be finished before any smart-home
integration exists.

Deliberately **not** in scope yet: Google Home, motion detection, gesture
recognition. The architecture has extension points for all three; none of them
have speculative implementations.

## Building

Requires JDK 17+ and the Android SDK (platform 37, build-tools 36+). Point the
build at your SDK with `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/android-sdk`.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # 83 unit tests, JVM only, no microphone needed
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
each half-kilobyte PCM buffer is reduced to a handful of numbers and discarded, and
there is no setting that changes this. The app contains no file, cache or network
code path for audio at all.

The microphone is opened only while detection is running, by exactly one class. The
detector's diagnostics report levels and counts, never samples, and a `TriggerEvent`
carries only the gap between the two claps.
