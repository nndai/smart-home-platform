/**
 * WebSocket connection manager.
 * Handles connect, disconnect, auto-reconnect, and message dispatch.
 */

class WebSocketManager {
  constructor() {
    this._ws = null;
    this._url = '';
    this._connected = false;
    this._wantConnect = false;
    this._reconnectTimer = null;
    this._onMessage = null;   // callback(data: string)
    this._onStateChange = null; // callback(connected: boolean)
  }

  get connected() {
    return this._connected;
  }

  get ws() {
    return this._ws;
  }

  /**
   * Connect to the WebSocket URL.
   */
  connect(url) {
    this._url = url;
    this._wantConnect = true;
    this._clearReconnect();
    this._doConnect();
  }

  /**
   * Disconnect and stop reconnecting.
   */
  disconnect() {
    this._wantConnect = false;
    this._clearReconnect();
    if (this._ws) {
      try { this._ws.close(); } catch (_) {}
      this._ws = null;
    }
    this._setConnected(false);
  }

  /**
   * Send a text message.
   */
  send(text) {
    if (this._connected && this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(text);
      return true;
    }
    return false;
  }

  /**
   * Send binary data.
   */
  sendBinary(data) {
    if (this._connected && this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(data);
      return true;
    }
    return false;
  }

  // ── Private ──

  _doConnect() {
    try {
      this._ws = new WebSocket(this._url);
    } catch (e) {
      if (this._onMessage) this._onMessage(JSON.stringify({ _internal: true, type: 'error', msg: `Connection error: ${e}` }));
      this._scheduleReconnect();
      return;
    }

    this._ws.binaryType = 'arraybuffer';

    this._ws.onopen = () => {
      this._setConnected(true);
    };

    this._ws.onmessage = (event) => {
      if (this._onMessage) {
        if (typeof event.data === 'string') {
          this._onMessage(event.data);
        }
        // Binary data ignored on monitor connection
      }
    };

    this._ws.onerror = () => {
      this._setConnected(false);
    };

    this._ws.onclose = () => {
      this._setConnected(false);
      if (this._wantConnect) {
        this._scheduleReconnect();
      }
    };
  }

  _setConnected(val) {
    const changed = this._connected !== val;
    this._connected = val;
    if (changed && this._onStateChange) {
      this._onStateChange(val);
    }
  }

  _scheduleReconnect() {
    if (!this._wantConnect) return;
    this._clearReconnect();
    if (this._onMessage) {
      this._onMessage(JSON.stringify({ _internal: true, type: 'info', msg: 'Reconnecting in 3s...' }));
    }
    this._reconnectTimer = setTimeout(() => {
      if (this._wantConnect) this._doConnect();
    }, 3000);
  }

  _clearReconnect() {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
  }
}
