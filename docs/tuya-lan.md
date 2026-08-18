# Local (LAN) control — what it takes

You want this to cost nothing. Here is the honest cost picture first, because one part of it is
not what it looks like.

## The cloud API is already free

Tuya's IoT Core service runs on a one-month **free** trial, and extensions are **free** — you
apply, and it is approved in about one to two working days. It costs administration, not money.

So "free" is not the reason to go local. The reasons to go local are better ones:

- **No recurring admin.** No extension to remember every few weeks.
- **Much lower latency.** A clap talks to a lamp across the room instead of to a data centre.
- **Works with the internet down.** Which is when you most want a light switch to work.
- **Nothing about your home leaves the house.** That matches what this app already promises
  about audio.

## The part you cannot avoid

**Local control needs the cloud exactly once.**

Every Tuya device has a per-device secret called the **local key**. It is what encrypts LAN
traffic, and it is never broadcast. The only way to obtain it is the cloud API — this is
confirmed by the community tooling as well as Tuya's own documentation; `tinytuya`'s setup
wizard requires a cloud project for precisely this reason, and there is no documented way
around it.

That is fine, and it is the key insight for your situation: **you need the cloud once, inside
the free trial month, and then never again.** Dump the keys, write them down, and the expiry
stops mattering.

One caveat worth knowing now: **the local key changes if you reset or re-pair a device.** Keep
the keys somewhere safe, and if you ever re-pair something you will need cloud access again —
still free, possibly needing an extension request.

---

## Wi-Fi devices and hub sub-devices are not the same problem

Learned from a real home, and easy to miss: a Tuya account is often mostly **Zigbee devices
behind one gateway**, not Wi-Fi devices. In the case that prompted this section, about seventeen
devices resolved to four on Wi-Fi and seven hanging off a single Multi-mode Gateway — including
both devices the user actually wanted to clap-control.

The distinction decides whether local control can address a device at all:

| | Wi-Fi device | Hub sub-device |
| --- | --- | --- |
| Has its own IP | Yes | **No** |
| Appears in the LAN scan | Yes | No |
| Local control | Direct, by protocol version | Must be **routed through the gateway**, addressed by `node_id` (Tuya calls it `cid`) |
| Cloud control | Works | Works — the cloud routes through the hub for you |

**The cloud reaches sub-devices transparently; local control does not.** That asymmetry is the
single strongest argument for keeping the cloud client as the fallback rather than replacing it:
a home whose lights are all Zigbee gains nothing from direct LAN control alone.

`TuyaLocalCredential.fromDeviceList` works out which is which. Tuya reports `sub` reliably but
the parent only sometimes, so the parent falls back to the heuristic `tinytuya` uses, whose own
comment explains an otherwise arbitrary rule: *"The only link between parent and child appears
to be the local key."* Tuya hands a sub-device the same local key as its hub, so a sub-device
whose key matches a non-sub device has found its parent. A hub must be excluded from matching
itself — it shares its key with its children by definition.

### Implement in this order

Doing all the variants at once means handing over a large lump that cannot be debugged. Each
step below is provable on hardware before the next is worth starting:

1. **Protocol 3.3, direct.** Smallest wire format, and it proves the framing and crypto against
   a real device.
2. **Protocol 3.4, direct.** Adds HMAC-SHA256 and the session handshake to a frame layer that
   is by then known good.
3. **Gateway routing.** Sub-device addressing on top of a working 3.4, for the Zigbee devices.

---

## What local control requires

### Per device

| Thing | Where it comes from |
| --- | --- |
| Device ID | LAN broadcast, or the cloud |
| IP address | LAN broadcast |
| Protocol version | LAN broadcast |
| **Local key** | **Cloud only, once** |

The app can already find the first three. **Settings → Smart home setup → Tuya → Scan local
network** listens for the broadcasts every Tuya device sends every few seconds, and reports
each device's IP and protocol version. It needs no account and sends nothing — it only listens.

**Run that scan before anything else.** The protocol version it reports decides how much work
the rest is, and the answer differs by roughly a factor of three between versions.

### From your network

- Phone and devices on the **same subnet**. Local control cannot cross a router.
- **No AP isolation / client isolation** on the Wi-Fi. This is a router setting, often on by
  default on guest networks, and it silently blocks device-to-device traffic. If the scan finds
  nothing, check this first.
- UDP **6666**, **6667** and **7000** reachable by the phone; TCP **6668** outbound to devices.
- **Reserve the devices' IPs** in your router's DHCP settings. Rules store a device id, but the
  connection needs an address, and a lease that moves overnight breaks an automation silently.
- The phone stays on **Wi-Fi**. Local control does not work over mobile data. For a handset
  sitting plugged in on a shelf, that is not a constraint.

---

## How much work it is, by protocol version

This is the number the scan gives you, and it matters a lot. All of it is implementable with
the JDK alone — `javax.crypto` covers AES-ECB and AES-GCM, `Mac` covers HMAC-SHA256,
`java.util.zip.CRC32` covers the checksum — so **still no new dependencies**.

