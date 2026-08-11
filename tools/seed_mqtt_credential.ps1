# seed_mqtt_credential.ps1 — seed credential HiveMQ shared vào Supabase app_secrets
# Chạy 1 lần, sau khi: (1) tạo credential `device-family` trong HiveMQ console
# (permission devices/+/#, mật khẩu <=32 ký tự), (2) chạy migration 0003 trong
# Supabase SQL Editor.
#
# Đọc .env (repo root) trước — điền .env:
#   SUPABASE_URL, SUPABASE_SERVICE_KEY (service_role), HIVE_MQTT_USER/PASS
# Hoặc set env var (env var thắng .env):
#   $env:SUPABASE_URL="https://<project>.supabase.co"
#   $env:SUPABASE_SERVICE_KEY="<service_role key>"
#   $env:MQTT_USER="device-family"
#   $env:MQTT_PASS="<mật khẩu HiveMQ>"
#   powershell -ExecutionPolicy Bypass -File tools/seed_mqtt_credential.ps1

$ErrorActionPreference = "Stop"

# ── Load .env (repo root) — không đè env var đã set ──
function Load-DotEnv {
    $dotEnv = Join-Path $PSScriptRoot "..\.env"
    if (-not (Test-Path -LiteralPath $dotEnv)) { return }
    foreach ($line in Get-Content -LiteralPath $dotEnv) {
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
Load-DotEnv

# Sửa: HIVE_MQTT_USER/PASS trong .env chính là credential shared (device-family)
if ([string]::IsNullOrWhiteSpace((Get-Item Env:MQTT_USER -ErrorAction SilentlyContinue).Value)) {
    $hiveUser = (Get-Item Env:HIVE_MQTT_USER -ErrorAction SilentlyContinue).Value
    if (-not [string]::IsNullOrWhiteSpace($hiveUser)) { Set-Item Env:MQTT_USER $hiveUser }
}
if ([string]::IsNullOrWhiteSpace((Get-Item Env:MQTT_PASS -ErrorAction SilentlyContinue).Value)) {
    $hivePass = (Get-Item Env:HIVE_MQTT_PASS -ErrorAction SilentlyContinue).Value
    if (-not [string]::IsNullOrWhiteSpace($hivePass)) { Set-Item Env:MQTT_PASS $hivePass }
}

$url    = $env:SUPABASE_URL
$svcKey = $env:SUPABASE_SERVICE_KEY
$user   = $env:MQTT_USER
$pass   = $env:MQTT_PASS

foreach ($name in @('SUPABASE_URL', 'SUPABASE_SERVICE_KEY', 'MQTT_USER', 'MQTT_PASS')) {
    $val = (Get-Item "Env:$name" -ErrorAction SilentlyContinue).Value
    if ([string]::IsNullOrWhiteSpace($val)) {
        $hint = @{
            SUPABASE_URL          = "diền vào .env hoặc set env";
            SUPABASE_SERVICE_KEY  = "diền SUPABASE_SERVICE_KEY vào .env (Project Settings -> API -> service_role)";
            MQTT_USER             = "diền HIVE_MQTT_USER vào .env";
            MQTT_PASS             = "diền HIVE_MQTT_PASS vào .env";
        }[$name]
        throw "Thiếu $name - $hint"
    }
}

if ($user.Length -gt 32 -or $pass.Length -gt 32) {
    throw "MQTT_USER/MQTT_PASS qua 32 ky tu - firmware buffer mqttUser[32]/mqttPass[32], HiveMQ Cloud cung gioi han 32"
}

$headers = @{
    "Authorization" = "Bearer $svcKey"
    apikey          = $svcKey
    Prefer          = "resolution=merge-duplicates,return=minimal"
}

$rows = @(
    @{ key = "mqtt_user"; value = $user; description = "HiveMQ shared credential - username (device-family)" },
    @{ key = "mqtt_pass"; value = $pass; description = "HiveMQ shared credential - password (<=32 ky tu)" }
) | ConvertTo-Json -Depth 4

$uri = "$url/rest/v1/app_secrets?on_conflict=key"
try {
    Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $rows -ContentType "application/json" | Out-Null
} catch {
    throw "Upsert that bai: $($_.Exception.Message)"
}

Write-Host "OK - da upsert mqtt_user/mqtt_pass vao app_secrets ($user)"
Write-Host "Kiem tra: SQL Editor -> select * from public.app_secrets;"
