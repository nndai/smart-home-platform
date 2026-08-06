/**
 * Dashboard Controller
 * Handles streaming getStatus data, connection monitoring, and pump control.
 */

class Dashboard {
  constructor(sendFn) {
    this._send = sendFn;
    this._isActive = false;
    this._streamTimer = null;
    this._watchdogTimer = null;
    this._lastRxTime = 0;
    this._isControlling = false;

    // DOM
    this._btnToggle = Utils.$('btn-pump-toggle');
    this._pumpIcon = Utils.$('pump-icon');
    this._pumpLabel = Utils.$('pump-label');
    this._pumpRing = Utils.$('pump-ring');
    this._pumpSpinner = Utils.$('pump-spinner');
    this._stateText = Utils.$('dash-state-text');
    this._connLost = Utils.$('dash-conn-lost');

    this._metrics = {
      voltage: Utils.$('dash-val-voltage'),
      current: Utils.$('dash-val-current'),
      power: Utils.$('dash-val-power'),
      energy: Utils.$('dash-val-energy'),
      temp: Utils.$('dash-val-temp'),
      rssi: Utils.$('dash-val-rssi')
    };

    // Bind events
    this._btnToggle.addEventListener('click', () => this.togglePump());
  }

  init() {
    this._isActive = true;
    this._startStream();
    this._startWatchdog();
  }

  destroy() {
    this._isActive = false;
    this._stopStream();
    this._stopWatchdog();
  }

  _startStream() {
    this._sendStreamRequest();
    this._streamTimer = setInterval(() => this._sendStreamRequest(), 60000); // every 1 min
  }

  _stopStream() {
    if (this._streamTimer) clearInterval(this._streamTimer);
    this._streamTimer = null;
  }

  _sendStreamRequest() {
    if (app._bridgeConnected) {
      this._send(JSON.stringify({ cmd: 'getStatus', payload: { stream: true } }));
    }
  }

  _startWatchdog() {
    this._watchdogTimer = setInterval(() => {
      if (!app._bridgeConnected) {
        this._connLost.classList.add('hidden');
        return;
      }
      
      const now = Date.now();
      if (this._lastRxTime > 0 && now - this._lastRxTime > 5000) {
        this._connLost.classList.remove('hidden');
        // Retry requesting stream
        this._sendStreamRequest();
      } else {
        this._connLost.classList.add('hidden');
      }
    }, 1000);
  }

  _stopWatchdog() {
    if (this._watchdogTimer) clearInterval(this._watchdogTimer);
    this._watchdogTimer = null;
  }

  /**
   * Handle incoming getStatus or turnOn/turnOff responses.
   */
  handleResponse(data) {
    if (data.cmd === 'getStatus') {
      this._lastRxTime = Date.now();
      this._connLost.classList.add('hidden');
      this._updateUI(data);
    } else if (data.cmd === 'setRelay') {
      // Hardware button or Web UI command response
      this._isControlling = false;
      this._pumpSpinner.classList.add('hidden');
      this._btnToggle.disabled = false;
      
      if (data.status === 'ok') {
         Utils.toast('success', `Pump turned ${data.state.toUpperCase()}`);
         // Immediately update UI to feel snappy!
         this._updatePumpState(data.state === 'on');
         // Request fresh getStatus to update metrics (delay 500ms to let sensors settle)
         setTimeout(() => {
           if (app._bridgeConnected) {
             this._send(JSON.stringify({ cmd: 'getStatus' }));
           }
         }, 500);
      } else {
         Utils.toast('error', `Control failed: ${data.message || 'unknown'}`);
      }
    }
  }

  _updateUI(data) {
    // Metrics
    if (data.voltage !== undefined) this._metrics.voltage.textContent = data.voltage.toFixed(1);
    if (data.current !== undefined) this._metrics.current.textContent = data.current.toFixed(2);
    if (data.power !== undefined) this._metrics.power.textContent = data.power.toFixed(1);
    if (data.energy !== undefined) this._metrics.energy.textContent = data.energy.toFixed(3);
    if (data.temperature !== undefined) this._metrics.temp.textContent = data.temperature.toFixed(1);
    if (data.rssi !== undefined) this._metrics.rssi.textContent = data.rssi;

    // Pump state
    if (data.relay !== undefined) {
      this._updatePumpState(data.relay);
    }

    // Detailed State text
    if (data.pumpStateStr) {
      this._stateText.textContent = data.pumpStateStr.replace('_', ' ');
      if (data.pumpStateStr === 'RUNNING_OK') this._stateText.className = 'text-green-400 font-bold';
      else if (data.pumpStateStr === 'OFF') this._stateText.className = 'text-gray-400 font-bold';
      else this._stateText.className = 'text-red-400 font-bold animate-pulse';
    }
  }

  _updatePumpState(isOn) {
    this._currentState = isOn;
    if (this._isControlling) return;

    if (isOn) {
      this._pumpIcon.classList.remove('text-gray-400');
      this._pumpIcon.classList.add('text-green-500');
      this._pumpLabel.classList.remove('text-gray-400');
      this._pumpLabel.classList.add('text-green-500');
      this._pumpLabel.textContent = 'ON';
      this._btnToggle.classList.remove('border-gray-600');
      this._btnToggle.classList.add('border-green-500');
      this._pumpRing.classList.add('border-green-500/30');
    } else {
      this._pumpIcon.classList.add('text-gray-400');
      this._pumpIcon.classList.remove('text-green-500');
      this._pumpLabel.classList.add('text-gray-400');
      this._pumpLabel.classList.remove('text-green-500');
      this._pumpLabel.textContent = 'OFF';
      this._btnToggle.classList.add('border-gray-600');
      this._btnToggle.classList.remove('border-green-500');
      this._pumpRing.classList.remove('border-green-500/30');
    }
  }

  togglePump() {
    if (this._isControlling) return;
    if (!app._bridgeConnected) {
      Utils.toast('error', 'Not connected to device');
      return;
    }

    this._isControlling = true;
    this._btnToggle.disabled = true;
    this._pumpSpinner.classList.remove('hidden');

    const nextState = !this._currentState;
    this._send(JSON.stringify({ 
      cmd: 'setRelay', 
      payload: { state: nextState } 
    }));
  }
}
