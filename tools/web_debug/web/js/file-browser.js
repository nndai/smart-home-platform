/**
 * File Browser — directory listing, file viewing, download, delete.
 */

class FileBrowser {
  constructor(sendFn) {
    this._send = sendFn;
    this._path = '/';
    this._entries = [];
    this._selectedEntry = null; // { name, type, size }
    this._viewing = null;       // { path, data, size, offset, more, loading }
    this._downloadState = null; // { path, fname, chunks, nextOffset }
    this._downloadAborted = false;

    // DOM refs — added openBtn
    this._breadcrumb = Utils.$('file-breadcrumb');
    this._fsInfo = Utils.$('fs-info');
    this._fileList = Utils.$('file-list');
    this._fileViewer = Utils.$('file-viewer');
    this._fileViewerContent = Utils.$('file-viewer-content');
    this._contentArea = Utils.$('file-content-area');
    this._deleteBtn = Utils.$('btn-file-delete');
    this._downloadBtn = Utils.$('btn-file-download');
    this._openBtn = Utils.$('btn-file-open');

    // Scroll-to-load-more
    this._scrollCheckPending = false;
    this._contentArea.addEventListener('scroll', () => this._scheduleScrollCheck());
    this._contentArea.addEventListener('wheel', () => this._scheduleScrollCheck(), { passive: true });
  }

  _scheduleScrollCheck() {
    if (this._scrollCheckPending) return;
    this._scrollCheckPending = true;
    requestAnimationFrame(() => {
      this._scrollCheckPending = false;
      this._checkScroll();
    });
  }

  // ── Protocol commands ──

  cmdListDir(path) {
    this._send(JSON.stringify({ cmd: 'listDir', payload: { path } }));
  }

  cmdReadFile(path, offset, limit, encode) {
    this._send(JSON.stringify({ cmd: 'readFile', payload: { path, offset, limit, encode } }));
  }

  cmdDelete(path) {
    this._send(JSON.stringify({ cmd: 'deleteItem', payload: { path } }));
  }

  cmdFsInfo() {
    this._send(JSON.stringify({ cmd: 'fsInfo' }));
  }

  // ── Handle responses ──

  handleResponse(data) {
    const cmd = data.cmd;
    if (cmd === 'listDir')        this._onListDir(data);
    else if (cmd === 'readFile')  this._onReadFile(data);
    else if (cmd === 'deleteItem') this._onDelete(data);
    else if (cmd === 'fsInfo')    this._onFsInfo(data);
  }

  _onListDir(data) {
    if (data.status !== 'ok') return;
    const oldSelName = this._selectedEntry?.name;
    this._path = data.path || '/';
    this._entries = data.entries || [];
    this._viewing = null;
    this._selectedEntry = null;
    this._downloadState = null;
    if (oldSelName) {
      this._selectedEntry = this._entries.find(e => e.name === oldSelName && e.type === 'file') || null;
    }
    this._renderDir();
  }

  _onReadFile(data) {
    if (data.status !== 'ok') {
      if (this._downloadState) {
        this._abortDownload(data.message || 'Unknown error');
      }
      return;
    }

    const path = data.path;
    const offset = data.offset || 0;
    let chunkData = data.data || '';
    const more = data.more || false;
    const size = data.size || 0;
    const encode = data.encode || false;

    if (this._downloadState) {
      this._onDownloadChunk({ path, offset, data: chunkData, more, encode, size });
      return;
    }

    let chunkBytesLength = 0;
    if (encode) {
      chunkBytesLength = limitFromSize(chunkData, true);
      if (typeof chunkData === 'string' && chunkData.length > 0) {
        try {
          const raw = atob(chunkData);
          const bytes = new Uint8Array(raw.length);
          for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
          chunkData = new TextDecoder('utf-8').decode(bytes);
        } catch (e) {
          console.error('Base64 decode error:', e);
        }
      }
    } else {
      chunkBytesLength = new TextEncoder().encode(chunkData).length;
    }

    // Plain text (open/view) flow
    if (this._viewing && this._viewing.path === path && offset > 0) {
      this._viewing.data += chunkData;
      this._viewing.offset = offset;
      this._viewing.nextOffset = offset + chunkBytesLength;
      this._viewing.more = more;
      this._viewing.loading = false;
      this._appendFileContent(chunkData);
    } else {
      this._viewing = { path, data: chunkData, size, offset, more, loading: false, nextOffset: offset + chunkBytesLength };
      this._renderFile(chunkData);
    }
  }

