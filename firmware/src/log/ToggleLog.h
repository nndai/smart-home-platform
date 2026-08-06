#pragma once
#include "LogBase.h"

class ToggleLog : public LogBase {
public:
    enum Source : uint8_t {
        TOGGLE_BUTTON = 0,
        TOGGLE_ONLINE = 1,
        TOGGLE_SCHEDULE = 2,
    };

    ToggleLog(TimeManager& tm);
    bool begin() override;

    void log(Source src, bool on);

    static constexpr const char* DIR = "/logs/toggle/";

private:
    void _rotate();
    String _path();

    static constexpr size_t MAX_FOLDER = 100 * 1024;
};
