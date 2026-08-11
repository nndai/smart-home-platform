# KẾ HOẠCH HỆ SINH THÁI IoT CÁ NHÂN — Chi phí hạ tầng $0

> Mục tiêu: hệ sinh thái thiết bị thông minh (bơm, đèn, quạt...) điều khiển qua app Android, điều khiển từ xa, xác thực thiết bị, chống clone (dump firmware), phân quyền & chia sẻ — với chi phí hạ tầng = $0.

## 1. Kiến trúc tổng thể

```mermaid
graph TB
    subgraph Cloud_Infra["Hạ tầng Cloud (miễn phí)"]
        HIVEMQ["HiveMQ Cloud Serverless<br/>100 connections / 10GB / tháng<br/>TLS 8883"]
        SUPA["Supabase Free<br/>Auth + Postgres 500MB + Storage 1GB"]
        EF["Supabase Edge Function (MQTT bridge)<br/>server-side — giữ credential app-family<br/>(không bao giờ trong APK / repo)"]
    end

    subgraph Devices["Thiết bị (LN882H / ESP32)"]
        PUMP["Bơm LN882H<br/>(profile: PUMP)"]
        LIGHT["Đèn ESP32<br/>(profile: SWITCH/DIMMER)"]
        FAN["Quạt ESP32<br/>(profile: FAN)"]
    end

    subgraph Apps["Ứng dụng"]
        APP["App Android<br/>(nhiều thiết bị)"]
    end

    PUMP -- "MQTT+TLS (credential riêng)" --> HIVEMQ
    LIGHT -- "MQTT+TLS (credential riêng)" --> HIVEMQ
    FAN -- "MQTT+TLS (credential riêng)" --> HIVEMQ
    EF -- "MQTT+WS/TLS (credential app-family)" --> HIVEMQ
    APP -- "REST+JWT (đăng nhập, danh bạ, điều khiển)" --> SUPA
    EF --> SUPA
    PUMP -- "HTTPS (OTA firmware)" --> SUPA
```

**Nguyên tắc tách lớp quan trọng:** thiết bị chỉ phụ thuộc MQTT (HiveMQ — không bao giờ pause). Supabase chỉ phục vụ app (tài khoản, danh bạ, phân quyền). Nếu Supabase free bị pause sau 7 ngày không hoạt động, thiết bị vẫn hoạt động bình thường.

**App KHÔNG kết nối MQTT trực tiếp** (project open-source → credential không bao giờ nằm trong APK/repo). Mọi lệnh/trạng thái đi qua Supabase: app gọi RPC `send_command`/`get_device_state` → **Edge Function bridge** (chạy trong Supabase, giữ credential `app-family` ở Function Secrets) → HiveMQ ↔ thiết bị.

## 2. Danh tính & xác thực thiết bị (lõi bảo mật)

### 2.1 Ba lớp danh tính

```mermaid
graph LR
    A["Lớp 1: chipAnchor<br/>LN882H: hal_flash_read_unique_id()<br/>128-bit UID die flash (OTP)<br/>ESP32: esp_efuse_mac_get_default()<br/>6 byte MAC eFuse<br/>(API 8B esp_efuse_get_chip_serial_number<br/>không tồn tại trong IDF 4.4 — xem IDENTITY §5.2)<br/>→ không nằm trong dump, không ghi được"] --> B
    B["Lớp 2: deviceId<br/>= &quot;dev-&quot; + hex(SHA-256(anchor))[:12]<br/>định danh public, in lên nhãn/QR"] --> C
    C["Lớp 3: deviceSecret<br/>32 byte ngẫu nhiên, sinh tại lần boot đầu<br/>lưu dạng AES-GCM(KDF(anchor))<br/>không bao giờ nhúng trong firmware"]
```

