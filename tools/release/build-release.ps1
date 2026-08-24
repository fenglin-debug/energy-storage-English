param(
    [string]$KeystorePath = $env:BESS_KEYSTORE,
    [string]$KeyAlias = $(if ($env:BESS_KEY_ALIAS) { $env:BESS_KEY_ALIAS } else { 'bess' })
)

$ErrorActionPreference = 'Stop'
$expectedCertificateSha256 = '1d6127a5de27b775349fef0b3786cb2eb3320a53e6443bd3cca0a7f781f2415b'
$expectedPackageName = 'com.bess.salestrainer'
$originalEnvironment = @{}
$signingVariables = @('BESS_KEYSTORE', 'BESS_STORE_PASSWORD', 'BESS_KEY_ALIAS', 'BESS_KEY_PASSWORD')
$signingVariables | ForEach-Object { $originalEnvironment[$_] = [Environment]::GetEnvironmentVariable($_, 'Process') }

function ConvertFrom-SecureValue([Security.SecureString]$SecureValue) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Resolve-AndroidSdk([string]$AndroidRoot) {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) { return $candidate }
    }
    $properties = Join-Path $AndroidRoot 'local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $candidate = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path -LiteralPath $candidate -PathType Container) { return $candidate }
        }
    }
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or configure local.properties.'
}

try {
    if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
        throw 'Release keystore path is empty. Set BESS_KEYSTORE or pass -KeystorePath.'
    }
    if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
        throw "Release keystore not found: $KeystorePath"
    }
    if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'Release key alias is empty.' }

    $storePassword = $env:BESS_STORE_PASSWORD
    if ([string]::IsNullOrWhiteSpace($storePassword)) {
        $storePassword = ConvertFrom-SecureValue (Read-Host 'Enter keystore password' -AsSecureString)
    }
    $keyPassword = $env:BESS_KEY_PASSWORD
    if ([string]::IsNullOrWhiteSpace($keyPassword)) {
        $keyPassword = ConvertFrom-SecureValue (Read-Host 'Enter key password' -AsSecureString)
    }
    if ([string]::IsNullOrWhiteSpace($storePassword) -or [string]::IsNullOrWhiteSpace($keyPassword)) {
        throw 'Release signing passwords cannot be empty.'
    }

    $env:BESS_KEYSTORE = (Resolve-Path -LiteralPath $KeystorePath).Path
    $env:BESS_KEY_ALIAS = $KeyAlias
    $env:BESS_STORE_PASSWORD = $storePassword
    $env:BESS_KEY_PASSWORD = $keyPassword

    $androidRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $configFile = Join-Path $androidRoot 'buildSrc\src\main\kotlin\BessModuleConfig.kt'
    $config = Get-Content -LiteralPath $configFile -Raw
    $versionName = [regex]::Match($config, 'VERSION_NAME\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($config, 'VERSION_CODE\s*=\s*(\d+)').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($versionName) -or [string]::IsNullOrWhiteSpace($versionCode)) {
        throw 'Unable to read version constants.'
    }

    $sdkRoot = Resolve-AndroidSdk $androidRoot
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android SDK build-tools not found.' }
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    $aapt = Join-Path $buildTools.FullName 'aapt.exe'
    if (-not (Test-Path -LiteralPath $apksigner) -or -not (Test-Path -LiteralPath $aapt)) {
        throw 'apksigner or aapt is missing from Android SDK build-tools.'
    }

    Push-Location $androidRoot
    try {
        & .\gradlew.bat --no-daemon --max-workers=1 :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally {
        Pop-Location
    }

    $sourceApk = Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) { throw 'Signed release APK was not produced.' }

    $signatureReport = (& $apksigner verify --verbose --print-certs $sourceApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed.`n$signatureReport" }
    $certificateMatch = [regex]::Match(
        $signatureReport,
        'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if (-not $certificateMatch.Success) { throw 'Unable to read APK certificate SHA-256.' }
    $certificateSha256 = ($certificateMatch.Groups[1].Value -replace ':', '').ToLowerInvariant()
    if ($certificateSha256 -ne $expectedCertificateSha256) {
        throw "Wrong release certificate. Expected $expectedCertificateSha256 but found $certificateSha256. No distribution package was created."
    }

    $badging = (& $aapt dump badging $sourceApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect APK metadata.' }
    $package = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'" )
    if (-not $package.Success -or $package.Groups[1].Value -ne $expectedPackageName -or
        $package.Groups[2].Value -ne $versionCode -or $package.Groups[3].Value -ne $versionName) {
        throw 'APK package name or version does not match the release configuration.'
    }
    if ($badging -match 'application-debuggable') { throw 'Release APK is debuggable.' }
    if ($badging -notmatch "native-code: 'arm64-v8a'" -or $badging -match "native-code:.*'(armeabi-v7a|x86|x86_64)'") {
        throw 'Release APK ABI set is not arm64-v8a only.'
    }

    $distributionDir = Join-Path $androidRoot "app\build\distributions\v$versionName"
    New-Item -ItemType Directory -Force -Path $distributionDir | Out-Null
    $apkName = "BESS-v$versionName.apk"
    $targetApk = Join-Path $distributionDir $apkName
    Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force
    $hash = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $distributionDir 'SHA256SUMS.txt') -Encoding utf8 -Value "$hash  $apkName"
    Copy-Item -LiteralPath (Join-Path $androidRoot 'docs\release\INSTALL.md') -Destination $distributionDir -Force
    Copy-Item -LiteralPath (Join-Path $androidRoot 'docs\release\RELEASE_NOTES.md') -Destination $distributionDir -Force
    Copy-Item -LiteralPath (Join-Path $androidRoot 'docs\release\RELEASE_LEDGER.md') -Destination $distributionDir -Force

    Write-Host "Release package: $distributionDir"
    Write-Host "Version: $versionName ($versionCode)"
    Write-Host "Certificate SHA-256: $certificateSha256"
    Write-Host "APK SHA-256: $hash"
} finally {
    $storePassword = $null
    $keyPassword = $null
    foreach ($name in $signingVariables) {
        $value = $originalEnvironment[$name]
        if ($null -eq $value) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        } else {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