  _onDownloadChunk({ path, offset, data, more, encode, size }) {
    if (!this._downloadState) return;

    if (encode) {
      // Decode base64 chunk
      try {
        const raw = atob(data);
        const bytes = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        this._downloadState.chunks.push(bytes);
      } catch (e) {
        this._abortDownload('Base64 decode error');
        return;
      }
    } else {
      // Text data encoded as UTF-8
      const bytes = new TextEncoder().encode(data);
      this._downloadState.chunks.push(bytes);
    }

    this._downloadState.nextOffset = offset + (encode ? limitFromSize(data, true) : data.length);

    if (more) {
      this.cmdReadFile(
        this._downloadState.path,
        this._downloadState.nextOffset,
        1024,
        encode
      );
    } else {
      this._finishDownload();
    }
  }

  _finishDownload() {
    const totalLen = this._downloadState.chunks.reduce((s, c) => s + c.length, 0);
    const combined = new Uint8Array(totalLen);
    let pos = 0;
    for (const c of this._downloadState.chunks) {
      combined.set(c, pos);
      pos += c.length;
    }
    const blob = new Blob([combined]);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = this._downloadState.fname;
    a.click();
    URL.revokeObjectURL(url);

    Utils.toast('success', `Downloaded ${this._downloadState.fname} (${Utils.formatBytes(totalLen)})`);
    this._resetDownloadUI();
    this._hideSpinnerForSelected();
    this._downloadState = null;
  }

  _abortDownload(msg) {
    Utils.toast('error', `Download error: ${msg}`);
    this._resetDownloadUI();
    this._hideSpinnerForSelected();
    this._downloadState = null;
  }

  _resetDownloadUI() {
    const span = this._downloadBtn.querySelector('span');
    if (span) span.textContent = 'Download';
    this._downloadBtn.disabled = false;
  }

  _onDelete(data) {
    if (data.status === 'ok') {
      Utils.toast('success', 'Deleted successfully');
      this.refresh();
    } else {
      Utils.toast('error', `Delete failed: ${data.message || ''}`);
    }
  }

  _onFsInfo(data) {
    if (data.status !== 'ok') return;
    const used = data.usedBytes || 0;
    const total = data.totalBytes || 0;
    const pct = total ? (used / total * 100).toFixed(0) : 0;
    this._fsInfo.textContent = `${Utils.formatBytes(used)} / ${Utils.formatBytes(total)} (${pct}%)`;
  }

  // ── UI actions ──

  refresh() {
    this.cmdFsInfo();
    this.cmdListDir(this._path);
  }

  enterDir(name) {
    const newPath = this._path.endsWith('/') ? this._path + name : this._path + '/' + name;
    this.cmdFsInfo();
    this.cmdListDir(newPath);
  }

  selectFile(entry) {
    this._selectedEntry = entry;
    this._viewing = null;
    this._downloadState = null;
    document.querySelectorAll('.file-entry').forEach(el => {
      el.classList.toggle('selected', el.dataset.name === entry.name && el.dataset.type === 'file');
    });
    this._fileViewer.classList.add('hidden');
    this._fileList.classList.remove('hidden');
    this._updateButtons();
  }

  _showSpinnerForSelected() {
    if (!this._selectedEntry) return;
    const items = this._fileList.querySelectorAll('.file-entry');
    for (const el of items) {
      if (el.dataset.name === this._selectedEntry.name && el.dataset.type === 'file') {
        const icon = el.querySelector('.file-icon');
        if (icon) icon.innerHTML = '<div class="spinner"></div>';
        break;
      }
    }
  }

