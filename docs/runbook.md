# Step-by-step setup — no coding required

Everything below happens in one of two places, and each step says which:

- **ON YOUR PHONE** — the Android phone that will listen for claps
- **IN A BROWSER** — Chrome, Edge or Firefox, on your Windows PC

**You will not type any commands.** No Android Studio, no Java, no Git, no Python. The app is
supplied already built.

Total time: about 45 minutes. Nothing costs money.

---

## Part 1 — Install the app

**ON YOUR PHONE**

1. Open the chat message where I sent you `shelfit-sentinel.apk` — open it *on the phone*, not
   on the PC. Signing in to claude.ai in the phone's browser is the easiest way.
2. Tap the file to download it. The browser may warn that this type of file can harm your
   device. Choose **Download anyway**. It is the app we have been building; Android says that
   about every APK.
3. Pull down the notification shade and tap the downloaded file. Or open the **Files** app →
   **Downloads** → tap `shelfit-sentinel.apk`.
4. Android will say something like *"For your security, your phone is not allowed to install
   unknown apps from this source."* Tap **Settings**, turn on **Allow from this source**, then
   press the back arrow.
5. Tap **Install**. Then **Open**.

You should now see a screen titled **Shelfit Sentinel** with a Start Listening button.

> **If it says "App not installed"** and you have had a version of this app before, uninstall
> the old one first: long-press its icon → **Uninstall**. Then install again.

---

## Part 2 — Check the clap detection works

**ON YOUR PHONE**

Do this before anything to do with lights. It confirms the hard part works.

1. The app asks for the **microphone**. Tap **Allow**. It needs this to hear claps; nothing is
   ever recorded or sent anywhere.
2. It asks about **notifications**. Tap **Allow**. Without this you cannot pause or resume
   listening.
3. Tap the settings icon (top right) → scroll to **Calibration** → **Calibrate now**.
   - Stay quiet for four seconds while it measures the room.
   - Then clap five times, normally, from where you will actually use it.
   - Accept the result when offered.
4. Go back to the main screen. Turn on **Sensor Mode**.
5. Clap twice, quickly — about half a second apart.

**The phone should buzz.** If it does, everything from the microphone to the automation engine
works, and all that is left is connecting it to your lights.

> **If it does not buzz:** open settings → **Advanced: clap detector test**. Clap and watch. It
> shows the sound level and, when a loud sound is rejected, which test rejected it. Tell me what
> it says.

---

## Part 3 — One router setting

**IN A BROWSER**

Skip this if you like, and come back to it if Part 4 finds nothing. It is the single commonest
reason local device control does not work.

1. Find your router's address. It is usually printed on a sticker on the router itself —
   something like `192.168.1.1` or `192.168.0.1`, sometimes a name like `routerlogin.net`.
2. Type that into the browser address bar and press Enter. Log in with the details on the
   sticker.
3. Look through the Wi-Fi or Wireless settings for anything called **AP isolation**, **Client
   isolation**, or **Station isolation**. If you find it, **turn it off** and save.
4. Make sure your phone and your smart plugs are on the **same Wi-Fi network** — not a "guest"
   network.

This setting stops devices on your Wi-Fi from talking to each other, which is exactly what we
need them to do.

---

## Part 4 — Find your smart devices

**ON YOUR PHONE**

This needs no account and takes two minutes. It tells me how much work the final step is.

1. Make sure your smart plugs or bulbs are switched on and working in the **Smart Life** app.
2. In Shelfit Sentinel: settings icon → **Smart home setup**.
3. Under **Smart home**, tap **Tuya / Smart Life**.
4. Scroll down to **Local network** and tap **Scan local network**.
5. Wait about ten seconds.

You should see a list, one line per device, like:

```
192.168.1.47
Protocol 3.3 · bf9c21a0e5...
```

**Write down the "Protocol" number for each device.** That is the one thing I need from you.

> **If nothing appears**, in order of likelihood: AP isolation is still on (Part 3); the phone is
> on a different Wi-Fi network or on mobile data; the devices are powered off; a VPN is running
> on the phone. Fix and tap **Scan again**.

---

## Part 5 — Create a free Tuya developer account

**IN A BROWSER**, with the phone beside you

This is the longest part, about twenty minutes. It is free. It is what makes the lights actually
switch, and it is also the only place your devices' local keys exist.

### 5a. First, check your region — ON YOUR PHONE

Open **Smart Life** and go to:

**Me → Settings → Account and Security → Region**

Write down what it says. Getting this wrong later produces an error that looks exactly like a
wrong password, so it is worth thirty seconds now.

### 5b. Make an account — IN A BROWSER

1. Go to **https://iot.tuya.com**
2. Sign up. Any email address works. Confirm the email.

### 5c. Create a project

1. In the menu on the left, click **Cloud**, then **Create Cloud Project**.
2. Fill in the form:
   - **Project Name** — anything, for example `Shelfit`
   - **Industry** — pick anything; it makes no difference
   - **Development Method** — choose **Smart Home**
   - **Data Center** — choose the one matching your region from step 5a
3. Click **Create**.

### 5d. Authorize the services

A screen appears called **Authorize API Services**.

1. Make sure **Device Status Notification** is in the list of selected services. Add it if it is
   not.
2. Leave everything already selected as it is.
3. Click **Authorize**.

