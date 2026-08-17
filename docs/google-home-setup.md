# Completing the Google Home integration

Everything on this app's side of the smart-home boundary is built and tested. What is
missing is the Google Home APIs Android SDK, which is not in this repository and did not
resolve as a normal Gradle dependency when this was written.

This document separates what is already done from what needs your Google account, your
signing key and your physical devices.

> **Verification note.** The steps below describe the *shape* of the setup Google requires
> for its Home APIs — a Developer Console project, an OAuth client tied to your app's
> package name and signing certificate, and an SDK obtained from Google. The exact console
> screens, menu labels, dependency coordinates and class names change between releases, and
> they are not reproduced here because guessing them would be worse than useless. Follow
> Google's own current Home APIs documentation for the specifics, and treat this as the
> checklist of what to look for.

---

## Already done — no account or hardware needed

| Piece | Where |
| --- | --- |
| The app's smart-home vocabulary: homes, devices, commands, failures, state | `core/smarthome/SmartHome.kt` |
| The provider interface every implementation satisfies | `core/smarthome/SmartHomeClient.kt` |
| Shared device list for the screens | `core/smarthome/SmartHomeDirectory.kt` |
| The action a rule stores: device ids plus a verb | `core/action/Action.kt` |
| The executor that performs it | `core/action/SmartHomeActionExecutor.kt` |
| Partial-success reporting for multi-device actions | `core/action/ActionResult.kt` |
| Persistence of device selections, with `v1` → `v2` migration | `data/RuleCodec.kt` |
| Connect / choose-home / device-list screen | `ui/smarthome/` |
| Device picker and command chooser in the rule editor | `ui/rules/` |
| A simulated home covering every failure condition | `platform/smarthome/SimulatedSmartHomeClient.kt` |
| The empty seam the SDK goes into | `platform/smarthome/GoogleHomeClient.kt` |

All of it is covered by JVM unit tests that run without a device: 274 tests pass, including
every error condition — not connected, permission withdrawn, no network, home unavailable,
device offline, device removed, unsupported device, unknown state, refused command, and
partial failure across several devices.

**You can try the whole flow right now** without any Google setup: Settings → Smart home →
*Use a simulated home* → Connect → pick a home. Then Automations → add one → *Turn lights or
smart plugs on, off, or toggle them* → choose devices. Clapping twice switches the simulated
devices, and the fault switches on the same screen let you see what the app does about each
failure.

---

## What you need to do

### 1. Decide which package name and signing key you are registering

Google ties an OAuth client to a specific package name **and** signing certificate
fingerprint. Get this right first, because changing it later means redoing the registration.

- Package name: `com.shelfit.sentinel`
- Debug signing key: the default Android debug keystore, at `~/.android/debug.keystore`

Read the debug key's SHA-1:

```bash
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android \
  -keypass android
```

Copy the `SHA1:` line from the output.

If you intend to install a release build, you need a release keystore too, and its SHA-1
registered as a second OAuth client. A build signed with a key Google does not recognise
will fail authorisation at runtime, not at build time — which is a confusing failure, so it
is worth doing both up front.

### 2. Create a Google Cloud / Home Developer Console project

In Google's Home Developer Console, create a project for this app and enable the Home APIs
for it. You will need a Google account that also owns or is a member of the home containing
the devices you want to control — the API grants access to *your* home, so testing requires
an account that actually has one.

### 3. Create an OAuth client for the Android app

Create an OAuth 2.0 client of the Android application type, and give it:

- the package name from step 1
- the SHA-1 fingerprint from step 1

Repeat for the release key if you have one.

### 4. Obtain the Home APIs Android SDK

Follow Google's current instructions for adding the SDK. This may be a Maven dependency, a
download, or gated behind an access request — it was not publicly resolvable when this
document was written, so check what is true now rather than assuming.

Add it to `app/build.gradle.kts` exactly as Google's documentation states. **Do not guess
coordinates**: a wrong dependency fails in a way that looks like a network problem.

### 5. Add whatever manifest or configuration the SDK requires

Some Google SDKs need a `google-services.json`, a manifest entry, or a Gradle plugin. There
is none in this repository today. Add only what Google's documentation actually asks for.

### 6. Implement `GoogleHomeClient`

`platform/smarthome/GoogleHomeClient.kt` has one `TODO(home-sdk)` per member describing what
it must produce. That file is the only place a Google type may appear — a vendor type
leaving it breaks the boundary the rest of the app depends on, and a test will fail if the
executor acquires one.

Four rules the implementation has to keep, all of them load-bearing:

1. **Never throw for an expected condition.** No network, withdrawn consent, an unplugged
   lamp: return a `SmartHomeFailure`. An exception escaping reaches a foreground service
   that has to keep running.
2. **One result per requested device**, whatever happened. The executor counts entries to
   distinguish "all worked" from "2 of 3 worked", so a missing entry reads as a success.
3. **Only lights and outlets.** Map anything else to `DeviceKind.UNSUPPORTED`. It will be
   listed so the user can see it was found, and never operated.
4. **Toggle reads before it writes.** If a device's on/off state cannot be determined, fail
   that device with `DEVICE_STATE_UNKNOWN`. A toggle that guesses is worse than one that
   declines.

`SimulatedSmartHomeClientTest` is the executable statement of this contract. An
implementation that behaves the way those tests describe is behaving correctly.

### 7. Switch the wiring

In `AppContainer.kt`, `GoogleHomeClient()` is already the `real` provider passed to
`SelectableSmartHomeClient`. Once it works, nothing else needs changing — turn the simulated
home off in Settings and the app uses the real one.

---

## Then test on real hardware

None of this can be verified without your account and devices:

- **Connect and consent.** The consent screen needs a visible activity. Check what happens
  when you decline: the app should say "not connected", not show an error.
- **Multiple homes.** If your account has more than one, the home picker appears. Confirm
  that switching homes changes the device list.
- **A device this app does not support.** A thermostat or a camera should be listed and
  greyed out, never switched.
- **A device that does not report state.** Confirm Toggle is refused for it and On/Off still
  work. Some older plugs behave this way; many do not, so you may not have one.
- **An offline device.** Unplug a lamp, then clap. The automation should report that device
  as offline and still switch the others.
- **Withdraw access.** Remove the app's access in your Google account settings, then reopen
  the smart-home screen. It should say access was withdrawn without being prodded.
- **No network.** Turn off Wi-Fi and clap. One clear "no network" message, not one error per
  device.
- **Latency.** Time the gap between the clap and the light. This is the number that decides
  whether the feature feels good, and it cannot be predicted from here — it depends on your
  network, Google's servers and the device.

## What is still not built, deliberately

- **Server-side automations.** If the Home APIs offer them, they would let Google evaluate a
  grouped action rather than the phone. `SmartHomeClient.execute` takes the whole target list
  and returns one report specifically so this can be added inside one file later. Direct
  per-device control is the right first implementation because its failures are individually
  attributable, which is what the UI reports.
- **Devices beyond lights and plugs.** Deliberately out of scope for a first version.
- **Anything other than on, off and toggle.** Brightness and colour need a richer device
  model, and there is no point designing one before something needs it.
