# 虚拟定位 (Shizuku) — MockGpsShizuku

> **Full “plan B” implementation**: via Shizuku, the app grants itself `mock_location` AppOps as shell UID, then uses standard `LocationManager.addTestProvider()` to keep injecting fake coordinates into GPS_PROVIDER + NETWORK_PROVIDER.
> No root on a real device. Works with apps that read system GPS (Amap / Baidu Maps / WeChat / Douyin location, and similar).

## Changelog

### v1.2 (current) — network stays on + Amap works + location is fake

New step 5: **GPS fence VpnService** (DNS sinkhole). Only intercepts Amap/Baidu *network-location API* hostnames so those lookups fail → fallback to GPS_PROVIDER → our mock.
- ✓ **New file `LocationFenceVpnService.kt`**: ~330 lines of Kotlin; VpnService + IP/UDP/DNS parse with zero extra deps
- ✓ **DNS blocklist**: `apilocate.amap.com` / `wifi.amap.com` / `cgicollector.amap.com` / `loc.map.baidu.com` and 11 Amap/Baidu/Tencent network-location API hosts
- ✓ **Does not take over ordinary traffic**: VPN applies only to `10.7.7.8/32` (fake DNS). Map tiles / search / navigation / WeChat / Douyin never enter the VPN — **zero network tax**
- ✓ **DNS forward**: non-blocklist names go to `114.114.114.114`; responses written back unchanged
- ✓ **Live counters**: UI every 2 s “blocked X / forwarded Y”
- ✓ **Standard `VpnService.prepare`**: first start shows the system “Allow VPN?” dialog
- ✓ **AndroidManifest `BIND_VPN_SERVICE` service**
- ✓ **Purple UI button “⑤ Start GPS fence”** + status card

### v1.1 — Amap still showed the real location after system location was on

- ✓ **AIDL `enableLocationServices()` / `queryLocationMode()` / `dumpLocation()`**: one-tap turn on the system location master switch, query it, dump `dumpsys location`
- ✓ **MockLocationProvider self-healing re-register**: listens for `LocationManager.MODE_CHANGED_ACTION`; when OFF→ON resets test providers, re-registers; injection loop health-checks every 4 s
- ✓ **Push rate 1 Hz → 2 Hz** (500 ms): harder for a stray real fix to win
- ✓ **Two UI buttons**: “Turn on system location via Shizuku” (orange) + “Diagnose”
- ✓ **Preflight**: if the master switch is off, a dialog (prefer Shizuku auto-on so flipping the switch does not drop the test provider)

### v1.0 — initial

- Shizuku + automatic `mock_location` AppOps + dual-provider 1 Hz loop + foreground service

---

## Project overview

You sometimes need the phone to “pretend” it is somewhere else (privacy, geo limits in games, LBS testing, and similar).
Developer options → Select mock location app also works, but it is manual every time.
This project automates that step with Shizuku and fills in details most mock-GPS apps skip (multi-provider injection, foreground keep-alive, reflection to clear the mock flag).

Stack: Gradle 7.5 + AGP 7.4.2 + JDK 17 + Kotlin 1.8.10, Shizuku 13.1.5 (api + provider), AIDL remote service.

---

## Features

### Mock location core
- ✓ **Dual-provider injection**: `GPS_PROVIDER` and `NETWORK_PROVIDER` so Amap cannot fall back to network location
- ✓ **1 Hz continuous refresh** (v1.1+ is 2 Hz): coroutine loop `setTestProviderLocation`, more like real GPS than a one-shot
- ✓ **Satellite extras**: `extras.satellites=9`, beats “satellites=0 means fake”
- ✓ **Reflection to clear the mock flag**: API 31+ clears `HAS_MOCK_PROVIDER` on `Location.mFieldsMask`; older APIs clear `mIsFromMockProvider` (same-process reads have no mock mark)
- ✓ **Foreground service**: persistent notification; survives screen-off and backgrounding
- ✓ **Coordinate persistence**: last lat/lng saved

