/**
 * Utility functions shared across modules.
 */

const Utils = {
  /**
   * Format byte count to human-readable string.
   */
  formatBytes(n) {
    if (n == null || isNaN(n)) return '0B';
    n = Number(n);
    if (n < 1024) return `${n}B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
    return `${(n / (1024 * 1024)).toFixed(1)}MB`;
  },

  /**
   * Get DOM element by ID (cached).
   */
  $(id) {
    return document.getElementById(id);
  },

  /**
   * Escape HTML entities for safe rendering.
   */
  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  },

  /**
   * Show a toast notification.
   * @param {'info'|'success'|'error'|'warn'} type
   * @param {string} message
   */
  toast(type, message) {
    const container = Utils.$('toast-container');
    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 3500);
  },

  /**
   * Create lucide icon element.
   */
  icon(name, cls = 'w-4 h-4') {
    const i = document.createElement('i');
    i.setAttribute('data-lucide', name);
    i.className = cls;
    return i;
  },

  /**
   * Re-initialize lucide icons in a container.
   */
  refreshIcons() {
    if (typeof lucide !== 'undefined') {
      lucide.createIcons();
    }
  },

  /**
   * Debounce a function.
   */
  debounce(fn, delay) {
    let timer;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), delay);
    };
  },
};
