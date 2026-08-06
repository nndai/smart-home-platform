/**
 * Firmware uploader.
 * Uses a separate WebSocket connection for binary upload.
 */

class FirmwareUploader {
  constructor(sendFn, logger) {
    this._sendMonitor = sendFn;
    this._logger = logger;
    this._uploading = false;
    this._cancelled = false;
    this._uploadWs = null;
  }

  get uploading() {
    return this._uploading;
  }

  /**
   * Start firmware upload from a File object.
   * @param {File} file - The firmware file
   */
  async startUpload(file) {
    if (this._uploading) {
      Utils.toast('warn', 'Upload already in progress');
      return;
    }

    if (!app || !app._wsManager || !app._wsManager.connected) {
      Utils.toast('error', 'Not connected to device');
      return;
    }

    this._uploading = true;
    this._cancelled = false;
    const fileSize = file.size;
    const ws = app._wsManager.ws;

    this._logger.log(`[INFO] Firmware: ${file.name} (${Utils.formatBytes(fileSize)})`, 'info');

    // Show progress
    const progressEl = Utils.$('upload-progress');
    const progressBar = Utils.$('progress-bar');
    const progressText = Utils.$('progress-text');
    const uploadBtn = Utils.$('btn-upload');
    const sendBtn = Utils.$('btn-send');

    progressEl.classList.remove('hidden');
    progressBar.style.width = '0%';
    progressText.textContent = '0%';
    uploadBtn.querySelector('#btn-upload-text').textContent = 'Stop Upload';
    sendBtn.disabled = true;

    try {
      // Send start command using the main connection
      this._sendMonitor(JSON.stringify({ cmd: 'uploadFirmwareStart', payload: { size: fileSize } }));
      this._logger.log('[INFO] Sent uploadFirmwareStart', 'info');

      // Wait for begin response
      const startResp = await this._waitForResponse(ws, 'beginUploadFirmwareSuccess', 'beginUploadFirmwareFailed');
      if (!startResp || startResp.cmd === 'beginUploadFirmwareFailed') {
        throw new Error(`Device refused: ${startResp?.message || 'unknown error'}`);
      }

      const fileData = await file.arrayBuffer();
      const bytes = new Uint8Array(fileData);
      const startTime = Date.now();

      // Đẩy nguyên 1 cục to sang cho Python xử lý chia nhỏ
      ws.send(bytes);

      // Lắng nghe tiến độ từ Python báo về
      await new Promise((resolve, reject) => {
        this._cancelReject = () => {
          ws.removeEventListener('message', handler);
          reject(new Error('Upload cancelled by user'));
        };

        const handler = (event) => {
          if (this._cancelled) {
            this._cancelReject();
            return;
          }
          try {
            if (typeof event.data === 'string') {
              const data = JSON.parse(event.data);
              if (data.cmd === 'bridgeProgress') {
                const elapsed = (Date.now() - startTime) / 1000;
                const speed = elapsed > 0.1 ? Utils.formatBytes(data.uploaded / elapsed) + '/s' : '0B/s';
                progressBar.style.width = `${data.pct}%`;
                progressText.textContent = `${data.pct}% [${Utils.formatBytes(data.uploaded)}/${Utils.formatBytes(data.total)}] ${speed}`;

                if (data.pct >= 100) {
                  ws.removeEventListener('message', handler);
                  resolve();
                }
              } else if (data.cmd === 'otaError') {
                ws.removeEventListener('message', handler);
                this._cancelReject = null;
                reject(new Error(data.message || 'Lỗi OTA từ bridge'));
              }
            }
          } catch (_) { }
        };
        ws.addEventListener('message', handler);
      });

      if (!this._cancelled) {
        await new Promise(r => setTimeout(r, 2000));
        this._sendMonitor(JSON.stringify({ cmd: 'uploadFirmwareEnd' }));
        this._logger.log('[INFO] Sent uploadFirmwareEnd', 'info');

        const endResp = await this._waitForResponse(ws, 'otaResult');
        if (!endResp) {
          this._logger.log('[ERROR] No response after upload', 'error');
        } else if (endResp.status === 'ok') {
          this._logger.log('[SUCCESS] Firmware uploaded and flashed!', 'success');
          Utils.toast('success', 'Firmware uploaded successfully!');
        } else {
          this._logger.log(`[ERROR] Flash failed: ${endResp.message || ''}`, 'error');
        }
      }

    } catch (e) {
      this._logger.log(`[ERROR] Upload: ${e.message}`, 'error');
      Utils.toast('error', e.message);
    } finally {
      this._uploading = false;
      this._cancelReject = null;
      this._resetUI();
    }
  }