### Shizuku
- ✓ **Status**: installed / running / granted / ready
- ✓ **One-tap Shizuku permission** + install jump
- ✓ **Remote AppOps**: UI runs `cmd appops set <pkg> android:mock_location allow`
- ✓ **AppOps query**: immediately shows `cmd appops get`
- ✓ **Fallback**: if Shizuku is unavailable, Developer options → Select mock location app

### UI
- ✓ **12 presets**: Beijing Tiananmen / Shanghai People’s Square / Shenzhen Ping An / Hong Kong Victoria Harbour / Taipei 101 / Tokyo Shibuya / New York Times Square …
- ✓ **Custom lat/lng + accuracy**: 6 decimal places
- ✓ **Live status**: ● running ○ stopped, with current coordinates

---

## Tech stack and dependencies

| Dependency | Version | Role |
|------------|---------|------|
| Kotlin | 1.8.10 | Primary language |
| AGP | 7.4.2 | Build plugin (avoids JDK 21+ issues) |
| Gradle | 7.5 | Build system |
| JDK | 17 | Compile JDK |
| androidx.appcompat | 1.6.1 | Compat |
| material | 1.9.0 | Material UI |
| constraintlayout | 2.1.4 | Layout |
| core-ktx | 1.12.0 | Kotlin extensions |
| lifecycle-runtime-ktx | 2.7.0 | LifecycleScope |
| kotlinx-coroutines-android | 1.7.3 | Injection loop |
| **dev.rikka.shizuku:api** | 13.1.5 | Shizuku client API |
| **dev.rikka.shizuku:provider** | 13.1.5 | Shizuku ContentProvider |
| minSdk | 26 (Android 8) | Minimum |
| targetSdk | 34 (Android 14) | Target |

---

## How to run / use

### 1. Install the APK
Path: `MockGpsShizuku/app/build/outputs/apk/debug/app-debug.apk` (5.4 MB)

```powershell
adb install -r MockGpsShizuku\app\build\outputs\apk\debug\app-debug.apk
```

### 2. Prepare Shizuku (first time, once)
- Download Shizuku: <https://github.com/RikkaApps/Shizuku/releases>
- Open the Shizuku app and start the privileged service **via ADB / wireless ADB / root**
  ```powershell
  # ADB start (the Shizuku app shows the full command)
  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
  ```
- ⚠️ After reboot Shizuku stops; start it again (normal on unrooted devices)

### 3. Five steps in this app
Open “虚拟定位 (Shizuku)”, **strictly ① → ② → ③ → ④ → ⑤**:

1. **① Shizuku status** → “Request Shizuku permission” → Allow
   - Status turns green `✓ Shizuku ready`
2. **② Mock-location grant** → green “Grant mock_location via Shizuku”
   - Below: `mode=allow` (success)
   - Then orange “Turn on system location via Shizuku” (v1.1)
3. **③ Set coordinates** → preset dropdown or type lat/lng
4. **④ Start / stop** → green “Start mock location”
   - Status `● Running  current xx.xxxxxx, xx.xxxxxx`
   - Persistent notification
5. **⑤ GPS fence (v1.2; only if the target is Amap/Baidu with its own network location)**
   - Purple “Start GPS fence”
   - Android “Allow this app to create a VPN connection?” → **Allow**
   - Key icon in the status bar; `● GPS fence running  blocked N | forwarded M`
   - Amap then shows your coordinates **with Wi-Fi/mobile data on; maps/search/navigation still work**

### 4. Verify
Open any GPS app (**maps are the clearest**):
- Amap / Baidu Maps: “My location” should jump to your coordinates
- Google Maps: blue dot at the set coordinates
- WeChat → Me → Moments → post with location: POIs near the set coordinates

### 5. Stop
Back in the app, red **Stop** — **do not just uninstall/kill the app**, or mock providers linger until next launch.

### 6. Without Shizuku
- Settings → About phone → tap build number 7 times → Developer options
- Developer options → Select mock location app → “虚拟定位 (Shizuku)”
- Skip ① ②; go straight to ③ ④

---

## Internals (code tour)

### Key files

