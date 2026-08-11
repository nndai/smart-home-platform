# Smart Home Platform — Hướng dẫn cho AI Agents

## Project

Hệ sinh thái IoT cá nhân $0: firmware 1 source cho nhiều MCU (LN882H/LibreTiny, ESP32, ESP8266) + app Android. Chi tiết: `docs/ECOSYSTEM_PLAN.md`, `docs/SOURCE_STRUCTURE.md`.

## Quy tắc cấu trúc (bắt buộc)

- `firmware/src/core/` + `main.cpp`: bất biến, **không bao giờ `#ifdef`** — chỉ dùng Arduino-standard API
- Mọi khác biệt chip/include: `firmware/src/compat/` (khai báo `#ifdef` duy nhất) + `firmware/src/chip/` (SDK riêng)
- Thêm thiết bị mới → `firmware/src/profiles/<tên>/` + env trong `firmware/platformio.ini`; không tạo file main khác
- `app/`: do Android Studio quản lý (user tự tạo)

## Commands

```powershell
# Build firmware (sau khi migrate src vào firmware/):
pio run -e pump_ln882h

# Tools Python (khi dùng):
python -m venv tools/.venv
tools/.venv/Scripts/pip install -r tools/requirements.txt
```

## Nguyên tắc viết code (Clean Code & Scalability)

- **Clean Code & Đọc hiểu**: Viết code sạch, đặt tên biến/hàm rõ nghĩa. **Viết comment trong code bằng tiếng Anh (English comments)** để chuẩn hóa codebase quốc tế và dễ bảo trì.
- **Dễ mở rộng & Thiết kế mô-đun**: Kiến trúc tuân thủ nguyên lý SOLID, giảm phụ thuộc trực tiếp (loose coupling). Chuẩn bị sẵn cấu trúc để dễ dàng thêm các tính năng mới trong tương lai.
- **Chia file hợp lý**: Mỗi class/module có một trách nhiệm duy nhất (Single Responsibility). Tránh viết file monolithic quá lớn; chủ động chia tách file theo layer/component rõ ràng.

## Lưu ý

- Không commit secret (HiveMQ credentials, SUPABASE keys) — xem `.env.example`
- Firmware chỉ phụ thuộc MQTT (HiveMQ); Supabase chỉ phục vụ app
- Tuyệt đối không sửa thư viện core (Arduino, FreeRTOS, ESP-IDF, LN882H SDK, LibreTiny SDK) — chỉ sửa code trong `src/` và `include/`

