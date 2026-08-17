# Connecting Tuya / Smart Life — a walkthrough

Tuya is the working smart-home provider in this build. **No hub is required** — unlike the
Google path, which needs a Nest hub on the network.

If your lights or plugs are in the **Smart Life** or **Tuya Smart** app, or in a brand app
built on Tuya, this should work. Roughly twenty minutes, all of it in a browser and on your
phone; nothing to compile.

## What you need

- One or more Tuya lights or smart plugs, already working in the Smart Life or Tuya Smart app
- The phone running Shelfit, with internet access
- A Tuya developer account — free to create

No hub, no paid subscription, no SDK download.

## Why the Cloud API and not Tuya's Android SDK

Tuya publishes a full app SDK (`com.thingclips.smart:thingsmart`). It would work, and it is
the wrong tool here: it brings fastjson, okhttp, native libraries for two ABIs and an embedded
V8 JavaScript engine into an app whose whole premise is running quietly on an old phone for
weeks, and it wants to own account login.

This integration uses three REST endpoints over `HttpsURLConnection`, with request signing in
`javax.crypto`. **No new dependencies at all** — the same reasoning as the hand-written
`RuleCodec`.

---

## Step 1 — Find out which region your account is in

Do this first. Choosing the wrong data centre later is the single most common way this setup
fails, and it presents as "invalid credentials" rather than as a region problem.

On your phone, in Smart Life or Tuya Smart:

**Me → Settings → Account and Security → Region**

Write it down.

---

## Step 2 — Create a Tuya developer account and a Cloud project