```
MockGpsShizuku/app/src/main/java/com/mockgps/shizuku/
├── MainActivity.kt              # UI + orchestration (① Shizuku → ② grant → ③ coords → ④ inject → ⑤ GPS fence)
├── ShizukuHelper.kt             # Shizuku status / permission / bind
├── MockGpsRemoteService.kt      # ★ remote service as shell UID 2000 (cmd appops set / cmd location / dumpsys)
├── MockLocationProvider.kt      # ★ injection loop in the app process (addTestProvider + setTestProviderLocation, 2 Hz, self-healing)
├── MockGpsForegroundService.kt  # Foreground keep-alive
└── LocationFenceVpnService.kt   # ★ v1.2: VpnService DNS sinkhole for Amap/Baidu network-location APIs
```

### Why the v1.2 GPS fence works: DNS only, not all traffic

`LocationFenceVpnService` VPN Builder has three effective routes:
```kotlin
.addAddress("10.7.7.7", 30)      // VPN iface address
.addRoute("10.7.7.8", 32)        // only 10.7.7.8 into the VPN
.addDnsServer("10.7.7.8")        // force system DNS to 10.7.7.8
```

**There is no `addRoute("0.0.0.0", 0)`**, so ordinary traffic stays on the system network. Only DNS to 10.7.7.8 hits our VPN fd. Blocklist → NXDOMAIN; otherwise forward to `114.114.114.114`.

Worker after `onStartCommand`:
```kotlin
val n = input.read(buf)               // IP packet from VPN fd
if (IPv4 && UDP && dstPort == 53) {
    val qName = parseDnsQName(buf)
    if (qName hits the blocklist) {
        write an NXDOMAIN response back to the VPN
    } else {
        new thread: protect()'d socket to 114.114.114.114, write the response back
    }
}
```

**Why Amap is fooled**
Amap SDK:
1. `getLastKnownLocation(GPS_PROVIDER)` — our mock ✓
2. Network location: Wi-Fi BSSID + cell CGI → HTTPS to `apilocate.amap.com` → DNS → **NXDOMAIN** → network location fails ✓
3. Amap trusts GPS = our mock ✓

Wi-Fi/mobile data stay **on**. Other Amap hosts (`vector.amap.com` tiles, `restapi.amap.com` search) are **untouched**.

### How the Shizuku remote service grants mock_location

`MockGpsRemoteService.kt` (shell UID):
```kotlin
override fun grantMockLocation(packageName: String): Int {
    val safePkg = sanitizePackage(packageName)  // injection guard
    val p = Runtime.getRuntime().exec(
        arrayOf("sh", "-c", "cmd appops set $safePkg android:mock_location allow"))
    p.waitFor()
    return p.exitValue()  // 0 = success
}
```

`cmd appops set` is a system tool. Shell UID can set any AppOps on any app without root.
After `OP_MOCK_LOCATION = MODE_ALLOWED`, `LocationManager.addTestProvider()` no longer throws SecurityException — **skipping** Developer options → Select mock location app.

### Injection loop

`MockLocationProvider.kt` (app process, off main-thread coroutine):
```kotlin
private fun registerProviders() {
    for (provider in listOf(GPS_PROVIDER, NETWORK_PROVIDER)) {
        runCatching { lm.removeTestProvider(provider) }
        lm.addTestProvider(provider, false, false, false, false,
            true, true, true, POWER_USAGE_LOW, ACCURACY_FINE)
        lm.setTestProviderEnabled(provider, true)
    }
}

// coroutine once per interval:
private fun pushOnce() {
    for (provider in providers) {
        val loc = Location(provider).apply {
            latitude = lat; longitude = lng
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            extras = Bundle().apply { putInt("satellites", 9) }
        }
        clearMockProviderFlag(loc)  // reflection on mFieldsMask
        lm.setTestProviderLocation(provider, loc)
    }
}
```

### Reflection to hide the mock flag

