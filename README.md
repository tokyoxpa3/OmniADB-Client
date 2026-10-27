# OmniADB-Client

**English** · [繁體中文](README.zh-TW.md)

> **Control one phone from another.** Scan → pair → install the Host → connect. No computer
> in the loop at any point.

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/mirror-Android%2013%2B%20for%20audio-blue" alt="Mirror audio">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
  <img src="https://img.shields.io/badge/version-0.5.0-informational" alt="Version">
  <a href="https://github.com/tokyoxpa3/OmniADB-Client/actions/workflows/build.yml"><img src="https://github.com/tokyoxpa3/OmniADB-Client/actions/workflows/build.yml/badge.svg" alt="build"></a>
</p>

<p align="center">
  <img src="shots/mirror1.png" width="760" alt="Phone A showing phone B's desktop full screen, with the mirror toolbar on top">
</p>

The third piece of the `OmniADB` family, and the one that gets the most use:

| Project | Role | Platform | Status |
|---|---|---|---|
| [`OmniADB-Host`](https://github.com/tokyoxpa3/OmniADB-Host) | Host side — keeps the ADB port pinned, comes back after reboot | Android | ✅ Released |
| **`OmniADB-Client`** | **Controller — phone A drives phone B** | **Android** | **✅ 0.5.0** |
| `OmniADB` (Python) | Desktop console / multi-device wall | Win / mac / Linux | ⏸ Shelved |

## The problem

The desktop version (`python -m omniadb setup`) works well, but it assumes you're sitting
at a computer. In practice you often aren't: the phone is on a shelf, in another room, or
there's simply no computer around.

So the whole flow moved onto the phone itself — two phones on the same Wi-Fi are enough to
scan, pair, install and mirror.

---

## What it does

### Connecting

| Feature | Details |
|---|---|
| Scan | mDNS discovery of nearby phones with wireless debugging on (`_adb-tls-connect` / `_adb-tls-pairing`) |
| Manual add | Type `IP:port` — for cross-subnet setups or when mDNS is blocked |
| Pair | Enter the 6-digit code; once the other side taps "Allow" the card flips to "Connected" by itself |
| Install Host | One tap pins the Host to port 5555 and runs all six authorization steps, so it survives reboots and Wi-Fi changes |
| Remembered devices | IP + model + serial, deduplicated by serial; they show up next time you open the app |

### Mirroring

| Feature | Details |
|---|---|
| Full-screen mirror | Aspect-correct, multi-touch, hole-punch and notch aware; ~100 ms latency on the same Wi-Fi |
| Audio | Three modes: **both sides**, **client only**, **muted** — switching never interrupts the video |
| Quality | 720p/2 Mbps, 1280p/6 Mbps, 1600p/12 Mbps |
| Rotation | Rotate 90° manually, or let the host follow your phone's orientation |
| Host screen off | Turns the host **panel** off while streaming continues — handy for a monitoring phone; remembered across sessions and restored on exit |
| Screenshot | Native resolution, without the client's toolbar |
| Recording | Record while watching, straight into MP4 with no re-encoding (same quality as the stream); adds an AAC audio track when audio is on |

### Controlling

| Feature | Details |
|---|---|
| Toolbar | Back / Home / Recents / Sound / Keyboard / Settings / Exit — auto-hides after 5 s |
| Your own IME | Type with **your** keyboard (Zhuyin, Pinyin, English…); the text lands in the host's input field |
| Physical keyboard | Works too |
| Paste local clipboard | Send what you already copied and tap paste on the other side |
| Clipboard sync | Text flows both ways |
| Volume & power | Hold-to-repeat volume panel, and a power button |

### Files

| Feature | Details |
|---|---|
| Two panes | Separate "local" and "remote" tabs |
| Transfer | Multi-select and batch transfer; folders are recursive |
| Manage | New folder, rename, delete, info, sort, jump to path, bookmarks |
| Thumbnails | Local images via the system, remote ones fetched and decoded; two-level cache, only while visible |
| Move | Cut and paste (same side uses `cp`/`mv`, cross-side goes through the stream) |
| Name collisions | Overwrite / keep both / cancel — nothing is picked for you |

---

## How to use it

### First time (two taps on the host — that's the Android security boundary)

1. **Phone B**: Developer options → enable **Wireless debugging**
2. **Phone B**: Wireless debugging → "Pair device with pairing code" → note the `IP:port` and the 6-digit code
3. **Phone A**: tap **Scan**, or **Add manually** and type B's IP
4. **Phone A**: tap **Pair** on that row → enter the pairing port and the 6-digit code
5. **Phone A**: once connected, tap **Install Host** → B's ADB is pinned to 5555 and comes back after a reboot

> Both phones must be on the same Wi-Fi and the same subnet. The first pairing makes B ask
> "Allow USB debugging?" — tick "Always allow" so it stops asking.

### Day to day

Open the app on phone A → B is already in the list (with battery, temperature, resolution) →
tap **Mirror**.

### The mirror screen

A single auto-hiding toolbar at the top, **seven buttons only**: Back / Home / Recents /
Sound / Keyboard / Settings / Exit. It collapses after 5 seconds; tap `≡` in the corner to
bring it back. The system back button is deliberately left alone — it's the most natural way
to leave the mirror.

**The "Settings" menu** has two sections (actions on the host, then client-side settings):

| Item | What it does | Needs reconnect |
|---|---|---|
| Rotate | Locks the host orientation, then follows | No |
| Follow my orientation ☑ | Track the client's sensor | No |
| Volume | Panel with `−` / `＋`, hold to repeat | No |
| Power | Press the host's power button (sleep / wake) | No |
| Host screen off ☑ | Panel off, stream keeps running; **remembered** | No |
| Paste local clipboard | Sends the clipboard and taps paste | No |
| Screenshot / Record | Saved to `Pictures/OmniADB` and `Movies/OmniADB` | No |
| Quality… | 720p / 1280p / 1600p | **Yes** |
| Keep host screen on ☑ | Stops the host from sleeping | **Yes** |
| Audio… | Both sides / client only / muted | No |
| Reconnect | Manual reconnect | — |

Checkboxes (☑) mean "currently on".

---

## Limitations

Some of these are Android's hard limits, not missing features:

| Limitation | Why |
|---|---|
| Host needs Android 8.0+ | Baseline for the app |
| **Mirror audio needs Android 13+** | `AudioPlaybackCapture` only exists from 13; below that you get video only |
| Media and game audio only | Notifications, calls and ringtones can't be captured |
| Apps may opt out | `allowAudioPlaybackCapture=false` (DRM content, a few players) — the sound button dims when this happens |
| Xiaomi / HyperOS asks for confirmation | The system shows "Continue install"; its 10-second countdown occasionally beats the auto-tap |
| Long transfers die if you leave the app | No foreground service yet (see Known issues) |
| Clipboard is text only | Images and files aren't transferred |
| Same Wi-Fi required | This is ADB over TCP, not a cloud relay |

## Known issues

- **No foreground service**: switching away from the app during a transfer closes the
  connection (`onStop`), so long transfers need it; mirroring currently relies on PiP only
- Clipboard: `ClipData` images and files are not handled
- When the Xiaomi installer's countdown wins, the dialog only explains why — no "retry" yet
- File manager is missing drag-to-move, video thumbnails, and a size/date preview before overwriting
- No automated tests: `test/` and `androidTest/` are empty, everything is verified on real devices

---

## Build

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (debug-key signed, sideload-ready)
```

Requires **JDK 17** and the **Android SDK** (`compileSdk 35`).

`local.properties` is not committed (it holds an absolute path), so a fresh clone needs one:

```properties
sdk.dir=/path/to/Android/Sdk
```

Android Studio creates it for you; command-line builds need it written by hand.

> If a phone refuses to update the app and you'd rather not wipe its data, use
> `./gradlew assembleDebug -PdevSuffix`: the package becomes `com.omniadb.client.dev` and can
> coexist with the regular one (note that the `FileProvider` authority follows the package
> name, so only one of the two can be installed at a time).

**About the release signature**

The release build deliberately reuses the debug key:

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

That way anyone can `assembleRelease` a sideload-ready APK without you sharing a keystore.
The trade-off is that it **cannot go on Google Play** (it will be rejected) and the
certificate reads `Android Debug`. For a store release, bring your own keystore and read it
from `keystore.properties` (already in `.gitignore`).

---

## Security

- **ADB is full system control.** Use it on your own home network only — don't leave wireless
  debugging on in public Wi-Fi, and don't forward port 5555 to the internet.
- The client's ADB key lives in the app's private directory and is shared across all remote
  devices, so the host's "authorized devices" list has exactly one `OmniADB-Client` entry.
- Uninstalling the app destroys the key and requires pairing again (same as the Host's
  `WRITE_SECURE_SETTINGS`).

---

## Credits

- [`libadb-android`](https://github.com/MuntashirAkon/libadb-android) — ADB protocol and SPAKE2 pairing
- [`scrcpy`](https://github.com/Genymobile/scrcpy) (Apache-2.0) — video pipeline and control protocol
  (only its `scrcpy-server.jar` is used; the client is our own: MediaCodec + SurfaceView)
- `OmniADB-Host` — shared key handling and provider setup

---

## License

Released under the **MIT** license, see [`LICENSE`](LICENSE).

Third-party licenses (libadb-android is GPL-3.0-or-later **or** Apache-2.0 dual-licensed,
this project takes the Apache-2.0 branch; scrcpy, Conscrypt and AndroidX are Apache-2.0;
Bouncy Castle is MIT-style) are collected in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), with upstream license files under `LICENSES/`.

---

<sub>If you plan to modify the project: implementation notes, measurements and the pitfalls
we hit are in [`docs/INTERNALS.md`](docs/INTERNALS.md) (written in Chinese).</sub>
