# Shelfit Sentinel

Turn an unused Android phone into an always-on smart-home sensor.

A spare handset is left plugged in somewhere useful. It watches or listens for a
physical cue — the first one being a **double clap** — and runs a configured
action. All sensor interpretation happens on the device.

## Status: stage 5 of 8 — clap twice, a real light switches

Clapping twice works end to end on a real device, **with the screen off**: a foreground
service holds the microphone, the detector confirms the gesture, a rule matches it, and the
phone buzzes. Nothing leaves the device and no audio is ever stored.

An automation can now point at smart-home devices instead — lights and smart plugs, on, off or
toggle, one or several — and **Tuya works end to end**. If your lights are in the Smart Life or
Tuya Smart app, twenty minutes of browser setup connects them; no hub, no SDK download, and no
new dependency in the app. [`docs/tuya-setup.md`](docs/tuya-setup.md) is the walkthrough.

Google Home is a different story. Its SDK is a ZIP behind a signed-in developer account and the
APIs need a Nest hub, so the one file it belongs in is deliberately empty rather than filled with
guessed API calls — see [`docs/google-home-setup.md`](docs/google-home-setup.md). A simulated home
covers every failure path without any account at all.

Microphones, room acoustics and noise floors differ enough that one fixed threshold
cannot serve every phone, so detection is **calibrated**: a guided flow measures the
room and a handful of the user's own claps, derives thresholds from them, and lets the
user try the result before saving it.

What exists:

- The full trigger → event → rule → action pipeline, with unit tests
- Real microphone capture via `AudioRecord`, and a deterministic clap detector built
  from peak, background level, attack, crest factor, spectral tilt, transient
  duration and the quiet either side of the sound
- Guided calibration, with a quality verdict when the room and the claps are too
  close together to separate
- An adaptive peak threshold that tightens as a room gets busier, bounded so it never
  drifts into deafness, plus burst suppression for hammering and rattles
- Sensitivity as **Low / Normal / High**, with numeric thresholds shown read-only on
  a developer screen
- A metadata-only event log — `18:43:12  Clap candidate  confidence 0.91`
- Sensor Mode: a `microphone` foreground service with Pause, Resume and Open App in its
  notification, automatic recovery when another app takes the microphone, and a health
  screen that names anything blocking unattended use
- An editable rule system — WHEN a trigger fires, DO an action — with three local
  actions: vibrate, show a notification, write to the log
- A smart-home action: choose devices, choose on/off/toggle, get per-device results
  including partial success — working against Tuya, with three providers behind one interface
- Passive LAN discovery of Tuya devices — no account, nothing sent, listens only
- 306 unit tests, including synthetic speech, music, doors, table knocks, changing
  room noise, rapid transient bursts, simulated microphone outages, the full
  clap → rule → executor path, every smart-home failure condition, the Tuya request
  signature, and LAN discovery packets in every frame format
- A release build that passes R8 minification (~2.4 MB APK)

