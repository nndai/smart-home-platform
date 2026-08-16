#pragma once

#include "core/DeviceDriver.h"
#include "RemoteSwitchConfig.h"
#include "core/ButtonMenu.h"
#include "core/LedController.h"
#include <OneButton.h>

class RemoteSwitchDriver : public DeviceDriver {
public:
    RemoteSwitchDriver() : _cfg(nullptr) {}

    void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) override;
    void loop(uint32_t nowMs) override;
    bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) override;
    void getStatus(JsonDocument& resp) override;
    void getConfig(JsonDocument& resp) override;
    bool setConfig(const JsonDocument& payload, JsonDocument& resp) override;
    void setServices(const DriverServices& svc) override;

    // Called when an MQTT message is received on the subscribed topics
    void handleTargetStatus(const JsonDocument& doc);

private:
    RemoteSwitchConfig* _cfg;
    DriverServices _services;
    ConfigSaveFn _saveCb;
    LedController _ledConn;
    LedController _ledStatus;
    LedController _ledError;
    OneButton _button;
    ButtonMenu _menu;
    ButtonMenu::Step _menuSteps[3];

    // Status tracking for LEDs
    bool _connected = false;
    bool _targetOn = false;
    bool _targetError = false;
    uint32_t _targetSeq = 0;

    void updateLeds();
    void sendToggleCommand();
    bool buildEnvelope(const char* cmd, const JsonDocument& payload, JsonDocument& envelope);

    // Callbacks for button
    void _onButtonClick();
    void _onButtonDoubleClick();
    void _onButtonLongPressStart();

    // Callbacks for menu
    void _menuResetWiFi();
    void _menuDebugMode();
    void _menuFactoryReset();
};
