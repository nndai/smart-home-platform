/**
 * Smart Home Platform — Binary Protocol Engine (JavaScript)
 * 
 * Provides 100% parity with Firmware C++ (BinaryWriter/BinaryReader)
 * and Android Kotlin (BinaryProtocolParser).
 */

const BinaryProtocol = {
  MAGIC_START: 0xB7,
  MAGIC_END: 0xA5,
  TZ_OFFSET_SEC: 7 * 3600,

  isValidFrame(data) {
    if (!data || data.length < 3) return false;
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    return bytes[0] === this.MAGIC_START && bytes[bytes.length - 1] === this.MAGIC_END;
  },

  buildFrame(cmdId, payloadBytes) {
    const totalLen = payloadBytes.length + 3;
    const frame = new Uint8Array(totalLen);
    frame[0] = this.MAGIC_START;
    frame[1] = cmdId & 0xFF;
    frame.set(payloadBytes, 2);
    frame[totalLen - 1] = this.MAGIC_END;
    return frame;
  },

  parseFrame(data) {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    if (!this.isValidFrame(bytes)) return null;
    const cmdId = bytes[1];
    const payload = bytes.subarray(2, bytes.length - 1);
    return { cmdId, payload };
  }
};

const BinaryType = {
  NULL: 0,
  BOOL: 1,
  UINT8: 2,
  UINT16: 3,
  UINT32: 4,
  UINT64: 5,
  INT8: 6,
  INT16: 7,
  INT32: 8,
  INT64: 9,
  FLOAT32: 10,
  FLOAT64: 11,
  STRING: 12,
  BYTES: 13,
  OBJECT: 14,
  ARRAY: 15,

  isVariableSize(type) {
    return type === this.STRING || type === this.BYTES || type === this.OBJECT || type === this.ARRAY;
  },

  getFixedTypeSize(type) {
    switch (type) {
      case this.NULL: return 0;
      case this.BOOL:
      case this.UINT8:
      case this.INT8: return 1;
      case this.UINT16:
      case this.INT16: return 2;
      case this.UINT32:
      case this.INT32:
      case this.FLOAT32: return 4;
      case this.UINT64:
      case this.INT64:
      case this.FLOAT64: return 8;
      default: return 0;
    }
  }
};

const BinaryCommandIds = {
  Unknown: 0,
  GetStatus: 1,
  GetConfig: 2,
  SetConfig: 3,
  GetLog: 4,
  ClearSysLog: 5,
  OtaUrl: 6,
  Reboot: 7,
  FactoryReset: 8,
  SetLogMqtt: 9,
  GetLogMqtt: 10,
  GetLogStats: 11,
  GetSystemInfo: 12,
  UploadFirmwareStart: 13,
  UploadFirmwareEnd: 14,
  UploadFirmwareAbort: 15,
  OtaChunk: 16,
  ScanWifi: 17,
  GetScanWifiData: 18,
  Pair: 19,
  Provision: 20,
  Log: 27,
  ListDir: 30,
  ReadFile: 31,
  FileInfo: 32,
  DeleteItem: 33,
  FsInfo: 34,
  DownloadFile: 35,
  OtaProgress: 50,
  OtaResult: 51,
  BeginUploadFirmwareSuccess: 52,
  BeginUploadFirmwareFailed: 53,
  SetRelay: 100,
  Calibrate: 101,
  ResetCalibration: 102,
  ClearPumpFault: 103,

  commandIdToString(id) {
    const map = {
      1: "getStatus",
      2: "getConfig",
      3: "setConfig",
      4: "getLog",
      5: "clearSysLog",
      6: "otaUrl",
      7: "reboot",
      8: "factoryReset",
      9: "setLogMqtt",
      10: "getLogMqtt",
      11: "getLogStats",
      12: "getSystemInfo",
      13: "uploadFirmwareStart",
      14: "uploadFirmwareEnd",
      15: "uploadFirmwareAbort",
      16: "otaChunk",
      17: "scanWifi",
      18: "getScanWifiData",
      19: "pair",
      20: "provision",
      27: "log",
      30: "listDir",
      31: "readFile",
      32: "fileInfo",
      33: "deleteItem",
      34: "fsInfo",
      35: "downloadFile",
      50: "otaProgress",
      51: "otaResult",
      52: "beginUploadFirmwareSuccess",
      53: "beginUploadFirmwareFailed",
      100: "setRelay",
      101: "calibrate",
      102: "resetCalibration",
      103: "clearPumpFault"
    };
    return map[id] || "unknown";
  },

  stringToCommandId(str) {
    if (!str) return 0;
    const map = {
      "getStatus": 1,
      "getConfig": 2,
      "setConfig": 3,
      "getLog": 4,
      "clearSysLog": 5,
      "otaUrl": 6,
      "reboot": 7,
      "factoryReset": 8,
      "setLogMqtt": 9,
      "getLogMqtt": 10,
      "getLogStats": 11,
      "getSystemInfo": 12,
      "uploadFirmwareStart": 13,
      "uploadFirmwareEnd": 14,
      "uploadFirmwareAbort": 15,
      "otaChunk": 16,
      "scanWifi": 17,
      "getScanWifiData": 18,
      "pair": 19,
      "provision": 20,
      "log": 27,
      "listDir": 30,
      "readFile": 31,
      "fileInfo": 32,
      "deleteItem": 33,
      "fsInfo": 34,
      "downloadFile": 35,
      "otaProgress": 50,
      "otaResult": 51,
      "beginUploadFirmwareSuccess": 52,
      "beginUploadFirmwareFailed": 53,
      "setRelay": 100,
      "calibrate": 101,
      "resetCalibration": 102,
      "clearPumpFault": 103
    };
    return map[str] !== undefined ? map[str] : 0;
  }
};

