# Danh tính & Pairing thiết bị — Hiện thực phase 1

> Tài liệu tham chiếu kỹ thuật: mọi giá trị key / id / secret, nhiệm vụ, mã hóa/giải mã.
> Thiết kế tổng thể: [ECOSYSTEM_PLAN.md](./ECOSYSTEM_PLAN.md) §2 (danh tính), §3 (phase 2).
> Code: `firmware/src/core/DeviceIdentity.{h,cpp}`, `firmware/src/chip/anchor.*`,
> `firmware/src/core/CommandHandler.ipp` (lệnh `getConfig`/`pair`/`provision`), `firmware/src/main.cpp` (boot).

---

## 1. Bảng danh tính (đầy đủ)

| Giá trị | Độ dài | Nguồn gốc | Ai biết | Nơi lưu | Nhiệm vụ |
|---|---|---|---|---|---|
| `chipAnchor` | LN882H: 16B / ESP32: 6B | UID OTP die flash (`hal_flash_read_unique_id`) / MAC eFuse (`esp_efuse_mac_get_default`) | Công khai của chip — không đọc được từ dump flash | OTP/eFuse, không ghi đè được | **Nguồn gốc `deviceId`** (băm toàn bộ anchor); không còn dùng làm khóa mã hóa |
| `deviceId` | 17 ký tự `dev-` + 12 hex | `hex(SHA-256(anchor))[:12]` (6 byte đầu — gộp đủ entropy LN882H 16B / ESP32 6B MAC) | Công khai | RAM (suy ra mỗi boot) + nhãn/QR + Supabase (phase 2) | Định danh MQTT: clientId `device-{deviceId}`, topic `devices/{deviceId}/#`; **thành phần seed mã hóa** |
| `apSSID` | ≤ 32 ký tự `myhome-<model>-XXXX` (model = pump/switch/..., XXXX = 4 hex) | `"myhome-" + profileName() + "-" + hex(SHA-256(anchor))[:4]` | Công khai | RAM (suy ra mỗi boot) | SSID AP pairing portal (open, không mật khẩu) — app lọc prefix `myhome-` + model để nhận biết loại thiết bị |
| `controlKey` | 32B (hex 64) | **App sinh ra**, gửi trong lệnh `pair`; hoặc `provision` (factory) ghi đè; thiết bị tự sinh lúc boot đầu (trước khi pair) | Thiết bị + app đã pair (app lưu vào Supabase) | KV `ident` (mã hóa AES-GCM, key = SHA-256(deviceId + FW_SECRET)) | **Khóa HMAC** envelope lệnh: `hmac = HMAC-SHA256(controlKey, ts\|cmd\|payload\|src)` |
| KV key `"ident"` | blob 64B | thiết bị tự ghi | — | eFlash (KV system) | Chứa 1 record mã hóa: controlKey |
| `mqttPass` (config, credential shared) | ≤ 31 ký tự (`device-family`) | credential shared từ app trong lệnh `pair` | Thiết bị + HiveMQ + Supabase `app_secrets` | KV `app_cfg` — **mã hóa AES-256-GCM, key = SHA-256(deviceId + FW_SECRET)** (field `mqttPassEnc`, RAM mới là bản rõ; flash chỉ bản mã; `FW_SECRET` = build secret từ `.env`, rỗng = chỉ deviceId) | Password MQTT HiveMQ (shared — flow gia đình, xem ECOSYSTEM §3.3). **Không có credential → thiết bị không connect MQTT** |

**Quy tắc:** `deviceId`/`apSSID` là **công khai**; `controlKey` là **bí mật** — không bao giờ nằm trong firmware binary, không lưu plaintext xuống flash, không trả về qua API (sau phase 1.1: `getConfig` không còn trả `controlKey`).

---

## 2. Mã hóa / giải mã KV `ident`

### 2.1 Thuật toán

- **AES-256-GCM** (mbedtls), không AAD (`add = nullptr, add_len = 0`)
- **Khóa:** `K = SHA256(deviceId + FW_SECRET)` — **cùng key với `mqttPass`** (`crypto::cfgKeyFromSeed`, seed = `deviceId + FW_SECRET`; `FW_SECRET` = build secret từ `.env` qua `scripts/build_env.py`, rỗng = chỉ deviceId). Không bao giờ lưu key xuống flash.
- **Layout blob 64B:** 1 record:

```
record = [ iv (16B) | ciphertext (32B) | GCM tag (16B) ]
```

### 2.2 Thứ tự tham số mbedtls (khác nhau giữa 2 hàm — đã verify code)

| Hàm | Tham số cuối | Ý nghĩa |
|---|---|---|
| `mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, 32, iv, 16, nullptr, 0, plain, out+16, **16, out+48**)` | `tag_len` (16) **TRƯỚC** `tag` (out+48) | Thứ tự Espressif |
| `mbedtls_gcm_auth_decrypt(&ctx, 32, blob, 16, nullptr, 0, **blob+48, 16**, blob+16, out)` | `tag` (blob+48) **TRƯỚC** `tag_len` (16) | Thứ tự chuẩn mbedtls |

