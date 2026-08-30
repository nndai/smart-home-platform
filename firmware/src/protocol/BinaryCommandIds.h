#pragma once
#include <stdint.h>
#include <string.h>

namespace protocol {

/**
 * Command IDs for the Binary Protocol.
 * Maps top-level strings like "getStatus" to a single byte for highly efficient serialization.
 */
enum class CommandId : uint8_t {
    Unknown = 0,
    GetStatus = 1,
    GetConfig = 2,
    SetConfig = 3,
    GetLog = 4,
    ClearSysLog = 5,
    OtaUrl = 6,
    Reboot = 7,
    FactoryReset = 8,
    SetLogMqtt = 9,
    GetLogMqtt = 10,
    GetLogStats = 11,
    GetSystemInfo = 12,
    UploadFirmwareStart = 13,
    UploadFirmwareEnd = 14,
    UploadFirmwareAbort = 15,
    OtaChunk = 16,
    ScanWifi = 17,
    GetScanWifiData = 18,
    Pair = 19,
    Provision = 20,
    Log = 27,
    
    // File commands
    ListDir = 30,
    ReadFile = 31,
    FileInfo = 32,
    DeleteItem = 33,
    FsInfo = 34,
    DownloadFile = 35,

    // Response commands
    OtaProgress = 50,
    OtaResult = 51,
    BeginUploadFirmwareSuccess = 52,
    BeginUploadFirmwareFailed = 53,
    SetRelay = 100,
    Calibrate = 101,
    ResetCalibration = 102,
    ClearPumpFault = 103
};

/**
 * Converts a 1-byte command ID into its original string representation.
 * Necessary for bridging the binary protocol with the existing text-based dispatch logic.
 * 
 * @param id The command ID (e.g. 1)
 * @return The corresponding string (e.g. "getStatus"), or "unknown"
 */
inline const char* commandIdToString(uint8_t id) {
    switch (static_cast<CommandId>(id)) {
        case CommandId::GetStatus: return "getStatus";
        case CommandId::GetConfig: return "getConfig";
        case CommandId::SetConfig: return "setConfig";
        case CommandId::GetLog: return "getLog";
        case CommandId::ClearSysLog: return "clearSysLog";
        case CommandId::OtaUrl: return "otaUrl";
        case CommandId::Reboot: return "reboot";
        case CommandId::FactoryReset: return "factoryReset";
        case CommandId::SetLogMqtt: return "setLogMqtt";
        case CommandId::SetRelay: return "setRelay";
        case CommandId::Calibrate: return "calibrate";
        case CommandId::ResetCalibration: return "resetCalibration";
        case CommandId::ClearPumpFault: return "clearPumpFault";
        case CommandId::GetLogMqtt: return "getLogMqtt";
        case CommandId::GetLogStats: return "getLogStats";
        case CommandId::GetSystemInfo: return "getSystemInfo";
        case CommandId::UploadFirmwareStart: return "uploadFirmwareStart";
        case CommandId::UploadFirmwareEnd: return "uploadFirmwareEnd";
        case CommandId::UploadFirmwareAbort: return "uploadFirmwareAbort";
        case CommandId::OtaChunk: return "otaChunk";
        case CommandId::ScanWifi: return "scanWifi";
        case CommandId::GetScanWifiData: return "getScanWifiData";
        case CommandId::Pair: return "pair";
        case CommandId::Provision: return "provision";
        case CommandId::Log: return "log";
        case CommandId::ListDir: return "listDir";
        case CommandId::ReadFile: return "readFile";
        case CommandId::FileInfo: return "fileInfo";
        case CommandId::DeleteItem: return "deleteItem";
        case CommandId::FsInfo: return "fsInfo";
        case CommandId::DownloadFile: return "downloadFile";
        case CommandId::OtaProgress: return "otaProgress";
        case CommandId::OtaResult: return "otaResult";
        case CommandId::BeginUploadFirmwareSuccess: return "beginUploadFirmwareSuccess";
        case CommandId::BeginUploadFirmwareFailed: return "beginUploadFirmwareFailed";
        default: return "unknown";
    }
}

/**
 * Parses a string command into its highly compact 1-byte ID equivalent.
 * 
 * @param str The string-based command name (e.g. "getStatus")
 * @return The corresponding uint8_t Command ID
 */
inline uint8_t stringToCommandId(const char* str) {
    if (!str) return 0;
    if (strcmp(str, "getStatus") == 0) return static_cast<uint8_t>(CommandId::GetStatus);
    if (strcmp(str, "getConfig") == 0) return static_cast<uint8_t>(CommandId::GetConfig);
    if (strcmp(str, "setConfig") == 0) return static_cast<uint8_t>(CommandId::SetConfig);
    if (strcmp(str, "getLog") == 0) return static_cast<uint8_t>(CommandId::GetLog);
    if (strcmp(str, "clearSysLog") == 0) return static_cast<uint8_t>(CommandId::ClearSysLog);
    if (strcmp(str, "otaUrl") == 0) return static_cast<uint8_t>(CommandId::OtaUrl);
    if (strcmp(str, "reboot") == 0) return static_cast<uint8_t>(CommandId::Reboot);
    if (strcmp(str, "factoryReset") == 0) return static_cast<uint8_t>(CommandId::FactoryReset);
    if (strcmp(str, "setLogMqtt") == 0) return static_cast<uint8_t>(CommandId::SetLogMqtt);
    if (strcmp(str, "getLogMqtt") == 0) return static_cast<uint8_t>(CommandId::GetLogMqtt);
    if (strcmp(str, "getLogStats") == 0) return static_cast<uint8_t>(CommandId::GetLogStats);
    if (strcmp(str, "getSystemInfo") == 0) return static_cast<uint8_t>(CommandId::GetSystemInfo);
    if (strcmp(str, "uploadFirmwareStart") == 0) return static_cast<uint8_t>(CommandId::UploadFirmwareStart);
    if (strcmp(str, "uploadFirmwareEnd") == 0) return static_cast<uint8_t>(CommandId::UploadFirmwareEnd);
    if (strcmp(str, "uploadFirmwareAbort") == 0) return static_cast<uint8_t>(CommandId::UploadFirmwareAbort);
    if (strcmp(str, "otaChunk") == 0) return static_cast<uint8_t>(CommandId::OtaChunk);
    if (strcmp(str, "scanWifi") == 0) return static_cast<uint8_t>(CommandId::ScanWifi);
    if (strcmp(str, "getScanWifiData") == 0) return static_cast<uint8_t>(CommandId::GetScanWifiData);
    if (strcmp(str, "pair") == 0) return static_cast<uint8_t>(CommandId::Pair);
    if (strcmp(str, "provision") == 0) return static_cast<uint8_t>(CommandId::Provision);
    if (strcmp(str, "log") == 0) return static_cast<uint8_t>(CommandId::Log);
    if (strcmp(str, "listDir") == 0) return static_cast<uint8_t>(CommandId::ListDir);
    if (strcmp(str, "readFile") == 0) return static_cast<uint8_t>(CommandId::ReadFile);
    if (strcmp(str, "fileInfo") == 0) return static_cast<uint8_t>(CommandId::FileInfo);
    if (strcmp(str, "deleteItem") == 0) return static_cast<uint8_t>(CommandId::DeleteItem);
    if (strcmp(str, "fsInfo") == 0) return static_cast<uint8_t>(CommandId::FsInfo);
    if (strcmp(str, "downloadFile") == 0) return static_cast<uint8_t>(CommandId::DownloadFile);
    if (strcmp(str, "otaProgress") == 0) return static_cast<uint8_t>(CommandId::OtaProgress);
    if (strcmp(str, "otaResult") == 0) return static_cast<uint8_t>(CommandId::OtaResult);
    if (strcmp(str, "beginUploadFirmwareSuccess") == 0) return static_cast<uint8_t>(CommandId::BeginUploadFirmwareSuccess);
    if (strcmp(str, "beginUploadFirmwareFailed") == 0) return static_cast<uint8_t>(CommandId::BeginUploadFirmwareFailed);
    if (strcmp(str, "setRelay") == 0) return static_cast<uint8_t>(CommandId::SetRelay);
    if (strcmp(str, "calibrate") == 0) return static_cast<uint8_t>(CommandId::Calibrate);
    if (strcmp(str, "resetCalibration") == 0) return static_cast<uint8_t>(CommandId::ResetCalibration);
    if (strcmp(str, "clearPumpFault") == 0) return static_cast<uint8_t>(CommandId::ClearPumpFault);
    return 0;
}

} // namespace protocol