const BinaryFieldIds = {
  NONE: 0,
  SYSTEM: 1,
  CHIP_ID: 2,
  CHIP_MODEL: 3,
  CPU_FREQ: 4,
  SDK_VERSION: 5,
  BUILD_TIME: 6,
  BUILD_UNIX_TIME: 7,
  UPTIME: 8,
  TIME_SYS: 9,
  RESET_REASON: 10,
  MEMORY: 20,
  FREE_HEAP: 21,
  MIN_EVER_FREE_HEAP: 22,
  MAX_ALLOC_HEAP: 23,
  WIFI: 30,
  RSSI: 31,
  SSID: 32,
  IP: 33,
  MAC: 34,
  CHANNEL: 35,
  CONN_MODE: 36,
  TEMPERATURE: 37,
  STORAGE: 40,
  FLASH_SIZE: 41,
  FS_TOTAL: 42,
  FS_USED: 43,
  FLASH_MODE: 44,
  FLASH_SPEED: 45,
  TASKS: 50,
  TASK: 51,
  TASK_NAME: 52,
  TASK_PRIORITY: 53,
  TASK_STACK_WATER_MARK: 54,
  TASK_STATE: 55,
  STATUS: 60,
  MESSAGE: 61,
  TIMESTAMP: 62,
  REQ_ID: 63,
  CMD: 64,
  PAYLOAD: 65,
  FIELDS: 66,
  LOG: 70,
  LOG_SIZE: 71,
  TOGGLE_LOG_SIZE: 72,
  POWER_LOG_SIZE: 73,
  TOTAL_BYTES: 74,
  USED_BYTES: 75,
  TIME_SYNCED: 76,
  ENABLED: 77,
  URL: 80,
  SIZE: 81,
  DATA: 82,
  PROGRESS: 83,
  TOTAL: 84,
  PCT: 85,
  RECEIVED: 86,
  MQTT_SERVER: 90,
  MQTT_PORT: 91,
  MQTT_USER: 92,
  MQTT_PASS: 93,
  MQTT_TOPIC: 94,
  WIFI_S_S_I_D: 95,
  WIFI_PASS: 96,
  DEBUG_S_S_I_D: 97,
  DEBUG_PASS: 98,
  DEBUG_IP: 99,
  DEBUG_GATEWAY: 100,
  DEBUG_NETMASK: 101,
  DEVICE_ID: 102,
  AP_S_S_I_D: 103,
  PROFILE: 104,
  PAIRING_STATE: 105,
  SYS_LOG_FILE_ENABLED: 106,
  SYS_LOG_FILE_LEVEL: 107,
  NEED_REBOOT: 108,
  STREAM: 109,
  SEQ: 110,
  TS: 111,
  HMAC: 112,
  SRC: 113,
  STATE: 120,
  RELAY: 121,
  ON_DURATION: 122,
  CURRENT: 123,
  POWER: 124,
  VOLTAGE: 125,
  DAILY_ENERGY: 126,
  HOURLY_ENERGY: 127,
  APPARENT: 128,
  PF: 129,
  PUMP_MODE: 130,
  PUMP_STATE_STR: 131,
  PUMP_STATE: 132,
  RELAY_START_MODE: 133,
  THRESH_OFF: 134,
  THRESH_NO_WATER: 135,
  THRESH_RUNNING: 136,
  THRESH_OVERLOAD: 137,
  DRY_TIMEOUT: 138,
  OVERLOAD_TIMEOUT: 139,
  C_CAL: 140,
  V_CAL: 141,
  P_CAL: 142,
  PUMP: 143,
  ENCODE: 150,
  ENTRIES: 151,
  LIMIT: 152,
  MORE: 153,
  OFFSET: 154,
  PATH: 155,
  TYPE: 156,
  CONTROL_KEY: 160,
  SYS_LOG_SIZE: 161,
  WIFI_DROP: 162,
  TARGET_ID: 163,
  TARGET_TYPE: 164,
  TARGET_KEY: 165,
  TARGET_PAIRED: 166,
  TARGET_ERROR: 167,
  NAME: 170,
  PRIORITY: 171,
  STACK_WATER_MARK: 172,
  NETWORKS: 173,
  BSSID: 174,
  IS_ENCRYPT: 175,
  MSG: 176,

  getName(id) {
    const map = {
      1: "system", 2: "chipId", 3: "chipModel", 4: "cpuFreq", 5: "sdkVersion", 6: "buildTime",
      7: "buildUnixTime", 8: "uptime", 9: "timeSys", 10: "resetReason", 20: "memory",
      21: "freeHeap", 22: "minEverFreeHeap", 23: "maxAllocHeap", 30: "wifi", 31: "rssi",
      32: "ssid", 33: "ip", 34: "mac", 35: "channel", 36: "connMode", 37: "temperature",
      40: "storage", 41: "flashSize", 42: "fsTotal", 43: "fsUsed", 44: "flashMode",
      45: "flashSpeed", 50: "tasks", 51: "task", 52: "taskName", 53: "taskPriority",
      54: "taskStackWaterMark", 55: "taskState", 60: "status", 61: "message", 62: "timestamp",
      63: "reqId", 64: "cmd", 65: "payload", 66: "fields", 70: "log", 71: "logSize",
      72: "toggleLogSize", 73: "powerLogSize", 74: "totalBytes", 75: "usedBytes",
      76: "timeSynced", 77: "enabled", 80: "url", 81: "size", 82: "data", 83: "progress",
      84: "total", 85: "pct", 86: "received", 90: "mqttServer", 91: "mqttPort",
      92: "mqttUser", 93: "mqttPass", 94: "mqttTopic", 95: "wifiSSID", 96: "wifiPass",
      97: "debugSSID", 98: "debugPass", 99: "debugIp", 100: "debugGateway", 101: "debugNetmask",
      102: "deviceId", 103: "apSSID", 104: "profile", 105: "pairingState", 106: "sysLogFileEnabled",
      107: "sysLogFileLevel", 108: "needReboot", 109: "stream", 110: "seq", 111: "ts",
      112: "hmac", 113: "src", 120: "state", 121: "relay", 122: "onDuration", 123: "current",
      124: "power", 125: "voltage", 126: "dailyEnergy", 127: "hourlyEnergy", 128: "apparent",
      129: "pf", 130: "pumpMode", 131: "pumpStateStr", 132: "pumpState", 133: "relayStartMode",
      134: "threshOff", 135: "threshNoWater", 136: "threshRunning", 137: "threshOverload",
      138: "dryTimeout", 139: "overloadTimeout", 140: "cCal", 141: "vCal", 142: "pCal",
      143: "pump", 150: "encode", 151: "entries", 152: "limit", 153: "more", 154: "offset",
      155: "path", 156: "type", 160: "controlKey", 161: "sysLogSize", 162: "wifiDrop",
      163: "targetId", 164: "targetType", 165: "targetKey", 166: "targetPaired",
      167: "targetError", 170: "name", 171: "priority", 172: "stackWaterMark",
      173: "networks", 174: "bssid", 175: "isEncrypt", 176: "msg"
    };
    return map[id] || `field_${id}`;
  },

  getId(name) {
    const map = {
      "system": 1, "chipId": 2, "chipModel": 3, "cpuFreq": 4, "sdkVersion": 5, "buildTime": 6,
      "buildUnixTime": 7, "uptime": 8, "timeSys": 9, "resetReason": 10, "memory": 20,
      "freeHeap": 21, "minEverFreeHeap": 22, "maxAllocHeap": 23, "wifi": 30, "rssi": 31,
      "ssid": 32, "wifiSsid": 95, "ip": 33, "mac": 34, "channel": 35, "connMode": 36, "temperature": 37,
      "storage": 40, "flashSize": 41, "fsTotal": 42, "fsUsed": 43, "flashMode": 44,
      "flashSpeed": 45, "tasks": 50, "task": 51, "taskName": 52, "taskPriority": 53,
      "taskStackWaterMark": 54, "taskState": 55, "status": 60, "message": 61, "timestamp": 62,
      "reqId": 63, "cmd": 64, "payload": 65, "fields": 66, "log": 70, "logSize": 71,
      "toggleLogSize": 72, "powerLogSize": 73, "totalBytes": 74, "usedBytes": 75,
      "timeSynced": 76, "enabled": 77, "url": 80, "size": 81, "data": 82, "progress": 83,
      "total": 84, "pct": 85, "received": 86, "mqttServer": 90, "mqttPort": 91,
      "mqttUser": 92, "mqttPass": 93, "mqttTopic": 94, "wifiSSID": 95, "wifiPass": 96,
      "debugSSID": 97, "debugPass": 98, "debugIp": 99, "debugGateway": 100, "debugNetmask": 101,
      "deviceId": 102, "apSSID": 103, "profile": 104, "pairingState": 105, "sysLogFileEnabled": 106,
      "sysLogFileLevel": 107, "needReboot": 108, "stream": 109, "seq": 110, "ts": 111,
      "hmac": 112, "src": 113, "state": 120, "relay": 121, "onDuration": 122, "current": 123,
      "power": 124, "voltage": 125, "dailyEnergy": 126, "hourlyEnergy": 127, "apparent": 128,
      "pf": 129, "pumpMode": 130, "pumpStateStr": 131, "pumpState": 132, "relayStartMode": 133,
      "threshOff": 134, "threshNoWater": 135, "threshRunning": 136, "threshOverload": 137,
      "dryTimeout": 138, "overloadTimeout": 139, "cCal": 140, "vCal": 141, "pCal": 142,
      "pump": 143, "encode": 150, "entries": 151, "limit": 152, "more": 153, "offset": 154,
      "path": 155, "type": 156, "controlKey": 160, "sysLogSize": 161, "wifiDrop": 162,
      "targetId": 163, "targetType": 164, "targetKey": 165, "targetPaired": 166,
      "targetError": 167, "name": 170, "priority": 171, "stackWaterMark": 172,
      "networks": 173, "bssid": 174, "isEncrypt": 175, "msg": 176
    };
    return map[name] !== undefined ? map[name] : 0;
  }
};