| Lớp | Giá trị | Độ nhạy | Nơi lưu |
|---|---|---|---|
| chipAnchor | UID phần cứng | Công khai (của chip) | OTP/eFuse — **không đọc được từ dump** |
| deviceId | Dẫn xuất từ SHA-256(anchor) — cắt 6 byte đầu (gộp đủ entropy 2 nền MCU, deterministic) | Công khai | Nhãn/QR + Supabase |
| deviceSecret | Ngẫu nhiên 32B | Bí mật | eFlash (mã hóa) + HiveMQ credential |

**Tại sao không dùng `lt_cpu_get_unique_id()`:** bản weak chỉ trả 24 bit cuối của MAC hiệu dụng — và MAC hiệu dụng bị override được (`wifi_set_macaddr`/`netdev_set_mac_addr`). Đã verify trong source LibreTiny/LN882H. Anchor chuẩn: `hal_flash_read_unique_id()` (đọc qua lệnh 0x4B — UID nằm trong OTP die flash, không phải vùng địa chỉ flash nên **dump không lấy được**, và không thể ghi đè).

### 2.2 Chống dump → nạp thiết bị khác

```mermaid
graph TD
    A["Attacker dump flash chip A"] --> B["Có: firmware + deviceSecret MÃ HÓA<br/>(AES-GCM với khóa KDF(anchor_A))"]
    B --> C["Nạp sang chip B"]
    C --> D["Firmware chạy, đọc anchor_B<br/>anchor_B ≠ anchor_A"]
    D --> E["KDF(anchor_B) ≠ KDF(anchor_A) →<br/>giải mã secret THẤT BẠI"]
    E --> F["Auth HiveMQ fail — clone vô dụng ✔"]
```

### 2.3 Ma trận tấn công & phòng thủ

| Kịch bản tấn công | Cơ chế chống | Hiệu quả |
|---|---|---|
| Dump flash → nạp board khác | AES-GCM + KDF(anchor) | ✅ Clone không auth được |
| Sửa MAC để "khớp" identity | Anchor không phải MAC; dùng UID die flash | ✅ Không sửa được |
| Recompile firmware bỏ check | LN882H: không có secure boot → dựa cloud detection; ESP32 (ESP-IDF): secure boot v2 + flash encryption (nâng cấp tùy chọn) | ⚠️ Tùy nền tảng |
| Replay lệnh | `seq` tăng dần + `ts` + HMAC controlKey, device từ chối seq cũ | ✅ |
| Chạy 2 thiết bị cùng danh tính | Client ID cố định → HiveMQ kick session cũ + app cảnh báo offline bất thường | ✅ |
| Tái activate vào tài khoản khác | Server-side revocation khi re-pair | ✅ |

## 3. Giao thức truyền thông

### 3.1 Topic namespace

```
devices/{deviceId}/cmd     ← lệnh app → thiết bị (QoS 1)
devices/{deviceId}/up      → trạng thái thiết bị → app (QoS 1, retained = trạng thái mới nhất)
```

### 3.2 Envelope lệnh (anti-replay, xác thực lệnh)

```json
{
  "reqId": "uuid-ngắn",
  "seq": 1024,
  "ts": 1750000000,
  "cmd": "setLevel",
  "payload": { "level": 80 },
  "hmac": "hex64"   // HMAC-SHA256(controlKey, seq|ts|cmd|payload)
}
```

- `controlKey` (32B): **sinh bởi APP** khi pair, gửi trong lệnh `pair` (xem IDENTITY §1/§4 — getConfig không trả key); lưu mã hóa trên thiết bị + Supabase (RLS — **chỉ OWNER/ADMIN đọc**, xem §6) → phân phối cho các app được chia sẻ
- Canonical string: `"<seq>|<ts>|<cmd>|<payload JSON compact>"` — gồm cả `cmd` (kẻ đánh cắp 1 lệnh hợp lệ không thể đổi `cmd`)
- Thiết bị kiểm tra: `seq > seq_cuối` (persist KV `last_seq`, chống replay cả sau reboot) + `|now - ts| < 60s` (bỏ qua khi NTP chưa set) + HMAC hợp lệ → mới thực thi
- Trạng thái `up` không cần ký (đã qua TLS + broker auth), kèm `seq` để app phát hiện lệch nhịp