The intended device is an old phone left plugged in. The screen does not need to stay
on, and nothing needs doing day to day — but Android reserves a few situations for the
owner, listed under [What still needs you](#what-still-needs-you).

## How it fits together

```
SENSOR  ->  TRIGGER DETECTOR  ->  TRIGGER EVENT  ->  RULE  ->  ACTION EXECUTOR
```

Today:

```
Microphone -> DoubleClapDetector -> DoubleClapDetected -> your rule -> SmartHomeDeviceAction
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
Camera -> GestureDetector -> OpenPalmDetected -> rule -> SmartHomeDeviceAction
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
screen shows the live level, the tracked background, the adaptive gate, the thresholds
in force, and which test rejected the last loud sound.

## Calibration

Generic thresholds are a compromise nobody fits. Calibration replaces them with
measurements:

1. **Measure the room** for four seconds with nobody clapping. Produces a mean level
   and a high percentile of frame peaks — the level the room actually *reaches*,
   rather than its average, because one cough must not define a room.
2. **Collect five claps** from where the system will really be used. Collection runs
   with deliberately loose gates anchored to the ambient level just measured, since
   the thresholds being established cannot also be the thresholds used to observe.
3. **Derive thresholds.** The peak gate is placed at the geometric midpoint of the gap
   between the room's peaks and the softest clap — the middle of the gap in decibels,
   which is how loudness behaves. The decay window comes from the longest observed
   clap, which is really a measurement of the room's reverberation. The brightness
   floor tracks the microphone's high-frequency response, so a bright microphone ends
   up *stricter* than the default and rejects more thuds.
4. **Report quality.** If the room is nearly as loud as the claps, no threshold works;
   the flow says so rather than shipping numbers that cannot succeed.
5. **Try it before saving.** The new profile runs through the real pipeline, vibration
   included, while still unsaved.

Only derived numbers are stored — ambient level, clap peak levels, transient
durations. Measurements are persisted rather than the thresholds computed from them,
so improving the derivation later benefits anyone who has already calibrated.

## Adapting to a changing room

Calibration fixes a baseline; two mechanisms handle drift from it.

The **absolute peak gate** rises with the tracked ambient peak, bounded to four times
the calibrated value. It only ever rises — a quieter room is already handled by the
ratio gate, which becomes easier to satisfy as the noise floor drops, so lowering the
absolute gate too would buy sensitivity nobody asked for and pay for it in false
triggers overnight.

**Burst suppression** stops trusting onsets when more than four clap-shaped impulses
arrive within two seconds. A double clap is two and a triple is three, so it engages
only on genuine bursts: applause, hammering, cutlery in a drawer.

## Automations

A trigger on its own does nothing. An automation says what should happen when one fires:

```
WHEN  Double clap
DO    Toggle Living room lamp
```

Editable from the **Automations** screen: add several, point them at different actions,
disable one without deleting it, set how long to wait before the same rule may run again.
Rules are persisted, so they survive restarts and upgrades.

Four actions exist. Three are local to the phone — vibrate, show a notification, write to the
log — and the fourth switches smart-home devices: lights and smart plugs, on, off or toggle,
one device or several. Pick several and the outcome is reported per device, because "2 of 3
lamps switched" is neither a success nor a failure and reporting it as either would hide a
problem or invent one.

Toggle is only offered when every chosen device reports whether it is currently on. A toggle
that guesses which way to switch something is worse than one that declines.

**The detector knows nothing about any of this.** It reports "a double clap happened" and
stops. The rule layer decides what that means, and an executor carries it out. Adding the
smart-home action touched the action layer and nothing else — no audio code, no detector, no
rule engine. Tests assert that boundary rather than trusting a comment: pass anything from the
action package into a detector, or let a smart-home type reach the audio pipeline, and the
build fails.

The same separation holds one level down. A provider sits behind a single interface, so the
entire app is written in terms of "homes, devices and commands" rather than any vendor's SDK.
That is what makes every failure — no network, an unplugged lamp, consent withdrawn last
Tuesday — a unit test rather than an afternoon of unplugging lamps.

Reserved identifiers exist for camera motion, hand gestures, ambient light and device
movement. They are names only — no detectors, no permissions, and the editor does not
offer them, because it offers what the app can actually detect. They exist so that rules
written against them by a future version stay readable by this one.

## Sensor Mode

Turning Sensor Mode on starts a foreground service typed `microphone`. That is the only
legitimate way to keep a microphone open with the screen off, and its notification is
not an obstacle to route around — it is where Pause and Resume live, and how the owner
of the phone can tell it is listening.

Pausing releases the microphone but keeps the service alive, so Resume stays one tap
away in the notification rather than requiring the app to be reopened.

**Recovery.** A phone left running for weeks will lose the microphone occasionally: a
call arrives, an assistant wakes, the audio server restarts. Capture is restarted with
an exponential backoff — five seconds, then ten, then twenty, capped at five minutes —
because the usual cause lasts seconds to minutes and retrying every second for the
length of a phone call would waste power to no purpose. A revoked microphone permission
is treated differently: retrying cannot grant a permission, so the app stops and says so
instead of looping.

**Battery.** No wake lock is taken. The device is plugged in, Doze does not engage while
charging, and `AudioRecord` plus a foreground service keeps the audio path alive — so a
wake lock would be one more resource to leak for no benefit. The microphone is read in
64 ms batches rather than 16 ms ones: the arithmetic was never the cost, thread wake-ups
are, and analysis resolution is unaffected.

## What still needs you

Android does not allow an app to start microphone monitoring by itself from the
background, and that restriction is correct — a phone should not be able to start
listening after a reboot without its owner knowing. So a few situations need one tap,
and the app's job is to make it exactly one:

| Situation | What happens | What you do |
| --- | --- | --- |
| Phone restarted | Notification: "Sensor Mode is paused — tap to resume listening" | One tap |
| App updated | Same notification | One tap |
| Process killed | A restart is attempted; if Android refuses, the same notification | One tap, often nothing |
| Microphone permission revoked | Listening stops, notification explains | Grant it, then Resume |
| Another app using the microphone | Retries on its own, backing off | Nothing |
| Notifications switched off | Listening still works, but its controls are invisible | Health screen offers to fix it |
| Phone stops listening by itself | Some manufacturers kill background apps regardless of foreground services | Exempt the app from battery optimisation |
| Smart-home access withdrawn | Automations report it and stop switching devices | Reconnect in Settings → Smart home |

**Battery optimisation** is genuinely optional. Stock Android will not stop a listening
foreground service for it, so the health screen says so rather than crying wolf. It
matters on manufacturer builds that are more aggressive. The app links to Android's own
exemption list rather than requesting a direct exemption dialog: that dialog needs the
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google Play restricts to a
short list of app categories, and two extra taps is a better trade than a policy risk.

## Development stages

| Stage | Scope | State |
| --- | --- | --- |
| **1. Architecture and shell** | Trigger/rule/action abstractions, Compose shell, dashboard, settings, navigation | **Done** |
| **2. Double clap detection** | `AudioRecord` capture, feature-based clap detection, gesture timing, permission handling, developer tuning screen, local haptic feedback | **Done** |
| **3. Always-on operation** | Foreground service, notification controls, boot and upgrade handling, automatic recovery, health screen | **Done** |
| **4. Actions** | Rule editor, persisted rules, local debug actions | **Done** |
| **5. Smart home** | A smart home as an action provider: connect, pick devices, on/off/toggle | **Done** — Tuya working, Google blocked on its SDK |
| **6. Local device control** | Tuya over the LAN — removes the cloud round trip and the trial expiry | Discovery **done**, control next |
| **7. Additional triggers** | Ambient light and accelerometer first (cheap, no camera permission), then camera motion, then hand gestures | Next |
| **8. Reliability** | Multi-week soak testing, false-positive tuning, thermal behaviour, recovery from revoked permissions | Planned |

Battery draw over multi-day runs, and false-positive rates in a real room, can only be
measured on a physical device — that measurement belongs to stage 8.

**The smart-home stage is done.** Three providers sit behind one interface:

| Provider | State |
| --- | --- |
| **Tuya / Smart Life** | **Working.** Cloud API over `HttpsURLConnection`, no hub, no new dependency |
| Google Home | Blocked — SDK is a login-gated download, and the APIs need a Nest hub |
| Simulated home | A pretend home covering every failure path, for development |

Adding Tuya changed no audio code, no rule code and no screen outside smart-home settings.
That is the return on putting a seam there: `platform/smarthome/tuya/` is three files, and
nothing above them knows Tuya exists.

Tuya's app SDK was available and was turned down — it brings fastjson, okhttp, native libraries
for two ABIs and an embedded V8 engine into an app built to run quietly on an old phone for
weeks. Three REST endpoints, `javax.crypto` for signing and `org.json` for parsing cost nothing.

One wart worth knowing: Tuya's IoT Core service is a **one-month free trial**, extendable free on
request. Local LAN control removes both that and the cloud round trip, and is the next step:
**discovery is already built** — the app finds Tuya devices on the Wi-Fi, with their IPs and
protocol versions, using no account at all. [`docs/tuya-lan.md`](docs/tuya-lan.md) covers what
local control needs, including the one thing that genuinely requires the cloud once: the
per-device local key.

Deliberately **not** in scope yet: motion detection and gesture recognition. The architecture
has extension points for both; neither has a speculative implementation.

## Building

Requires JDK 17+ and the Android SDK (platform 37, build-tools 36+). Point the
build at your SDK with `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/android-sdk`.

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # 306 unit tests, JVM only, no microphone needed
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

Connecting a smart home does not change any of that. The app gained `INTERNET` for one purpose:
sending a device id and a verb when a rule fires. **No sensor data leaves the phone** — there is
no code path that could send it. Nothing is linked until you connect it, and disconnecting drops
the app's authorisation.

Provider credentials live in the app's private storage. That is not readable by other apps on an
unrooted device, but it is not encrypted either, and `docs/tuya-setup.md` says so plainly rather
than implying more protection than exists.
