/**
 * Main application controller.
 * Wires all modules together.
 */

const app = {
  _wsManager: null,
  _logger: null,
  _uploader: null,
  fileBrowser: null,
  debugInfo: null,
  dashboard: null,
  _currentTab: 'dashboard',
  _buildTargets: [],

  init() {
    this._logger = new Logger();
    this._wsManager = new WebSocketManager();
    this.fileBrowser = new FileBrowser((msg) => this._sendRaw(msg));
    this.debugInfo = new DebugInfo((msg) => this._sendRaw(msg));
    this.dashboard = new Dashboard((msg) => this._sendRaw(msg));
    this._uploader = new FirmwareUploader((msg) => this._sendRaw(msg), this._logger);

    // Wire WebSocket callbacks
    this._wsManager._onMessage = (data) => this._onMessage(data);
    this._wsManager._onStateChange = (connected) => {
      if (!connected) {
        this._onConnectionChange(false); // If bridge dies
      } else {
        // Tự động quét build targets ngay khi bridge kết nối
        this.scanBuildTargets();

        // Python Bridge connected. Auto-connect to MCU!
        if (this._autoConnectToDevice && !this._bridgeConnected) {
          this._doConnectToDevice();
        }
      }
    };

    this._logger.log('[INFO] Remote Pump Debug Tool ready', 'info');

    // Tự động kết nối ngầm tới Python Bridge ngay khi mở web
    this._wsManager.connect('ws://localhost:8080');
    this._bridgeConnected = false;
    this._autoConnectToDevice = true;

    this.loadSettings();
    this.updateSettingsUI();
  },

  // ── Settings ──

  loadSettings() {
    const protocol = localStorage.getItem('rp_protocol') || 'ws';
    const wsUrl = localStorage.getItem('rp_ws_url');
    const mqttBroker = localStorage.getItem('rp_mqtt_broker');
    const mqttPort = localStorage.getItem('rp_mqtt_port');
    const mqttUser = localStorage.getItem('rp_mqtt_user');
    const mqttPass = localStorage.getItem('rp_mqtt_pass');
    const mqttTopicPub = localStorage.getItem('rp_mqtt_topic_pub');
    const mqttTopicSub = localStorage.getItem('rp_mqtt_topic_sub');
    const mqttTopicOta = localStorage.getItem('rp_mqtt_topic_ota');
    const controlKey = localStorage.getItem('rp_control_key') || '';
    const senderId = localStorage.getItem('rp_sender_id') || 'web-debug';

    if (protocol) {
      const radio = document.querySelector(`input[name="protocol"][value="${protocol}"]`);
      if (radio) radio.checked = true;
    }
    if (wsUrl) Utils.$('ws-url').value = wsUrl;
    if (mqttBroker) Utils.$('mqtt-broker').value = mqttBroker;
    if (mqttPort) Utils.$('mqtt-port').value = mqttPort;
    if (mqttUser) Utils.$('mqtt-user').value = mqttUser;
    if (mqttPass) Utils.$('mqtt-pass').value = mqttPass;
    if (mqttTopicPub) Utils.$('mqtt-topic-pub').value = mqttTopicPub;
    if (mqttTopicSub) Utils.$('mqtt-topic-sub').value = mqttTopicSub;
    if (Utils.$('control-key')) Utils.$('control-key').value = controlKey;
    if (Utils.$('sender-id')) Utils.$('sender-id').value = senderId;

    // Attach listeners to save on change
    const inputs = ['ws-url', 'mqtt-broker', 'mqtt-port', 'mqtt-user', 'mqtt-pass', 'mqtt-topic-pub', 'mqtt-topic-sub', 'control-key', 'sender-id'];
    inputs.forEach(id => {
      const el = Utils.$(id);
      if (el) {
        el.addEventListener('change', () => this.saveSettings());
      }
    });

    const pubEl = Utils.$('mqtt-topic-pub');
    if (pubEl) {
      pubEl.addEventListener('input', () => {
        const val = pubEl.value.trim();
        if (val.endsWith('/cmd')) {
          const base = val.substring(0, val.length - 4);
          const subEl = Utils.$('mqtt-topic-sub');
          if (subEl && (!subEl.value || subEl.value === 'pump/log' || subEl.value.endsWith('/up') || subEl.value.endsWith('/log'))) {
            subEl.value = `${base}/up`;
          }
          this.saveSettings();
        }
      });
    }

    const keyEl = Utils.$('control-key');
    if (keyEl) {
      keyEl.addEventListener('input', () => this.updateControlKeyStatus());
    }

    this.updateControlKeyStatus();
  },

  saveSettings() {
    const protocol = document.querySelector('input[name="protocol"]:checked').value;
    localStorage.setItem('rp_protocol', protocol);
    localStorage.setItem('rp_ws_url', Utils.$('ws-url').value);
    localStorage.setItem('rp_mqtt_broker', Utils.$('mqtt-broker').value);
    localStorage.setItem('rp_mqtt_port', Utils.$('mqtt-port').value);
    localStorage.setItem('rp_mqtt_user', Utils.$('mqtt-user').value);
    localStorage.setItem('rp_mqtt_pass', Utils.$('mqtt-pass').value);
    localStorage.setItem('rp_mqtt_topic_pub', Utils.$('mqtt-topic-pub').value);
    localStorage.setItem('rp_mqtt_topic_sub', Utils.$('mqtt-topic-sub').value);
    if (Utils.$('control-key')) localStorage.setItem('rp_control_key', Utils.$('control-key').value.trim());
    if (Utils.$('sender-id')) localStorage.setItem('rp_sender_id', Utils.$('sender-id').value.trim() || 'web-debug');
    this.updateControlKeyStatus();
  },

  updateSettingsUI() {
    const protocol = document.querySelector('input[name="protocol"]:checked').value;
    const wsSettings = Utils.$('settings-ws');
    const mqttSettings = Utils.$('settings-mqtt');

    if (protocol === 'ws') {
      wsSettings.classList.remove('hidden');
      mqttSettings.classList.add('hidden');
    } else {
      wsSettings.classList.add('hidden');
      mqttSettings.classList.remove('hidden');
    }
    this.saveSettings();
  },

  updateControlKeyStatus() {
    const keyEl = Utils.$('control-key');
    const badge = Utils.$('control-key-badge');
    const lenEl = Utils.$('control-key-len');
    if (!keyEl || !badge || !lenEl) return;

    const val = keyEl.value.trim();
    lenEl.textContent = `${val.length}/64`;

    if (val.length === 0) {
      badge.textContent = 'Not Set';
      badge.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-gray-500/10 text-gray-400 border border-gray-500/20';
      lenEl.className = 'text-[10px] font-mono text-gray-500';
    } else if (val.length === 64 && /^[0-9a-fA-F]{64}$/.test(val)) {
      badge.textContent = '32B Key Valid';
      badge.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20';
      lenEl.className = 'text-[10px] font-mono text-emerald-400';
    } else {
      badge.textContent = 'Invalid Key';
      badge.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-rose-500/10 text-rose-400 border border-rose-500/20';
      lenEl.className = 'text-[10px] font-mono text-rose-400';
    }
  },

  toggleControlKeyVisibility() {
    const input = Utils.$('control-key');
    const eye = Utils.$('control-key-eye');
    if (!input) return;

    if (input.type === 'password') {
      input.type = 'text';
      if (eye) eye.setAttribute('data-lucide', 'eye-off');
    } else {
      input.type = 'password';
      if (eye) eye.setAttribute('data-lucide', 'eye-outline');
    }
    Utils.refreshIcons();
  },

  // ── Connection ──

  toggleConnect() {
    if (!this._wsManager.connected) {
      this._logger.log('[ERROR] Local Bridge is not running. Please start bridge_server.py', 'error');
      return;
    }

    if (this._bridgeConnected) {
      this._autoConnectToDevice = false; // Người dùng chủ động ngắt
      this._logger.log('[INFO] Disconnecting from device...', 'info');
      this._wsManager.send(JSON.stringify({ cmd: "bridgeDisconnect" }));
    } else {
      this._autoConnectToDevice = true; // Người dùng chủ động kết nối
      if (this._reconnectTimer) {
        clearTimeout(this._reconnectTimer);
        this._reconnectTimer = null;
      }
      this._doConnectToDevice();
    }
  },

  _doConnectToDevice() {
    const protocol = document.querySelector('input[name="protocol"]:checked').value;
    if (protocol === 'ws') {
      let url = Utils.$('ws-url').value.trim();
      if (!url) url = 'ws://192.168.137.111:82';
      if (!url.startsWith('ws://') && !url.startsWith('wss://')) url = 'ws://' + url;
      this._logger.log(`[INFO] Command Bridge to connect via WS: ${url}`, 'info');
      this._wsManager.send(JSON.stringify({ cmd: "bridgeConnect", url: url }));
    } else if (protocol === 'mqtt') {
      const broker = Utils.$('mqtt-broker').value.trim();
      const port = parseInt(Utils.$('mqtt-port').value.trim()) || 1883;
      const user = Utils.$('mqtt-user').value.trim();
      const password = Utils.$('mqtt-pass').value.trim();
      const topic_pub = Utils.$('mqtt-topic-pub').value.trim() || 'devices/pump-ln882h/cmd';
      const topic_sub = Utils.$('mqtt-topic-sub').value.trim() || 'devices/pump-ln882h/up';

      if (!broker) {
        this._logger.log(`[ERROR] Please specify MQTT Broker IP/Domain`, 'error');
        this._autoConnectToDevice = false;
        return;
      }

      this._logger.log(`[INFO] Command Bridge to connect via MQTT: ${broker}:${port}`, 'info');
      const control_key = (localStorage.getItem('rp_control_key') || '').trim();
      const sender_id = (localStorage.getItem('rp_sender_id') || 'web-debug').trim() || 'web-debug';
      this._wsManager.send(JSON.stringify({
        cmd: "bridgeConnectMqtt",
        broker: broker,
        port: port,
        user: user,
        password: password,
        topic_pub: topic_pub,
        topic_sub: topic_sub,
        control_key: control_key,
        sender_id: sender_id
      }));
    }
  },

  _onConnectionChange(connected) {
    this._bridgeConnected = connected;
    const dot = Utils.$('status-dot');
    const text = Utils.$('status-text');
    const btnText = Utils.$('btn-connect-text');
    const btn = Utils.$('btn-connect');
    const sendBtn = Utils.$('btn-send');
    const uploadBtn = Utils.$('btn-upload');
    const bar = Utils.$('connection-bar');

    if (connected) {
      if (this._reconnectTimer) {
        clearTimeout(this._reconnectTimer);
        this._reconnectTimer = null;
      }

      dot.className = 'w-2 h-2 rounded-full bg-green-500 shadow-[0_0_6px_rgba(34,197,94,0.5)]';
      text.textContent = 'Device Connected';
      text.className = 'text-xs font-medium text-green-400';
      btnText.textContent = 'Disconnect';
      btn.className = btn.className.replace('bg-accent hover:bg-accent-dark', 'bg-red-500/80 hover:bg-red-500');
      sendBtn.disabled = false;
      uploadBtn.disabled = false;
      bar.classList.add('status-connected');

      this._logger.log('[INFO] Connected', 'info');
      Utils.toast('success', 'Connected to device');

      // Auto-refresh file browser on connect
      setTimeout(() => this.fileBrowser.refresh(), 500);

      // Start dashboard if it's the active tab
      if (this._currentTab === 'dashboard' && this.dashboard) {
        this.dashboard.init();
      }
    } else {
      dot.className = 'w-2 h-2 rounded-full bg-red-500';
      text.textContent = 'Disconnected';
      text.className = 'text-xs font-medium text-gray-400';
      btnText.textContent = 'Connect';
      btn.className = btn.className.replace('bg-red-500/80 hover:bg-red-500', 'bg-accent hover:bg-accent-dark');
      sendBtn.disabled = true;
      uploadBtn.disabled = true;
      bar.classList.remove('status-connected');

      if (this._autoConnectToDevice && this._wsManager.connected) {
        if (!this._reconnectTimer) {
          const protocol = document.querySelector('input[name="protocol"]:checked').value;
          const delay = protocol === 'ws' ? 5000 : 10000;
          this._logger.log(`[INFO] Auto-reconnecting in ${delay / 1000}s...`, 'info');
          this._reconnectTimer = setTimeout(() => {
            this._reconnectTimer = null;
            if (this._autoConnectToDevice && this._wsManager.connected && !this._bridgeConnected) {
              this._doConnectToDevice();
            }
          }, delay);
        }
      }

      if (this.dashboard) {
        this.dashboard.destroy();
      }
    }
  },

  // ── Message handling ──

  _onMessage(rawMessage) {
    // 1. Binary Protocol Frame (from MCU via WebSocket or MQTT)
    if (rawMessage instanceof ArrayBuffer || rawMessage instanceof Uint8Array) {
      const data = BinaryProtocolParser.parse(rawMessage);
      if (data) {
        this._dispatchDeviceResponse(data);
        return;
      }
    }

    // 2. Text / JSON Message (from Bridge Server or Fallback)
    try {
      const data = typeof rawMessage === 'string' ? JSON.parse(rawMessage) : rawMessage;

      if (data._internal) {
        const tag = data.type === 'error' ? 'error' : 'info';
        this._logger.log(`[${data.type.toUpperCase()}] ${data.msg}`, tag);
        return;
      }

      if (typeof data === 'object' && data !== null) {
        const cmd = data.cmd || '';

        // Bridge Internal Events
        if (cmd === 'bridgeConnected') {
          this._onConnectionChange(true);
          this.scanBuildTargets();
          const protocol = document.querySelector('input[name="protocol"]:checked')?.value || 'ws';
          if (protocol === 'mqtt') {
            this._sendRaw({ cmd: 'setLogMqtt', payload: { enabled: true } });
          }
          return;
        }
        if (cmd === 'bridgeDisconnected') {
          this._onConnectionChange(false);
          return;
        }
        if (cmd === 'bridgeBuildTargets') {
          this.handleBuildTargets(data);
          return;
        }

        // Hide background progress spam from logs
        if (cmd === 'bridgeProgress' || cmd === 'otaChunk') {
          return;
        }

        this._dispatchDeviceResponse(data);
        return;
      }
    } catch (_) {
      // Not JSON — raw text
    }

    // Plain text message
    if (typeof rawMessage === 'string') {
      this._logger.log(rawMessage);
    }
  },

  _dispatchDeviceResponse(data) {
    const cmd = data.cmd;

    // File browser responses
    const fileCmds = new Set(['listDir', 'readFile', 'fileInfo', 'deleteItem', 'fsInfo', 'downloadFile']);
    if (fileCmds.has(cmd)) {
      this.fileBrowser.handleResponse(data);
      return;
    }

    // Debug info response
    if (cmd === 'getSystemInfo') {
      this.debugInfo.handleResponse(data);
      return;
    }

    // Dashboard responses
    if (cmd === 'getStatus' || cmd === 'setRelay') {
      if (this.dashboard) this.dashboard.handleResponse(data);
      if (cmd !== 'getStatus') {
        this._logger.log(`[INFO] Command '${cmd}' response: ${data.status || 'unknown'}, state: ${data.state !== undefined ? data.state : 'unknown'}`, 'info');
      }
      return;
    }

    // Upload responses
    if (cmd === 'beginUploadFirmwareSuccess') {
      this._logger.log('[INFO] Device ready for firmware data', 'info');
      return;
    }
    if (cmd === 'beginUploadFirmwareFailed') {
      this._logger.log(`[ERROR] Device rejected: ${data.message || ''}`, 'error');
      return;
    }
    if (cmd === 'otaResult') {
      if (data.status === 'ok') {
        this._logger.log('[SUCCESS] Firmware flashed!', 'success');
      } else {
        this._logger.log(`[ERROR] Flash failed: ${data.message || ''}`, 'error');
      }
      return;
    }
    if (cmd === 'otaError') {
      this._logger.log(`[ERROR] ${data.message || 'Lỗi OTA'}`, 'error');
      const uploadBtn = Utils.$('btn-upload');
      const sendBtn = Utils.$('btn-send');
      if (uploadBtn) uploadBtn.querySelector('#btn-upload-text').textContent = 'Upload Firmware';
      if (this._wsManager && this._wsManager.connected && sendBtn) {
        sendBtn.disabled = false;
      }
      const progressEl = Utils.$('upload-progress');
      if (progressEl) progressEl.classList.add('hidden');
      if (Utils.$('progress-bar')) Utils.$('progress-bar').style.width = '0%';
      if (Utils.$('progress-text')) Utils.$('progress-text').textContent = '0%';
      return;
    }

    // Device log messages
    if (cmd === 'log') {
      const msg = data.msg || data.message || '';
      let tag = 'info';
      if (msg.includes('[ERROR]')) tag = 'error';
      else if (msg.includes('[WARN]')) tag = 'warn';
      else if (msg.includes('[DEBUG]')) tag = 'debug';
      this._logger.log(msg, tag);
      return;
    }

    // Generic JSON response
    this._logger.log(JSON.stringify(data, null, 2));
  },

  // ── Sending & Binary Protocol Serialization ──

  /**
   * Serializes and optionally signs a device command into a binary frame,
   * then sends it over WebSocket.
   *
   * @param {string|object|Uint8Array|ArrayBuffer} msg 
   */
  _sendRaw(msg) {
    if (!this._wsManager || !this._wsManager.connected) return;

    // 1. Internal Bridge Commands (JSON string)
    if (typeof msg === 'string' && msg.includes('"bridge')) {
      this._wsManager.send(msg);
      return;
    }
    if (typeof msg === 'object' && msg !== null && !(msg instanceof Uint8Array) && !(msg instanceof ArrayBuffer) && msg.cmd && msg.cmd.startsWith('bridge')) {
      this._wsManager.send(JSON.stringify(msg));
      return;
    }

    // 2. Device Binary Frame
    const protocolRadio = document.querySelector('input[name="protocol"]:checked');
    const protocol = protocolRadio ? protocolRadio.value : (localStorage.getItem('rp_protocol') || 'ws');
    const controlKey = (localStorage.getItem('rp_control_key') || '').trim();
    const senderId = (localStorage.getItem('rp_sender_id') || 'web-debug').trim() || 'web-debug';

    let binaryFrame;
    if (msg instanceof Uint8Array || msg instanceof ArrayBuffer) {
      binaryFrame = msg instanceof Uint8Array ? msg : new Uint8Array(msg);
    } else {
      binaryFrame = BinaryProtocolParser.serialize(msg);
    }

    // 3. Sign Binary Frame if MQTT
    if (protocol === 'mqtt') {
      binaryFrame = BinaryProtocolParser.signBinary(binaryFrame, controlKey, senderId);
    }

    this._wsManager.sendBinary(binaryFrame);
  },

  /**
   * Handle user-entered raw command in the log console.
   * Accepts JSON commands (e.g. {"cmd":"getStatus"} or {"cmd":"setRelay","state":true})
   * or command names (e.g. getStatus), converts them to Binary Protocol frames, signs if MQTT,
   * and dispatches them.
   */
  sendCmd() {
    if (!this._wsManager.connected) {
      Utils.toast('error', 'Not connected to bridge');
      return;
    }
    const input = Utils.$('cmd-input');
    const text = input.value.trim();
    if (!text) return;

    let jsonObj = null;
    try {
      jsonObj = JSON.parse(text);
    } catch (_) {
      // Allow shorthand e.g. "getStatus" -> {"cmd":"getStatus"}
      if (/^[a-zA-Z0-9_]+$/.test(text)) {
        jsonObj = { cmd: text };
      } else {
        this._logger.log(`[ERROR] Invalid JSON: ${text}`, 'error');
        Utils.toast('error', 'Invalid JSON syntax');
        return;
      }
    }

    const protocolRadio = document.querySelector('input[name="protocol"]:checked');
    const protocol = protocolRadio ? protocolRadio.value : (localStorage.getItem('rp_protocol') || 'ws');
    const controlKey = (localStorage.getItem('rp_control_key') || '').trim();
    const senderId = (localStorage.getItem('rp_sender_id') || 'web-debug').trim() || 'web-debug';

    let binaryFrame = BinaryProtocolParser.serialize(jsonObj);
    if (protocol === 'mqtt') {
      binaryFrame = BinaryProtocolParser.signBinary(binaryFrame, controlKey, senderId);
    }

    if (this._wsManager.sendBinary(binaryFrame)) {
      const hexPreview = Array.from(binaryFrame.slice(0, 16)).map(b => b.toString(16).padStart(2, '0')).join(' ');
      this._logger.log(`>>> [JSON -> Binary: ${jsonObj.cmd || 'cmd'}, ${binaryFrame.length}B] ${JSON.stringify(jsonObj)}`, 'sent');
      input.value = '';
    } else {
      this._logger.log('[ERROR] Send failed', 'error');
    }
  },

  // ── Tabs ──

  switchTab(tabName) {
    this._currentTab = tabName;

    // Update buttons and indicator
    const btns = document.querySelectorAll('.tab-btn');
    btns.forEach(btn => {
      const match = btn.dataset.tab === tabName;
      if (match) {
        btn.classList.add('active', 'text-accent');
        btn.classList.remove('text-gray-400', 'hover:text-gray-200');
        
        // Move indicator
        const indicator = document.getElementById('tab-indicator');
        if (indicator) {
          indicator.style.width = `${btn.offsetWidth}px`;
          indicator.style.left = `${btn.offsetLeft}px`;
        }
      } else {
        btn.classList.remove('active', 'text-accent');
        btn.classList.add('text-gray-400', 'hover:text-gray-200');
      }
    });
    
    if (tabName === 'system' && this.debugInfo) {
      this.debugInfo.refresh();
    }

    // Update panels
    document.querySelectorAll('.tab-panel').forEach(panel => {
      const match = panel.id === `panel-${tabName}`;
      panel.style.display = match ? 'flex' : 'none';
    });

    if (tabName === 'dashboard' && this.dashboard && this._bridgeConnected) {
      this.dashboard.init();
    } else if (tabName !== 'dashboard' && this.dashboard) {
      this.dashboard.destroy();
    }
  },

  // ── Log ──

  clearLog() {
    this._logger.clear();
  },

  // ── Firmware Build Targets ──

  scanBuildTargets() {
    if (this._wsManager && this._wsManager.connected) {
      const scanBtn = Utils.$('btn-scan-fw');
      if (scanBtn) {
        const icon = scanBtn.querySelector('i');
        if (icon) icon.classList.add('animate-spin');
        setTimeout(() => { if (icon) icon.classList.remove('animate-spin'); }, 600);
      }
      this._sendRaw(JSON.stringify({ cmd: 'bridgeScanBuildTargets' }));
    }
  },

  handleBuildTargets(data) {
    this._buildTargets = data.targets || [];
    const select = Utils.$('fw-target-select');
    const badge = Utils.$('fw-build-dir-badge');
    if (badge && data.buildDir) {
      badge.textContent = data.buildDir;
    }

    if (!select) return;

    select.innerHTML = '';
    if (this._buildTargets.length === 0) {
      const opt = document.createElement('option');
      opt.value = '';
      opt.textContent = 'No build targets found (build project first)';
      opt.disabled = true;
      opt.selected = true;
      select.appendChild(opt);
      this.updateFirmwareInfoCard(null);
      return;
    }

    const savedTarget = localStorage.getItem('rp_fw_target');
    let selectedIdx = 0;

    this._buildTargets.forEach((target, idx) => {
      const opt = document.createElement('option');
      opt.value = target.name;
      const sizeStr = target.exists ? Utils.formatBytes(target.size) : 'not found';
      opt.textContent = `${target.name} [${target.file} • ${sizeStr}]`;
      select.appendChild(opt);

      if (savedTarget && target.name === savedTarget) {
        selectedIdx = idx;
      }
    });

    select.selectedIndex = selectedIdx;
    this.onTargetSelected(this._buildTargets[selectedIdx].name);
    if (window.lucide) lucide.createIcons();
  },

  onTargetSelected(targetName) {
    if (!targetName) return;
    localStorage.setItem('rp_fw_target', targetName);
    const target = (this._buildTargets || []).find(t => t.name === targetName);
    this.updateFirmwareInfoCard(target);
  },

  updateFirmwareInfoCard(target) {
    const nameEl = Utils.$('fw-info-filename');
    const statusEl = Utils.$('fw-info-status');
    const sizeEl = Utils.$('fw-info-size');
    const mtimeEl = Utils.$('fw-info-mtime');
    const pathEl = Utils.$('fw-info-path');

    if (!target) {
      if (nameEl) nameEl.textContent = 'firmware.bin';
      if (statusEl) {
        statusEl.textContent = 'Not Found';
        statusEl.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-rose-500/10 text-rose-400 border border-rose-500/20';
      }
      if (sizeEl) sizeEl.textContent = '-';
      if (mtimeEl) mtimeEl.textContent = '-';
      if (pathEl) pathEl.textContent = 'No build target selected';
      return;
    }

    if (nameEl) nameEl.textContent = target.file || 'firmware.bin';
    if (statusEl) {
      if (target.exists) {
        statusEl.textContent = 'Ready';
        statusEl.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20';
      } else {
        statusEl.textContent = 'Missing .bin';
        statusEl.className = 'px-2 py-0.5 rounded-full text-[10px] font-semibold bg-amber-500/10 text-amber-400 border border-amber-500/20';
      }
    }
    if (sizeEl) sizeEl.textContent = target.exists ? Utils.formatBytes(target.size) : '0 B';
    if (mtimeEl) mtimeEl.textContent = target.exists ? target.mtimeStr : 'Not built yet';
    if (pathEl) pathEl.textContent = target.relPath || target.path || '';
  },

  getSelectedBuildTarget() {
    const select = Utils.$('fw-target-select');
    if (!select || !select.value) return null;
    return (this._buildTargets || []).find(t => t.name === select.value) || null;
  },

  // ── Upload ──

  startUpload() {
    if (this._uploader.uploading) {
      this._uploader.stopUpload();
      return;
    }

    const target = this.getSelectedBuildTarget();
    if (target && target.exists && target.path) {
      this._uploader.startLocalUpload(target.path);
    } else if (target && !target.exists) {
      Utils.toast('error', `Firmware file not found in ${target.name}. Please compile with PlatformIO first.`);
    } else {
      // Fallback: create file input to pick firmware manually
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.uf2,.bin,.hex';
      input.addEventListener('change', () => {
        if (input.files.length > 0) {
          this._uploader.startUpload(input.files[0]);
        }
      });
      input.click();
    }
  },
};

// Boot
document.addEventListener('DOMContentLoaded', () => {
  app.init();
});
