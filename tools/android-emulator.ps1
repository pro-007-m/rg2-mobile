param([string]$SdkDirectory = '', [string]$CacheDirectory = '', [int]$Port = 5584, [int]$AdbServerPort = 15048)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SdkDirectory) { $SdkDirectory = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$CacheDirectory) { $CacheDirectory = Join-Path (Split-Path $repoRoot -Parent) 'rg2-mobile-build-cache' }
$cacheRoot = [IO.Path]::GetFullPath($CacheDirectory)
$avdHome = Join-Path $cacheRoot 'qa-avd'
$name = 'RG2_Mobile_QA_API36'
$avdRoot = Join-Path $avdHome ($name + '.avd')
$image = Join-Path $SdkDirectory 'system-images/android-36/google_apis_playstore/x86_64'
if (!(Test-Path -LiteralPath (Join-Path $image 'system.img'))) { throw 'The existing API36 x86_64 image is required.' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port,($Port+1) -ErrorAction SilentlyContinue) {
    throw 'The requested emulator console/transport port is already in use.'
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
target=android-36
"@ | Set-Content -LiteralPath (Join-Path $avdHome ($name+'.ini')) -Encoding ascii
@"
AvdId=$name
avd.ini.displayname=RG2 Mobile QA API36
avd.ini.encoding=UTF-8
PlayStore.enabled=true
abi.type=x86_64
disk.dataPartition.size=2048M
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.battery=yes
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.gps=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=no
image.sysdir.1=$($image.Replace('\','/'))/
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
tag.id=google_apis_playstore
tag.display=Google Play
target=android-36
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
$stdout = Join-Path $cacheRoot 'emulator-qa.stdout.log'
$stderr = Join-Path $cacheRoot 'emulator-qa.stderr.log'
$emulatorArguments = @('-avd', $name, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
    '-no-metrics', '-camera-back', 'none', '-camera-front', 'none', '-gpu', 'swiftshader_indirect',
    '-port', [string]$Port, '-memory', '3072', '-cores', '4')
$process = Start-Process -FilePath (Join-Path $SdkDirectory 'emulator/emulator.exe') -ArgumentList $emulatorArguments `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$metadata = [pscustomobject]@{pid=$process.Id;serial=('emulator-'+$Port);adbServerPort=$AdbServerPort;avd=$avdRoot;stdout=$stdout;stderr=$stderr}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheRoot 'emulator-qa.json') -Encoding utf8
$metadata | ConvertTo-Json