  _hideSpinnerForSelected() {
    if (!this._selectedEntry) return;
    const items = this._fileList.querySelectorAll('.file-entry');
    for (const el of items) {
      if (el.dataset.name === this._selectedEntry.name && el.dataset.type === 'file') {
        const icon = el.querySelector('.file-icon');
        if (icon) {
          icon.innerHTML = '<i data-lucide="file-text" class="w-4 h-4"></i>';
          Utils.refreshIcons();
        }
        break;
      }
    }
  }

  openSelected() {
    if (!this._selectedEntry) return;
    this._showSpinnerForSelected();
    const path = this._path.replace(/\/$/, '') + '/' + this._selectedEntry.name;
    this.cmdReadFile(path, 0, 1024, true);
  }

  goBack() {
    if (this._viewing) {
      this._viewing = null;
      this._selectedEntry = null;
      this._downloadState = null;
      this._renderDir();
      return;
    }
    if (this._path === '/') return;
    const parts = this._path.replace(/\/$/, '').split('/');
    parts.pop();
    const parent = parts.join('/') || '/';
    this.cmdFsInfo();
    this.cmdListDir(parent);
  }

  deleteSelected() {
    if (!this._selectedEntry) return;
    const path = this._path.replace(/\/$/, '') + '/' + this._selectedEntry.name;
    const fullPath = this._selectedEntry.type === 'dir' ? path + '/' : path;
    if (confirm(`Delete "${this._selectedEntry.name}"?`)) {
      this._showSpinnerForSelected();
      this.cmdDelete(fullPath);
    }
  }

  download() {
    if (!this._selectedEntry) return;
    this._showSpinnerForSelected();
    const span = this._downloadBtn.querySelector('span');
    if (span) span.textContent = 'Downloading...';
    this._downloadBtn.disabled = true;
    const path = this._path.replace(/\/$/, '') + '/' + this._selectedEntry.name;
    this._downloadState = {
      path,
      fname: this._selectedEntry.name,
      chunks: [],
      nextOffset: 0,
    };
    this.cmdReadFile(path, 0, 1024, true);
  }

  // ── Rendering ──

  _renderDir() {
    this._fileList.innerHTML = '';
    this._fileViewer.classList.add('hidden');
    this._fileList.classList.remove('hidden');
    this._updateButtons();

    // Breadcrumb
    const parts = this._path === '/' ? [] : this._path.replace(/^\/|\/$/g, '').split('/');
    let bc = '<i data-lucide="hard-drive" class="w-3.5 h-3.5 text-gray-500 flex-shrink-0"></i>';
    bc += `<span class="breadcrumb-part cursor-pointer hover:text-accent-light transition-colors" data-path="/">/</span>`;
    let accPath = '';
    for (const p of parts) {
      accPath += '/' + p;
      bc += `<span class="text-gray-600">/</span>`;
      bc += `<span class="breadcrumb-part cursor-pointer hover:text-accent-light transition-colors" data-path="${Utils.escapeHtml(accPath)}">${Utils.escapeHtml(p)}</span>`;
    }
    this._breadcrumb.innerHTML = bc;
    Utils.refreshIcons(this._breadcrumb);

    // Breadcrumb click navigation
    this._breadcrumb.querySelectorAll('.breadcrumb-part').forEach(el => {
      el.addEventListener('click', () => {
        this.cmdFsInfo();
        this.cmdListDir(el.dataset.path);
      });
    });

    // Up entry
    if (this._path !== '/') {
      this._fileList.appendChild(this._createEntry({
        name: '..',
        type: 'up',
      }));
    }

    // Entries — directories first
    const dirs = this._entries.filter(e => e.type === 'dir');
    const files = this._entries.filter(e => e.type === 'file');
    [...dirs, ...files].forEach((entry, idx) => {
      const el = this._createEntry(entry);
      el.style.animationDelay = `${idx * 30}ms`;
      this._fileList.appendChild(el);
    });

    Utils.refreshIcons();
  }

