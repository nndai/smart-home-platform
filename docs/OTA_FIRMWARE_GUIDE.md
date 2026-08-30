# Hướng Dẫn Upload Firmware & Quản Lý OTA Trên Supabase

Tài liệu hướng dẫn quy trình build, upload firmware `.bin` lên **Supabase Storage** và ghi nhận metadata (version, checksum, URL) vào **Supabase Database** để phục vụ cập nhật OTA từ xa cho thiết bị.

---

## 1. Kiến Trúc Lưu Trữ & OTA

- **Supabase Storage (Bucket: `firmwares`)**: Lưu các file binary nhị phân (`.bin`). Bucket được cấu hình `public = true` để thiết bị và app có thể tải trực tiếp qua giao thức HTTPS.
- **Supabase Database (Bảng: `public.firmware_releases`)**: Lưu thông tin chi tiết từng bản build:
  - `profile`: `pump`, `switch`, `remote_switch`...
  - `env`: `remote_switch_esp8266`, `pump-ln882h`, `switch_esp32`...
  - `target_chip`: `esp8266`, `esp32`, `ln882h`...
  - `version`: ví dụ `v1.0.0` hoặc timestamp `v2026.08.30.69563`
  - `file_url`: HTTPS download link trực tiếp
  - `checksum_sha256` & `checksum_md5`: phục vụ kiểm tra tính toàn vẹn
  - `is_latest`: tự động đánh dấu bản mới nhất cho từng cặp `(profile, env)`

---

## 2. Bước Chuẩn Bị (1 Lần Duy Nhất)

### Chạy SQL Migration trên Supabase
1. Mở [Supabase Console](https://app.supabase.com) → Chọn Project của bạn.
2. Vào **SQL Editor** → Tạo query mới.
3. Mở file [`supabase/migrations/0009_firmware_releases.sql`](file:///d:/projects/smart-home-platform/supabase/migrations/0009_firmware_releases.sql), copy toàn bộ nội dung và dán vào SQL Editor.
4. Bấm **Run** để khởi tạo:
   - Storage bucket `firmwares`
   - Bảng `firmware_releases` + RLS policies
   - Trigger tự động đồng bộ `is_latest`
   - RPC function `get_latest_firmware()`

---

## 3. Hướng Dẫn Sử Dụng CLI Upload

Công cụ hỗ trợ cả **Python** (không cần cài pip ngoài) và **PowerShell** (Windows native).

### Cách 1: Sử dụng Python CLI (`tools/upload_firmware.py`)

```bash
# 1. Liệt kê các environment có sẵn:
python tools/upload_firmware.py --list

# 2. Build và upload firmware cho một môi trường cụ thể:
python tools/upload_firmware.py -e remote_switch_esp8266 --build

# 3. Upload tất cả các môi trường cùng lúc:
python tools/upload_firmware.py -e all --build

# 4. Chỉ định Version hoặc Changelog tùy biến:
python tools/upload_firmware.py -e remote_switch_esp8266 --build -v "1.2.0" -c "Sửa lỗi kết nối MQTT"

# 5. Chạy thử nghiệm (không upload):
python tools/upload_firmware.py -e remote_switch_esp8266 --dry-run

# 6. Chế độ chọn trực quan (Interactive Menu):
python tools/upload_firmware.py
```

### Cách 2: Sử dụng PowerShell (`tools/upload_firmware.ps1`)

```powershell
# 1. Liệt kê các môi trường:
powershell -ExecutionPolicy Bypass -File tools/upload_firmware.ps1 -List

# 2. Build và upload:
powershell -ExecutionPolicy Bypass -File tools/upload_firmware.ps1 -Env remote_switch_esp8266 -Build

# 3. Upload tất cả:
powershell -ExecutionPolicy Bypass -File tools/upload_firmware.ps1 -Env all -Build
```

---

## 4. Kích Hoạt OTA Trên Thiết Bị

Sau khi upload thành công, script sẽ in ra URL công khai và payload mẫu. Bạn có thể kích hoạt cập nhật OTA cho thiết bị theo 2 cách:

### A. Gửi qua MQTT (Lệnh `otaUrl`)
Publish payload sau vào topic `devices/{deviceId}/cmd`:
```json
{
  "reqId": "ota-001",
  "seq": 100,
  "ts": 1788069563,
  "cmd": "otaUrl",
  "payload": {
    "url": "https://ezvakqxzgrhognejllyz.supabase.co/storage/v1/object/public/firmwares/releases/remote_switch/esp8266/remote_switch_esp8266_1788069563.bin"
  },
  "src": "app-admin",
  "hmac": "..."
}
```

### B. App Android / RPC Query
App Android có thể gọi RPC `get_latest_firmware` từ Supabase:
```kotlin
val latestFw = supabase.postgrest.rpc(
    function = "get_latest_firmware",
    parameters = mapOf("p_profile" to "remote_switch", "p_env" to "remote_switch_esp8266")
).decodeSingle<FirmwareRelease>()
```
Khi phát hiện phiên bản mới hơn phiên bản đang chạy trên thiết bị, app hiển thị nút **[Cập nhật Firmware]** và gửi lệnh `otaUrl`.

---

## 5. An Toàn & Rollback
- Thiết bị sau khi tải binary sẽ tự động ghi vào flash OTA partition.
- Nếu quá trình tải bị đứt quãng hoặc lỗi mạng, thiết bị sẽ hủy (`abort`) và tiếp tục chạy firmware hiện tại mà không bị brick.
- Sau khi tải và verify thành công, thiết bị khởi động lại và nạp phân vùng mới.
