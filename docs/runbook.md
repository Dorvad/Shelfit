# Getting from here to "clap twice, the lamp switches" — the runbook

Every step you need to do, in order. Roughly an hour end to end, most of it waiting on
downloads. Nothing costs money.

Steps 1–4 are yours. Step 5 is mine, and I need one thing from you to start it.

> **What never leaves your machine.** Step 4 produces your devices' local keys and your Tuya
> Access Secret. Those control your devices. Do not paste them into a chat with me, do not
> commit them, do not put them in a screenshot. I only need the **protocol version numbers** —
> see step 5.

---

## Step 0 — Get the app onto the phone

You cannot run the scan in step 2 until the app is installed. Skip this if it already is.

### What you need on your computer

- **JDK 17 or newer** — `java -version` to check
- **The Android SDK** — easiest via [Android Studio](https://developer.android.com/studio),
  which brings the SDK, `adb`, and the emulator
- Either `ANDROID_HOME` set, or a `local.properties` in the repo root containing
  `sdk.dir=/path/to/android-sdk`

### Get the code

```bash
git clone https://github.com/Dorvad/Shelfit.git
cd Shelfit
git checkout claude/android-smart-home-sensor-g78u58
```

### Put the phone in developer mode

On the phone, in Settings:

1. **About phone** → tap **Build number** seven times. It will say you are now a developer.
2. Back in Settings → **System** → **Developer options** → turn on **USB debugging**.
3. Plug the phone into the computer. Accept the "Allow USB debugging?" prompt — tick
   *always allow* so it does not ask again.

Check the computer can see it:

```bash
adb devices
```

You want a line ending in `device`. If it says `unauthorized`, look at the phone for the
prompt. If the list is empty, try a different cable — charge-only cables are common.

### Build and install

```bash
./gradlew :app:installDebug
```

First run downloads Gradle and the Android build tools, so expect several minutes. Later runs
take seconds.

**No USB cable?** Build the APK and transfer it however you like:

```bash
./gradlew :app:assembleDebug
# produces app/build/outputs/apk/debug/app-debug.apk
```

Copy it to the phone and open it. Android will ask you to allow installing from that source.

### First run

Open **Shelfit Sentinel** and:

1. Grant the **microphone** permission when asked.
2. Grant **notifications** — without it the Pause and Resume controls have nowhere to live.
3. Go to **Settings → Calibrate now** and follow the guided flow. Four seconds of quiet, then
   five claps. Worth the minute: generic thresholds are a compromise nobody fits.
4. On the dashboard, turn on **Sensor Mode** and clap twice. The phone should buzz.

If the phone buzzes, the whole pipeline works and the only thing left is what it talks to.

---

## Step 1 — Prepare the network

Five minutes, and it prevents the two commonest failures later.

**Turn off AP isolation.** In your router's admin pages, look for *AP isolation*, *client
isolation*, *station isolation*, or *guest network*. Any of these stop devices on the same
Wi-Fi from talking to each other, which silently breaks local control. Make sure the phone and
the smart devices are on a normal, non-guest network with isolation off.

**Put the phone and the devices on the same Wi-Fi.** Same network name, and if your router
publishes separate 2.4 GHz and 5 GHz names, that is usually still one subnet — but check that
"guest" is not in the name. Most Tuya devices are 2.4 GHz only.

**Reserve the devices' IP addresses.** In the router's DHCP settings, find each smart plug or
bulb and give it a fixed address (often called *DHCP reservation* or *static lease*). Local
control connects by address, so a lease that moves overnight breaks an automation with no
error anywhere. Do this once and forget it.

---

## Step 2 — Scan the local network

Two minutes, no account needed. This is the step that tells us how much work step 5 is.

In the app: **Settings → Smart home setup**, choose **Tuya / Smart Life**, then scroll to
**Local network** and tap **Scan local network**.

It listens for eight seconds. Tuya devices announce themselves every few seconds, so anything
awake should appear with its IP address and protocol version.

**Write down the protocol version of each device.** That is the number I need.

### If nothing appears

In order of likelihood:

1. **AP isolation is on** — step 1. By far the commonest cause.
2. **The phone is on a different network** from the devices — guest Wi-Fi, or mobile data
   because the Wi-Fi dropped.
3. **The devices are asleep or unplugged.** Switch one on from the Smart Life app first, then
   scan again.
4. **A VPN is active on the phone.** Turn it off and rescan; some VPNs capture all traffic
   including the local subnet.

A device can also be found but report no version. That is still useful — tell me and I will
work out what it needs.

---

## Step 3 — Create the Tuya cloud project

About twenty minutes. **This is the step that gives you working automations today**, and it is
the only source of the local keys that step 4 collects.

Full walkthrough with the gotchas: **[`docs/tuya-setup.md`](tuya-setup.md)**. In summary:

1. In Smart Life / Tuya Smart on the phone: **Me → Settings → Account and Security → Region**.
   Write it down. Getting this wrong later looks exactly like a wrong password.
2. Sign in at [iot.tuya.com](https://iot.tuya.com/) → **Cloud** → **Create Cloud Project**.
   Development Method **Smart Home**, Data Center matching your region.
3. On the **Authorize API Services** screen, enable **Device Status Notification** alongside
   the defaults, then **Authorize**.
4. **Devices** tab → **Link Tuya App Account** → **Add App Account** → scan the QR with the
   Smart Life app (**Me**, then the scan icon). Confirm on the phone.
   **Not the Users tab** — it looks right and it is not.
5. Open **All Devices** and confirm your lights and plugs are listed. **If they are not, stop
   and fix that before going on.**
6. **Overview** page → **Authorization Key** → copy the **Access ID** and **Access Secret**.

Then in the app: **Settings → Smart home setup → Tuya / Smart Life**, paste both keys, pick
your data centre from the chips (they show Tuya's own codes — `eu`, `us`, `cn` and so on, so
you can match them exactly), and tap **Save and connect**.

You should see **Connected** and your devices listed. Make an automation —
**Automations → Add automation → Double clap → Turn lights or smart plugs on, off, or toggle
them** — pick a device, save, and clap twice.

**At this point it works.** Everything below is about making it work without the cloud.

---

## Step 4 — Collect the local keys

Ten minutes. Every device has a per-device secret called the **local key**, and the cloud API
is the only place it exists. You need it once. After this you never need the cloud again.

The tool for this is `tinytuya`, the reference Python implementation. I verified the exact
prompts below against version 1.20.0.

### Install it

You need **Python 3**. `python3 --version` to check; if it is missing, get it from
[python.org](https://www.python.org/downloads/) or your package manager.

```bash
python3 -m venv tuya-keys
cd tuya-keys
# macOS / Linux:
source bin/activate
# Windows:
#   Scripts\activate
pip install tinytuya
```

A virtual environment rather than a system-wide install, so nothing else on your machine is
affected and you can delete the folder when you are done.

### Run the wizard

```bash
python -m tinytuya wizard
```

It asks, in this order:

| Prompt | What to enter |
| --- | --- |
| `Enter API Key from tuya.com:` | Your **Access ID** from step 3 |
| `Enter API Secret from tuya.com:` | Your **Access Secret** from step 3 |
| `Enter any Device ID ... or 'scan' to scan for one:` | Type **`scan`** — it finds one on the network, so you do not need to copy an id from the console |
| `Enter Your Region (Options: cn, us, us-e, eu, eu-w, in, or sg):` | The code matching your data centre — the same one shown on the chips in the app |
| `Download DP Name mappings? (Y/n):` | **Y** |
| `Poll local devices? (Y/n):` | **Y** — this is what fills in each device's IP and protocol version |

If it answers `sign invalid`, the Access ID or Secret is wrong — re-copy them. If it complains
about permissions or error `1010`, the tool itself will tell you your IoT Core subscription may
have expired; renew it at iot.tuya.com.

### What you end up with

Four files in that folder:

| File | Contents |
| --- | --- |
| **`devices.json`** | **The one that matters.** Each device's name, `id`, `key` (the local key), `ip` and `version` |
| `tinytuya.json` | Your Access ID and Secret |
| `tuya-raw.json` | The unprocessed cloud response |
| `snapshot.json` | What the local poll saw |

**Back `devices.json` up somewhere safe** — a password manager is ideal. Two reasons:

- These keys let anyone on your network control your devices.
- **The local key changes if you reset or re-pair a device.** If that happens you will need
  cloud access again to get the new one — still free, though possibly needing an extension
  request by then.

Then `deactivate` the virtual environment. You are done with Python.

---

## Step 5 — Tell me the protocol versions

This is all I need to build local control:

> "Three devices: two on 3.3, one on 3.5"

That is it. The version numbers, and how many of each.

**Do not send me:**

- Local keys (the `key` field)
- Your Access ID or Access Secret
- The contents of `devices.json`
- Device IDs

None of it helps me write the code, and all of it is a credential.

### What I will do with the answer

Implement the LAN protocol for the versions you actually have, rather than all four blind — the
work differs by roughly a factor of three between 3.3 and 3.5. Then the app will need somewhere
to store the keys, which I will add as a paste-in field alongside the existing Tuya settings.

### What to expect when we get there

The LAN protocol is undocumented and community-reverse-engineered. I can unit-test the framing
and the crypto round-trips — the discovery decoder already is — but "does your device accept
this" only proves out against your hardware. Expect a round or two of "works on this one, not
that one". That is normal for this protocol, not a sign the approach is wrong.

Devices are also quirky in ways no specification warns about: many accept only one local
connection at a time, so this app and the Smart Life app can compete for it.

---

## Quick reference

| Step | Time | Costs | Needs an account |
| --- | --- | --- | --- |
| 0. Install the app | 20 min | Free | No |
| 1. Prepare the network | 5 min | Free | No |
| 2. Scan the LAN | 2 min | Free | No |
| 3. Tuya cloud project | 20 min | Free | Yes, free |
| 4. Collect local keys | 10 min | Free | Uses step 3 |
| 5. I implement local control | — | Free | No |

Related documents:

- [`docs/tuya-setup.md`](tuya-setup.md) — the cloud walkthrough in full, with troubleshooting
- [`docs/tuya-lan.md`](tuya-lan.md) — what local control requires and why, plus the reflashing
  alternative
- [`docs/google-home-setup.md`](google-home-setup.md) — the Google path, blocked on needing a
  Nest hub

---

## Sources

- [tinytuya](https://github.com/jasonacox/tinytuya) — the wizard used in step 4; prompts and
  output files verified against 1.20.0
- [tinytuya protocol reference](https://github.com/jasonacox/tinytuya/blob/master/PROTOCOL.md)
- [Tuya IoT Platform Configuration Guide](https://github.com/tuya/tuya-home-assistant/wiki/Tuya-IoT-Platform-Configuration-Guide)
- [Android Studio and the SDK](https://developer.android.com/studio)
