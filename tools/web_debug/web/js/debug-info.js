/**
 * Debug Info tab — fetch and display device debug data.
 * Renders all values as label : value rows (never raw JSON).
 * Uses in-place DOM updates to avoid flicker and scroll-reset.
 */

class DebugInfo {
  constructor(sendFn) {
    this._send = sendFn;
    this._display = Utils.$('debug-display');
    this._statusLabel = Utils.$('debug-status');
    this._autoCheckbox = Utils.$('debug-auto');
    this._autoTimer = null;
    this._lastSections = null; // track structure for full rebuild detection
    this._valueElements = {};  // key -> DOM span element for in-place updates
  }

  /**
   * Send a getDebugInfo request with selected fields.
   */
  refresh() {
    const checkboxes = document.querySelectorAll('.debug-field');
    const selected = [];
    checkboxes.forEach(cb => { if (cb.checked) selected.push(cb.value); });
    if (selected.length === 0) {
      selected.push('system', 'memory', 'tasks');
    }
    this._send(JSON.stringify({ cmd: 'getSystemInfo', payload: { fields: selected } }));
    this._statusLabel.textContent = 'Request sent...';
  }

  /**
   * Handle getDebugInfo response.
   */
  handleResponse(data) {
    this._statusLabel.textContent = '';
    const displayData = {};

    // Sort tasks by name alphabetically if present
    if (Array.isArray(data.tasks)) {
      data.tasks.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    }

    for (const [k, v] of Object.entries(data)) {
      if (k !== 'cmd' && k !== 'status') {
        displayData[k] = v;
      }
    }
    this._renderOrUpdate(displayData);
  }

  /**
   * Toggle auto-refresh every 3 seconds.
   */
  toggleAuto() {
    if (this._autoCheckbox.checked) {
      this._scheduleAuto();
    } else {
      this._clearAuto();
    }
  }

  _scheduleAuto() {
    if (!this._autoCheckbox.checked) return;
    if (app && app._currentTab === 'system') {
      this.refresh();
    }
    this._autoTimer = setTimeout(() => this._scheduleAuto(), 3000);
  }

  _clearAuto() {
    if (this._autoTimer) {
      clearTimeout(this._autoTimer);
      this._autoTimer = null;
    }
  }

  destroy() {
    this._clearAuto();
  }

  // ── Icon mapping for known sections ──
  static ICON_MAP = {
    system:  'cpu',
    memory:  'memory-stick',
    tasks:   'list-checks',
    wifi:    'wifi',
    storage: 'hard-drive',
    pump:    'droplets',
  };

  /**
   * Recursively flatten a value into label-value rows.
   * Each row gets a unique key for in-place DOM updates.
   */
  _flattenToRows(val, keyPrefix = '', indent = 0) {
    const rows = [];

    if (val === null || val === undefined) {
      rows.push({ key: keyPrefix || '_null', label: keyPrefix, value: 'null', indent });
    } else if (Array.isArray(val)) {
      if (val.length === 0) {
        rows.push({ key: keyPrefix || '_empty', label: keyPrefix, value: '(empty)', indent });
      } else {
        val.forEach((item, i) => {
          const itemKey = `${keyPrefix}[${i}]`;
          if (typeof item === 'object' && item !== null) {
            rows.push({ key: itemKey, label: `#${i + 1}`, value: '', indent, isSubHeader: true });
            rows.push(...this._flattenToRows(item, itemKey + '.', indent + 1));
          } else {
            rows.push({ key: itemKey, label: `#${i + 1}`, value: String(item), indent });
          }
        });
      }
    } else if (typeof val === 'object') {
      for (const [k, v] of Object.entries(val)) {
        const childKey = keyPrefix + k;
        if (typeof v === 'object' && v !== null) {
          rows.push({ key: childKey, label: k, value: '', indent, isSubHeader: true });
          rows.push(...this._flattenToRows(v, childKey + '.', indent + 1));
        } else {
          rows.push({ key: childKey, label: k, value: this._formatValue(v), indent });
        }
      }
    } else {
      rows.push({ key: keyPrefix || '_val', label: keyPrefix, value: this._formatValue(val), indent });
    }

    return rows;
  }

  /**
   * Format a primitive value for display.
   */
  _formatValue(v) {
    if (v === null || v === undefined) return 'null';
    if (typeof v === 'boolean') return v ? '✓ true' : '✗ false';
    return String(v);
  }

  /**
   * Get a CSS color hint for a value.
   */
  _getValueColor(val) {
    if (val === 'null' || val === '✗ false') return 'text-red-400/80';
    if (val === '✓ true') return 'text-green-400';
    if (/^\d+(\.\d+)?%$/.test(val)) return 'text-amber-400';
    return 'text-gray-200';
  }

  /**
   * Compute a structural signature to detect when a full rebuild is needed.
   * Only the keys matter — value changes are handled in-place.
   */
  _getStructureSignature(data) {
    const allRows = {};
    for (const section of Object.keys(data)) {
      const rows = this._flattenToRows(data[section], section + '.');
      allRows[section] = rows.map(r => r.key + (r.isSubHeader ? ':H' : ':V')).join('|');
    }
    return JSON.stringify(Object.keys(data)) + '::' + JSON.stringify(allRows);
  }

