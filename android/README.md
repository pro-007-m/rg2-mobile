# Android shell

Java Activity + AndroidX WebKit 1.18.0. Gradle 8.13 / AGP 8.13.2; compile/target SDK36, Build Tools36.0.0, minimum Android7.0/API24. Java source/bytecode17. The verified local Windows build also runs on the installed Oracle JDK23.0.1; the build helper applies a process-local selector socket workaround for that exact runtime.

`syncWebAssets` copies **all** `../data` files into `app/build/generated/rg2assets/www`. WebView serves that tree from `https://appassets.androidplatform.net/assets/www/index.html` through WebViewAssetLoader, including correct JavaScript MIME for `.mjs`. The APK does not contain the Windows runtime. Remote subresource requests are blocked. HTTP/HTTPS article links open in a browser; mail links use the device's mail composer. The app has no INTERNET, installation, advertising, analytics, or broad storage permission.

From the repository root on Windows:

```powershell
.\tools\android-build.ps1
.\tools\android-build.ps1 -Configuration Debug -IncludeInstrumentation
```

The helper only creates local project/build-cache files. `-SdkDirectory` and `-JavaDirectory` select already installed tools. `-CacheDirectory` and `-SigningDirectory` are optional. No environment or SDK configuration is changed globally. It downloads official checksum-pinned Gradle files and resolves Android dependencies from Google Maven/Maven Central. `android/gradlew` is the standard pinned Gradle wrapper for other platforms (`bash android/gradlew -p android :app:assembleDebug`).

Release outputs:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`
- `app/build/outputs/build-verification.json` with file hashes and the public signing-certificate fingerprint

The initial release build created one RSA3072 PKCS12 key at the default **outside-repository** location `../.rg2-mobile-signing/rg2-mobile-release.p12` relative to the repository, alongside a private `signing.json`. Subsequent builds reuse that key and never print its passwords. On Windows this folder is restricted to the current user and SYSTEM. Keep an encrypted backup of the **whole private signing folder** separately from source/release archives. Both the key and password file are needed to publish compatible updates. Do not publish either file or replace the key for a new store/channel. `release-certificate.sha256` pins the public certificate; a source clone without the original private key can build Debug but must restore that backup for a canonical Release build. APK/AAB certificate equality and the public fingerprint are checked after each release build.

`tools/android-emulator.ps1` creates a headless QA AVD entirely in the local build cache, using the existing Android36 image. It uses its own ADB server15048 and emulator serial `emulator-5584`; it does not select or alter personal AVDs/devices. Stop that emulator with `adb -P15048 -s emulator-5584 emu kill` after QA. No desktop UI automation is involved.

Native bridge `RG2NativePort.postMessage(JSON)` accepts only the exact local origin and main frame. Commands: `ready`, `clipboard`, `theme`, `keep-awake`, `haptic`, `open-external`, `export-data`, `import-data`. Backup import/export uses the Android document picker and an 8MiB cap. The JavaScript layer validates the backup and confirms changes before applying it. Back invokes the UI's `RG2Mobile.back()`; returning false backgrounds the Activity. Pause/resume/system-theme events are dispatched without reloading the page. Native and web dialogs remain available, and Android's system text scaling is respected.

Пакеты AAB предназначены для личного кабинета владельца в магазине. Их сборка и проверка выполняются локально; в публичные GitHub Releases, Pages и архивы исходников они не включаются. Локальная подготовка магазинных пакетов отделена от публичного набора APK в `artifacts/store-upload/<версия>/`.
