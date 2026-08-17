# Completing the Google Home integration — a walkthrough

Everything on this app's side of the smart-home boundary is built and tested. What is
missing is the Google Home APIs Android SDK, which **Google does not publish to Maven** —
it is a ZIP download behind a signed-in developer account, hosted locally as a Maven
repository. That is why `GoogleHomeClient` is empty rather than guessed at.

This document is the walkthrough. Do the steps in order; step 0 can stop the whole thing,
so do it first.

## What is verified here and what is not

| Claim | Confidence |
| --- | --- |
| `./gradlew :app:signingReport` prints the SHA-1 you need | **Verified** — run in this repo |
| The SDK is not on Google Maven or Maven Central | **Verified** — probed both, and Google's own docs say so |
| Hub requirement, Android Studio version, emulator limitation, 100-user cap | From Google's current docs (linked below) |
| Console menu paths | From Google's docs, but console UIs get reorganised — treat as "look for something like this" |
| Gradle coordinates and versions | **Unknown.** They are inside the download. Do not let anyone guess them for you |
| Kotlin API names in step 7 | From Google's official codelab, but the API is **open beta and may change**. Check them against the SDK you actually download |

The Home APIs are in open beta. Anything below may have moved; Google's own documentation
wins over this file.

---

## Step 0 — Check you can actually do this

Three hard requirements. If you fail any of them, stop here and read
"[If you cannot meet step 0](#if-you-cannot-meet-step-0)" at the end.

**A Google/Nest hub on your Wi-Fi.** The Home APIs route through a hub. Google's SDK page
currently lists: Nest Audio, Nest Hub (1st and 2nd generation), Nest Hub Max, Nest Mini,
Google TV Streamer (4K), and Nest WiFi Pro. Without one, the API has nothing to talk to.

**A physical Android phone**, Android 10 or later, with Google Play Services and the Google
Home app installed, signed in to the Google account that owns the home. Google states
plainly that **an emulator will not work.** Since the point of this project is an old phone
left plugged in, you likely have this already.

**At least one supported light or smart plug** already set up and working in the Google Home
app. Confirm you can switch it from the Home app before writing any code — otherwise you
will be debugging your integration against a device that was never working.

Also install **Android Studio 2024.2.1 ("Ladybug") or later**. Google's codelab asks for
2024.3.1; install the current stable release and you are comfortably past both.

---

## Step 1 — Get your SHA-1

Google identifies your app by its package name **plus** the SHA-1 fingerprint of the key it
was signed with. Get the fingerprint first, because everything in step 3 needs it.

Run this **on your own machine**, in the repository root:

```bash
./gradlew :app:signingReport
```

You will get a block per variant. Take the SHA-1 from the one that reads `Variant: debug`:

```
Variant: debug
Config: debug
Store: /Users/you/.android/debug.keystore
Alias: AndroidDebugKey
SHA1: F2:1E:8F:BF:87:99:B9:F6:D8:44:95:92:7D:19:79:66:8E:46:1D:4E
```

Copy the `SHA1:` line. **Not** MD5, **not** SHA-256 — Google wants SHA-1, and using the
wrong one produces an authorisation error at runtime that looks like a network fault.

Two things to know:

- **Run it on your machine, not in a cloud session.** The debug keystore is generated per
  machine. A fingerprint from a throwaway container is worthless, and the one printed during
  this project's development is not yours.
- **The debug key is fine to start with.** If you later build a release APK, that is a
  different key and needs a second OAuth client registered the same way. A release build
  signed with an unregistered key fails at *runtime*, not at build time — which is a
  confusing way to lose an evening, so register it before you need it.

---

## Step 2 — Create a Google Cloud project

Go to the Google Cloud Console and create a project for this app, or pick an existing one.
Use the Google account that owns the home from step 0 — the API grants access to *your*
home, so the account matters.

Note the project name. Steps 3 and 5 both need it, and it is easy to end up with
credentials in one project and the SDK enabled in another.

---

## Step 3 — Configure the OAuth consent screen and client

This is the fiddly part and the most common place to get stuck.

**3a. Consent screen.** In the Cloud Console, go to **APIs and Services → Credentials**. If
the consent screen is not configured yet, choose **Configure consent screen**, then:

1. Pick **External** unless your account is in a Google Workspace organisation, in which
   case **Internal** is simpler.
2. Fill in the **App information** page — app name, your support email.
3. **Skip the scopes page.** Google's Home APIs documentation says scope configuration is
   not required here. Do not add scopes speculatively.