```kotlin
private fun clearMockProviderFlag(loc: Location) {
    runCatching {
        val field = Location::class.java.getDeclaredField("mFieldsMask")
        field.isAccessible = true
        val mask = field.getInt(loc)
        field.setInt(loc, mask and (1 shl 10).inv())  // HAS_MOCK_PROVIDER_MASK
    }
    runCatching {
        val field = Location::class.java.getDeclaredField("mIsFromMockProvider")
        field.isAccessible = true
        field.setBoolean(loc, false)
    }
}
```

> **Honest note**: this reflection only affects **same-process** Location reads (this app reading its own Location).
> For other apps, LocationManagerService **re-stamps the mock flag** on the way out.
> That can only be intercepted on the system_server side (root or Xposed/LSPosed).
>
> **Bottom line**: this fools ~90% of ordinary location apps (maps, food delivery, ride-hail, social).
> **Strict sports check-in, commercial attendance, DiDi driver, and similar can still tell.** For those, use a VirtualApp container.

---

## File structure

```
MockGpsShizuku/
├── README.md                    # This file
├── settings.gradle              # rootProject.name + include ':app'
├── build.gradle                 # Root Gradle (AGP 7.4.2 + Kotlin 1.8.10)
├── gradle.properties            # JVM args (proxy as needed; see comments)
├── local.properties             # SDK path (local, gitignored)
├── gradlew.bat                  # Gradle wrapper
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   ├── gradle-wrapper-shared.jar
│   └── gradle-wrapper.properties (gradle-7.5-bin)
└── app/
    ├── build.gradle             # Module deps (Shizuku + AndroidX + Coroutines)
    ├── proguard-rules.pro       # Keep RemoteService
    └── src/main/
        ├── AndroidManifest.xml  # Permissions + Activity + Service + ShizukuProvider
        ├── aidl/com/mockgps/shizuku/
        │   └── IMockGpsService.aidl   # Remote service API
        ├── java/com/mockgps/shizuku/
        │   ├── MainActivity.kt              # UI (5-step guide + diagnose + VPN prepare)
        │   ├── ShizukuHelper.kt             # Shizuku status/permission/bind
        │   ├── MockGpsRemoteService.kt      # shell UID remote (cmd appops/cmd location/dumpsys)
        │   ├── MockLocationProvider.kt      # Dual-provider loop (2 Hz, self-healing)
        │   ├── MockGpsForegroundService.kt  # Foreground keep-alive
        │   └── LocationFenceVpnService.kt   # ★ v1.2: VpnService DNS sinkhole
        └── res/
            ├── layout/
            │   └── activity_main.xml
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml
            │   ├── themes.xml
            │   └── styles.xml               # CardTitle / CardBody / buttons
            ├── drawable/
            │   └── ic_launcher_foreground.xml  # Pin icon
            └── mipmap-anydpi-v26/
                ├── ic_launcher.xml
                └── ic_launcher_round.xml
```

---

## Permissions

| Permission | Role | Notes |
|------------|------|-------|
| `ACCESS_MOCK_LOCATION` | Declares mock location | system\|signature; ignored at install; AppOps is what matters |
| `ACCESS_FINE_LOCATION` | Prerequisite for LocationManager | Runtime |
| `ACCESS_COARSE_LOCATION` | Same | Runtime |
| `FOREGROUND_SERVICE` | Foreground keep-alive | Android 8+ |
| `FOREGROUND_SERVICE_LOCATION` | FGS type = location | Android 14+ |
| `POST_NOTIFICATIONS` | Status-bar notification (required for FGS) | Android 13+ runtime |

`MockGpsRemoteService` as shell UID runs `cmd appops set ... mock_location allow` — that is the Shizuku approach.

---

## Build

```powershell
# Set JAVA_HOME to JDK 17
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# Proxy if needed
# $env:HTTP_PROXY = "http://YOUR_PROXY:8080"
# $env:HTTPS_PROXY = "http://YOUR_PROXY:8080"

cd MockGpsShizuku
.\gradlew assembleDebug --no-daemon
```

Output: `MockGpsShizuku\app\build\outputs\apk\debug\app-debug.apk` (~5.4 MB)

---