Lưu ý: hai hàm có thứ tự tham số khác nhau — đổi nhầm sẽ decrypt fail toàn bộ. `iv` ngẫu nhiên mỗi lần ghi (`chip::randomBytes`). GCM dùng chung cho `ident` + `mqttPass` (`crypto::aesGcmEncrypt/aesGcmDecrypt`, xem `firmware/src/core/Crypto.h`).

### 2.3 Vòng đời blob & hành vi lỗi

| Tình huống | Hành vi |
|---|---|
| Boot lần đầu (chưa có KV) | `_load()` false → sinh controlKey mới → `_save()` |
| Dump flash → nạp chip B | anchor_B ≠ anchor_A → deviceId khác → KDF khác → decrypt fail → **sinh controlKey mới** → app cũ không còn key đúng → phải re-pair (clone vô dụng) ✔ |
| Đổi `FW_SECRET` giữa 2 build | seed khác → key khác → decrypt fail → sinh controlKey mới → **phải re-pair** (FW_SECRET là hằng số không đổi sau deploy) |
| Blob hỏng / thiếu (storedLen ≠ 64) | `_load()` false → sinh lại key mới |
| `reset()` (factory reset) | Xóa cờ + zero RAM + `kvDel("ident")` → boot sau sinh key mới (xoay khóa) |

---

## 3. Vòng đời thiết bị

```
Factory (flash firmware mới)
   │  boot đầu: sinh controlKey, connMode = AP_WS (default)
   ▼
AP pairing portal "myhome-pump-XXXX" (open, model theo profile)
   │  app kết nối AP → getConfig (deviceId, pairingState) → scanWifi → pair
   ▼
pair { wifiSsid, wifiPass, controlKey, mqttServer, mqttPort, mqttUser, mqttPass }
   │  controlKey do APP sinh, hex 64; mqtt credential = device-family từ Keystore/Supabase
   │  thiết bị lưu controlKey → connMode = STA_MQTT → reboot
   ▼
STA_MQTT (phase 2: MQTT + envelope HMAC(controlKey))
   │  muốn đổi chủ / mất quyền kiểm soát → bấm nút factory reset
   ▼
reset(): xóa config + identity (kvDel "ident") → về AP portal (re-pair, xoay khóa)
```

- **Điểm vào AP portal:** `connMode` mặc định = `AP_WS` (device mới / sau factory reset). Không dùng `isProvisioned()` để ép nữa — thiết bị luôn tự sinh key nên `isProvisioned()` luôn đúng; nhánh dead-code đó đã được xóa ở `main.cpp`.
- **Clone:** anchor khác → deviceId khác → key khác → controlKey mới → app cũ mất quyền, phải re-pair (phase 2), kể cả khi config (wifi) bị lấy theo từ dump.
- **Không có credential MQTT (`mqttUser` rỗng):** thiết bị **không connect MQTT** — không có fallback per-device.

---

## 4. Giao thức pairing (WebSocket trong AP `myhome-<model>-XXXX`)

Mọi lệnh chỉ được chấp nhận khi `g_connMode == AP_WS` — từ STA/MQTT/DEBUG đều bị từ chối.

| Lệnh | Điều kiện | Payload | Trả về (nổi bật) |
|---|---|---|---|
| `getConfig` | luôn (trong AP) | — | `deviceId`, `apSSID`, `profile`, `pairingState`, `connMode`, cấu hình WiFi/MQTT (ẩn mật khẩu). **KHÔNG còn `controlKey`** |
| `scanWifi` | AP_WS | — | danh sách WiFi lân cận |
| `pair` | AP_WS | `wifiSsid` (bắt buộc), `wifiPass` (tùy chọn), **`controlKey` (bắt buộc, hex 64)**, `mqttServer`, `mqttPort`, `mqttUser`, `mqttPass` (credential shared) | `status`, `deviceId` → reboot sang STA_MQTT |
| `provision` (factory) | AP_WS | `controlKey` (tùy chọn, hex 64; bỏ trống = giữ nguyên) | `status`, `deviceId`, `pairingState` |
| `factoryReset` | AP_WS | — | reset config + identity → về unpaired |

**Luồng pair chuẩn (app):**
1. App sinh `controlKey` 32B ngẫu nhiên (giữ cục bộ SharedPreferences, sau lưu Supabase)
2. Bấm [＋] → `WifiNetworkSpecifier(prefix="myhome-")` → Android OS hiện popup chọn thiết bị → `getConfig` (lấy `deviceId`, xác nhận profile)
3. `pair { wifiSsid, wifiPass, controlKey, mqttServer, mqttPort, mqttUser, mqttPass }` → chờ reboot
4. Nối lại WiFi nhà (~3.5s delay); `claim_device` lên Supabase (ghi đè ownership nếu đã active, chủ cũ → TRANSFERRED)

---

## 5. Bảo mật — ma trận tấn công & rủi ro đã biết

### 5.1 Chống được