  /**
   * Decide whether to do a full rebuild or an in-place value update.
   */
  _renderOrUpdate(data) {
    const sig = this._getStructureSignature(data);

    if (this._lastSections === sig && Object.keys(this._valueElements).length > 0) {
      // Structure unchanged — update values in-place
      this._updateValuesInPlace(data);
    } else {
      // Structure changed or first render — full rebuild
      this._lastSections = sig;
      this._fullRender(data);
    }
  }

  /**
   * Update only the value text content of existing DOM elements.
   * No DOM rebuild, no scroll reset, no flicker.
   */
  _updateValuesInPlace(data) {
    for (const section of Object.keys(data)) {
      const rows = this._flattenToRows(data[section], section + '.');
      for (const row of rows) {
        if (row.isSubHeader) continue;
        const el = this._valueElements[row.key];
        if (el) {
          const newVal = row.value;
          if (el.textContent !== newVal) {
            el.textContent = newVal;
            // Update color class
            el.className = 'value rounded-sm ' + this._getValueColor(newVal);
            // Brief highlight animation
            el.style.transition = 'background-color 0.3s ease';
            el.style.backgroundColor = 'rgba(66, 130, 249, 0.3)';
            setTimeout(() => { el.style.backgroundColor = 'transparent'; }, 400);
          }
        }
      }
    }
  }

  /**
   * Full DOM rebuild — used on first render or when structure changes.
   */
  _fullRender(data) {
    const sections = Object.keys(data);
    this._valueElements = {};

    if (sections.length === 0) {
      this._display.innerHTML = '<span class="text-gray-500 italic">No data received</span>';
      return;
    }

    // Separate tasks and merge memory+storage
    const tasksKey = sections.find(s => s === 'tasks');
    const mergedSections = new Set(['memory', 'storage']);
    const otherSections = sections.filter(s => s !== 'tasks' && !mergedSections.has(s));

    // Build DOM
    const grid = document.createElement('div');
    grid.className = 'debug-grid';

    // Left column — tasks
    const left = document.createElement('div');
    left.className = 'debug-grid-left pr-1';
    if (tasksKey && data[tasksKey] !== undefined) {
      left.appendChild(this._buildCardDOM('tasks', data[tasksKey]));
    }
    grid.appendChild(left);

    // Right 3 columns — other sections
    const right = document.createElement('div');
    right.className = 'debug-grid-right pr-1';

    // Merged memory + storage card
    const hasMemory = sections.includes('memory');
    const hasStorage = sections.includes('storage');
    if (hasMemory || hasStorage) {
      const merged = {};
      if (hasMemory) merged.memory = data.memory;
      if (hasStorage) merged.storage = data.storage;
      right.appendChild(this._buildCardDOM('memory / storage', merged, '', 'memory-stick'));
    }

    for (const section of otherSections) {
      right.appendChild(this._buildCardDOM(section, data[section]));
    }
    grid.appendChild(right);

    this._display.innerHTML = '';
    this._display.appendChild(grid);
    Utils.refreshIcons(this._display);
  }

  /**
   * Build a card DOM element (not innerHTML string).
   * Registers value elements in this._valueElements for in-place updates.
   */
  _buildCardDOM(section, val, extraClass = '', iconOverride = '') {
    const iconName = iconOverride || DebugInfo.ICON_MAP[section] || 'info';

    const card = document.createElement('div');
    card.className = 'debug-card' + (extraClass ? ' ' + extraClass : '');

    // Title
    const title = document.createElement('div');
    title.className = 'debug-card-title';
    title.innerHTML = `<i data-lucide="${iconName}" class="w-3.5 h-3.5"></i>${Utils.escapeHtml(section)}`;
    card.appendChild(title);

    // Rows
    const rows = this._flattenToRows(val, section + '.');

    for (const row of rows) {
      const paddingLeft = row.indent * 12;

      if (row.isSubHeader) {
        const sub = document.createElement('div');
        sub.className = 'debug-card-subheader';
        sub.style.paddingLeft = paddingLeft + 'px';
        const span = document.createElement('span');
        span.className = 'text-accent-light/70 text-[11px] font-semibold uppercase tracking-wider';
        span.textContent = row.label;
        sub.appendChild(span);
        card.appendChild(sub);
      } else {
        const rowEl = document.createElement('div');
        rowEl.className = 'debug-card-row';
        rowEl.style.paddingLeft = paddingLeft + 'px';

        const labelSpan = document.createElement('span');
        labelSpan.className = 'label';
        labelSpan.textContent = row.label;

        const valueSpan = document.createElement('span');
        valueSpan.className = 'value ' + this._getValueColor(row.value);
        valueSpan.textContent = row.value;

        // Register for in-place updates
        this._valueElements[row.key] = valueSpan;

        rowEl.appendChild(labelSpan);
        rowEl.appendChild(valueSpan);
        card.appendChild(rowEl);
      }
    }

    return card;
  }
}
