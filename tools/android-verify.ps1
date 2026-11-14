param([string]$SdkDirectory = '', [string]$JavaDirectory = '')
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$JavaDirectory) { $JavaDirectory = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-23' } }
$apk = Join-Path $repoRoot 'android/app/build/outputs/apk/release/app-release.apk'
$bundle = Join-Path $repoRoot 'android/app/build/outputs/bundle/release/app-release.aab'
$savedJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaDirectory
    $apkVerification = & (Join-Path $SdkDirectory 'build-tools/36.0.0/apksigner.bat') verify --verbose --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $apkCertificate = ($apkVerification | Select-String 'Signer #1 certificate SHA-256 digest: (\S+)').Matches.Groups[1].Value.ToLowerInvariant()
    if (!$apkCertificate) { throw 'APK signing certificate is missing.' }
    $pinPath = Join-Path $repoRoot 'android/release-certificate.sha256'
    if ((Test-Path -LiteralPath $pinPath) -and (Get-Content -LiteralPath $pinPath -Raw).Trim() -ne $apkCertificate) {
        throw 'APK signing certificate differs from the canonical public release fingerprint.'
    }
    $bundleVerification = & (Join-Path $JavaDirectory 'bin/jarsigner.exe') '-J-Duser.language=en' -verify $bundle 2>&1
    if ($LASTEXITCODE -ne 0 -or ($bundleVerification -join "`n") -notmatch 'jar verified') {
        throw 'AAB JAR signature verification failed.'
    }
    $bundleCertificateInfo = & (Join-Path $JavaDirectory 'bin/keytool.exe') '-J-Duser.language=en' -printcert -jarfile $bundle 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read AAB signing certificate.' }
    $match = [regex]::Match(($bundleCertificateInfo -join "`n"), 'SHA256:\s*([A-Fa-f0-9:]+)')
    $bundleCertificate = $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
    if ($bundleCertificate -ne $apkCertificate) { throw 'APK and AAB must be signed by the same release key.' }
    $files = foreach ($path in @($apk, $bundle)) {
        $file = Get-Item -LiteralPath $path
        [pscustomobject]@{name=$file.Name;path=$file.FullName;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $gradleSource = Get-Content -LiteralPath (Join-Path $repoRoot 'android/app/build.gradle') -Raw
    $versionMatch = [regex]::Match($gradleSource, "versionName\s+'(\d+\.\d+\.\d+)'")
    if (!$versionMatch.Success) { throw 'Cannot identify Android release version.' }
    $report = [pscustomobject]@{version=$versionMatch.Groups[1].Value;apkVerified=$true;aabVerified=$true;certificateSha256=$apkCertificate;files=$files}
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $repoRoot 'android/app/build/outputs/build-verification.json') -Encoding utf8
    $report | ConvertTo-Json -Depth 5
} finally { $env:JAVA_HOME = $savedJava }