/**
 * BinaryWriter: dynamic buffer serialization for Binary Protocol.
 */
class BinaryWriter {
  constructor(initialCapacity = 128) {
    this.buffer = new Uint8Array(initialCapacity);
    this.offset = 0;
    this.containerStack = [];
  }

  _ensureCapacity(needed) {
    if (this.offset + needed <= this.buffer.length) return;
    let newCap = Math.max(this.buffer.length * 2, this.offset + needed);
    const newBuf = new Uint8Array(newCap);
    newBuf.set(this.buffer);
    this.buffer = newBuf;
  }

  _syncContainers() {
    for (let i = this.containerStack.length - 1; i >= 0; i--) {
      const hdrPos = this.containerStack[i];
      if (hdrPos + 3 <= this.offset) {
        const actualSize = this.offset - hdrPos - 3;
        const sizeField = actualSize & 0x7FF;
        this.buffer[hdrPos + 1] = (this.buffer[hdrPos + 1] & 0xF8) | ((sizeField >> 8) & 0x07);
        this.buffer[hdrPos + 2] = sizeField & 0xFF;
      }
    }
  }

  _writeHeader(id, type, size = 0) {
    if (BinaryType.isVariableSize(type)) {
      this._ensureCapacity(3);
      const header = ((id & 0x1FF) << 7) | ((type & 0x0F) << 3) | ((size >> 8) & 0x07);
      this.buffer[this.offset++] = (header >> 8) & 0xFF;
      this.buffer[this.offset++] = header & 0xFF;
      this.buffer[this.offset++] = size & 0xFF;
    } else {
      this._ensureCapacity(2);
      const header = ((id & 0x1FF) << 7) | ((type & 0x0F) << 3);
      this.buffer[this.offset++] = (header >> 8) & 0xFF;
      this.buffer[this.offset++] = header & 0xFF;
    }
  }

