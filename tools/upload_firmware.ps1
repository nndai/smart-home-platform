# ============================================================
# upload_firmware.ps1 — Upload Firmware Build lên Supabase Storage & Database
# ============================================================
# Tự động build (PlatformIO), tính SHA256/MD5, upload binary .bin lên Supabase Storage
# (bucket: firmwares) và lưu metadata vào PostgreSQL (bảng: firmware_releases).
#
# Cách dùng:
#   .\tools\upload_firmware.ps1 -Env remote_switch_esp8266 -Build
#   .\tools\upload_firmware.ps1 -Env all -Build
#   .\tools\upload_firmware.ps1 -List
#   .\tools\upload_firmware.ps1 -DryRun
# ============================================================

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Env,

    [switch]$Build,
    [string]$Version,
    [string]$Changelog = "",
    [switch]$DryRun,
    [switch]$List
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$firmwareDir = Join-Path $repoRoot "firmware"
$dotEnv = Join-Path $repoRoot ".env"

# ── 1) Đọc .env — không đè env var đã set ──
function Load-DotEnv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $line = $line.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { continue }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { continue }
        $name = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim().Trim('"', "'")
        if ([string]::IsNullOrWhiteSpace((Get-Item "Env:$name" -ErrorAction SilentlyContinue).Value)) {
            Set-Item "Env:$name" $value
        }
    }
}
Load-DotEnv -Path $dotEnv

$supabaseUrl = $env:SUPABASE_URL
$svcKey      = $env:SUPABASE_SERVICE_KEY

# ── 2) Đọc platformio.ini ──
function Get-PioEnvs {
    param([string]$IniPath)
    if (-not (Test-Path -LiteralPath $IniPath)) {
        throw "Khong tim thay platformio.ini tai: $IniPath"
    }

    $envs = @{}
    $currentEnv = $null

    foreach ($rawLine in Get-Content -LiteralPath $IniPath) {
        $line = $rawLine.Trim()
        if ($line.StartsWith("[env:") -and $line.EndsWith("]")) {
            $envName = $line.Substring(5, $line.Length - 6)
            $currentEnv = @{
                Name     = $envName
                Profile  = "unknown"
                Chip     = "unknown"
                Platform = ""
                BinPath  = (Join-Path $firmwareDir ".pio\build\$envName\firmware.bin")
            }
            $envs[$envName] = $currentEnv
            continue
        }

        if ($currentEnv -ne $null) {
            if ($line.StartsWith("platform")) {
                $p = $line.Split("=")[1].Trim()
                $currentEnv.Platform = $p
            }
            if ($line.Contains("PROFILE_PUMP")) { $currentEnv.Profile = "pump" }
            elseif ($line.Contains("PROFILE_REMOTE_SWITCH")) { $currentEnv.Profile = "remote_switch" }
            elseif ($line.Contains("PROFILE_SWITCH")) { $currentEnv.Profile = "switch" }

            if ($currentEnv.Platform -like "*libretiny*" -or $currentEnv.Name -like "*ln882h*") { $currentEnv.Chip = "ln882h" }
            elseif ($currentEnv.Platform -like "*espressif8266*" -or $currentEnv.Name -like "*esp8266*") { $currentEnv.Chip = "esp8266" }
            elseif ($currentEnv.Platform -like "*espressif32*" -or $currentEnv.Name -like "*esp32*") { $currentEnv.Chip = "esp32" }
        }
    }

    foreach ($k in $envs.Keys) {
        $e = $envs[$k]
        if ($e.Profile -eq "unknown") {
            if ($k -like "*remote_switch*") { $e.Profile = "remote_switch" }
            elseif ($k -like "*switch*") { $e.Profile = "switch" }
            elseif ($k -like "*pump*") { $e.Profile = "pump" }
        }
    }
    return $envs
}

$pioIni = Join-Path $firmwareDir "platformio.ini"
$availableEnvs = Get-PioEnvs -IniPath $pioIni

if ($List) {
    Write-Host "`nDanh sach PlatformIO Environments:" -ForegroundColor Cyan
    foreach ($k in $availableEnvs.Keys) {
        $item = $availableEnvs[$k]
        Write-Host ("  - {0,-26} [Profile: {1,-14} Chip: {2,-8} Platform: {3}]" -f $k, $item.Profile, $item.Chip, $item.Platform)
    }
    exit 0
}

