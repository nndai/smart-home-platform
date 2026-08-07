/**
 * Log display manager.
 * Renders colored log lines with smart auto-scroll.
 */

class Logger {
  constructor() {
    this._container = Utils.$('log-content');
    this._scrollArea = Utils.$('log-container');
    this._autoScrollCheck = Utils.$('auto-scroll');
    this._maxLines = 2000;
  }

  /**
   * Append a log line with an optional tag (color class).
   * @param {string} text
   * @param {'error'|'warn'|'info'|'debug'|'sent'|'upload'|'success'|null} tag
   */
  log(text, tag = null) {
    const atBottom = this._isAtBottom();
    const line = document.createElement('div');
    line.className = 'log-line' + (tag ? ` log-${tag}` : '');
    line.textContent = text;
    this._container.appendChild(line);

    // Trim old lines
    while (this._container.children.length > this._maxLines) {
      this._container.removeChild(this._container.firstChild);
    }

    if (atBottom && this._autoScrollCheck.checked) {
      this._scrollToBottom();
    }
  }

  /**
   * Clear all logs.
   */
  clear() {
    this._container.innerHTML = '';
  }

  /**
   * Check if scrolled near bottom.
   */
  _isAtBottom() {
    const el = this._scrollArea;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 50;
  }

  _scrollToBottom() {
    requestAnimationFrame(() => {
      this._scrollArea.scrollTop = this._scrollArea.scrollHeight;
    });
  }
}