### 3.3 Credential HiveMQ (cho gia đình — 1 credential shared)

| Client | Username | Password | Permission (topic filter) |
|---|---|---|---|
| Thiết bị (shared) | `device-family` | tạo 1 lần trong HiveMQ console (≤32 ký tự), seed vào Supabase `app_secrets` | `devices/+/#` pub+sub |
| Thiết bị (fallback per-device) | `device-{deviceId}` | `deviceSecret` (firmware **tự sinh boot đầu** — xem IDENTITY §1) | `devices/{deviceId}/#` pub+sub |
| Edge Function bridge | `app-family` | random, tạo 1 lần trong console, **chỉ nằm trong Supabase Function Secrets** | `devices/+/#` pub+sub |

- App Android open-source: **không nhúng credential MQTT vào APK/BuildConfig**. Khi pair, app gọi RPC `get_mqtt_credential()` (SECURITY DEFINER, chỉ `authenticated`) lấy `device-family` rồi gửi kèm trong lệnh `pair` → thiết bị lưu vào config. Firmware fallback sang per-device nếu chưa có credential shared.
- Migration: `supabase/migrations/0003_mqtt_shared_credential.sql`; seed 1 lần: `tools/seed_mqtt_credential.ps1`.
- **Lưu trữ:** app chỉ cache credential trong RAM (không ghi prefs); Supabase `app_secrets` chứa pass plaintext (RLS + HTTPS, chấp nhận ở quy mô $0); thiết bị lưu `mqttPass` dạng **mã hóa AES-256-GCM, key = SHA-256(deviceId)** (`mqttPassEnc` trong KV `app_cfg`) — flash không chứa plaintext.
- App **kết nối MQTT trực tiếp** (kênh điều khiển `MqttDeviceChannel`, credential từ RPC chỉ cache RAM) — không qua Edge Function bridge.
- **Bước tay duy nhất (2 phút, 1 lần cho cả gia đình):** HiveMQ Serverless free **không có REST API** (chỉ Starter trả phí) → tạo credential `device-family` (permission `devices/+/#`) trong console rồi seed vào Supabase — thiết bị mới không bao giờ phải chạm console. Kịch bản bán thiết bị sau này: self-host EMQX 5 (có REST API tạo credential per-device, cô lập + tự động $0) — firmware giữ fallback per-device nên gần như không đổi.

## 4. Flow "Thêm thiết bị" (pairing — 2 bước: phone scan → MCU scan)

### 4.0 Factory provisioning (lúc nạp firmware cho board mới, trên PC — ~2 phút)

| Việc | Công cụ |
|---|---|
| Read anchor → deviceId (`dev-...`), SSID AP cố định `myhome-{model}-{4hex}` (từ deviceId + profile) | `tools/` script (đọc qua serial/đã biết ở flash) |
| Credential MQTT: dùng shared `device-family` đã seed từ trước (xem §3.3) — **không cần tạo credential mới** | Supabase `app_secrets` |
| Inject config (AP SSID/pass) vào partition config | script |

Thiết bị chưa được pair → chưa có credential MQTT → không auth được → **không bao giờ online trên cloud** (deny mặc định). Khi pair, app gửi `device-family` vào lệnh `pair` (xem §3.3).

### 4.1 Sơ đồ flow

