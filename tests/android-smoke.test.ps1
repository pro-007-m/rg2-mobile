$ErrorActionPreference = 'Stop'
$helper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../tools/android-smoke.ps1'))
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
$testRoot = Join-Path $tempBase ('rg2-smoke-tests-' + [Guid]::NewGuid().ToString('N'))
New-Item -Path $testRoot -ItemType Directory | Out-Null
function Check([bool]$Value,[string]$Message) { if (!$Value) { throw $Message } }
try {
    $apk = Join-Path $testRoot 'frozen.apk'
    $fixture = Join-Path $testRoot 'fixture.apk'
    [IO.File]::WriteAllBytes($apk,[byte[]](1,2,3,4))
    [IO.File]::WriteAllBytes($fixture,[byte[]](4,3,2,1))
    $actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $arguments = @{Apk=$apk;TestApk=$fixture;Serial='emulator-5584';AdbPort=15048;ExpectedVersionName='1.0.1';ExpectedVersionCode=2}

    # This must fail before apksigner or ADB can be invoked; dummy APKs suffice.
    $wrongHashOutput = Join-Path $testRoot 'wrong-hash'
    $failed = $false
    try { & $helper @arguments -ExpectedApkSha256 ('0'*64) -OutputDirectory $wrongHashOutput | Out-Null } catch { $failed = $true }
    Check $failed 'Wrong frozen APK hash was accepted.'
    $report = Get-Content -LiteralPath (Join-Path $wrongHashOutput 'native-verification.json') -Raw | ConvertFrom-Json
    Check (!$report.passed -and $report.failedStage -eq 'verify-input' -and $report.stages.Count -eq 0) 'Wrong-hash failure was not reported before external tools.'
    Check ($report.targetApkSha256 -eq $actual) 'Failure report lost the actual input APK identity.'
    Write-Output 'PASS: wrong frozen hash fails before all external tools, with a failure report.'

    $preservedPath = Join-Path $wrongHashOutput 'native-verification.json'
    $before = (Get-FileHash -LiteralPath $preservedPath -Algorithm SHA256).Hash
    $failed = $false
    try { & $helper @arguments -ExpectedApkSha256 $actual -OutputDirectory $wrongHashOutput | Out-Null } catch { $failed = $true }
    Check ($failed -and (Get-FileHash -LiteralPath $preservedPath -Algorithm SHA256).Hash -eq $before) 'Existing QA report was overwritten.'
    Write-Output 'PASS: previous QA evidence is preserved.'

    $wrongPinOutput = Join-Path $testRoot 'wrong-pin'
    $failed = $false
    try { & $helper @arguments -ExpectedApkSha256 $actual -ExpectedSignerSha256 ('a'*64) -OutputDirectory $wrongPinOutput | Out-Null } catch { $failed = $true }
    Check $failed 'A release signer override bypassed the canonical repository pin.'
    $pinReport = Get-Content -LiteralPath (Join-Path $wrongPinOutput 'native-verification.json') -Raw | ConvertFrom-Json
    Check (!$pinReport.passed -and $pinReport.failedStage -eq 'verify-canonical-pin' -and $pinReport.stages.Count -eq 0) 'Canonical pin override was not rejected before external tools.'
    Write-Output 'PASS: release certificate overrides cannot bypass the canonical pin, before external tools.'

    $sharedPortArguments = $arguments.Clone()
    $sharedPortArguments.AdbPort = 5037
    $sharedOutput = Join-Path $testRoot 'shared-adb'
    $failed = $false
    try { & $helper @sharedPortArguments -ExpectedApkSha256 $actual -OutputDirectory $sharedOutput | Out-Null } catch { $failed = $true }
    Check ($failed -and !(Test-Path -LiteralPath $sharedOutput)) 'Shared default ADB port was accepted.'
    Write-Output 'PASS: the shared ADB port is rejected without side effects.'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (!$resolved.StartsWith($tempBase,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'rg2-smoke-tests-*') {
        throw 'Refusing cleanup outside this test temporary directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