4. Add **test users**: your own Google account, the one that owns the home. This step is not
   optional — with an External, unverified app, only listed test users can grant access, and
   omitting yourself means your own consent attempt is rejected.
5. Review and save.

**3b. OAuth client.** Still under **Credentials**, create an **OAuth 2.0 Client ID** with
application type **Android**, and give it:

- Package name: `com.shelfit.sentinel`
- SHA-1: the fingerprint from step 1

Repeat with your release key's SHA-1 if you have one.

**A cap to be aware of:** Google documents a **100-user limit** while you are using an
existing OAuth client, lifted once you complete registration in the Home Developer Console.
For testing on your own phone this does not matter. It matters if you ever distribute the
app.

---

## Step 4 — Join Google Home Developers and download the SDK

Sign in at the Google Home Developers site. You need to be signed in before the SDK download
is visible at all — this is the gate that stops the coordinates being guessable.

Download the SDK ZIP, then follow Google's instructions to install it as a **local Maven
repository**. In outline, that means unpacking the archive somewhere stable and adding
`mavenLocal()` (or a `maven { url = ... }` pointing at the unpacked directory) to the
repositories block, so Gradle can resolve the libraries from disk instead of the network.

In this project, repositories are declared in `settings.gradle.kts`, and dependencies in
`app/build.gradle.kts`.

**Use the coordinates and versions from the download itself** — they are in the archive, and
the sample app Google ships (`google-home-api-sample-app-android`) has a working
`build.gradle` you can read them from. Do not accept a coordinate from a blog post, an AI
answer, or this file. A wrong dependency fails in a way that looks like a network problem
and will waste your time.

Add whatever manifest entries, permissions or Gradle plugins Google's setup page asks for.
There are none in this repository today, and I have not guessed at any.

---

## Step 5 — Prove the SDK works before touching our code

Build and run Google's own sample app against your account, hub and light. Get it switching
a real device.

This is worth the twenty minutes. If you go straight to implementing our class and nothing
works, you cannot tell whether the problem is your OAuth setup, your hub, your device, or
your code. The sample app eliminates the first three.

---

## Step 6 — Two structural changes our code needs

Before implementing, two things in the app have to change. Both are small, and neither is
guesswork — they follow from how the SDK is initialised.

**The client needs a `Context`.** `GoogleHomeClient` currently takes no constructor
arguments. Google's initialisation is:

```kotlin
val registry = FactoryRegistry(types = supportedTypes, traits = supportedTraits)
val config = HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry)
homeClient = Home.getClient(context = context, homeConfig = config)
```

So it becomes `GoogleHomeClient(context)`, constructed in `AppContainer`, which already has
the application `Context`. That is enough for `getClient`.

**The permission flow needs an `Activity`, and that plumbing does not exist yet.** Google's
consent flow requires:

```kotlin
homeClient.registerActivityResultCallerForPermissions(activity)
```

`AppContainer` holds an application `Context`, not an `Activity`, deliberately — it outlives
every screen. So `MainActivity.onCreate` will have to hand itself to the client
(`ComponentActivity` is an `ActivityResultCaller`, so it satisfies the parameter).

Do that carefully: **the client must not keep the Activity past its lifetime.** The
`SmartHomeClient.connect()` documentation already says it must be called while the app is
visible, so the design anticipated this — but the wiring is genuinely not written, and a
retained Activity in a process meant to run for weeks is a leak that will show up as
climbing memory rather than a crash.

---

## Step 7 — Implement `GoogleHomeClient`

`platform/smarthome/GoogleHomeClient.kt` has one `TODO(home-sdk)` per member. It is the only
file in this codebase where a Google type may appear — a vendor type escaping it breaks the
boundary the rest of the app relies on, and `PipelineBoundaryTest` will fail if the executor
or the audio pipeline acquires one.

Here is how our interface maps onto the API as Google's codelab documents it. **Verify each
name against the SDK you downloaded** — this is a beta API, and these are the names as
published, not names I have compiled.

