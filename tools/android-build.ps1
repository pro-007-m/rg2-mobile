param(
    [ValidateSet('Release', 'Debug')][string]$Configuration = 'Release',
    [string]$SdkDirectory = '',
    [string]$JavaDirectory = '',
    [string]$CacheDirectory = '',
    [string]$SigningDirectory = '',
    [switch]$Offline,
    [switch]$IncludeInstrumentation
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workRoot = Split-Path $repoRoot -Parent
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$JavaDirectory) { $JavaDirectory = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-23' } }
if (!$CacheDirectory) { $CacheDirectory = Join-Path $workRoot 'rg2-mobile-build-cache' }
if (!$SigningDirectory) { $SigningDirectory = Join-Path $workRoot '.rg2-mobile-signing' }
$sdkRoot = [IO.Path]::GetFullPath($SdkDirectory)
$javaRoot = [IO.Path]::GetFullPath($JavaDirectory)
$cacheRoot = [IO.Path]::GetFullPath($CacheDirectory)
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
$repoPrefix = $repoRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($signingRoot.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $signingRoot.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The release signing directory must be outside the source repository.'
}
foreach ($required in @((Join-Path $sdkRoot 'platforms/android-36/android.jar'),
    (Join-Path $sdkRoot 'build-tools/36.0.0/apksigner.bat'), (Join-Path $javaRoot 'bin/javac.exe'))) {
    if (!(Test-Path -LiteralPath $required)) { throw ('Required installed tool is missing: ' + $required) }
}
$gradleRoot = & (Join-Path $PSScriptRoot 'android-bootstrap.ps1') -CacheDirectory $cacheRoot -Offline:$Offline
$androidRoot = Join-Path $repoRoot 'android'
('sdk.dir=' + $sdkRoot.Replace('\', '/')) | Set-Content -LiteralPath (Join-Path $androidRoot 'local.properties') -Encoding utf8
$settings = @{
    JAVA_HOME = $javaRoot
    ANDROID_HOME = $sdkRoot
    ANDROID_SDK_ROOT = $sdkRoot
    ANDROID_USER_HOME = (Join-Path $cacheRoot 'android-user')
    GRADLE_USER_HOME = (Join-Path $cacheRoot 'gradle-user-home')
    PATH = (Join-Path $javaRoot 'bin') + ';' + $env:PATH
}
# Oracle 23.0.1 on this Windows build cannot connect its AF_UNIX selector pipe.
# A deliberately long private socket directory makes the JDK use its TCP fallback.
# Pass this to every build JVM, including Gradle's single-use daemon; no global setting changes.
if ($env:OS -eq 'Windows_NT' -and (Get-Content -LiteralPath (Join-Path $javaRoot 'release') -Raw) -match 'JAVA_VERSION="23\.0\.1"') {
    $socketDirectory = Join-Path $cacheRoot 'jvm-sockets'
    while ($socketDirectory.Length -lt 140) { $socketDirectory = Join-Path $socketDirectory 'local-tcp-fallback' }
    New-Item -Path $socketDirectory -ItemType Directory -Force | Out-Null
    $settings.JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir=' + $socketDirectory).Trim()
}
$saved = @{}
try {
    if ($Configuration -eq 'Release') {
        New-Item -Path $signingRoot -ItemType Directory -Force | Out-Null
        $keyPath = Join-Path $signingRoot 'rg2-mobile-release.p12'
        $secretPath = Join-Path $signingRoot 'signing.json'
        $certificatePin = Join-Path $repoRoot 'android/release-certificate.sha256'
        if ((Test-Path -LiteralPath $certificatePin) -and !(Test-Path -LiteralPath $keyPath)) {
            throw 'The canonical release certificate is pinned. Restore the original private signing backup before a release build.'
        }
        if ((Test-Path -LiteralPath $keyPath) -ne (Test-Path -LiteralPath $secretPath)) {
            throw 'The signing key or its password file is missing. Restore the original private backup; do not create a replacement key.'
        }
        if (!(Test-Path -LiteralPath $secretPath)) {
            $random = New-Object byte[] 32
            $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $generator.GetBytes($random) } finally { $generator.Dispose() }
            $password = [Convert]::ToBase64String($random)
            [pscustomobject]@{alias='rg2-mobile-release';storePassword=$password;keyPassword=$password} |
                ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding utf8
        }
        # This newly created private folder alone receives restrictive permissions.
        if ($env:OS -eq 'Windows_NT') {
            $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            $aclOutput = & icacls $signingRoot /inheritance:r /grant:r ('*' + $sid + ':(OI)(CI)F') '*S-1-5-18:(OI)(CI)F' 2>&1
            if ($LASTEXITCODE -ne 0) { throw 'Cannot protect the private signing directory.' }
        }
        $secret = Get-Content -LiteralPath $secretPath -Raw | ConvertFrom-Json
        $settings.RG2_SIGNING_STORE_FILE = $keyPath
        $settings.RG2_SIGNING_STORE_PASSWORD = [string]$secret.storePassword
        $settings.RG2_SIGNING_KEY_ALIAS = [string]$secret.alias
        $settings.RG2_SIGNING_KEY_PASSWORD = [string]$secret.keyPassword
    }
    foreach ($key in $settings.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    if ($Configuration -eq 'Release' -and !(Test-Path -LiteralPath $settings.RG2_SIGNING_STORE_FILE)) {
        $keytoolOutput = & (Join-Path $javaRoot 'bin/keytool.exe') -genkeypair -noprompt -storetype PKCS12 `
            -keystore $settings.RG2_SIGNING_STORE_FILE -storepass:env RG2_SIGNING_STORE_PASSWORD `
            -keypass:env RG2_SIGNING_KEY_PASSWORD -alias $settings.RG2_SIGNING_KEY_ALIAS `
            -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=RG2 Mobile, O=RG2' 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the release key; the password was not printed.' }
    }
    if ($Configuration -eq 'Release' -and (Test-Path -LiteralPath $certificatePin)) {
        $publicCertificate = Join-Path $cacheRoot 'release-certificate.der'
        $certificateOutput = & (Join-Path $javaRoot 'bin/keytool.exe') -exportcert `
            -keystore $settings.RG2_SIGNING_STORE_FILE -storepass:env RG2_SIGNING_STORE_PASSWORD `
            -alias $settings.RG2_SIGNING_KEY_ALIAS -file $publicCertificate 2>&1
        if ($LASTEXITCODE -ne 0 -or
            (Get-FileHash -LiteralPath $publicCertificate -Algorithm SHA256).Hash.ToLowerInvariant() -ne (Get-Content -LiteralPath $certificatePin -Raw).Trim()) {
            throw 'The private key does not match the canonical release certificate. Restore the original signing backup.'
        }
    }
    [string[]]$tasks = if ($Configuration -eq 'Release') { @(':app:assembleRelease', ':app:bundleRelease') } else { @(':app:assembleDebug') }
    if ($IncludeInstrumentation) { $tasks += ':app:assembleDebugAndroidTest' }
    $gradleArgs = @('-p', $androidRoot, '--no-daemon', '--console=plain', '--max-workers=2')
    if ($Offline) { $gradleArgs += '--offline' }
    & (Join-Path $gradleRoot 'bin/gradle.bat') @gradleArgs @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed. See the Gradle diagnostics above.' }
    if ($Configuration -eq 'Release') {
        & (Join-Path $PSScriptRoot 'android-verify.ps1') -SdkDirectory $sdkRoot -JavaDirectory $javaRoot
    }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
