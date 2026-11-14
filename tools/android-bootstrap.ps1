param([string]$CacheDirectory = '', [switch]$Offline)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$CacheDirectory) { $CacheDirectory = Join-Path (Split-Path $repoRoot -Parent) 'rg2-mobile-build-cache' }
$cacheRoot = [IO.Path]::GetFullPath($CacheDirectory)
$androidRoot = Join-Path $repoRoot 'android'
$gradleVersion = '8.13'
$zipHash = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$wrapperHash = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
New-Item -Path $cacheRoot -ItemType Directory -Force | Out-Null
New-Item -Path (Join-Path $androidRoot 'gradle/wrapper') -ItemType Directory -Force | Out-Null
$wrapperJar = Join-Path $androidRoot 'gradle/wrapper/gradle-wrapper.jar'
if (!(Test-Path -LiteralPath $wrapperJar)) {
    if ($Offline) { throw 'The verified Gradle wrapper is not cached; offline bootstrap cannot download it.' }
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $wrapperJar
}
if ((Get-FileHash -LiteralPath $wrapperJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $wrapperHash) {
    throw 'Gradle wrapper SHA256 verification failed.'
}
foreach ($launcher in @('gradlew', 'gradlew.bat')) {
    $launcherPath = Join-Path $androidRoot $launcher
    if (!(Test-Path -LiteralPath $launcherPath)) {
        if ($Offline) { throw 'The Gradle launcher is not cached; offline bootstrap cannot download it.' }
        Invoke-WebRequest -Uri ('https://raw.githubusercontent.com/gradle/gradle/v8.13.0/' + $launcher) -OutFile $launcherPath
    }
}
$zipPath = Join-Path $cacheRoot ('gradle-' + $gradleVersion + '-bin.zip')
if (!(Test-Path -LiteralPath $zipPath)) {
    if ($Offline) { throw 'The verified Gradle distribution is not cached; offline bootstrap cannot download it.' }
    $partialPath = $zipPath + '.partial'
    Invoke-WebRequest -Uri ('https://downloads.gradle.org/distributions/gradle-' + $gradleVersion + '-bin.zip') -OutFile $partialPath
    if ((Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zipHash) {
        throw 'Gradle distribution SHA256 verification failed.'
    }
    Move-Item -LiteralPath $partialPath -Destination $zipPath
}
if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zipHash) {
    throw 'Cached Gradle distribution SHA256 verification failed.'
}
$gradleRoot = Join-Path $cacheRoot ('gradle-' + $gradleVersion)
if (!(Test-Path -LiteralPath (Join-Path $gradleRoot 'bin/gradle.bat'))) {
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($entry in $zip.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $cacheRoot $entry.FullName))
            if (!$target.StartsWith($cacheRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Unsafe path in Gradle archive.'
            }
        }
    } finally { $zip.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $cacheRoot, $true)
}
Write-Output $gradleRoot
