param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$TestApk,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateRange(1,65535)][int]$AdbPort,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedApkSha256,
    [Parameter(Mandatory=$true)][string]$ExpectedVersionName,
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$ExpectedVersionCode,
    [ValidateSet('Release','Debug')][string]$BuildType = 'Release',
    [string]$ExpectedSignerSha256 = '',
    [switch]$VirtualPuzzles,
    [switch]$ReviewMode,
    [switch]$LauncherIcons,
    [string]$SdkDirectory = '',
    [string]$JavaDirectory = ''
)
# Only an explicitly selected isolated emulator is supported. This never uninstalls
# packages, generates signing keys, re-signs the target, or switches to a debug APK.
$ErrorActionPreference = 'Stop'
if ($AdbPort -eq 5037) { throw 'Use a dedicated QA ADB port, not the shared default port 5037.' }
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$JavaDirectory) { $JavaDirectory = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-23' } }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$testPath = (Resolve-Path -LiteralPath $TestApk).Path
$out = [IO.Path]::GetFullPath($OutputDirectory)
if ((Test-Path -LiteralPath $out) -and @(Get-ChildItem -LiteralPath $out -Force).Count) { throw 'QA output directory must be new or empty; previous reports are preserved.' }
New-Item -Path $out -ItemType Directory -Force | Out-Null
$adb = Join-Path $SdkDirectory 'platform-tools/adb.exe'
$apksigner = Join-Path $SdkDirectory 'build-tools/36.0.0/apksigner.bat'
$reportPath = Join-Path $out 'native-verification.json'
$report = [ordered]@{passed=$false;buildType=$BuildType.ToLowerInvariant();targetApk=$apkPath;testApk=$testPath;expectedApkSha256=$ExpectedApkSha256.ToLowerInvariant();serial=$Serial;adbPort=$AdbPort;startedAt=[DateTime]::UtcNow.ToString('o');stages=[Collections.Generic.List[object]]::new()}
$savedJava = $env:JAVA_HOME
$failure = $null
$stage = 'verify-input'
$expectedCheckCount = if ($VirtualPuzzles) { 18 } else { 13 }
if ($ReviewMode) { $expectedCheckCount += 3 }
function Capture-Command([string]$Name, [string]$Program, [string[]]$CommandArguments) {
    $lines = @(& $Program @CommandArguments 2>&1)
    $code = $LASTEXITCODE
    $output = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
    $output | Set-Content -LiteralPath (Join-Path $out ($Name + '.txt')) -Encoding utf8
    $report.stages.Add([ordered]@{name=$Name;exitCode=$code;output=$output})
    return [pscustomobject]@{exitCode=$code;output=$output}
}
function Adb-Command([string]$Name, [string[]]$CommandArguments) {
    return Capture-Command $Name $adb (@('-P',[string]$AdbPort,'-s',$Serial) + $CommandArguments)
}
function Require-Success($Result, [string]$Message) {
    if ($Result.exitCode -ne 0) { throw ($Message + "`n" + $Result.output) }
}
function Certificate-Of([string]$Name, [string]$Path) {
    $result = Capture-Command $Name $apksigner @('verify','--verbose','--print-certs',$Path)
    Require-Success $result ('APK signature verification failed: ' + $Path)
    $matches = [regex]::Matches($result.output, 'Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})')
    if ($matches.Count -ne 1) { throw 'Exactly one APK signer is required for this QA gate.' }
    return $matches[0].Groups[1].Value.ToLowerInvariant()
}
try {
    $env:JAVA_HOME = $JavaDirectory
    $actualHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.targetApkSha256 = $actualHash
    $report.testApkSha256 = (Get-FileHash -LiteralPath $testPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $ExpectedApkSha256.ToLowerInvariant()) { throw 'The input APK does not match the expected frozen artifact SHA256.' }
    if ($BuildType -eq 'Release') {
        $stage = 'verify-canonical-pin'
        $canonicalSigner = (Get-Content -LiteralPath (Join-Path $repoRoot 'android/release-certificate.sha256') -Raw).Trim().ToLowerInvariant()
        if ($canonicalSigner -notmatch '^[a-f0-9]{64}$') { throw 'The repository canonical release certificate pin is invalid.' }
        if ($ExpectedSignerSha256 -and $ExpectedSignerSha256.ToLowerInvariant() -ne $canonicalSigner) {
            throw 'An explicit release signer must match the repository canonical certificate pin.'
        }
        $ExpectedSignerSha256 = $canonicalSigner
        $report.canonicalSignerSha256 = $canonicalSigner
    }
    $stage = 'verify-target-signature'
    $targetSigner = Certificate-Of $stage $apkPath
    $stage = 'verify-test-signature'
    $testSigner = Certificate-Of $stage $testPath
    if (!$ExpectedSignerSha256) {
        $ExpectedSignerSha256 = $targetSigner
    }
    $ExpectedSignerSha256 = $ExpectedSignerSha256.ToLowerInvariant()
    if ($ExpectedSignerSha256 -notmatch '^[a-f0-9]{64}$' -or $targetSigner -ne $ExpectedSignerSha256 -or $testSigner -ne $ExpectedSignerSha256) { throw 'Target/test certificates do not match the expected signing pin.' }
    $report.targetSignerSha256 = $targetSigner
    $report.testSignerSha256 = $testSigner
    $stage = 'check-device'
    $result = Adb-Command $stage @('get-state')
    Require-Success $result 'The explicitly selected QA emulator is unavailable.'
    if ($result.output.Trim() -ne 'device') { throw 'The selected emulator is not ready.' }
    $stage = 'install-target'
    # Deliberately no -t or -d: a production APK must install normally.
    $result = Adb-Command $stage @('install','-r',$apkPath)
    Require-Success $result 'Release target installation failed; instrumentation was not run.'
    if ($result.output -notmatch '(?m)^Success\s*$') { throw ('Package Manager did not report target install success: ' + $result.output) }
    $stage = 'install-test'
    $result = Adb-Command $stage @('install','-r','-t',$testPath)
    Require-Success $result 'Instrumentation fixture installation failed.'
    foreach ($command in @(@('cmd','connectivity','airplane-mode','enable'),@('svc','wifi','disable'),@('svc','data','disable'))) {
        $stage = 'offline-' + $command[1]
        Require-Success (Adb-Command $stage (@('shell') + $command)) 'Could not disable networking in the isolated emulator.'
    }
    $stage = 'instrumentation'
    $requireRelease = ($BuildType -eq 'Release').ToString().ToLowerInvariant()
    $virtualFlag = if ($VirtualPuzzles) { 'true' } else { 'false' }
    $reviewFlag = if ($ReviewMode) { 'true' } else { 'false' }
    $iconFlag = if ($LauncherIcons) { 'true' } else { 'false' }
    $smokeTimeout = if ($ReviewMode) { '240000' } elseif ($VirtualPuzzles) { '180000' } else { '90000' }
    $report.virtualPuzzlesRequired = [bool]$VirtualPuzzles
    $report.reviewModeRequired = [bool]$ReviewMode
    $report.launcherIconsRequired = [bool]$LauncherIcons
    $result = Adb-Command $stage @('shell','am','instrument','-w','-e','timeoutMs',$smokeTimeout,'-e','virtualPuzzles',$virtualFlag,'-e','reviewMode',$reviewFlag,'-e','launcherIcons',$iconFlag,'-e','requireRelease',$requireRelease,
        '-e','expectedApkSha256',$actualHash,'-e','expectedFixtureSha256',$report.testApkSha256,'-e','expectedSignerSha256',$ExpectedSignerSha256,'-e','expectedVersionName',$ExpectedVersionName,
        '-e','expectedVersionCode',[string]$ExpectedVersionCode,'io.github.pro007m.rg2.test/io.github.pro007m.rg2.OfflineSmokeInstrumentation')
    Require-Success $result 'Instrumentation command failed.'
    $match = [regex]::Match($result.output, '(?m)^INSTRUMENTATION_RESULT: report=(.+)$')
    if (!$match.Success) { throw 'Instrumentation did not return its structured report.' }
    $runtime = $match.Groups[1].Value | ConvertFrom-Json
    $report.runtime = $runtime
    if (!$runtime.passed -or $runtime.checks.Count -ne $expectedCheckCount -or @($runtime.checks | Where-Object { !$_.passed }).Count -or
        $result.output -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$') { throw ('Instrumentation did not pass all ' + $expectedCheckCount + ' smoke checks.') }
    if ($VirtualPuzzles) {
        foreach ($required in @('virtual-game-boot','virtual-nxn50','virtual-polyhedra','virtual-ortega-worker','virtual-native-touch-and-restore')) {
            if (@($runtime.checks | Where-Object { $_.name -eq $required -and $_.passed }).Count -ne 1) { throw ('Required virtual-puzzle verification is missing: ' + $required) }
        }
    }
    if ($ReviewMode) {
        if (!$runtime.reviewModeRequested -or !$runtime.reviewDatabaseRestored) { throw 'Review QA did not restore its private workspace snapshot.' }
        foreach ($required in @('review-developer-unlock','review-edit-preview-persistence','review-native-document-roundtrip')) {
            if (@($runtime.checks | Where-Object { $_.name -eq $required -and $_.passed }).Count -ne 1) { throw ('Required review verification is missing: ' + $required) }
        }
    }
    if ($LauncherIcons -and (!$runtime.launcherIconsRequested -or !$runtime.launcherIcons.passed)) { throw 'Installed native launcher icon mask/themed verification did not pass.' }
    $identity = $runtime.installedTarget
    if (!$identity -or $identity.installedApkSha256 -ne $actualHash -or $identity.fixtureApkSha256 -ne $report.testApkSha256 -or $identity.signerSha256.Count -ne 1 -or
        $identity.signerSha256[0] -ne $ExpectedSignerSha256 -or $runtime.versionName -ne $ExpectedVersionName -or $runtime.versionCode -ne $ExpectedVersionCode) { throw 'Runtime identity does not match the frozen target APK.' }
    if ($BuildType -eq 'Release' -and ($identity.debuggable -or $identity.testOnly -or !$identity.releaseGateRequired -or $identity.buildType -ne 'release')) { throw 'Runtime target did not satisfy the release gate.' }
    $stage = 'pull-runtime-report'
    Require-Success (Adb-Command $stage @('pull','/sdcard/Android/data/io.github.pro007m.rg2/files/qa/offline-smoke-result.json',(Join-Path $out 'offline-smoke-result.json'))) 'Cannot save the runtime report.'
    foreach ($screen in $runtime.screenshots) {
        if ($screen.name -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid QA screenshot filename.' }
        $name = $screen.name + '.png'
        $stage = 'pull-' + $screen.name
        Require-Success (Adb-Command $stage @('pull',('/sdcard/Android/data/io.github.pro007m.rg2/files/qa/' + $name),(Join-Path $out $name))) 'Cannot save a QA screenshot.'
    }
    $report.passed = $true
    $report.visualReviewRequired = $true
} catch {
    $failure = $_
    $report.failedStage = $stage
    $report.error = $_.Exception.Message
} finally {
    $env:JAVA_HOME = $savedJava
    $report.finishedAt = [DateTime]::UtcNow.ToString('o')
    $report | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $reportPath -Encoding utf8
}
if ($failure) { throw ('Android native verification failed at ' + $stage + '. See ' + $reportPath + "`n" + $failure.Exception.Message) }
[pscustomobject]@{passed=$true;buildType=$report.buildType;versionName=$ExpectedVersionName;versionCode=$ExpectedVersionCode;checks=$expectedCheckCount;report=$reportPath}
