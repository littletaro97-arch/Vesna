[CmdletBinding()]
param(
    [switch]$InitializeSigning,
    [switch]$ShowSigningPassword,
    [ValidatePattern('^\d+\.\d+(?:\.\d+)?$')]
    [string]$VersionName = '1.5.4',
    [ValidateRange(1, 2147483647)]
    [int]$VersionCode = 10
)

$ErrorActionPreference = 'Stop'

if ($InitializeSigning -and $ShowSigningPassword) {
    throw 'Use -InitializeSigning or -ShowSigningPassword in one invocation, not both.'
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$signingDirectory = Join-Path $env:APPDATA 'Vesna'
$storeFile = Join-Path $signingDirectory 'vesna-release.p12'
$passwordFile = Join-Path $signingDirectory 'vesna-release-password.dpapi'
$keyAlias = 'vesna-release'

function Read-ReleasePassword {
    if (-not (Test-Path -LiteralPath $passwordFile -PathType Leaf)) {
        throw "Release signing password is missing. Run .\tools\build-release.ps1 -InitializeSigning once."
    }

    $protectedValue = Get-Content -LiteralPath $passwordFile -Raw
    $secureValue = ConvertTo-SecureString -String $protectedValue
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Initialize-ReleaseSigning {
    if ((Test-Path -LiteralPath $storeFile) -or (Test-Path -LiteralPath $passwordFile)) {
        throw "Signing material already exists under '$signingDirectory'; refusing to replace it."
    }

    $opensslCommand = Get-Command openssl -ErrorAction Stop
    New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null

    $randomBytes = New-Object byte[] 32
    $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomGenerator.GetBytes($randomBytes)
    }
    finally {
        $randomGenerator.Dispose()
    }
    $password = [BitConverter]::ToString($randomBytes).Replace('-', '')
    [Array]::Clear($randomBytes, 0, $randomBytes.Length)

    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('vesna-signing-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    $privateKeyPem = Join-Path $temporaryRoot 'release-key.pem'
    $certificatePem = Join-Path $temporaryRoot 'release-cert.pem'
    $temporaryStore = Join-Path $temporaryRoot 'vesna-release.p12'
    $env:VESNA_RELEASE_PASSWORD = $password

    try {
        & $opensslCommand.Source req -x509 -newkey rsa:3072 -sha256 -days 9131 -batch `
            -keyout $privateKeyPem -out $certificatePem `
            -subj '/CN=Vesna Android Release/' -passout 'env:VESNA_RELEASE_PASSWORD'
        if ($LASTEXITCODE -ne 0) {
            throw 'OpenSSL could not create the release signing certificate.'
        }

        & $opensslCommand.Source pkcs12 -export -out $temporaryStore `
            -inkey $privateKeyPem -in $certificatePem -name $keyAlias `
            -passin 'env:VESNA_RELEASE_PASSWORD' -passout 'env:VESNA_RELEASE_PASSWORD'
        if ($LASTEXITCODE -ne 0) {
            throw 'OpenSSL could not package the release signing key.'
        }

        $securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
        $protectedPassword = ConvertFrom-SecureString -SecureString $securePassword
        [IO.File]::WriteAllText($passwordFile, $protectedPassword, [Text.Encoding]::UTF8)
        Move-Item -LiteralPath $temporaryStore -Destination $storeFile | Out-Null
    }
    catch {
        Remove-Item -LiteralPath $passwordFile -Force -ErrorAction SilentlyContinue
        throw
    }
    finally {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
        $env:VESNA_RELEASE_PASSWORD = $null
    }

    return $password
}

if ($InitializeSigning) {
    $releasePassword = Initialize-ReleaseSigning
}
elseif ($ShowSigningPassword) {
    Write-Output (Read-ReleasePassword)
    return
}
else {
    if (-not (Test-Path -LiteralPath $storeFile -PathType Leaf)) {
        throw "Release keystore is missing at '$storeFile'. Run .\tools\build-release.ps1 -InitializeSigning once."
    }
    $releasePassword = Read-ReleasePassword
}

$oldEnvironment = @{
    StoreFile = $env:VESNA_RELEASE_STORE_FILE
    StorePassword = $env:VESNA_RELEASE_STORE_PASSWORD
    KeyAlias = $env:VESNA_RELEASE_KEY_ALIAS
    KeyPassword = $env:VESNA_RELEASE_KEY_PASSWORD
    Temp = $env:TEMP
    Tmp = $env:TMP
}
$mappedDrive = $null
$pushedLocation = $false

try {
    $env:VESNA_RELEASE_STORE_FILE = $storeFile
    $env:VESNA_RELEASE_STORE_PASSWORD = $releasePassword
    $env:VESNA_RELEASE_KEY_ALIAS = $keyAlias
    $env:VESNA_RELEASE_KEY_PASSWORD = $releasePassword

    New-Item -ItemType Directory -Force -Path 'C:\jtmp' | Out-Null
    $env:TEMP = 'C:\jtmp'
    $env:TMP = 'C:\jtmp'

    foreach ($candidate in @('V', 'U', 'T', 'S', 'R', 'Q')) {
        if (-not (Get-PSDrive -Name $candidate -ErrorAction SilentlyContinue) -and
            -not (Test-Path -LiteralPath ([string]::Concat($candidate, ':\')))) {
            $mappedDrive = [string]::Concat($candidate, ':')
            break
        }
    }
    if (-not $mappedDrive) {
        throw 'No free temporary drive letter is available for the Android build.'
    }

    & subst.exe $mappedDrive $projectRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Could not map the project to temporary drive $mappedDrive."
    }

    $mappedRoot = [string]::Concat($mappedDrive, '\')
    Push-Location -LiteralPath $mappedRoot
    $pushedLocation = $true
    $gradle = Join-Path $mappedRoot 'gradlew.bat'
    $gradleArguments = @(
        '--no-daemon',
        '-Pkotlin.incremental=false',
        "-PversionCode=$VersionCode",
        "-PversionName=$VersionName",
        'lintDebug',
        'testDebugUnitTest',
        'assembleRelease'
    )
    & $gradle @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Gradle release build or required checks failed.'
    }

    $releaseApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $releaseApk -PathType Leaf)) {
        throw "Gradle completed without producing '$releaseApk'."
    }

    $sdkDirectory = $env:ANDROID_SDK_ROOT
    if (-not $sdkDirectory) { $sdkDirectory = $env:ANDROID_HOME }
    if (-not $sdkDirectory) {
        $localProperties = Join-Path $projectRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
            $sdkLine = Get-Content -LiteralPath $localProperties |
                Where-Object { $_ -match '^sdk\.dir=' } |
                Select-Object -First 1
            if ($sdkLine) {
                $sdkDirectory = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':')
            }
        }
    }
    if (-not $sdkDirectory) {
        throw 'Android SDK path is missing; set ANDROID_SDK_ROOT or ANDROID_HOME.'
    }

    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkDirectory 'build-tools') -Directory |
        Sort-Object Name -Descending
    $apksigner = $null
    foreach ($directory in $buildTools) {
        $candidate = Join-Path $directory.FullName 'apksigner.bat'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $apksigner = $candidate
            break
        }
    }
    if (-not $apksigner) {
        throw 'Android SDK build-tools does not contain apksigner.bat.'
    }

    $outputDirectory = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $outputApk = Join-Path $outputDirectory "vesna-v$VersionName-release.apk"
    Copy-Item -LiteralPath $releaseApk -Destination $outputApk -Force

    & $apksigner verify --verbose --print-certs $outputApk
    if ($LASTEXITCODE -ne 0) {
        throw 'apksigner verification failed for the release APK.'
    }

    $hash = Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
    $size = (Get-Item -LiteralPath $outputApk).Length
    Write-Output "Release APK: $outputApk"
    Write-Output "Version: $VersionName (versionCode $VersionCode)"
    Write-Output "Size: $size bytes"
    Write-Output "SHA-256: $($hash.Hash)"
}
finally {
    if ($pushedLocation) {
        Pop-Location
    }
    if ($mappedDrive) {
        & subst.exe $mappedDrive /D | Out-Null
    }
    $env:VESNA_RELEASE_STORE_FILE = $oldEnvironment.StoreFile
    $env:VESNA_RELEASE_STORE_PASSWORD = $oldEnvironment.StorePassword
    $env:VESNA_RELEASE_KEY_ALIAS = $oldEnvironment.KeyAlias
    $env:VESNA_RELEASE_KEY_PASSWORD = $oldEnvironment.KeyPassword
    $env:TEMP = $oldEnvironment.Temp
    $env:TMP = $oldEnvironment.Tmp
    $releasePassword = $null
}
