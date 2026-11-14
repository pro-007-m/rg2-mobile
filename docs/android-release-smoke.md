# Release APK smoke gate

The 13-check native runner supports both debug and release targets. A debug PASS must not be described as release validation. The runner now reports the installed target's actual BuildConfig build type, Package Manager flags, APK SHA256, signing certificates and version.

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

Screenshots are diagnostic artifacts and require visual review before store use. In particular, the first UIAutomation capture can retain an older native frame even after the DOM route has changed; filenames alone do not prove what a screenshot shows. The six original screenshot names remain stable for existing QA consumers.

Host-side regression checks need no SDK, emulator or signing key:

```powershell
.\tests\android-smoke.test.ps1
```

They verify rejection of a wrong frozen hash before external tools, preservation of previous reports, rejection of the shared ADB port, and rejection of an explicit signer that differs from the canonical release pin. Full release acceptance additionally requires the native emulator run above.

Android documents the [test build-type selection](https://developer.android.com/studio/test/advanced-test-setup), [AndroidTest signing config](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/AndroidTest), and [variant signing API](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/SigningConfig).