# ── Helper tính Hash & Unix Time ──
function Get-Md5Hash {
    param([string]$FilePath)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $stream = [System.IO.File]::OpenRead($FilePath)
    try {
        $hashBytes = $md5.ComputeHash($stream)
        return [System.BitConverter]::ToString($hashBytes).Replace("-", "").ToLowerInvariant()
    } finally {
        $stream.Close()
        $md5.Dispose()
    }
}

function Get-BuildTimeHeader {
    param([string]$HeaderPath)
    $unixTime = [int][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $buildStr = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    if (Test-Path -LiteralPath $HeaderPath) {
        $content = Get-Content -LiteralPath $HeaderPath -Raw
        if ($content -match '#define\s+BUILD_UNIX_TIME\s+(\d+)u?') {
            $unixTime = [long]$matches[1]
        }
        if ($content -match '#define\s+BUILD_STR\s+"([^"]+)"') {
            $buildStr = $matches[1]
        }
    }
    return @{ UnixTime = $unixTime; BuildStr = $buildStr }
}

# ── 3) Xử lý từng môi trường ──
function Process-Env {
    param(
        [hashtable]$EnvInfo,
        [switch]$DoBuild,
        [string]$CustomVersion,
        [string]$ReleaseNotes,
        [switch]$IsDryRun
    )

    $name    = $EnvInfo.Name
    $profile = $EnvInfo.Profile
    $chip    = $EnvInfo.Chip
    $binPath = $EnvInfo.BinPath

    Write-Host "`n============================================================" -ForegroundColor DarkCyan
    Write-Host ">> XU LY: $name (Profile: $profile, Chip: $chip)" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor DarkCyan

    if ($DoBuild -or (-not (Test-Path -LiteralPath $binPath))) {
        Write-Host "[PIO] Dang build firmware cho env '$name'..." -ForegroundColor Yellow
        Push-Location $firmwareDir
        try {
            pio run -e $name
            if ($LASTEXITCODE -ne 0) {
                throw "PlatformIO build that bai voi exit code $LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
    }

    if (-not (Test-Path -LiteralPath $binPath)) {
        throw "Khong tim thay file binary tai: $binPath"
    }

    $fileItem = Get-Item -LiteralPath $binPath
    $fileSize = $fileItem.Length

    $headerPath = Join-Path $firmwareDir "include\build_time.h"
    $bt = Get-BuildTimeHeader -HeaderPath $headerPath
    $unixTs = $bt.UnixTime
    $buildStr = $bt.BuildStr

    $sha256 = (Get-FileHash -Path $binPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $md5 = Get-Md5Hash -FilePath $binPath

    $ver = $CustomVersion
    if ([string]::IsNullOrWhiteSpace($ver)) {
        $dateStr = ([DateTimeOffset]::FromUnixTimeSeconds($unixTs)).LocalDateTime.ToString("yyyy.MM.dd")
        $ver = "v$dateStr.$($unixTs % 100000)"
    }

    $fileName = "${name}_${unixTs}.bin"
    $storagePath = "releases/$profile/$chip/$fileName"
    $publicUrl = "$($supabaseUrl.TrimEnd('/'))/storage/v1/object/public/firmwares/$storagePath"

    $notes = $ReleaseNotes
    if ([string]::IsNullOrWhiteSpace($notes)) {
        $notes = "Build $buildStr"
    }

    Write-Host ("  * File:       {0} ({1:N0} bytes / {2:N1} KB)" -f $fileItem.Name, $fileSize, ($fileSize / 1024.0))
    Write-Host "  * Version:    $ver"
    Write-Host "  * Build time: $buildStr (UNIX: $unixTs)"
    Write-Host "  * SHA256:     $sha256"
    Write-Host "  * MD5:        $md5"
    Write-Host "  * Storage:    firmwares/$storagePath"
    Write-Host "  * Public URL: $publicUrl"

    if ($IsDryRun) {
        Write-Host "`n[DRY RUN] Bo qua upload len Supabase." -ForegroundColor Green
        return
    }

    if ([string]::IsNullOrWhiteSpace($supabaseUrl) -or [string]::IsNullOrWhiteSpace($svcKey)) {
        throw "Thieu SUPABASE_URL hoac SUPABASE_SERVICE_KEY trong .env!"
    }

    # Upload Storage
    Write-Host "`n[1/2] Dang upload binary len Supabase Storage (bucket: firmwares)..." -ForegroundColor Yellow
    $uploadUri = "$($supabaseUrl.TrimEnd('/'))/storage/v1/object/firmwares/$([Uri]::EscapeDataString($storagePath))"
    $headers = @{
        "Authorization" = "Bearer $svcKey"
        "apikey"        = $svcKey
        "x-upsert"      = "true"
    }

    try {
        Invoke-RestMethod -Method Post -Uri $uploadUri -Headers $headers -InFile $binPath -ContentType "application/octet-stream" | Out-Null
        Write-Host "      [OK] Upload Storage thanh cong!" -ForegroundColor Green
    } catch {
        throw "Upload Storage that bai: $($_.Exception.Message)"
    }

    # Insert DB
    Write-Host "[2/2] Dang luu ban release vao PostgreSQL (public.firmware_releases)..." -ForegroundColor Yellow
    $dbUri = "$($supabaseUrl.TrimEnd('/'))/rest/v1/firmware_releases"
    $dbHeaders = @{
        "Authorization" = "Bearer $svcKey"
        "apikey"        = $svcKey
        "Prefer"        = "return=representation"
    }
    $body = @{
        profile         = $profile
        env             = $name
        target_chip     = $chip
        version         = $ver
        build_timestamp = $unixTs
        file_name       = $fileName
        storage_path    = $storagePath
        file_url        = $publicUrl
        file_size       = $fileSize
        checksum_sha256 = $sha256
        checksum_md5    = $md5
        is_latest       = $true
        changelog       = $notes
    } | ConvertTo-Json -Depth 4

    try {
        $resp = Invoke-RestMethod -Method Post -Uri $dbUri -Headers $dbHeaders -Body $body -ContentType "application/json"
        $relId = if ($resp -is [array]) { $resp[0].id } else { $resp.id }
        Write-Host "      [OK] Da tao ban ghi release ID: $relId" -ForegroundColor Green
    } catch {
        throw "Insert DB that bai: $($_.Exception.Message)"
    }

    Write-Host "`n------------------------------------------------------------" -ForegroundColor DarkGreen
    Write-Host "DA HOAN TAT UPLOAD FIRMWARE!" -ForegroundColor Green
    Write-Host "Download URL: $publicUrl" -ForegroundColor Cyan
    Write-Host "`nLenh mau otaUrl (MQTT):"
    $otaPayload = @{
        cmd = "otaUrl"
        payload = @{
            url = $publicUrl
        }
    } | ConvertTo-Json -Depth 3
    Write-Host $otaPayload -ForegroundColor Magenta
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkGreen
}

# ── 4) Lựa chọn Env & Thực thi ──
if ([string]::IsNullOrWhiteSpace($Env)) {
    $keys = @($availableEnvs.Keys)
    Write-Host "`nChon PlatformIO Environment de upload:" -ForegroundColor Cyan
    for ($i = 0; $i -lt $keys.Count; $i++) {
        $k = $keys[$i]
        Write-Host ("  [{0}] {1,-26} ({2} / {3})" -f ($i + 1), $k, $availableEnvs[$k].Profile, $availableEnvs[$k].Chip)
    }
    Write-Host ("  [{0}] ALL (Upload tat ca)" -f ($keys.Count + 1))
    Write-Host "  [0] Thoat"

    $choice = Read-Host "`nNhap so lua chon"
    if ([string]::IsNullOrWhiteSpace($choice) -or $choice -eq "0") {
        Write-Host "Da huy."
        exit 0
    }
    $cInt = [int]$choice
    if ($cInt -eq ($keys.Count + 1)) {
        $Env = "all"
    } elseif ($cInt -ge 1 -and $cInt -le $keys.Count) {
        $Env = $keys[$cInt - 1]
    } else {
        throw "Lua chon khong hop le!"
    }
}

if ($Env -eq "all") {
    foreach ($k in $availableEnvs.Keys) {
        Process-Env -EnvInfo $availableEnvs[$k] -DoBuild:$Build -CustomVersion $Version -ReleaseNotes $Changelog -IsDryRun:$DryRun
    }
} else {
    if (-not $availableEnvs.ContainsKey($Env)) {
        throw "Environment '$Env' khong ton tai trong platformio.ini. Cac env: $($availableEnvs.Keys -join ', ')"
    }
    Process-Env -EnvInfo $availableEnvs[$Env] -DoBuild:$Build -CustomVersion $Version -ReleaseNotes $Changelog -IsDryRun:$DryRun
}
