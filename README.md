# Fake GPS — Shizuku, no root

> An Android mock-location app. With [Shizuku](https://github.com/RikkaApps/Shizuku) it grants itself `mock_location` as the shell user, then uses the standard `LocationManager.addTestProvider()` API to keep injecting fake coordinates into `GPS_PROVIDER` and `NETWORK_PROVIDER`. **No root on a real device.** It works with most apps that read system GPS (Amap, Baidu Maps, WeChat, Douyin location, and similar).
>
> The repository ships **two independent apps** (they can be installed together and do not interfere):
>
> | Project | Launcher name | applicationId |
> |---------|-----------------|---------------|
> | `MockGpsShizuku/` | 虚拟定位 (Shizuku) | `com.mockgps.shizuku` |
> | `AmiMockGps/` | **阿米 虚拟定位** (Ami Mock GPS) | `com.ami.mockgps` |

---

## Project overview

You sometimes need the phone to “pretend” it is somewhere else (privacy, LBS testing, and similar). The official path — Developer options → Select mock location app — works, but it is manual every time, and apps that use a network-location SDK (Amap, Baidu, and so on) often ignore it.

This project automates the grant step with Shizuku and fills in details most mock-GPS apps skip:

- **Two branded apps**: the original “虚拟定位 (Shizuku)” is unchanged; Ami Mock GPS (`com.ami.mockgps`) is a separate project with the same features and a different package name so both can be installed
- **Dual-provider injection** (GPS + NETWORK) so Amap cannot fall back to network location
- **2 Hz refresh + self-healing re-registration** so flipping the master location switch does not drop the test provider
- **Foreground service** so the process survives screen-off and backgrounding
- **GPS fence VpnService (DNS sinkhole)**: intercepts only Amap/Baidu *network-location API* hostnames so those SDKs fall back to GPS (our mock). **The rest of the network stays open**; maps, search, and navigation keep working
- **Reflection to clear the mock flag** (no mock mark when the same process reads the location)

> ⚠️ **For learning, testing, and legitimate privacy use only.** Do not use this to cheat check-in, farm orders, or bypass geo-restrictions in violation of an app’s terms or the law. You are responsible for how you use it.

---

## Repository layout

```
Apk_for_Fake_GPS/
├── README.md
├── MockGpsShizuku/           # Original Gradle project
│   ├── README.md             # Full technical docs (5-step usage, internals, troubleshooting)
│   ├── settings.gradle
│   ├── build.gradle
│   ├── gradle.properties
│   ├── gradlew.bat
│   ├── gradle/wrapper/
│   └── app/src/main/         # package com.mockgps.shizuku
│
└── AmiMockGps/               # Ami Mock GPS (different package; can sit next to the original)
    ├── README.md
    ├── settings.gradle
    ├── build.gradle
    ├── gradle.properties
    ├── gradlew.bat
    ├── gradle/wrapper/
    └── app/
        ├── build.gradle      # applicationId = com.ami.mockgps
        └── src/main/
```

> Original usage / internals / troubleshooting: [`MockGpsShizuku/README.md`](MockGpsShizuku/README.md)
> Ami edition differences: [`AmiMockGps/README.md`](AmiMockGps/README.md)

---

## Quick start

### Option 1: Install a build you already compiled

```powershell
adb install -r "MockGpsShizuku\app\build\outputs\apk\debug\app-debug.apk"
adb install -r "AmiMockGps\app\build\outputs\apk\debug\app-debug.apk"
```

### Option 2: Build it yourself

```powershell
# JDK 17 required; set JAVA_HOME to your install
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:ANDROID_HOME = "C:\Android\sdk"
# Corporate proxy if needed:
# $env:HTTP_PROXY  = "http://YOUR_PROXY:8080"
# $env:HTTPS_PROXY = "http://YOUR_PROXY:8080"

# Original (project ships a Gradle Wrapper)
.\MockGpsShizuku\gradlew.bat assembleDebug --no-daemon
# Output: MockGpsShizuku\app\build\outputs\apk\debug\app-debug.apk

# Ami edition
.\AmiMockGps\gradlew.bat assembleDebug --no-daemon
# Output: AmiMockGps\app\build\outputs\apk\debug\app-debug.apk
```

### Usage (5 steps; see the subproject READMEs for detail)

1. **① Shizuku status** → request Shizuku permission (first time: start the Shizuku service via ADB)
2. **② Mock-location grant** → grant `mock_location` through Shizuku and turn on the system location master switch
3. **③ Set coordinates** → pick a preset or type lat/lng
4. **④ Start / stop** → start mock location
5. **⑤ GPS fence** (v1.2) → enable only when the target app (Amap, Baidu, …) uses its own network location

---

## Tech stack and dependencies

| Dependency | Version | Role |
|------------|---------|------|
| Kotlin | 1.8.10 | Primary language |
| AGP | 7.4.2 | Build plugin (avoids JDK 21+ issues) |
| Gradle | 7.5 | Build system |
| JDK | 17 | Compile JDK |
| androidx.appcompat | 1.6.1 | Compatibility |
| material | 1.9.0 | Material UI |
| constraintlayout | 2.1.4 | Layout |
| core-ktx | 1.12.0 | Kotlin extensions |
| lifecycle-runtime-ktx | 2.7.0 | LifecycleScope |
| kotlinx-coroutines-android | 1.7.3 | Injection loop |
| dev.rikka.shizuku:api | 13.1.5 | Shizuku client API |
| dev.rikka.shizuku:provider | 13.1.5 | Shizuku ContentProvider |
| minSdk | 26 (Android 8) | Minimum |
| targetSdk | 34 (Android 14) | Target |

---

## How it works (short)

1. **Shizuku grant**: `MockGpsRemoteService` runs as shell UID (2000) and executes `cmd appops set <pkg> android:mock_location allow`, so the app gets mock-location without root.
2. **Injection loop**: `MockLocationProvider` calls `addTestProvider` + `setTestProviderLocation` on `GPS_PROVIDER` / `NETWORK_PROVIDER`, refreshes at 2 Hz, and re-registers if the master switch changes.
3. **GPS fence**: `LocationFenceVpnService` is a VpnService that only routes a fake DNS IP (`10.7.7.8/32`). Amap/Baidu network-location hostnames get NXDOMAIN; everything else is forwarded to `114.114.114.114`. Amap falls back to GPS (our mock) without breaking ordinary traffic.

---

## Known limits

- ❌ **Cross-process `isFromMockProvider()` can still return true** (the system re-stamps the mock flag in LocationManagerService). Hiding that fully needs an LSPosed hook in system_server or a VirtualApp container.
- ❌ **Cannot fake raw GnssMeasurement**. Apps that read `GnssStatus.Callback` (e.g. WeChat Sports) can still tell.
- ❌ **Shizuku dies after reboot** until you start it with ADB again (unless the device is rooted).
- ⚠️ Strict anti-cheat on sports check-in, commercial attendance, DiDi driver, and similar can still detect this.

---

## Secrets and config

This repository has no keys or personal data. For a networked first build, set a proxy **locally and do not commit it**:

- Environment: `HTTP_PROXY` / `HTTPS_PROXY`
- Or `systemProp.http(s).proxyHost/Port` in `MockGpsShizuku/gradle.properties` / `AmiMockGps/gradle.properties`

`local.properties` (your Android SDK path) is gitignored. Android Studio creates it the first time you open the project.