  /**
   * Start firmware upload using a local file path sent to Python.
   * @param {string} path - Local firmware path
   */
  async startLocalUpload(path) {
    if (this._uploading) {
      Utils.toast('warn', 'Upload already in progress');
      return;
    }

    if (!app || !app._wsManager || !app._wsManager.connected) {
      Utils.toast('error', 'Not connected to device');
      return;
    }

    this._uploading = true;
    this._cancelled = false;
    const ws = app._wsManager.ws;

    this._logger.log(`[INFO] Requesting file info for: ${path}`, 'info');

    try {
      // 1. Get file size from python
      this._sendMonitor(JSON.stringify({ cmd: 'bridgeGetFileInfo', path: path }));
      const infoResp = await this._waitForResponse(ws, 'bridgeFileInfo', 'bridgeFileInfoError');
      if (infoResp.cmd === 'bridgeFileInfoError') {
        throw new Error(`Local file error: ${infoResp.msg}`);
      }

      const fileSize = infoResp.size;
      this._logger.log(`[INFO] Local Firmware: ${path} (${Utils.formatBytes(fileSize)})`, 'info');

      // Show progress UI
      const progressEl = Utils.$('upload-progress');
      const progressBar = Utils.$('progress-bar');
      const progressText = Utils.$('progress-text');
      const uploadBtn = Utils.$('btn-upload');
      const sendBtn = Utils.$('btn-send');

      progressEl.classList.remove('hidden');
      progressBar.style.width = '0%';
      progressText.textContent = '0%';
      uploadBtn.querySelector('#btn-upload-text').textContent = 'Stop Upload';
      sendBtn.disabled = true;

      // 2. Send start cmd to MCU
      this._sendMonitor(JSON.stringify({ cmd: 'uploadFirmwareStart', payload: { size: fileSize } }));
      this._logger.log('[INFO] Sent uploadFirmwareStart', 'info');

      // Wait for device success
      const startResp = await this._waitForResponse(ws, 'beginUploadFirmwareSuccess', 'beginUploadFirmwareFailed');
      if (!startResp || startResp.cmd === 'beginUploadFirmwareFailed') {
        throw new Error(`Device refused: ${startResp?.message || 'unknown error'}`);
      }

      const startTime = Date.now();

      // 3. Tell Python to read local file and chunk it
      ws.send(JSON.stringify({ cmd: 'bridgeStartUploadLocal', path: path }));

      // 4. Lắng nghe tiến độ từ Python báo về
      await new Promise((resolve, reject) => {
        this._cancelReject = () => {
          ws.removeEventListener('message', handler);
          reject(new Error('Upload cancelled by user'));
        };
        
        const handler = (event) => {
          if (this._cancelled) {
            this._cancelReject();
            return;
          }
          try {
            if (typeof event.data === 'string') {
              const data = JSON.parse(event.data);
              if (data.cmd === 'bridgeProgress') {
                const elapsed = (Date.now() - startTime) / 1000;
                const speed = elapsed > 0.1 ? Utils.formatBytes(data.uploaded / elapsed) + '/s' : '0B/s';
                progressBar.style.width = `${data.pct}%`;
                progressText.textContent = `${data.pct}% [${Utils.formatBytes(data.uploaded)}/${Utils.formatBytes(data.total)}] ${speed}`;
                
                if (data.pct >= 100) {
                  ws.removeEventListener('message', handler);
                  this._cancelReject = null;
                  resolve();
                }
              } else if (data.cmd === 'otaError') {
                ws.removeEventListener('message', handler);
                this._cancelReject = null;
                reject(new Error(data.message || 'Lỗi OTA từ bridge'));
              }
            }
          } catch (_) {}
        };
        ws.addEventListener('message', handler);
      });

      if (!this._cancelled) {
        await new Promise(r => setTimeout(r, 2000));
        this._sendMonitor(JSON.stringify({ cmd: 'uploadFirmwareEnd' }));
        this._logger.log('[INFO] Sent uploadFirmwareEnd', 'info');

        const endResp = await this._waitForResponse(ws, 'otaResult');
        if (!endResp) {
          this._logger.log('[ERROR] No response after upload', 'error');
        } else if (endResp.status === 'ok') {
          this._logger.log('[SUCCESS] Firmware uploaded and flashed!', 'success');
          Utils.toast('success', 'Firmware uploaded successfully!');
        } else {
          this._logger.log(`[ERROR] Flash failed: ${endResp.message || ''}`, 'error');
        }
      }

    } catch (e) {
      this._logger.log(`[ERROR] Upload: ${e.message}`, 'error');
      Utils.toast('error', e.message);
    } finally {
      this._uploading = false;
      this._cancelReject = null;
      this._resetUI();
    }
  }

  stopUpload() {
    this._cancelled = true;
    this._uploading = false;
    if (app && app._wsManager) {
      app._wsManager.send(JSON.stringify({ cmd: 'bridgeCancelUpload' }));
    }
    if (this._cancelReject) {
      this._cancelReject();
    }
    this._resetUI();
    this._logger.log('[WARNING] Upload cancelled by user', 'warn');
  }

  _resetUI() {
    const progressEl = Utils.$('upload-progress');
    const uploadBtn = Utils.$('btn-upload');
    const sendBtn = Utils.$('btn-send');

    progressEl.classList.add('hidden');
    Utils.$('progress-bar').style.width = '0%';
    Utils.$('progress-text').textContent = '0%';
    uploadBtn.querySelector('#btn-upload-text').textContent = 'Upload Firmware';
    // Send btn re-enabled by app state
    if (app && app._wsManager && app._wsManager.connected) {
      sendBtn.disabled = false;
    }
  }

  /**
   * Wait for a specific response cmd from the WebSocket.
   */
  _waitForResponse(ws, ...expectedCmds) {
    return new Promise((resolve) => {
      const timeout = setTimeout(() => {
        ws.removeEventListener('message', handler);
        resolve(null);
      }, 10000);

      const handler = (event) => {
        try {
          if (typeof event.data === 'string') {
            const data = JSON.parse(event.data);
            if (data && expectedCmds.includes(data.cmd)) {
              clearTimeout(timeout);
              ws.removeEventListener('message', handler);
              resolve(data);
            }
          }
        } catch (_) { }
      };

      ws.addEventListener('message', handler);
    });
  }
}