```mermaid
sequenceDiagram
    actor U as Người dùng
    participant D as Thiết bị (chưa pair)
    participant A as App Android
    participant S as Supabase (edge fn)
    participant H as HiveMQ

    Note over D: Boot đầu: đọc anchor → deviceId<br/>connMode default = AP_WS → AP "myhome-pump-XXXX" (open)<br/>(isProvisioned() luôn đúng — không dùng để ép AP, xem IDENTITY §3)
    U->>D: Nhấn nút 5s (hoặc tự vào AP khi chưa provisioned)
    A->>A: "Thêm thiết bị" → scan WiFi (Android API)<br/>lọc SSID prefix "myhome-" + model → danh sách thiết bị
    A-->>U: [myhome-pump-4A3F: RemotePump #1, myhome-pump-9C11: RemotePump #2]
    U->>A: Chọn 1 thiết bị
    A->>D: WifiNetworkSpecifier → dialog hệ thống →<br/>phone kết nối AP thiết bị (tự về WiFi nhà khi xong)
    A->>D: WS: getConfig → {deviceId, profile, pairingState}
    A->>D: WS: scanWifi → MCU scan 2.4GHz (đã có sẵn)
    D-->>A: Danh sách WiFi mà MCU nhìn thấy [{ssid, rssi, secure}]
    U->>A: Chọn WiFi nhà từ danh sách MCU + nhập mật khẩu
    A->>D: WS: pair {wifiSsid, wifiPass, controlKey} (chỉ chấp nhận khi AP_WS)
    D->>D: Lưu config, connMode=STA_MQTT, reboot
    D->>H: Connect MQTT bằng credential per-device → publish announce retained<br/>devices/{id}/up {cmd:"announce", profile}
    A->>S: claim_device(deviceId, profile, name, controlKey) — SQL function RPC<br/>(app đã biết deviceId từ WS getConfig; tự claim nếu đã đăng nhập)
    S-->>A: OK + ghi danh bạ (controlKey RLS: chỉ OWNER/ADMIN đọc lại)
    A-->>U: ✅ Đã thêm thiết bị
    Note over A,S: Phase 2 xác nhận online: EF get_device_state<br/>đọc retained /up qua MQTT → trả về app
```

### 4.2 Command protocol (WS pairing portal)

```json
// App → device (AP_WS mode):
{ "cmd": "getConfig" }                                   // đã có
{ "cmd": "scanWifi" } → { "cmd": "getScanWifiData" }     // đã có, MCU scan async
{ "cmd": "pair", "payload": { "wifiSsid": "Nha-Toi", "wifiPass": "...", "controlKey": "hex64" } }
```

- `pair` chỉ được xử lý khi `connMode == AP_WS` (chặn từ MQTT/STA — bảo mật)
- AP SSID format cố định `myhome-{model}-{4hex}` do firmware tự build từ profile + anchor (không phải config tay) → app lọc được

### 4.3 Bằng chứng proximity & an toàn

- **Proximity**: phải ở gần thiết bị (AP 2.4GHz phạm vi ~10m) + announce chỉ đến từ WiFi nhà → bỏ `pairingCode` (thiết bị không màn hình không hiển thị được)
- `claim_device` rate limit; chỉ 1 owner; re-pair (factory reset) → revocation server-side
- Thiết bị chưa provisioned: **deny mặc định** — chỉ mở WS pairing, không có quyền gì trên cloud

## 5. Flow điều khiển & chia sẻ

```mermaid
sequenceDiagram
    participant U as User (member)
    participant A as App
    participant S as Supabase (RPC + Edge Function)
    participant H as HiveMQ
    participant D as Thiết bị
    U->>A: Bật công tắc đèn
    A->>A: Kiểm tra quyền (viewer? → chặn ở UI)
    A->>S: RPC send_command(deviceId, seq, ts, cmd, payload) — RLS: member
    S->>S: Edge Function lấy controlKey (server-side) → ký HMAC → publish
    S->>H: publish devices/dev-xxx/cmd {seq, ts, cmd:setRelay, payload:{on:true}, hmac}
    H->>D: chuyển tiếp
    D->>D: Verify seq/ts/HMAC → relay ON
    D->>H: publish devices/dev-xxx/up {cmd:getStatus, relay:on, ...} (retained)
    A->>S: RPC get_device_state(deviceId) — EF đọc retained /up → trả UI
```

## 6. Phân quyền & chia sẻ

