param(
    [string]$SdkDirectory = '',
    [string]$CacheDirectory = '',
    [ValidateRange(5554,5680)][int]$Port = 5584,
    [ValidateRange(1,65535)][int]$AdbServerPort = 15048,
    [ValidateSet(35,36)][int]$ApiLevel = 36,
    [ValidateRange(1536,8192)][int]$Memory = 3072,
    [ValidateRange(2,8)][int]$Cores = 4,
    [string]$ImageDirectory = ''
)
$ErrorActionPreference = 'Stop'
if ($AdbServerPort -eq 5037) { throw 'Use a dedicated QA ADB port, not the shared default port 5037.' }
if ($Port % 2) { throw 'The Android emulator console port must be even.' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$CacheDirectory) { $CacheDirectory = Join-Path (Split-Path $repoRoot -Parent) 'rg2-mobile-build-cache' }
$cacheRoot = [IO.Path]::GetFullPath($CacheDirectory)
$avdHome = Join-Path $cacheRoot 'qa-avd'
$name = if ($ApiLevel -eq 36 -and $Memory -eq 3072 -and $Cores -eq 4) { 'RG2_Mobile_QA_API36' } else { 'RG2_Mobile_QA_API' + $ApiLevel + '_M' + $Memory + '_C' + $Cores }
$avdRoot = Join-Path $avdHome ($name + '.avd')
$image = if ($ImageDirectory) { [IO.Path]::GetFullPath($ImageDirectory) } else { Join-Path $SdkDirectory ('system-images/android-' + $ApiLevel + '/google_apis_playstore/x86_64') }
if (!(Test-Path -LiteralPath (Join-Path $image 'system.img')) -or !(Test-Path -LiteralPath (Join-Path $image 'source.properties'))) { throw ('An installed API' + $ApiLevel + ' x86_64 image is required.') }
$properties = Get-Content -LiteralPath (Join-Path $image 'source.properties') -Raw
if ($properties -notmatch ('(?m)^AndroidVersion.ApiLevel\s*=\s*' + $ApiLevel + '\s*$') -or $properties -notmatch '(?m)^SystemImage.Abi\s*=\s*x86_64\s*$' -or $properties -notmatch '(?m)^SystemImage.TagId\s*=\s*google_apis_playstore\s*$') {
    throw 'The image API level/ABI does not match the requested QA emulator.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port,($Port+1) -ErrorAction SilentlyContinue) {
    throw 'The requested emulator console/transport port is already in use.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $AdbServerPort -ErrorAction SilentlyContinue) {
    throw 'The requested QA ADB port is already in use; refusing to attach to an unverified server.'
}
if ((Test-Path -LiteralPath $avdRoot) -and !(Test-Path -LiteralPath (Join-Path $avdRoot '.rg2-qa-owned'))) {
    throw 'Refusing to use an AVD not created by this RG2 QA helper.'
}
New-Item -Path $avdRoot -ItemType Directory -Force | Out-Null
New-Item -Path (Join-Path $cacheRoot 'emulator-home') -ItemType Directory -Force | Out-Null
New-Item -Path (Join-Path $cacheRoot 'android-user') -ItemType Directory -Force | Out-Null
'RG2 isolated QA AVD; no user profile is used.' | Set-Content -LiteralPath (Join-Path $avdRoot '.rg2-qa-owned') -Encoding ascii
@"
avd.ini.encoding=UTF-8
path=$avdRoot
target=android-$ApiLevel
"@ | Set-Content -LiteralPath (Join-Path $avdHome ($name+'.ini')) -Encoding ascii
@"
AvdId=$name
avd.ini.displayname=RG2 Mobile QA API$ApiLevel
avd.ini.encoding=UTF-8
PlayStore.enabled=true
abi.type=x86_64
disk.dataPartition.size=2048M
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.battery=yes
hw.cpu.arch=x86_64
hw.cpu.ncore=$Cores
hw.gps=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=$Memory
hw.sdCard=no
image.sysdir.1=$($image.Replace('\','/'))/
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
tag.id=google_apis_playstore
tag.display=Google Play
target=android-$ApiLevel
vm.heapSize=256
"@ | Set-Content -LiteralPath (Join-Path $avdRoot 'config.ini') -Encoding ascii
$env:ANDROID_AVD_HOME = $avdHome
$env:ANDROID_EMULATOR_HOME = Join-Path $cacheRoot 'emulator-home'
$env:ANDROID_USER_HOME = Join-Path $cacheRoot 'android-user'
$env:ANDROID_SDK_ROOT = $SdkDirectory
$env:ANDROID_ADB_SERVER_PORT = [string]$AdbServerPort
$env:ADB_SERVER_PORT = [string]$AdbServerPort
$env:ADB_SERVER_SOCKET = 'tcp:127.0.0.1:' + $AdbServerPort
$adb = Join-Path $SdkDirectory 'platform-tools/adb.exe'
& $adb -P $AdbServerPort start-server
if ($LASTEXITCODE -ne 0) { throw 'Cannot start the isolated ADB server.' }
$logStem = if ($ApiLevel -eq 36 -and $Port -eq 5584 -and $Memory -eq 3072 -and $Cores -eq 4) { 'emulator-qa' } else { 'emulator-qa-' + $ApiLevel + '-' + $Port }
$stdout = Join-Path $cacheRoot ($logStem + '.stdout.log')
$stderr = Join-Path $cacheRoot ($logStem + '.stderr.log')
$emulatorArguments = @('-avd', $name, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
    '-no-metrics', '-camera-back', 'none', '-camera-front', 'none', '-gpu', 'swiftshader_indirect',
    '-port', [string]$Port, '-memory', [string]$Memory, '-cores', [string]$Cores)
$process = Start-Process -FilePath (Join-Path $SdkDirectory 'emulator/emulator.exe') -ArgumentList $emulatorArguments `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$metadata = [pscustomobject]@{pid=$process.Id;serial=('emulator-'+$Port);adbServerPort=$AdbServerPort;apiLevel=$ApiLevel;memory=$Memory;cores=$Cores;image=$image;avd=$avdRoot;stdout=$stdout;stderr=$stderr}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheRoot ($logStem + '.json')) -Encoding utf8
$metadata | ConvertTo-Json