  writeNull(id = 0) {
    this._writeHeader(id, BinaryType.NULL);
    this._syncContainers();
  }

  writeBool(id, value) {
    this._writeHeader(id, BinaryType.BOOL);
    this._ensureCapacity(1);
    this.buffer[this.offset++] = value ? 1 : 0;
    this._syncContainers();
  }

  writeU8(id, value) {
    this._writeHeader(id, BinaryType.UINT8);
    this._ensureCapacity(1);
    this.buffer[this.offset++] = value & 0xFF;
    this._syncContainers();
  }

  writeU16(id, value) {
    this._writeHeader(id, BinaryType.UINT16);
    this._ensureCapacity(2);
    this.buffer[this.offset++] = (value >> 8) & 0xFF;
    this.buffer[this.offset++] = value & 0xFF;
    this._syncContainers();
  }

  writeU32(id, value) {
    this._writeHeader(id, BinaryType.UINT32);
    this._ensureCapacity(4);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 4);
    view.setUint32(0, value >>> 0, false); // Big Endian
    this.offset += 4;
    this._syncContainers();
  }

  writeU64(id, value) {
    this._writeHeader(id, BinaryType.UINT64);
    this._ensureCapacity(8);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 8);
    const bigVal = typeof value === 'bigint' ? value : BigInt(value);
    view.setBigUint64(0, bigVal, false); // Big Endian
    this.offset += 8;
    this._syncContainers();
  }

  writeI8(id, value) {
    this._writeHeader(id, BinaryType.INT8);
    this._ensureCapacity(1);
    this.buffer[this.offset++] = value & 0xFF;
    this._syncContainers();
  }

  writeI16(id, value) {
    this._writeHeader(id, BinaryType.INT16);
    this._ensureCapacity(2);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 2);
    view.setInt16(0, value, false);
    this.offset += 2;
    this._syncContainers();
  }

  writeI32(id, value) {
    this._writeHeader(id, BinaryType.INT32);
    this._ensureCapacity(4);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 4);
    view.setInt32(0, value, false);
    this.offset += 4;
    this._syncContainers();
  }

  writeI64(id, value) {
    this._writeHeader(id, BinaryType.INT64);
    this._ensureCapacity(8);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 8);
    const bigVal = typeof value === 'bigint' ? value : BigInt(value);
    view.setBigInt64(0, bigVal, false);
    this.offset += 8;
    this._syncContainers();
  }

  writeFloat32(id, value) {
    this._writeHeader(id, BinaryType.FLOAT32);
    this._ensureCapacity(4);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 4);
    view.setFloat32(0, value, false);
    this.offset += 4;
    this._syncContainers();
  }

  writeFloat64(id, value) {
    this._writeHeader(id, BinaryType.FLOAT64);
    this._ensureCapacity(8);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset + this.offset, 8);
    view.setFloat64(0, value, false);
    this.offset += 8;
    this._syncContainers();
  }

  writeString(id, str) {
    const bytes = typeof Utils !== 'undefined' && Utils.stringToUtf8Bytes
      ? Utils.stringToUtf8Bytes(str || '')
      : (typeof TextEncoder !== 'undefined' ? new TextEncoder().encode(str || '') : new Uint8Array(0));
    this._writeHeader(id, BinaryType.STRING, bytes.length);
    this._ensureCapacity(bytes.length);
    this.buffer.set(bytes, this.offset);
    this.offset += bytes.length;
    this._syncContainers();
  }

  writeBytes(id, bytes) {
    const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    this._writeHeader(id, BinaryType.BYTES, b.length);
    this._ensureCapacity(b.length);
    this.buffer.set(b, this.offset);
    this.offset += b.length;
    this._syncContainers();
  }

  beginObject(id = 0) {
    const hdrPos = this.offset;
    this._writeHeader(id, BinaryType.OBJECT, 0);
    this.containerStack.push(hdrPos);
    return {
      end: () => this.endObject()
    };
  }

  endObject() {
    this._syncContainers();
    this.containerStack.pop();
  }

  beginArray(id = 0) {
    const hdrPos = this.offset;
    this._writeHeader(id, BinaryType.ARRAY, 0);
    this.containerStack.push(hdrPos);
    return {
      end: () => this.endArray()
    };
  }

  endArray() {
    this._syncContainers();
    this.containerStack.pop();
  }

  toByteArray() {
    this._syncContainers();
    return this.buffer.slice(0, this.offset);
  }
}

