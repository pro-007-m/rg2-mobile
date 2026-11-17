# Release APK smoke gate

The base 13-check native runner supports both debug and release targets. `-VirtualPuzzles` extends it to 18 checks for releases containing the playable puzzles. A debug PASS must not be described as release validation. The runner reports the installed target's actual BuildConfig build type, Package Manager flags, APK SHA256, signing certificates and version.

Build the release instrumentation fixture with the existing canonical signing backup:

```powershell
.\tools\android-build.ps1 -Configuration Release -IncludeInstrumentation -Offline
```

The wrapper selects `-Prg2TestBuildType=release` and `assembleReleaseAndroidTest`. It verifies that target and fixture certificates match the pinned release certificate. Missing signing backup/pin fails the build; release QA never creates a replacement signing key. Normal debug builds retain the default debug test variant.

Use an explicitly selected, separate QA emulator and its own ADB server. Do not use a personal device or the shared ADB port 5037. Test the frozen APK that was actually distributed, rather than substituting a newly compiled target:

```powershell
.\tools\android-smoke.ps1 `
  -Apk .\artifacts\releases\1.0.1\RG2-Mobile-1.0.1.apk `
  -TestApk .\android\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk `
  -ExpectedApkSha256 '<SHA256 from the published artifact manifest>' `
  -ExpectedVersionName 1.0.1 -ExpectedVersionCode 2 `
  -Serial emulator-5584 -AdbPort 15048 `
  -OutputDirectory ..\mobile-native-qa\release-1.0.1\attempt-1
```

The example hash placeholder must be replaced with the expected release artifact hash. Output must be new or empty; existing QA evidence is preserved. The script never uninstalls packages, re-signs the target, changes its version, or switches to debug after a failure. A previously installed debug app with the same package/signature mismatch must be handled separately in that isolated QA profile.

Target installation deliberately omits `-t` and `-d`. Only the test fixture may use `-t`. Every command's output and status is retained in `native-verification.json`; install failures stop before instrumentation. Wi-Fi/data are disabled and airplane mode is enabled only on the selected emulator.

Release acceptance requires all of these facts from the running target:

- Actual build type `release`, `debuggable=false`, `testOnly=false`.
- Installed base.apk SHA256 equals the supplied frozen artifact hash, and the running fixture APK hash equals the selected test APK hash.
- Target and fixture certificates both equal the canonical release pin.
- Expected versionName/versionCode and all 13 original smoke checks pass.
- Structured `passed=true` and instrumentation result code −1; adb process exit 0 alone is insufficient.

The runner's explicit `requireRelease=true` path enforces these facts before launching the UI. Without that argument, normal debug runs report observed identity without pretending to be release validation. Runtime identity is inspected through public Package Manager APIs and the target's own class loader; WebView debugging/CDP is not enabled for release QA.

## Virtual puzzles on Android 15

Use the final, frozen Release APK and its matching Release AndroidTest fixture. For RG2 Mobile 1.1.0 the expected version is `1.1.0`, versionCode `3`; the signing certificate remains the canonical repository pin. The example below is a command template, not evidence that this release has already passed.

An isolated lower-resource Android 15 profile can be created without using a personal AVD or the shared ADB server:

```powershell
.\tools\android-emulator.ps1 `
  -ApiLevel 35 -Memory 1536 -Cores 2 `
  -Port 5586 -AdbServerPort 15049 `
  -ImageDirectory ..\rg2-mobile-build-cache\system-images\android-35-playstore-r9\x86_64
```

The prepared QA image is the official Google Play x86_64 API 35 revision 9 archive: 1,762,061,559 bytes, SHA-1 `2f0054868e6aab3c098acd3decba17a82aed4176`. Its files live outside the source repository. If the matching image is already installed in the Android SDK, omit `-ImageDirectory`. The helper checks the image's API level, ABI and tag; it refuses shared ADB port 5037, odd console ports, occupied ports and AVD directories without its ownership marker. Existing API 36 / 3072 MiB / 4-core defaults are preserved.

Wait until the selected emulator is connected and `adb -P 15049 -s emulator-5586 shell getprop sys.boot_completed` returns `1`, then run:

```powershell
.\tools\android-smoke.ps1 `
  -Apk .\artifacts\releases\1.1.0\RG2-Mobile-1.1.0.apk `
  -TestApk .\android\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk `
  -ExpectedApkSha256 '<SHA256 from the frozen release artifact manifest>' `
  -ExpectedVersionName 1.1.0 -ExpectedVersionCode 3 `
  -Serial emulator-5586 -AdbPort 15049 `
  -VirtualPuzzles `
  -OutputDirectory ..\mobile-native-qa\release-1.1.0\api35-attempt-1
```

`-VirtualPuzzles` passes `virtualPuzzles=true` and a 180,000 ms global instrumentation budget. It requires the original 13 checks plus exactly one passing result for each of these five names:

- `virtual-game-boot`: actual 3x3 model, selected cross color below, WebGL 2 and opaque geometry.
- `virtual-nxn50`: wide/inner turns on 50x50 and independent logical/visual replay.
- `virtual-polyhedra`: pyramids and dodecahedra at orders 2 and 20, including hidden cells and opaque rendering.
- `virtual-ortega-worker`: visible stage choices, actual Worker messages containing state without move history, and a completed 2x2 Ortega solve with independent replay. Additional actual Worker hints exercise the 4x4 center reduction, standard pyramid/dodecahedron SGS imports, and the large order-20 pyramid/dodecahedron modules. Each starts from a legal nonsolved state and independently replays a nonempty candidate; only the 2x2 sample claims full completion.
- `virtual-native-touch-and-restore`: native Instrumentation `MotionEvent` swipe and two-pointer pinch, native pause/resume callbacks, IndexedDB persistence and WebView reload with exact restoration. It also enters the mobile fullscreen layout and sends a real Android Back key, checking that fullscreen closes while the route and puzzle state remain unchanged.

The optional test snapshots and restores the puzzle IndexedDB store as well as the original localStorage data. Its report identifies whether virtual checks were requested and whether database restoration completed. Five additional screenshots have names `07-virtual-cube-portrait` through `11-virtual-touch-restored`; they still require visual review.

The 1536 MiB / 2-core emulator is a reproducible compatibility and low-resource profile. It is not a physical TECNO SPARK Go 3 benchmark. Record the emulator/API, WebView version, artifact identity and actual results separately from claims about a real phone. With `-VirtualPuzzles` omitted, the historical 13-check / 90,000 ms behavior remains available for older artifacts.

Screenshots are diagnostic artifacts and require visual review before store use. In particular, the first UIAutomation capture can retain an older native frame even after the DOM route has changed; filenames alone do not prove what a screenshot shows. The six original screenshot names remain stable for existing QA consumers.

Host-side regression checks need no SDK, emulator or signing key:

```powershell
.\tests\android-smoke.test.ps1
```

They verify rejection of a wrong frozen hash before external tools, preservation of previous reports, rejection of the shared ADB port, and rejection of an explicit signer that differs from the canonical release pin. Full release acceptance additionally requires the native emulator run above.

Android documents the [test build-type selection](https://developer.android.com/studio/test/advanced-test-setup), [AndroidTest signing config](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/AndroidTest), and [variant signing API](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/SigningConfig).
