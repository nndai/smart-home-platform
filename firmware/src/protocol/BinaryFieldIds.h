#pragma once
#include <stdint.h>

namespace protocol {

/**
 * Common Field IDs for the Binary Protocol.
 * Instead of embedding JSON string keys like "relay", "temperature" in every packet, 
 * the keys are compressed into 9-bit unsigned integer identifiers (0-511) to save bandwidth.
 */
enum class FieldId : uint16_t {
    None = 0,
    System = 1,
    ChipId = 2,
    ChipModel = 3,
    CpuFreq = 4,
    SdkVersion = 5,
    BuildTime = 6,
    BuildUnixTime = 7,
    Uptime = 8,
    TimeSys = 9,
    ResetReason = 10,

    Memory = 20,
    FreeHeap = 21,
    MinEverFreeHeap = 22,
    MaxAllocHeap = 23,

    Wifi = 30,
    Rssi = 31,
    Ssid = 32,
    Ip = 33,
    Mac = 34,
    Channel = 35,
    ConnMode = 36,
    Temperature = 37,

    Storage = 40,
    FlashSize = 41,
    FsTotal = 42,
    FsUsed = 43,
    FlashMode = 44,
    FlashSpeed = 45,

    Tasks = 50,
    Task = 51,
    TaskName = 52,
    TaskPriority = 53,
    TaskStackWaterMark = 54,
    TaskState = 55,

    // Command specific fields
    Status = 60,
    Message = 61,
    Timestamp = 62,
    ReqId = 63,
    Cmd = 64,
    Payload = 65,
    Fields = 66,

    // Log fields
    Log = 70,
    LogSize = 71,
    ToggleLogSize = 72,
    PowerLogSize = 73,
    TotalBytes = 74,
    UsedBytes = 75,
    TimeSynced = 76,
    Enabled = 77,

    // OTA/System fields
    Url = 80,
    Size = 81,
    Data = 82,
    Progress = 83,
    Total = 84,
    Pct = 85,
    Received = 86,

    // Config fields
    MqttServer = 90,
    MqttPort = 91,
    MqttUser = 92,
    MqttPass = 93,
    MqttTopic = 94,
    WifiSSID = 95,
    WifiPass = 96,
    DebugSSID = 97,
    DebugPass = 98,
    DebugIp = 99,
    DebugGateway = 100,
    DebugNetmask = 101,
    DeviceId = 102,
    ApSSID = 103,
    Profile = 104,
    PairingState = 105,
    SysLogFileEnabled = 106,
    SysLogFileLevel = 107,
    NeedReboot = 108,
    Stream = 109,
    
    // Envelope fields
    Seq = 110,
    Ts = 111,
    Hmac = 112,
    Src = 113,

    // Device specific fields
    State = 120,
    Relay = 121,
    OnDuration = 122,
    Current = 123,
    Power = 124,
    Voltage = 125,
    DailyEnergy = 126,
    HourlyEnergy = 127,
    Apparent = 128,
    Pf = 129,
    PumpMode = 130,
    PumpStateStr = 131,
    PumpState = 132,
    RelayStartMode = 133,
    ThreshOff = 134,
    ThreshNoWater = 135,
    ThreshRunning = 136,
    ThreshOverload = 137,
    DryTimeout = 138,
    OverloadTimeout = 139,
    CCal = 140,
    VCal = 141,
    PCal = 142,
    Pump = 143,

    // File Browser fields
    Encode = 150,
    Entries = 151,
    Limit = 152,
    More = 153,
    Offset = 154,
    Path = 155,
    Type = 156,

    // Envelope / Extra fields
    ControlKey = 160,
    SysLogSize = 161,
    WifiDrop = 162,
    TargetId = 163,
    TargetType = 164,
    TargetKey = 165,
    TargetPaired = 166,
    TargetError = 167,

    // Task & WiFi Scan & Log fields
    Name = 170,
    Priority = 171,
    StackWaterMark = 172,
    Networks = 173,
    Bssid = 174,
    IsEncrypt = 175,
    Msg = 176
};

} // namespace protocol