/**
 * BinaryReader: TLV decoding for Binary Protocol.
 */
class BinaryReader {
  constructor(data) {
    this.data = data instanceof Uint8Array ? data : new Uint8Array(data);
    this.offset = 0;
  }

  hasRemaining() {
    return this.offset < this.data.length;
  }

  readHeader() {
    if (this.offset + 2 > this.data.length) return null;
    const header = (this.data[this.offset] << 8) | this.data[this.offset + 1];
    this.offset += 2;

    const id = (header >> 7) & 0x1FF;
    const type = (header >> 3) & 0x0F;
    const sizeHigh = header & 0x07;

    let size = 0;
    if (BinaryType.isVariableSize(type)) {
      if (this.offset >= this.data.length) return null;
      const sizeLow = this.data[this.offset++];
      size = (sizeHigh << 8) | sizeLow;
    } else {
      size = BinaryType.getFixedTypeSize(type);
    }

    return { id, type, size };
  }

  readBool() {
    if (this.offset >= this.data.length) return false;
    return this.data[this.offset++] !== 0;
  }

  readU8() {
    if (this.offset >= this.data.length) return 0;
    return this.data[this.offset++];
  }

  readU16() {
    if (this.offset + 2 > this.data.length) return 0;
    const v = (this.data[this.offset] << 8) | this.data[this.offset + 1];
    this.offset += 2;
    return v >>> 0;
  }

