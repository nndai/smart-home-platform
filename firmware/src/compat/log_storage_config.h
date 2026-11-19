#pragma once
#include <stdint.h>

// Override these in a PlatformIO environment after measuring the target MCU.
#ifndef LOG_V2_MAX_SEGMENTS
#define LOG_V2_MAX_SEGMENTS 16
#endif
#ifndef LOG_V2_IMPORTANT_QUEUE
#define LOG_V2_IMPORTANT_QUEUE 6
#endif
#ifndef LOG_V2_SYS_QUEUE
#define LOG_V2_SYS_QUEUE 8
#endif
#ifndef LOG_V2_TICK_MS
#define LOG_V2_TICK_MS 25
#endif
#ifndef LOG_V2_CHECKPOINT_MS
#define LOG_V2_CHECKPOINT_MS 30000
#endif
#ifndef LOG_V2_FREE_MIN
#define LOG_V2_FREE_MIN 16384
#endif
#ifndef LOG_V2_SEGMENT_MAX
#define LOG_V2_SEGMENT_MAX 32768
#endif

namespace logv2 {
static_assert(LOG_V2_MAX_SEGMENTS >= 2 && LOG_V2_MAX_SEGMENTS <= 16, "Catalog must fit the v2 client limit");
static_assert(LOG_V2_SEGMENT_MAX >= 1024 && LOG_V2_SEGMENT_MAX <= 32768, "Segment must fit the v2 cache limit");
static_assert(LOG_V2_IMPORTANT_QUEUE > 0 && LOG_V2_SYS_QUEUE > 0 && LOG_V2_TICK_MS > 0, "Storage limits must be positive");
}
