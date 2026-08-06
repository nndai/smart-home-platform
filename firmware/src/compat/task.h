#pragma once

// Include điểm chung cho FreeRTOS - có trên LN882H/LibreTiny và ESP32.
// (ESP8266 không có FreeRTOS: sau này thay bản stub riêng cho env đó.)
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