```mermaid
graph LR
    O["OWNER<br/>sở hữu, xóa, chuyển nhượng,<br/>tạo invite, xoay khóa"] --> D
    AD["ADMIN<br/>quản lý member, đổi tên"] --> D
    M["MEMBER<br/>điều khiển, xem lịch sử"] --> D
    V["VIEWER<br/>chỉ xem"] --> D
    D["Thiết bị"]
```

- **Owner → tạo invite**: app sinh mã invite (role, hết hạn) → người khác nhập → thành member
- **2 tầng enforce**: Supabase RLS (ai thấy/truy cập thiết bị nào) + app UI (viewer ẩn nút điều khiển). Broker ACL coarse (`devices/+/#`) — tradeoff chấp nhận với HiveMQ free (mỗi credential chỉ 1 permission)

**Schema Supabase:**

```sql
devices         (id uuid pk, device_id text unique, profile text, name text,
                 owner_id uuid → auth.users, status text, control_key bytea,
                 created_at timestamptz)
device_members  (device_id fk, user_id fk, role text, pk(device_id,user_id))
invites         (code text pk, device_id fk, role text, expires_at timestamptz)
```

RLS: select/update qua `device_members`; cột `control_key` có policy riêng — **chỉ OWNER/ADMIN đọc** (VIEWER không lấy được key → không ký lệnh); insert = chỉ SQL function `claim_device` (SECURITY DEFINER, gọi qua RPC — không phải edge fn); invite chỉ owner.

## 7. Firmware — kiến trúc

```mermaid
graph TD
    subgraph Board["Board Abstraction Layer (PIO env: LN882H / ESP32)"]
        PINS["pins: relay/triac/pwm/led/button"]
        UID["chipAnchor: hal_flash_read_unique_id() / esp_efuse..."]
        NET["WiFi + TLS"]
    end
    subgraph Identity["DeviceIdentity"]
        SECRET["deviceSecret AES-GCM(KDF(anchor))"]
        STATE["pairing state máy trạng thái"]
        CONTROL["controlKey + seq"]
    end
    subgraph Capability["Capability Registry"]
        PUMP["Profile PUMP<br/>power + current/temp sensor<br/>+ auto dry-run/overload (giữ logic hiện tại)"]
        SWITCH["Profile SWITCH<br/>power"]
        DIMMER["Profile DIMMER<br/>power + level"]
        FAN["Profile FAN<br/>power + speed"]
    end
    MQTT["MqttClient: topic /devices/{id}/{cmd,up}<br/>envelope + verify seq/ts/hmac"] --> Identity
    CMD["CommandHandler: pair, identify, setRelay, setLevel,<br/>setSpeed, getStatus, getConfig..."] --> Capability
    PAIR["Pairing Portal AP_WS: SSID myhome-{model}-XXXX<br/>scanWifi (danh sách MCU) + nhận WiFi + controlKey"]
```

**Thêm thiết bị mới = thêm 1 profile + cấu hình pin** — không sửa core.

## 8. App Android — kiến trúc

```
Màn hình:
  Login/Register (Supabase Auth)
  Device List  ──►  [＋ Thêm thiết bị] ──► Pairing Wizard (mục 4)
  Device Dashboard (render theo profile: PumpCard/SwitchCard/DimmerSlider/FanCard)
  Device Management (đổi tên, xóa, chuyển nhượng, chia sẻ, xoay khóa)
  Invite Screen (tạo/nhập mã, quản lý member)

Data:
  Room (danh bạ cache) + Supabase REST (danh bạ, quyền)
  Điều khiển/trạng thái qua Supabase RPC (send_command / get_device_state → Edge Function bridge) — KHÔNG MQTT trực tiếp (open-source)
  Thay config local.properties cứng → registry động
```

## 9. OTA

- Firmware upload → Supabase Storage (1GB free) → URL HTTPS → giữ nguyên cơ chế OTA verify + rollback hiện có (OtaVerify/boot guard)