## Troubleshooting

### 1. Amap says “turn on system location”; after you do, it shows the real location

This is what v1.0 → v1.1 fixed. Three causes:

#### A: ROM resets test providers when the master switch goes OFF→ON
- **Symptom**: you start mock location first, then turn on system location → Amap gets the real fix
- **Why**: MIUI / HyperOS / some ColorOS clear every `addTestProvider` when the master switch flips
- **Fix (automatic in v1.1)**:
  1. Prefer the orange “Turn on system location via Shizuku” (order is under our control)
  2. Even if you toggle it by hand, v1.1 `MockLocationProvider` listens for `MODE_CHANGED_ACTION` and re-registers
  3. Injection loop health-checks every 4 s and restores missing providers

#### B: Amap/Baidu use their own network location and never ask LocationManager
- **Symptom**: `mock_location` allow, `location_mode=3`, dumpsys gps provider `mock=true`, but Amap still shows the real place
- **Why**: `AMapLocationClient` default high-accuracy mode **reads Wi-Fi BSSID + cell CGI and talks to Amap servers**, **bypassing LocationManager**. System mock cannot change that
- **Fixes** (best first):
  1. **★ v1.2**: start ⑤ GPS fence VpnService — NXDOMAIN on Amap/Baidu network-location DNS → fallback to GPS (our mock) → network stays on
  2. Turn off Wi-Fi + mobile data: blunt, works for every app (no v1.2 VPN)
  3. In Amap: Me → Settings → General → location preference → disable network location (some versions)
  4. Diagnose button: check dumpsys `network provider:` for `mock=true`

#### C: mock_location AppOps never actually granted
- **Symptom**: “missing mock_location” on start, or dumpsys gps provider is not mock
- **Fix**:
  1. Step ② `mock_location status` should show `mode=allow`; if not, grant again
  2. `adb shell cmd appops get com.mockgps.shizuku android:mock_location` should print `MOCK_LOCATION: allow`

### 2. “Turn on system location” Shizuku command fails
Some ROMs (especially EMUI / HarmonyOS) block `cmd location set-location-enabled true`. The app falls back to `settings put secure location_mode 3`. If that fails too, it opens system Settings for a manual toggle.

### 3. Status says Running but coordinates do not change
1. Diagnose → dumpsys; look at `gps provider:` and `network provider:`
2. If neither shows `mock=true` / `TestProvider`, `addTestProvider` failed → redo step ②
3. If TestProvider is there but the target app shows the old coords — force-stop the target app so it resubscribes LocationListener

### 4. GPS fence on, Amap still real
- Look at ⑤ “blocked N”:
  - **N=0**: Amap may use DoH/DoT or a raw IP, skipping system DNS. Mode ③ cannot help; turn off Wi-Fi+data
  - **N>0 but still real**: Amap may have cached the last IP; force-stop Amap and reopen
- `adb logcat -s GpsFence` to see blocked / forwarded names; add hits to `LocationFenceVpnService.BLOCK_DOMAINS`

### 5. Shizuku dies after reboot
- Inherent unrooted Shizuku limit; ADB-start again after each reboot (rooted devices can auto-start)
- The Shizuku app shows the full ADB command

---

## Known limits / later work

- ❌ **Cross-process `isFromMockProvider()` can still return true** (LocationManagerService re-stamps the flag)
  - Full hide needs LSPosed on system_server or a VirtualApp container
- ❌ **Cannot fake GnssMeasurement**: apps that read `GnssStatus.Callback` (Amap / WeChat Sports) can still tell
- ❌ **Shizuku dies after reboot**: ADB-start again unless rooted
- ⚠️ **Some China ROMs use a different AppOps command** (old: `appops`, new: `cmd appops`); if grant fails, read the mockOps status text in the UI

Possible later:
- 🚧 Map pick (Tencent Maps SDK, long-press to set coords)
- 🚧 Joystick (step every 100 ms: walk / bike / drive)
- 🚧 Route replay (import GPX, play by timestamp)
- 🚧 Fake speed / bearing (from the path)
