#pragma once

// Log capture dùng chung cho mọi MCU (LibreTiny, ESP8266, ESP32...):
// bắt toàn bộ output đi qua printf/ets_printf (log LT_ + log SDK khi
// os_print=1), gom thành dòng. Trước khi LITTLEFS init: lưu tạm tối đa
// PREINIT_MAX dòng (cấp phát động), sau khi logCaptureFlushFile() được gọi
// thì đẩy thẳng vào LogManager (ghi file khi LITTLEFS sẵn sàng + forward
// WS/MQTT khi callback đã set).
// Triển khai: core/log/LogCapture.cpp.

// Cài hook bắt ký tự (ESP8266: putc1; LibreTiny: --wrap=putchar_p từ link time).
void logCaptureInit();

class LogManager;

// Ghi các dòng lưu tạm vào file và xóa cấp phát động (gọi sau LITTLEFS init /
// logManager.begin()). Từ đó trở đi log đẩy thẳng vào LogManager.
void logCaptureFlushFile(LogManager* lm);

// Gọi bởi các module hook UART (vd: compat/log_capture_esp8266.cpp)
void logCaptureChar(char c);