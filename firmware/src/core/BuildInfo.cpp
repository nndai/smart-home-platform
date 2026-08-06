#include "BuildInfo.h"
#include "build_time.h"

const char* buildStr(void) {
    return BUILD_STR;
}

uint32_t buildUnixTime(void) {
    return BUILD_UNIX_TIME;
}
