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
    const fwPath = localStorage.getItem('rp_fw_path');

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
    if (fwPath) Utils.$('fw-path').value = fwPath;

    // Attach listeners to save on change
    const inputs = ['ws-url', 'mqtt-broker', 'mqtt-port', 'mqtt-user', 'mqtt-pass', 'mqtt-topic-pub', 'mqtt-topic-sub', 'mqtt-topic-ota', 'fw-path'];
    inputs.forEach(id => {
      const el = Utils.$(id);
      if (el) {
        el.addEventListener('change', () => this.saveSettings());
      }
    });
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
    localStorage.setItem('rp_fw_path', Utils.$('fw-path').value);
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
      this._wsManager.send(JSON.stringify({
        cmd: "bridgeConnectMqtt",
        broker: broker,
        port: port,
        user: user,
        password: password,
        topic_pub: topic_pub,
        topic_sub: topic_sub,
        topic_ota: topic_ota
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
        const cmd = data.cmd;

        if (cmd === 'bridgeConnected') {
          this._onConnectionChange(true);
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

  // ── Sending ──

  _sendRaw(text) {
    this._wsManager.send(text);
  },

  sendCmd() {
    if (!this._wsManager.connected) return;
    const input = Utils.$('cmd-input');
    const text = input.value.trim();
    if (!text) return;
    if (this._wsManager.send(text)) {
      this._logger.log(`>>> ${text}`, 'sent');
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

  // ── Upload ──

  startUpload() {
    if (this._uploader.uploading) {
      this._uploader.stopUpload();
      return;
    }

    const fwPath = Utils.$('fw-path').value.trim();
    if (fwPath) {
      this._uploader.startLocalUpload(fwPath);
    } else {
      // Create file input to pick firmware
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