  readU32() {
    if (this.offset + 4 > this.data.length) return 0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 4);
    const v = view.getUint32(0, false);
    this.offset += 4;
    return v;
  }

  readU64() {
    if (this.offset + 8 > this.data.length) return 0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 8);
    const v = Number(view.getBigUint64(0, false));
    this.offset += 8;
    return v;
  }

  readI8() {
    if (this.offset >= this.data.length) return 0;
    const v = (this.data[this.offset++] << 24) >> 24;
    return v;
  }

  readI16() {
    if (this.offset + 2 > this.data.length) return 0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 2);
    const v = view.getInt16(0, false);
    this.offset += 2;
    return v;
  }

  readI32() {
    if (this.offset + 4 > this.data.length) return 0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 4);
    const v = view.getInt32(0, false);
    this.offset += 4;
    return v;
  }

  readI64() {
    if (this.offset + 8 > this.data.length) return 0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 8);
    const v = Number(view.getBigInt64(0, false));
    this.offset += 8;
    return v;
  }

  readFloat32() {
    if (this.offset + 4 > this.data.length) return 0.0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 4);
    const v = view.getFloat32(0, false);
    this.offset += 4;
    return v;
  }

  readFloat64() {
    if (this.offset + 8 > this.data.length) return 0.0;
    const view = new DataView(this.data.buffer, this.data.byteOffset + this.offset, 8);
    const v = view.getFloat64(0, false);
    this.offset += 8;
    return v;
  }

  readString(size) {
    if (this.offset + size > this.data.length) size = this.data.length - this.offset;
    const slice = this.data.subarray(this.offset, this.offset + size);
    this.offset += size;
    if (typeof TextDecoder !== 'undefined') {
      return new TextDecoder('utf-8').decode(slice);
    }
    let str = '';
    for (let i = 0; i < slice.length; i++) {
      str += String.fromCharCode(slice[i]);
    }
    return str;
  }

  readBytes(size) {
    if (this.offset + size > this.data.length) size = this.data.length - this.offset;
    const slice = this.data.slice(this.offset, this.offset + size);
    this.offset += size;
    return slice;
  }
}