> Tuya gives you a free one-month trial of the service this uses. You only need it for Part 5
> and Part 8, so a month is plenty. If it ever runs out, Tuya extends it free — you just have
> to ask on their site.

### 5e. Connect your Smart Life account — this is the step people get wrong

1. In your project, click the **Devices** tab along the top.
2. Click **Link Tuya App Account**, then **Add App Account**.
3. A QR code appears on screen.
4. **ON YOUR PHONE**, open **Smart Life** → tap **Me** → tap the scan icon (top right, looks
   like a small square/barcode) → point it at the QR code on your PC screen.
5. Confirm on the phone.
6. Back in the browser, click **All Devices**. **Your plugs and bulbs should be listed here.**

> **The tab matters.** There is also a **Users** tab. It looks like the right place and it is
> not. Only the **Devices** tab link gives access to your devices.
>
> **If no devices appear here, stop.** Nothing in the app can work until this list is populated.
> The usual cause is the Data Center in step 5c not matching your region from step 5a — you can
> edit the project and change it.

### 5f. Copy the two keys

1. Click **Overview** in your project.
2. Find the box called **Authorization Key**.
3. You will see **Access ID** (sometimes "Client ID") and **Access Secret** (sometimes "Client
   Secret"). Click the eye icon to reveal the secret.
4. Copy both somewhere you can get at them from the phone. Emailing them to yourself is the
   easiest way — the Access Secret is a long random string and typing it by hand on a phone
   keyboard is miserable.

---

## Part 6 — Put the keys into the app

**ON YOUR PHONE**

1. Settings icon → **Smart home setup**.
2. **Tuya / Smart Life** should already be selected.
3. In **Tuya keys**, paste the **Access ID** into the first box and the **Access Secret** into
   the second.
4. Under **Data centre**, tap the chip matching what you chose in step 5c. The short codes shown
   (`eu`, `us`, `cn` …) are Tuya's own, so they match exactly.
5. Tap **Save and connect**.

The **Connection** card should now say **Connected**, and below it you should see your devices.

> **"Not connected"** means the Access ID or Secret is wrong — copy them again, carefully.
> **Connected but no devices** means either the app account link in 5e did not take, or the data
> centre does not match.

---

## Part 7 — Make it switch a light

**ON YOUR PHONE**

1. Go back to the main screen and open **Automations**.
2. Tap **Add automation**.
3. Under **When**, leave **Double clap** selected.
4. Under **Do**, choose **Turn lights or smart plugs on, off, or toggle them**.
5. Under **Devices**, tick the light or plug you want.
6. Under **What to do with them**, choose **Toggle**.
7. Tap **Add automation**.
8. Make sure **Sensor Mode** is on, then clap twice.

**The light should switch.** That is the whole thing working.

---

## Part 8 — Collect the local keys

**ON YOUR PHONE**

Two minutes. This is the last thing you need the Tuya website for, ever.

1. Settings icon → **Smart home setup**.
2. Scroll to **Local keys**.
3. Tap **Show local keys**.
4. You will see each device with a **Local key** and a **Device ID** — long strings of letters
   and numbers.
5. **Copy them somewhere safe.** Long-press a value to select and copy it. A password manager
   or a note in your password manager is ideal.

> **Treat these like passwords.** Anyone on your Wi-Fi with a device's local key can switch that
> device. They also change if you ever reset or re-pair a device, in which case come back here
> and read them again.

---

## Part 9 — Tell me two things

Send me:

1. **The protocol numbers** you wrote down in Part 4 — for example *"two devices on 3.3, one on
   3.5"*.
2. **Whether Part 7 worked.**

Then I will build local control, so the claps talk straight to your lights over your own Wi-Fi
instead of going out to Tuya's servers.

### Do not send me

- Local keys from Part 8
- Your Access ID or Access Secret
- Device IDs

None of them help me write the code, and all of them are passwords. The protocol number is the
only thing I need.

---

## Where each part happens, at a glance

| Part | What | Where | Time |
| --- | --- | --- | --- |
| 1 | Install the app | Phone | 5 min |
| 2 | Check clapping works | Phone | 5 min |
| 3 | Turn off AP isolation | Browser | 5 min |
| 4 | Scan for devices | Phone | 2 min |
| 5 | Tuya developer account | Browser + phone | 20 min |
| 6 | Enter the keys | Phone | 3 min |
| 7 | Make the automation | Phone | 2 min |
| 8 | Copy the local keys | Phone | 2 min |
| 9 | Report back | — | — |

---

## A note about future updates

I sign the APK with a key that lives only in the session that built it. If I send you an updated
APK later, Android may refuse to install it over the top, saying **App not installed**. If that
happens, uninstall the old app first and install the new one.

Uninstalling clears your automations, your calibration and your Tuya keys, so keep the Access ID
and Secret from Part 5f to hand. If that becomes annoying, say so and I will commit a fixed
signing key to the repository so updates always install cleanly.

---

## If you get stuck

Tell me which part number, and what you see on screen. The app is written to explain failures in
words rather than fail silently, so whatever it says is usually enough to work out the cause.

More detail, if you ever want it:

- [`tuya-setup.md`](tuya-setup.md) — the Tuya website part, with more troubleshooting
- [`tuya-lan.md`](tuya-lan.md) — what local control needs and why
- [`google-home-setup.md`](google-home-setup.md) — the Google Home route, which needs a Nest hub