## 10. Lộ trình triển khai

| Phase | Nội dung | Deliverable |
|---|---|---|
| **P1** | Hạ tầng cloud | Supabase project + SQL migration (schema+RLS+RPC `claim_device`, `get_mqtt_credential`); tạo credential `app-family` (EF bridge) + `device-family` (shared) trong HiveMQ console → `app-family` vào Function Secrets, `device-family` seed qua `tools/seed_mqtt_credential.ps1`; script test MQTT trong `tools/` |
| **P2** | Firmware core | Board abstraction; DeviceIdentity (anchor + AES-GCM blob + máy trạng thái pairing); Pairing Portal AP_WS (`myhome-{model}-XXXX`, `pair` command, scanWifi trong AP); envelope seq/ts/hmac ✅; MQTT auth: credential shared qua pair (fallback per-device) ✅; revocation khi re-pair; verify scan khi đang ở AP mode |
| **P3** | Firmware profiles | SWITCH/DIMMER/FAN (cùng codebase, build thử ESP32); PUMP giữ nguyên |
| **P4** | App core | Login; Device List + Add Device (scan AP `myhome-` prefix → WifiNetworkSpecifier → chọn WiFi từ danh sách MCU → pair → tự claim); refactor repository/navigation |
| **P5** | App device UI | Màn hình theo capability; điều khiển qua RPC send_command; quản lý thiết bị |
| **P6** | Chia sẻ & hoàn thiện | Invite/roles (invites có sẵn schema); Edge Function bridge (send_command/get_device_state); OTA qua Supabase Storage; cảnh báo clone (offline bất thường/seq lệch); tài liệu |

## 11. Việc bạn cần làm (tổng cộng ~30 phút, 0 đồng)

1. Tạo project Supabase free → lấy `SUPABASE_URL` + `anon key` (publishable — không phải secret; RLS là tường lửa)
2. HiveMQ console: tạo credential `app-family` (permission `devices/+/#`) → dán vào Supabase → **Function Secrets**; tạo credential `device-family` (permission `devices/+/#`, ≤32 ký tự) → seed 1 lần: `supabase/migrations/0003_mqtt_shared_credential.sql` (SQL Editor) + `tools/seed_mqtt_credential.ps1`
3. Pair thiết bị mới bằng app — credential `device-family` tự gửi vào lệnh `pair`, **không bao giờ phải mở HiveMQ console** (bước tay duy nhất đã xong ở bước 2)

## 12. Tradeoffs đã xác nhận

- HiveMQ free: 1 bước tay 1 lần cho cả gia đình (credential shared `device-family`); 100 connections — dư cho gia đình. Kịch bản bán thiết bị → self-host EMQX 5 (REST API tạo credential per-device)
- Supabase free: pause sau 7 ngày không hoạt động → thiết bị không phụ thuộc (thiết kế tách lớp)
- **App không MQTT trực tiếp** (open-source an toàn) → lệnh điều khiển +1-3s latency do Edge Function cold start; trạng thái đọc qua retained `/up` (poll) — chấp nhận với app gia đình
- Edge Function không persistent → không subscribe MQTT lâu dài; mỗi lệnh = 1 kết nối ngắn (HiveMQ 100 connections dư)
- Broker ACL coarse cho `app-family` → phân quyền chi tiết enforce ở Supabase RLS (control_key: OWNER/ADMIN) + UI (viewer ẩn nút)
- LN882H không secure boot → chống "kẻ có chip thật + recompile" dựa cloud detection; ESP32 có thể nâng cấp secure boot v2 (tùy chọn)
- Android 10+: kết nối AP thiết bị luôn có 1 dialog xác nhận của hệ thống (`WifiNetworkSpecifier` — ràng buộc OS, không bypass); cần permission vị trí (Android <13) / `NEARBY_WIFI_DEVICES` (13+); scan khi đang nối WiFi khác có thể hạn chế channel → AP thiết bị cố định channel 1
