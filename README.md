# PCL — Android Bluetooth Call Companion

[![Latest release](https://img.shields.io/github/v/release/N2K02/PCL?label=download)](https://github.com/N2K02/PCL/releases/latest) ![Android](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)

**Keep your SIM in one Android phone and handle its calls from a second Android phone nearby.**

The phone with the SIM is the **Gateway**. The phone you hold is the **Remote**. Over Bluetooth, the Remote handles SIM calls on the Gateway: incoming calls, dialing, answer, decline and hang up. Calls and their audio stay on the Gateway. The Remote needs no SIM or internet. Third-party calling and notification mirroring are not supported.

**[⬇ Download the latest APK](https://github.com/N2K02/PCL/releases/latest)** · [All releases](https://github.com/N2K02/PCL/releases)

## How it works

Install the same app on both phones and pick a role on each.

```text
┌─────────────────────────┐
│ GATEWAY PHONE           │  SIM and Phone app,     
│ PCL in Gateway role     │  mobile data. Call audio
└────────────┬────────────┘  stays on this phone.
             │
             │  paired Bluetooth (RFCOMM):
             │  SIM call state,         
             │  contacts and commands
             │
┌────────────┴────────────┐
│ REMOTE PHONE            │  Rings for Gateway calls;
│ PCL in Remote role      │  answer, decline, end, dial.
└─────────────────────────┘  No SIM or internet.      
```

The Bluetooth link carries SIM call state, contacts and commands. It does **not** carry call audio or internet, or copy app accounts. You hear and speak through the Gateway's earpiece, loudspeaker or a Bluetooth headset connected to it. No PCL server is involved.

## Setup

1. Install PCL on both phones, and keep both on the same version. Each phone updates separately; Home shows a warning when the two versions differ.
2. Pair the two phones in Android **Settings → Bluetooth**.
3. **On the Gateway**, open PCL, choose **Gateway** in Settings, and grant:
   - Phone, Nearby devices and Notifications (required)
   - Contacts (optional: contact sync and caller names)
4. **On the Remote**, choose **Remote** and grant Nearby devices and Notifications. Contacts is optional. Neither phone needs Notification access or Accessibility for SIM calling.
5. On **Home**, each phone selects the other one from its paired devices, then press **Start** on both. The Gateway only accepts connections from the Remote you selected.
6. Let PCL run in the background. On Xiaomi/Redmi/POCO, Huawei/Honor, OPPO/realme/OnePlus, vivo and some Samsung builds, set PCL's battery usage to **Unrestricted**, allow auto-start if offered, and lock it in Recents. Menu names vary.

While the link is on, PCL tries to restart by itself after the phone reboots, after an update, or if Android stops it. **Stop** or **Quit** turns that off until you press Start again.

PCL needs Android 8.0 or newer. It never needs the microphone.

## Features

| Feature | What it does |
|---|---|
| SIM calls | The Remote rings for incoming Gateway calls and shows the caller (and contact name, with Contacts permission on the Gateway). It can answer, decline, end, and dial through the Gateway's SIM. A call answered or placed on the Gateway stops Remote ringing but remains visible as an ongoing call with volume and output controls. Missed calls appear in Calls → Recents without persistent alerts. |
| Calls tab | On the Remote, next to Home. See [Calls tab](#calls-tab). |
| Contacts | The Gateway's contacts are copied to the Remote for offline search. On each connection the phones compare a fingerprint and only resync when the list changed; the sync button in **Calls → Contacts** forces a refresh. |
| Call alerts | Incoming and active SIM calls appear on the Remote only while the call is ongoing. Missed or ended calls do not leave alerts. |
| Link traffic | Home and the status notification show what PCL sends between the phones over Bluetooth. |
| Activity log | Home keeps a short log for troubleshooting, with a Copy button. It never shows full phone numbers or contact names (only the last two digits). |

## Calls tab

The Remote has a **Calls** tab next to Home, laid out like a phone's dialer:

- **Keypad.** A dial pad with key tones (when the ringer is on) and haptics. Hold **0** for **+**, tap ⌫ to delete and hold it to clear, and long-press the number to paste one. Typing on the keys also searches contacts by name, like a phone's smart dial: 5‑6‑2 finds "Joan". Below the keys: **SIM call** (through the Gateway's SIM).
- **Recents.** SIM calls on the Gateway, grouped by day: incoming, outgoing (with talk time), missed and declined. Missed calls show as a badge on the Calls icon until you open Recents. The list stays on the Remote only; **Clear** empties it.
- **Contacts.** Search the synced contacts, sync again, and star **favourites**. Favourites appear at the top of Contacts and above the keypad.
- Tap a call in **Recents** to reveal Favourite, Edit, Copy, Share, Delete and verified SIM-selection actions; the phone icon calls immediately. Tap a person in **Contacts** for Favourite and Edit.
- While a SIM call rings or is connected, the top of the tab shows it with **Answer / Decline** or **Hang up** and a call timer. Tapping a call notification opens this tab, and on a locked Remote a ringing call opens it straight away.

Android only lets the phone's own dialer send keypad tones into a call, so PCL can't press keys in a menu ("press 1") on the Gateway's call.

## Internet

The Remote needs no internet: other-app calls run on the Gateway with the Gateway's mobile data. The Remote only needs internet (Wi-Fi, for example) to download PCL updates; otherwise install the new APK on it by hand.

## Remote control of Gateway call audio

- In **Calls → active call**, the Remote can adjust the Gateway's reported volume and request **Earpiece**, **Speaker** or **Bluetooth**. Only call-capable audio endpoints detected on the Gateway are offered; Bluetooth media-only A2DP speakers are not call endpoints.
- **AUTO** selects Gateway voice-call volume while a call is active. Select **CALL** explicitly for voice volume; **MEDIA** and **RING** change different Android audio streams.
- The volume slider transmits a single request when you release it, rather than flooding the Bluetooth connection. Gateway responses report the actual level and audio route, including when Android refuses to apply a request.
- The Bluetooth output option refers to a **hands-free headset paired to the Gateway**, not the PCL Remote phone. PCL transports control commands over Bluetooth, **not call audio**.
- Android grants priority for cellular-call audio routing to the native phone application. On some devices, PCL's `AudioManager.setCommunicationDevice` request may not override the system dialer, even when the endpoint is available. The Remote displays the detected route, never promises the audio was rerouted merely because a request was sent. For reliable cellular-call routing use the Gateway's native call screen.

## Concurrent calls and call waiting

- Install the **same PCL build on both phones** (Bluetooth protocol v11). Remote Answer, Decline and Hangup now carry the exact Gateway call identifier, so delayed actions from an older call cannot control a newer call.
- If PCL observes a waiting call while another SIM call is connected, or detects multiple busy SIM subscriptions, it stops Remote ringing, disables Remote call controls and asks you to use the **Gateway's native dialer**. Ambiguous call-history entries are not saved.
- Android's ordinary phone-state APIs do not expose reliable individual call objects during call waiting, especially with two calls on the same SIM. Full separate-call hold/swap/answer/end controls would require an authorized default-dialer/InCallService architecture; PCL does not claim these capabilities.
- On both devices, verify answering, declining, rapid consecutive calls, Bluetooth loss/reconnect, lock-screen notification actions, dual-SIM selection and call waiting before promoting the experimental build.

## Limitations

- **SIM call controls depend on Android and the manufacturer.** PCL uses the public Android Telecom API. Some phones restrict answering or ending cellular calls for non-default dialers. PCL does not currently implement notification-button fallback, hidden APIs, root or ADB. Ending a call through this API requires Android 9 (API 28) or newer. Android 8 devices can still pair and view call state, but cannot use PCL to end calls.
- **Optional Call Log enrichment is installer-restricted.** PCL declares READ_CALL_LOG but Android may refuse to grant it unless the installer explicitly allowlists the restricted permission. Normal SIM calling and contact synchronization do not depend on this permission; optional post-call caller recovery may be unavailable.
- **Locked Remote call alerts:** Only a minimal incoming-call interface can appear above the keyguard, not private Recents, Contacts or Settings. On Android 14 and newer, full-screen alerts may need to be enabled in system settings.
- **Caller numbers aren't always available.** Android may report an outgoing call without its number. On startup PCL reads the current call state, but not the number or direction.
- **Bluetooth uses the secure, paired RFCOMM socket** and never falls back to an insecure one. If the phones can't connect, remove the pairing on both, pair again, and restart PCL on both.
- **Battery:** PCL connects only to the selected paired phone and never scans. Reconnect attempts back off from 3 to 30 seconds while the other phone is unreachable. Turning off notification mirroring saves a little more.

## Build

Pushes to `main` that change the app build an APK and publish it as a GitHub release; documentation-only pushes don't. To build locally: `./gradlew assembleDebug` (JDK 17 and the Android SDK required). Local builds are versioned 0.1, below every release, so a release can always be installed over them.

## Links

- **Latest APK:** [GitHub Releases — latest](https://github.com/N2K02/PCL/releases/latest)
- **All releases:** [GitHub Releases](https://github.com/N2K02/PCL/releases)
- **Source and issues:** [github.com/N2K02/PCL](https://github.com/N2K02/PCL)