  _createEntry(entry) {
    const el = document.createElement('div');
    el.className = 'file-entry';
    el.dataset.name = entry.name;
    el.dataset.type = entry.type;

    if (entry.type === 'up') {
      el.innerHTML = `
        <div class="file-icon dir"><i data-lucide="corner-left-up" class="w-4 h-4"></i></div>
        <span class="file-name text-gray-500">..</span>`;
      el.addEventListener('click', () => {
        const icon = el.querySelector('.file-icon');
        if (icon) icon.innerHTML = '<div class="spinner"></div>';
        this.goBack();
      });
    } else if (entry.type === 'dir') {
      el.innerHTML = `
        <div class="file-icon dir"><i data-lucide="folder" class="w-4 h-4"></i></div>
        <span class="file-name">${Utils.escapeHtml(entry.name)}</span>`;
      el.addEventListener('click', () => {
        const icon = el.querySelector('.file-icon');
        if (icon) icon.innerHTML = '<div class="spinner"></div>';
        this.enterDir(entry.name);
      });
    } else {
      const isSel = this._selectedEntry?.name === entry.name;
      if (isSel) el.classList.add('selected');
      el.innerHTML = `
        <div class="file-icon file"><i data-lucide="file-text" class="w-4 h-4"></i></div>
        <span class="file-name">${Utils.escapeHtml(entry.name)}</span>
        <span class="file-size">${Utils.formatBytes(entry.size || 0)}</span>`;
      el.addEventListener('click', () => {
        this.selectFile(entry);
      });
    }

    return el;
  }

  _renderFile(content) {
    this._fileList.classList.add('hidden');
    this._fileViewer.classList.remove('hidden');
    this._fileViewerContent.textContent = content || '';
    this._updateButtons();

    // Breadcrumb shows full file path
    const path = this._viewing.path;
    let bc = '<i data-lucide="file-text" class="w-3.5 h-3.5 text-accent-light flex-shrink-0"></i>';
    bc += `<span class="text-accent-light">${Utils.escapeHtml(path)}</span>`;
    this._breadcrumb.innerHTML = bc;
    Utils.refreshIcons(this._breadcrumb);

    // Add loading indicator if more data available
    if (this._viewing.more) {
      this._showLoadingMore();
      setTimeout(() => this._checkScroll(), 50);
    }
  }

  _appendFileContent(chunkData) {
    this._fileViewerContent.textContent += chunkData;
    this._removeLoadingMore();
    if (this._viewing && this._viewing.more) {
      this._showLoadingMore();
      setTimeout(() => this._checkScroll(), 50);
    }
  }

  _showLoadingMore() {
    this._removeLoadingMore();
    const el = document.createElement('div');
    el.id = 'loading-more-indicator';
    el.className = 'loading-more';
    el.innerHTML = '<div class="spinner"></div><span>Loading more...</span>';
    this._fileViewer.appendChild(el);
  }

  _removeLoadingMore() {
    const el = Utils.$('loading-more-indicator');
    if (el) el.remove();
  }

  _checkScroll() {
    if (!this._viewing || !this._viewing.more || this._viewing.loading) return;
    const el = this._contentArea;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) {
      this._viewing.loading = true;
      const nextOffset = this._viewing.nextOffset || 0;
      this.cmdReadFile(this._viewing.path, nextOffset, 1024, true);
    }
  }

  _updateButtons() {
    const hasSelection = this._selectedEntry && this._selectedEntry.type === 'file';
    const isViewing = this._viewing !== null;
    this._downloadBtn.disabled = !hasSelection;
    this._openBtn.disabled = !hasSelection;
    this._deleteBtn.disabled = !hasSelection;
    // Back button shows "Back to list" when viewing
    const backBtn = Utils.$('btn-file-back');
    if (backBtn) {
      backBtn.querySelector('span').textContent = isViewing ? 'Back' : 'Up';
    }
  }
}

// Helper: approximate original byte length from base64 string
function limitFromSize(b64, encoded) {
  if (!encoded) return b64.length;
  // base64: 4 chars = 3 bytes; strip padding
  const padding = (b64.match(/=+$/) || [''])[0].length;
  return Math.floor(b64.length * 3 / 4) - padding;
}