| Version | What it needs | Effort |
| --- | --- | --- |
| **3.1 / 3.2** | AES-ECB, base64, an MD5-derived prefix. Legacy | Moderate |
| **3.3** | AES-128-ECB with PKCS#7, `55AA` frame, CRC32, clear 15-byte version header | **Smallest** |
| **3.4** | `55AA` frame but HMAC-SHA256 instead of CRC32, **plus a three-message session-key handshake**, header encrypted | Larger |
| **3.5** | `6699` frame, AES-GCM with a 12-byte IV, 16-byte tag and 14-byte AAD, session handshake with different key derivation | Largest |

If everything you own is 3.3, this is a contained job. If you have 3.4 or 3.5 devices, there is
a session negotiation to implement as well — the client sends a nonce, the device answers with
its own nonce and an HMAC, the client confirms, and both sides derive a session key by XORing
the nonces and encrypting the result with the real local key.

### The honest risk

Unlike the cloud API — where Tuya publishes the signing formula, so the implementation could be
tested against a specification — **the LAN protocol is undocumented**. It is reverse-engineered
by the community, and it is well understood, but that has two consequences:

1. **Correctness can only be proven against your hardware.** Framing and crypto round-trips can
   be unit-tested (the discovery decoder already is, with packets the tests build themselves),
   but "does the device accept this" needs a device.
2. **Devices are finicky in ways no specification warns you about.** Many accept only one local
   connection at a time, so the app and the Smart Life app can fight over it. Some need a
   keep-alive or they drop the socket. Some report data points under unexpected codes.

Expect a round or two of "it works for this device but not that one". That is normal for this
protocol and not a sign anything is wrong with the approach.

---

## The third option: remove Tuya entirely

Worth knowing before committing, because for some hardware it is *less* work than the LAN
protocol and permanently simpler.

Many Tuya devices can be **reflashed over the air** with open firmware —
[tuya-cloudcutter](https://docs.libretiny.eu/docs/flashing/tools/cloudcutter/) exploits a known
vulnerability to replace the firmware with **OpenBeken** (a Tasmota-alike) or **ESPHome**,
without soldering or opening the case.

If you do that, the app side becomes almost trivial: an HTTP GET per command, no keys, no
crypto, no cloud, no protocol versions. Perhaps thirty lines behind `SmartHomeClient` instead
of several hundred.

| | LAN protocol | Reflashing |
| --- | --- | --- |
| App code | Several hundred lines of reverse-engineered crypto | An HTTP call |
| Cloud needed | Once, for local keys | Never |
| Works on | Any Tuya device | Only supported chips |
| Risk | Software only; a bug costs debugging | **Can brick the device** |
| Keeps Smart Life app | Yes | No |

Cloudcutter currently covers **BK7231T and BK7231N** chips — modules including CB1S, CB2L,
CB2S, CB3L and CB3S — and support for BK7238 is appearing. It does **not** cover every chip, and
newer devices ship with the vulnerability patched, so check your specific model against the
project's supported list before buying into this route.

Reflashing is irreversible in practice and can leave a device unusable. It is a good option for
a cheap plug you are willing to lose, and a bad one for something you are not.

---

## Recommended order

Each step below is written out in full, with the exact prompts and gotchas, in
[`runbook.md`](runbook.md).


1. **Run the local scan** in the app. Note the protocol versions. Free, instant, no account.
2. **Do the cloud setup once** — `docs/tuya-setup.md`, about twenty minutes. This gives you
   working automations *today* via the cloud, and it is the only source of local keys.
3. **Record the local keys** somewhere safe. `python -m tinytuya wizard` dumps them to a
   `devices.json` using the same Access ID and Secret; the app could also be extended to show
   them, since the cloud device list carries them.
4. **Then implement local control** for the versions you actually have, and keep the cloud
   client as a fallback for anything local cannot reach.

Step 2 is not wasted work under any plan. Even the reflashing route benefits from it, because
you will want the devices working before you start replacing their firmware.

---

## What is built so far

| Piece | State |
| --- | --- |
| LAN discovery — find devices, IPs, protocol versions | **Done.** `TuyaLanDiscovery`, `TuyaLanPacket`, 15 unit tests |
| Cloud control | **Done.** See `docs/tuya-setup.md` |
| Local key retrieval in-app | Not built — `tinytuya wizard` does it today |
| Local control (3.3) | Not built |
| Local control (3.4 / 3.5) | Not built |

`TuyaLanAnnouncement.controlSupported` reports the empty set of supported versions on purpose:
the UI lists what it finds but never claims it can switch it. That flag is what flips as each
protocol version is implemented.

Discovery decoding is deliberately forgiving — it tries the plausible readings of a datagram and
keeps whichever yields valid JSON, rather than asserting one layout. The tests construct
datagrams in each shape a device might send, so the forgiveness is verified rather than assumed.

---

## Sources

- [tinytuya protocol reference](https://github.com/jasonacox/tinytuya/blob/master/PROTOCOL.md) — frame layouts, command codes, session negotiation
- [tinytuya](https://github.com/jasonacox/tinytuya) — the setup wizard that dumps local keys
- [localtuya communication protocol](https://deepwiki.com/rospogrigio/localtuya/3.1-communication-protocol)
- [tuya-cloudcutter via LibreTiny](https://docs.libretiny.eu/docs/flashing/tools/cloudcutter/) — supported chips and the flashing process
- [Extending the Tuya IoT Core trial](https://support.tuya.com/en/help/_detail/Kc3n6kr7kllhc)