| Kịch bản | Cơ chế | Ghi chú |
|---|---|---|
| Dump flash → clone board khác | AES-GCM + KDF(SHA256(deviceId + FW_SECRET)) | controlKey mới sinh → app cũ mất quyền → phải re-pair |
| Đọc firmware binary tìm secret | Không nhúng; chỉ blob mã hóa | — |
| Lấy `controlKey` từ xa (STA/MQTT) | `pair`/`provision` chỉ AP_WS; `getConfig` không trả key | — |
| Gửi lệnh giả mạo / replay | HMAC envelope + `ts` window | ✅ |

### 5.2 Rủi ro đã biết (chấp nhận ở phase 1, cần giảm nhẹ phase sau)

| Rủi ro | Mức | Hiện trạng / hướng xử lý |
|---|---|---|
| **AP `myhome-<model>-XXXX` open, không auth** — kẻ trong ~10m có thể vào portal | Trung bình | Mô hình proximity chủ đích. Kẻ chỉ đọc được thông tin công khai (không lấy được `controlKey`). Kẻ có thể tự `pair` trước với WiFi của hắn = **cướp thiết bị** — chủ nhân phát hiện vì thiết bị rời khỏi WiFi nhà → bấm factory reset để chiếm lại |
| AP không có timeout tự tắt | Thấp | Phase sau: tự tắt AP sau N phút nếu không pair |
| WiFi password nằm plaintext trong KV config; **mqttPass đã mã hóa** (AES-256-GCM, key = SHA-256(deviceId + FW_SECRET), `FW_SECRET` = build secret từ `.env`) | Thấp→Trung bình | Clone lấy được wifi creds nhà nạn nhân (nhưng cloud vẫn không dùng được, mqttPass chỉ đọc được trên chính chip đó + biết FW_SECRET). Phase sau: cân nhắc mã hóa cả config hoặc chấp nhận (firmware $0) |
| STA connect fail (nhập sai wifi) → retry vô hạn, không fallback AP | UX | Phase sau: fallback AP sau N lần fail |
| Kẻ có máy thật (đọc được anchor) | Chấp nhận | Mô hình: không chống attacker có hardware access vật lý |
| ESP32 dùng MAC eFuse 6B (lệch plan 8B `esp_efuse_get_chip_serial_number` — API không tồn tại trong IDF 4.4/Arduino core 3.x) | Chấp nhận | Anchor khác độ dài giữa chip, nhưng mọi suy diễn đều qua SHA-256(anchor) rồi cắt đều (deviceId 6B, key encrypt: toàn bộ hash) nên format đồng nhất |
| `pair` ghi đè `controlKey` cũ | Đã triển khai | Re-pair = xoay quyền điều khiển; `claim_device` (migration 0005) ghi đè owner_id + control_key, chủ cũ → role `TRANSFERRED` trong `device_members`, chủ cũ bấm xóa → dọn dòng rác DB (`remove_device`) |

---

## 6. Phase 2 — sử dụng các giá trị này

| Giá trị | Dùng ở phase 2 |
|---|---|
| `controlKey` | Envelope lệnh: `hmac = HMAC-SHA256(controlKey, ts\|cmd\|payload\|src)` (payload JSON compact — giữ thứ tự key); `src` = senderId ổn định (`app-...` của app / `dev-...` của remote switch) — thiết bị kiểm tra `ts` trong ±60s (khi NTP đã set) + HMAC đúng (xem ECOSYSTEM_PLAN §3.2) |
| `deviceId` | Topic `devices/{deviceId}/cmd` (app→device), `devices/{deviceId}/up` (retained, device→app); clientId cố định để chống chạy 2 thiết bị cùng danh tính |
| Supabase | App lưu `deviceId` + `controlKey` (RLS); revocation khi re-pair; credential MQTT shared trong `app_secrets` (RPC `get_mqtt_credential`) |

**Trạng thái code:** phase 1 đã đủ (sinh + lưu + đọc + pair/provision/getConfig). Đã triển khai: clientId `device-{deviceId}`, topic chuẩn `devices/{deviceId}/cmd|up|log` (field config `mqttTopic` legacy không dùng nữa), **announce** retained khi connect MQTT (`{cmd:"announce", deviceId, profile}`), **envelope ts/hmac + `controlKey` verify** (mọi lệnh MQTT không có envelope hợp lệ bị drop), MQTT auth: credential shared `device-family` do app gửi trong `pair` (**không có credential → không connect MQTT**), **`mqttPass` mã hóa AES-256-GCM khi lưu flash** (key = SHA-256(deviceId + FW_SECRET), field `mqttPassEnc`; load tự giải mã + migrate blob legacy plaintext), **controlKey blob cùng key mã hóa** (KV `ident` 64B, key = SHA-256(deviceId + FW_SECRET); `deviceSecret` đã bỏ — không fallback per-device), **`deviceId` = `dev-` + hex(SHA-256(anchor))[:12]** (gộp entropy 2 nền MCU), **revocation server-side khi re-pair** (`claim_device` ghi đè owner + TRANSFERRED + `remove_device`). Chưa làm: chặn lệnh nguy hiểm theo cấp quyền qua MQTT, Edge Function bridge cho chia sẻ thiết bị.