| Our member | Google's API |
| --- | --- |
| `connect()` | `registerActivityResultCallerForPermissions(activity)`, then `requestPermissions(forceLaunch = true)`; success is `result.status == PermissionsResultStatus.SUCCESS` |
| `state` | `homeClient.hasPermissions()` emits `PermissionsState`; ignore `PERMISSIONS_STATE_UNINITIALIZED` and map the rest onto our `SmartHomeState` |
| `refresh()` | Re-read `hasPermissions()` |
| structures | `homeClient.structures().list()` returns `Set<Structure>`; `structures().collect { }` for live updates |
| `devices(structureId)` | `structure.rooms().list()`, then `room.devices().list()` for each |
| device id / name | `device.id.id`, `device.name` |
| `reachable` | `device.sourceConnectivity.connectivityState` |
| `DeviceKind` | Inspect `device.types()`, take the type whose `metadata.isPrimaryType` is true, and map only lights and outlets — everything else is `UNSUPPORTED` |
| `isOn` | Read the `OnOff` trait |
| `execute(ON/OFF)` | `OnOff` trait: `trait.on()` / `trait.off()` |
| `execute(TOGGLE)` | Read `OnOff` first, then send the inverse. If it cannot be read, fail that device with `DEVICE_STATE_UNKNOWN` |

Four rules the implementation has to keep. These are not style preferences — each one is
load-bearing somewhere else in the app:

1. **Never throw for an expected condition.** No network, withdrawn consent, an unplugged
   lamp: return a `SmartHomeFailure`. An exception escaping here reaches a foreground service
   that has to keep running for weeks.
2. **One result per requested device**, whatever happened. The executor counts entries to
   tell "all worked" from "2 of 3 worked", so a missing entry is silently counted as a
   success.
3. **Only lights and outlets.** Everything else is `DeviceKind.UNSUPPORTED` — listed so the
   user can see it was found, never operated. Guessing at how to drive an unfamiliar device
   is how an automation does something alarming.
4. **Toggle reads before it writes.** A toggle that guesses direction is worse than one that
   declines.

`SimulatedSmartHomeClientTest` is the executable statement of that contract. An
implementation that behaves the way those tests describe is behaving correctly, and you can
point the same expectations at your real client.

---

## Step 8 — Switch the wiring

In `AppContainer.kt`, `GoogleHomeClient()` is already the `real` provider handed to
`SelectableSmartHomeClient`. Once it works, turn the simulated home **off** in
Settings → Smart home and the app uses the real one. Nothing else changes.

---

## Step 9 — Test on real hardware

None of this can be verified without your account and devices:

- **Consent, and declining it.** Decline deliberately: the app should say "not connected",
  not show an error. Declining is a choice, not a fault.
- **Multiple homes.** If your account has more than one, the picker appears; confirm
  switching changes the device list.
- **An unsupported device.** A thermostat or camera should be listed and greyed out, never
  switched.
- **A device that does not report state.** Toggle should be refused, On and Off should still
  work. Some older plugs behave this way and many do not, so you may not have one — the
  simulator covers it if not.
- **An offline device.** Unplug a lamp, then clap. The automation should report that one as
  offline and still switch the others.
- **Withdraw access** in your Google account settings, then reopen the smart-home screen. It
  should report it without being prodded.
- **No network.** Turn off Wi-Fi and clap. One clear "no network" message, not one error per
  device.
- **Latency.** Time the gap between the clap and the light. This is the number that decides
  whether the feature feels good, and it cannot be predicted from here — it depends on your
  network, your hub and Google's servers.

---

## If you cannot meet step 0

If you have no Nest hub, or your devices are not supported, the Google path is closed for
now. Nothing you have is wasted: the app's smart-home layer is written against
`SmartHomeClient`, not against Google, so an alternative provider is one new implementation
of that interface and no changes anywhere else. Local network protocols reachable without a
hub or a cloud account would be the thing to look at, and the boundary is already in the
right place for it.

In the meantime the simulated home exercises the entire flow, including every failure path.

---

## What is still not built, deliberately

- **Server-side automations.** If the Home APIs offer them, they would let Google evaluate a
  grouped action rather than the phone. `SmartHomeClient.execute` takes the whole target list
  and returns one report specifically so this can be added inside one file later. Direct
  per-device control is the right first implementation because its failures are individually
  attributable, which is what the UI reports.
- **Devices beyond lights and plugs.** Out of scope for a first version.
- **Brightness and colour.** These need a richer device model, and there is no point
  designing one before something needs it.

---

## Sources

- [Add the Home APIs to your Android app](https://developers.home.google.com/apis/android/sdk)
- [Set up OAuth for your Android app](https://developers.home.google.com/apis/android/oauth)
- [Build a mobile app using the Home APIs on Android (codelab)](https://developers.home.google.com/codelabs/home-apis-android-build-mobile-app)
- [Home APIs Android Sample App](https://developers.home.google.com/apis/android/sample-app/build)
- [Home APIs overview](https://developers.home.google.com/apis)
