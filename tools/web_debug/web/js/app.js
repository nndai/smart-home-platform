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
    if (mqttTopicOta && Utils.$('mqtt-topic-ota')) Utils.$('mqtt-topic-ota').value = mqttTopicOta;
    if (Utils.$('control-key')) Utils.$('control-key').value = controlKey;
    if (Utils.$('sender-id')) Utils.$('sender-id').value = senderId;

    // Attach listeners to save on change
    const inputs = ['ws-url', 'mqtt-broker', 'mqtt-port', 'mqtt-user', 'mqtt-pass', 'mqtt-topic-pub', 'mqtt-topic-sub', 'mqtt-topic-ota', 'control-key', 'sender-id'];
    inputs.forEach(id => {
      const el = Utils.$(id);
      if (el) {
        el.addEventListener('change', () => this.saveSettings());
      }
    });

    const keyEl = Utils.$('control-key');
    if (keyEl) {
      keyEl.addEventListener('input', () => this.updateControlKeyStatus());
    }

    this.updateControlKeyStatus();
    this.updateSeqDisplay();
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
    if (Utils.$('mqtt-topic-ota')) localStorage.setItem('rp_mqtt_topic_ota', Utils.$('mqtt-topic-ota').value);
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

  updateSeqDisplay() {
    const badge = Utils.$('seq-badge');
    if (badge) {
      const ts = Math.floor(Date.now() / 1000) + (7 * 3600);
      const seq = localStorage.getItem('rp_seq') || ts;
      badge.textContent = `Seq: ${seq}`;
    }
  },

  resetSeq() {
    const ts = Math.floor(Date.now() / 1000) + (7 * 3600);
    localStorage.setItem('rp_seq', ts.toString());
    this.updateSeqDisplay();
    Utils.toast('info', `Command Sequence (seq) set to timestamp: ${ts}`);
    this._logger.log(`[INFO] Command Sequence (seq) synced with timestamp: ${ts}`, 'info');
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
      const topic_pub = Utils.$('mqtt-topic-pub').value.trim() || 'pump/cmd';
      const topic_sub = Utils.$('mqtt-topic-sub').value.trim() || 'pump/log';
      const topic_ota_el = Utils.$('mqtt-topic-ota');
      const topic_ota = topic_ota_el ? topic_ota_el.value.trim() : 'pump/otachunk';

      if (!broker) {
        this._logger.log(`[ERROR] Please specify MQTT Broker IP/Domain`, 'error');
        this._autoConnectToDevice = false;
        return;
      }

      this._logger.log(`[INFO] Command Bridge to connect via MQTT: ${broker}:${port}`, 'info');
      const control_key = (localStorage.getItem('rp_control_key') || '').trim();
      const sender_id = (localStorage.getItem('rp_sender_id') || 'web-debug').trim() || 'web-debug';
      const current_seq = parseInt(localStorage.getItem('rp_seq') || '0', 10);
      this._wsManager.send(JSON.stringify({
        cmd: "bridgeConnectMqtt",
        broker: broker,
        port: port,
        user: user,
        password: password,
        topic_pub: topic_pub,
        topic_sub: topic_sub,
        topic_ota: topic_ota,
        control_key: control_key,
        sender_id: sender_id,
        current_seq: current_seq
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
    // Internal messages (from WebSocketManager)
    try {
      const data = JSON.parse(rawMessage);

      if (data._internal) {
        const tag = data.type === 'error' ? 'error' : 'info';
        this._logger.log(`[${data.type.toUpperCase()}] ${data.msg}`, tag);
        return;
      }

      if (typeof data === 'object' && data !== null) {
        if (typeof data.seq === 'number') {
          const cur = parseInt(localStorage.getItem('rp_seq') || '0', 10);
          if (data.seq > cur) {
            localStorage.setItem('rp_seq', data.seq.toString());
            this.updateSeqDisplay();
          }
        }

        const cmd = data.cmd;

        if (cmd === 'bridgeConnected') {
          this._onConnectionChange(true);
          this.scanBuildTargets();
          const protocol = document.querySelector('input[name="protocol"]:checked').value;
          if (protocol === 'mqtt') {
            this._sendRaw(JSON.stringify({cmd: 'setLogMqtt', payload: {enabled: true}}));
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

        // Hide progress messages from the generic log output
        if (cmd === 'bridgeProgress' || cmd === 'otaChunk') {
          return;
        }

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
          // If setRelay, we also might want to log it generically
          if (cmd !== 'getStatus') {
            this._logger.log(`[INFO] Command '${cmd}' response: ${data.status || 'unknown'}, state: ${data.state || 'unknown'}`, 'info');
          }
          return;
        }

        // Upload responses (shown in logs)
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
          // Reset UI buttons nếu uploader chưa kịp reset
          const uploadBtn = Utils.$('btn-upload');
          const sendBtn = Utils.$('btn-send');
          uploadBtn.querySelector('#btn-upload-text').textContent = 'Upload Firmware';
          if (this._wsManager && this._wsManager.connected) {
            sendBtn.disabled = false;
          }
          const progressEl = Utils.$('upload-progress');
          progressEl.classList.add('hidden');
          Utils.$('progress-bar').style.width = '0%';
          Utils.$('progress-text').textContent = '0%';
          return;
        }

        // Device log messages
        if (cmd === 'log') {
          const msg = data.msg || '';
          let tag = 'info';
          if (msg.includes('[ERROR]')) tag = 'error';
          else if (msg.includes('[WARN]')) tag = 'warn';
          else if (msg.includes('[DEBUG]')) tag = 'debug';
          this._logger.log(msg, tag);
          return;
        }

        // Generic JSON response
        this._logger.log(JSON.stringify(data, null, 2));
        return;
      }
    } catch (_) {
      // Not JSON — raw text
    }

    // Plain text message
    this._logger.log(rawMessage);
  },

  // ── Sending & Signing ──

  /**
   * Format and sign a JSON command with HMAC-SHA256 envelope if MQTT protocol is active.
   * Canonical: "seq|ts|cmd|payload|src"
   * HMAC: HMAC-SHA256(controlKey, canonical)
   *
   * @param {string|object} rawMsg JSON string or object
   * @returns {string} Signed JSON string or original message
   */
  formatAndSignCommand(rawMsg) {
    if (!rawMsg) return rawMsg;

    const protocolRadio = document.querySelector('input[name="protocol"]:checked');
    const protocol = protocolRadio ? protocolRadio.value : (localStorage.getItem('rp_protocol') || 'ws');

    // Only sign for MQTT protocol
    if (protocol !== 'mqtt') {
      return typeof rawMsg === 'string' ? rawMsg : JSON.stringify(rawMsg);
    }

    try {
      const data = typeof rawMsg === 'object' ? { ...rawMsg } : JSON.parse(rawMsg);
      if (typeof data !== 'object' || data === null) {
        return rawMsg;
      }

      const cmd = data.cmd;
      if (!cmd || typeof cmd !== 'string' || cmd.startsWith('bridge')) {
        return typeof rawMsg === 'string' ? rawMsg : JSON.stringify(data);
      }

      // If already signed, do not re-sign
      if (data.hmac) {
        return typeof rawMsg === 'string' ? rawMsg : JSON.stringify(data);
      }

      const controlKey = (localStorage.getItem('rp_control_key') || '').trim();
      if (!controlKey) {
        return typeof rawMsg === 'string' ? rawMsg : JSON.stringify(data);
      }

      if (controlKey.length !== 64 || !/^[0-9a-fA-F]{64}$/.test(controlKey)) {
        this._logger.log(`[WARN] Invalid controlKey (${controlKey.length}/64 hex chars). Sending unsigned command '${cmd}'.`, 'warn');
        return typeof rawMsg === 'string' ? rawMsg : JSON.stringify(data);
      }

      // Ensure payload object exists and is compact JSON
      if (data.payload === undefined) {
        data.payload = {};
      }
      const payloadCompact = typeof data.payload === 'object' && data.payload !== null
        ? JSON.stringify(data.payload)
        : (data.payload ? String(data.payload) : "{}");

      // Timestamp with UTC+7 offset (firmware local clock)
      const ts = Math.floor(Date.now() / 1000) + (7 * 3600);

      // Sequence number: always strictly increasing, tracking at least the current timestamp
      let currentSeq = parseInt(localStorage.getItem('rp_seq') || '0', 10);
      let seq = Math.max(ts, currentSeq + 1) >>> 0;
      localStorage.setItem('rp_seq', seq.toString());
      this.updateSeqDisplay();

      const src = (localStorage.getItem('rp_sender_id') || 'web-debug').trim() || 'web-debug';

      // Canonical string format: "seq|ts|cmd|payload|src"
      // Matching DeviceCommandEnvelope.kt and Crypto.h
      const canonical = `${seq}|${ts}|${cmd}|${payloadCompact}|${src}`;
      const hmac = Utils.hmacSha256Hex(controlKey, canonical);

      data.seq = seq;
      data.ts = ts;
      data.src = src;
      data.hmac = hmac;

      return JSON.stringify(data);
    } catch (_) {
      // Not a valid JSON, send as raw string
      return rawMsg;
    }
  },

  _sendRaw(text) {
    const toSend = this.formatAndSignCommand(text);
    this._wsManager.send(toSend);
  },

  sendCmd() {
    if (!this._wsManager.connected) return;
    const input = Utils.$('cmd-input');
    const text = input.value.trim();
    if (!text) return;

    const signed = this.formatAndSignCommand(text);
    if (this._wsManager.send(signed)) {
      this._logger.log(`>>> ${signed}`, 'sent');
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
