# Smart Home Platform

Hệ sinh thái IoT cá nhân: bơm (LN882H), đèn, quạt (ESP32/ESP8266) điều khiển qua app Android — chi phí hạ tầng $0 (HiveMQ Cloud Serverless + Supabase Free).

## Cấu trúc

```
smart-home-platform/
├── app/                          # Android app (Kotlin/Compose)
├── firmware/                     # 1 source tree cho MỌI thiết bị
│   ├── include/  src/
│   │   ├── core/                 # MqttClient, CommandHandler, OTA, Pairing, identity
│   │   ├── compat/               # kv.h, tls.h, rt.h, pm.h (nơi chứa #ifdef duy nhất)
│   │   ├── chip/                 # anchor.cpp, softap.cpp, io.cpp (per-chip SDK)
│   │   └── profiles/             # pump/ switch/ fan/ — chỉ phần khác nhau của thiết bị
│   ├── boards/  lib/
│   └── platformio.ini            # env = (board × profile): pump_ln882h, switch_esp32...
├── tools/                        # provisioning script, bridge, dumper
└── docs/                         # ECOSYSTEM_PLAN.md, SOURCE_STRUCTURE.md
```

## Quy tắc

- Thêm **thiết bị** → chỉ đụng `firmware/src/profiles/` + `platformio.ini`
- Thêm **chip** → chỉ đụng `firmware/src/chip/` + `firmware/src/compat/`
- `core/` + `main.cpp` bất biến — xem chi tiết `docs/SOURCE_STRUCTURE.md`