1. Sign in at [iot.tuya.com](https://iot.tuya.com/).
2. In the left menu choose **Cloud**, then **Create Cloud Project**.
3. Fill in:
   - **Project Name** — anything, e.g. `Shelfit Sentinel`
   - **Industry** — whichever fits; it does not affect the API
   - **Development Method** — **Smart Home**
   - **Data Center** — the one serving the region from step 1

Tuya publishes a mapping between app-account regions and data centres. If you are unsure,
pick the obvious geographic match; you can change it later, and a mismatch simply means no
devices appear.

---

## Step 3 — Authorize the API services

After the project is created, an **Authorize API Services** screen appears. Enable at least
**Device Status Notification**, alongside the services already selected by default, then
confirm with **Authorize**.

> **The one operational wart.** Tuya's IoT Core connection service runs on a **one-month free
> trial**. When it lapses the API stops answering and your automations stop switching
> anything — the app will report it, but it will still have stopped. Tuya grants extensions
> free on request, approved in about one to two working days.
>
> For a device meant to run unattended, put a calendar reminder in now. This is the strongest
> argument for moving to local LAN control later; see the end of this document.

---

## Step 4 — Link your app account

This is the step that makes your own devices visible, and the tab matters.

1. Open your project and go to the **Devices** tab.
2. Choose **Link Tuya App Account** → **Add App Account**.
3. A QR code appears.
4. In Smart Life / Tuya Smart, tap **Me**, then the scan icon, and scan it.
5. Confirm on the phone.
6. Back in the browser, open **All Devices** — your lights and plugs should be listed.

**Do not link your account under the Users tab.** It looks like the right place and it is not;
device access comes from the Devices-tab link. If devices do not appear, unlink and relink from
that tab.

If nothing appears here, stop and fix it before touching the app. No amount of correct
configuration in Shelfit will conjure devices that the console cannot see either.

---

## Step 5 — Copy the two keys

Go to your project's **Overview** page and find the **Authorization Key** block. It holds:

- **Access ID** (sometimes labelled Client ID)
- **Access Secret** (sometimes labelled Client Secret)

Reveal the secret and copy both.

---

## Step 6 — Enter them in Shelfit

On the phone: **Settings → Smart home setup**.

1. Under **Smart home**, choose **Tuya / Smart Life**.
2. Paste the **Access ID** and **Access Secret**.
3. Pick the **data centre** matching step 2.
4. Tap **Save and connect**.

The Connection card should read **Connected**, and the device list should fill with your
lights and plugs. Anything the app cannot switch — a thermostat, a camera — is listed and
greyed out rather than hidden, so you can see it was found.

The secret is not shown again once saved. Replacing it means typing both keys again, which is
a deliberate trade for not holding a credential in a screen's state.

---

## Step 7 — Make an automation

**Automations → Add automation**:

- **When**: Double clap
- **Do**: Turn lights or smart plugs on, off, or toggle them
- Choose one or more devices, then pick **Turn on**, **Turn off** or **Toggle**

Toggle is only offered when every chosen device reports whether it is currently on. Some Tuya
devices do not, and a toggle that guesses direction can switch a lamp *on* at 3am as easily
as off.

Save, turn on Sensor Mode, and clap twice.

---

## When something does not work

| Symptom | Most likely cause |
| --- | --- |
| "Not connected" right after saving keys | Access ID or Secret mistyped — the secret is long, re-copy it |
| Connected, but no devices | App account not linked, or linked under **Users** instead of **Devices** |
| Connected, no devices, keys definitely right | Data centre does not match your account region — step 1 |
| Worked for weeks, then stopped | IoT Core trial lapsed. Request an extension |
| "Permission withdrawn" | Token rejected — the app account may have been unlinked |
| One device fails, others work | That device is offline, or exposes no on/off control this app recognises |
| Toggle greyed out | One chosen device does not report its state. Use Turn on or Turn off |

The smart-home screen reports each of these in words rather than failing silently, and the
`ui/claplab` developer screen shows the outcome of every automation run, including
"2 of 3 switched".

---

## What is stored on the phone, and where

The Access ID and Access Secret live in the app's private DataStore. On an unrooted device
that is not readable by other apps, **but it is not encrypted**. Anyone with the unlocked
phone, or a backup of it, could extract them.

That is a considered trade rather than an oversight — the alternative, `EncryptedSharedPreferences`,
adds a dependency and stores its key in the same keystore an attacker with device access already
has. If it matters to you, the keys can be revoked and regenerated in the Tuya console at any
time, which is the real mitigation.

Nothing else changes about the app's privacy behaviour. Audio is still analysed in memory and
discarded; a device command carries a device id and a verb, and only when a rule fires.

---

## Where this is implemented

Three files know Tuya exists, and nothing above them does:

| File | Responsibility |
| --- | --- |
| `platform/smarthome/tuya/TuyaSignature.kt` | Request signing. Pure Kotlin, unit-tested against Tuya's published formula |
| `platform/smarthome/tuya/TuyaCloudApi.kt` | Signed HTTP, token caching, error mapping |
| `platform/smarthome/tuya/TuyaCloudClient.kt` | Implements `SmartHomeClient` |

The rule engine, the clap detector and every screen outside smart-home settings were unchanged
by adding this — which is what the `SmartHomeClient` seam was for.

### Deliberate simplifications

- **One "home".** Tuya's model is an account with devices; this app's is homes containing
  devices. The linked account is presented as a single structure rather than guessing at a
  homes API. Per-home separation, if wanted, belongs in `TuyaCloudClient` and nowhere else.
- **Every command reads state first.** Tuya has no uniform on/off — a bulb answers to
  `switch_led`, a socket to `switch_1`, a plug to `switch`. The code has to be discovered from
  the device's own status, and reading it also supplies the value a toggle needs. One extra
  round trip, in exchange for working on devices this code has never seen.
- **Commands are sent one device at a time.** A rule points at a handful of devices, and a
  burst of parallel requests at a rate-limited API is a worse failure than a few hundred extra
  milliseconds.

---

## Worth doing next: local control

Tuya devices accept commands directly over the LAN, which would remove the cloud round trip
and the trial expiry in one move — and would suit a phone sitting on a shelf far better than a
round trip to a data centre.

The catch is that the LAN protocol is community-reverse-engineered rather than documented, and
each device needs a **local key** that is only obtainable from the cloud API. So the setup
above is not wasted work — it is a prerequisite either way.

If you want that, it is one more `SmartHomeClient` implementation beside the three files above.
Nothing else in the app would change.

---

## Sources

- [Tuya IoT Development Platform](https://iot.tuya.com/)
- [Tuya IoT Platform Configuration Guide](https://github.com/tuya/tuya-home-assistant/wiki/Tuya-IoT-Platform-Configuration-Guide)
- [Authentication and request signing](https://developer.tuya.com/en/docs/iot/authentication-method?id=Ka49gbaxjygox)
- [Device Control API reference](https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7)
- [Get User's Device List](https://developer.tuya.com/en/docs/cloud/cacc9c4989?id=Ka7kk03zdecl4)
- [Extending the IoT Core trial](https://support.tuya.com/en/help/_detail/Kc3n6kr7kllhc)