/**
 * High-level JSON <-> Binary Converter and Serializer.
 */
const BinaryProtocolParser = {
  /**
   * Serializes a JavaScript Object / JSON into a Binary Protocol Frame (Uint8Array).
   */
  serialize(jsonObj) {
    if (typeof jsonObj === 'string') {
      try {
        jsonObj = JSON.parse(jsonObj);
      } catch (_) {
        return new Uint8Array(0);
      }
    }
    if (!jsonObj || typeof jsonObj !== 'object') return new Uint8Array(0);

    const cmdStr = jsonObj.cmd || '';
    const cmdId = BinaryCommandIds.stringToCommandId(cmdStr);

    const copy = { ...jsonObj };
    delete copy.cmd;

    // Hoist payload keys to root object if present
    if (copy.payload && typeof copy.payload === 'object' && !Array.isArray(copy.payload)) {
      Object.assign(copy, copy.payload);
      delete copy.payload;
    }

    const writer = new BinaryWriter();
    this._writeObject(writer, BinaryFieldIds.NONE, copy);
    const contentBytes = writer.toByteArray();

    return BinaryProtocol.buildFrame(cmdId, contentBytes);
  },

  _writeObject(writer, id, obj) {
    const container = writer.beginObject(id);
    for (const key of Object.keys(obj)) {
      const fieldId = BinaryFieldIds.getId(key);
      const val = obj[key];
      this._writeValue(writer, fieldId, val);
    }
    container.end();
  },

  _writeArray(writer, id, arr) {
    const container = writer.beginArray(id);
    for (let i = 0; i < arr.length; i++) {
      this._writeValue(writer, 0, arr[i]);
    }
    container.end();
  },

  _writeValue(writer, id, val) {
    if (val === null || val === undefined) {
      writer.writeNull(id);
    } else if (typeof val === 'boolean') {
      writer.writeBool(id, val);
    } else if (typeof val === 'number') {
      if (Number.isInteger(val)) {
        if (val >= 0 && val <= 0xFF) writer.writeU8(id, val);
        else if (val >= 0 && val <= 0xFFFF) writer.writeU16(id, val);
        else if (val >= 0 && val <= 0xFFFFFFFF) writer.writeU32(id, val);
        else writer.writeI32(id, val);
      } else {
        writer.writeFloat32(id, val);
      }
    } else if (typeof val === 'string') {
      if ((id === BinaryFieldIds.HMAC || id === BinaryFieldIds.CONTROL_KEY) && val.length === 64 && typeof Utils !== 'undefined' && Utils.hexToBytes) {
        const bytes = Utils.hexToBytes(val);
        if (bytes && bytes.length === 32) {
          writer.writeBytes(id, bytes);
          return;
        }
      } else if (id === BinaryFieldIds.TARGET_KEY && (val.length === 64 || val.length === 128) && typeof Utils !== 'undefined' && Utils.hexToBytes) {
        const bytes = Utils.hexToBytes(val);
        if (bytes && (bytes.length === 32 || bytes.length === 64)) {
          writer.writeBytes(id, bytes);
          return;
        }
      }
      writer.writeString(id, val);
    } else if (val instanceof Uint8Array || ArrayBuffer.isView(val)) {
      writer.writeBytes(id, val);
    } else if (Array.isArray(val)) {
      this._writeArray(writer, id, val);
    } else if (typeof val === 'object') {
      this._writeObject(writer, id, val);
    }
  },

  /**
   * Parses a Binary Protocol Frame (Uint8Array or ArrayBuffer) into a JavaScript Object.
   */
  parse(raw) {
    const bytes = raw instanceof Uint8Array ? raw : new Uint8Array(raw);
    const frame = BinaryProtocol.parseFrame(bytes);
    if (!frame) return null;

    const { cmdId, payload } = frame;
    const cmdStr = BinaryCommandIds.commandIdToString(cmdId);

    const reader = new BinaryReader(payload);
    const header = reader.readHeader();
    if (!header || header.id !== BinaryFieldIds.NONE || header.type !== BinaryType.OBJECT) {
      return null;
    }

    const obj = this._readObject(reader, header.size);
    if (cmdStr && cmdStr !== 'unknown') {
      obj.cmd = cmdStr;
    }

    return obj;
  },

  _readObject(reader, size) {
    const obj = {};
    const startOffset = reader.offset;
    while (reader.hasRemaining() && (reader.offset - startOffset) < size) {
      const header = reader.readHeader();
      if (!header) break;
      const key = BinaryFieldIds.getName(header.id);
      let val = this._readValue(reader, header.type, header.size);
      if (val !== undefined) {
        if (val instanceof Uint8Array && (header.id === BinaryFieldIds.CONTROL_KEY || header.id === BinaryFieldIds.TARGET_KEY || header.id === BinaryFieldIds.HMAC) && typeof Utils !== 'undefined' && Utils.bytesToHex) {
          val = Utils.bytesToHex(val);
        }
        obj[key] = val;
      }
    }
    return obj;
  },

  _readArray(reader, size) {
    const arr = [];
    const startOffset = reader.offset;
    while (reader.hasRemaining() && (reader.offset - startOffset) < size) {
      const header = reader.readHeader();
      if (!header) break;
      const val = this._readValue(reader, header.type, header.size);
      if (val !== undefined) {
        arr.push(val);
      }
    }
    return arr;
  },

  _readValue(reader, type, size) {
    switch (type) {
      case BinaryType.NULL: return null;
      case BinaryType.BOOL: return reader.readBool();
      case BinaryType.UINT8: return reader.readU8();
      case BinaryType.UINT16: return reader.readU16();
      case BinaryType.UINT32: return reader.readU32();
      case BinaryType.UINT64: return reader.readU64();
      case BinaryType.INT8: return reader.readI8();
      case BinaryType.INT16: return reader.readI16();
      case BinaryType.INT32: return reader.readI32();
      case BinaryType.INT64: return reader.readI64();
      case BinaryType.FLOAT32: return Math.round(reader.readFloat32() * 100) / 100;
      case BinaryType.FLOAT64: return Math.round(reader.readFloat64() * 100) / 100;
      case BinaryType.STRING: return reader.readString(size);
      case BinaryType.BYTES: return reader.readBytes(size);
      case BinaryType.OBJECT: return this._readObject(reader, size);
      case BinaryType.ARRAY: return this._readArray(reader, size);
      default: return undefined;
    }
  },

  /**
   * Signs a binary command frame with HMAC envelope if needed.
   * Canonical string format: "${ts}|${cmdStr}||${src}"
   *
   * @param {Uint8Array|ArrayBuffer} rawBinary 
   * @param {string} controlKeyHex 64-hex char key
   * @param {string} senderId Sender ID (default: "web-debug")
   * @returns {Uint8Array} Signed binary frame
   */
  signBinary(rawBinary, controlKeyHex, senderId = "web-debug") {
    if (!rawBinary || rawBinary.length < 3) return rawBinary;
    const parsed = this.parse(rawBinary);
    if (!parsed) return rawBinary;

    if (parsed.hmac) {
      return rawBinary; // already signed
    }

    if (!controlKeyHex || controlKeyHex.length !== 64 || !/^[0-9a-fA-F]{64}$/.test(controlKeyHex)) {
      return rawBinary;
    }

    const cmdStr = parsed.cmd || "";
    const ts = Math.floor(Date.now() / 1000) + BinaryProtocol.TZ_OFFSET_SEC;
    const src = (senderId || "web-debug").trim() || "web-debug";

    // Canonical format matching firmware: "ts|cmd||src"
    const canonical = `${ts}|${cmdStr}||${src}`;
    const hmacBytes = typeof Utils !== 'undefined' && Utils.hmacSha256Bytes
      ? Utils.hmacSha256Bytes(controlKeyHex, canonical)
      : (typeof Utils !== 'undefined' && Utils.hmacSha256Hex ? Utils.hexToBytes(Utils.hmacSha256Hex(controlKeyHex, canonical)) : null);

    if (!hmacBytes || hmacBytes.length !== 32) {
      return rawBinary;
    }

    parsed.ts = ts;
    parsed.src = src;
    parsed.hmac = hmacBytes;

    return this.serialize(parsed);
  }
};

// Export for Node.js test environment if applicable
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    BinaryProtocol,
    BinaryType,
    BinaryCommandIds,
    BinaryFieldIds,
    BinaryWriter,
    BinaryReader,
    BinaryProtocolParser
  };
}
