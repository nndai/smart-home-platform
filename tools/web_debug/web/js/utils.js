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

  /**
   * SHA-256 hash function (pure JS, synchronous, standard).
   * @param {Uint8Array} bytes
   * @returns {Uint8Array} 32 bytes hash
   */
  sha256Bytes(bytes) {
    const K = [
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ];

    let H0 = 0x6a09e667, H1 = 0xbb67ae85, H2 = 0x3c6ef372, H3 = 0xa54ff53a;
    let H4 = 0x510e527f, H5 = 0x9b05688c, H6 = 0x1f83d9ab, H7 = 0x5be0cd19;

    const l = bytes.length;
    const bitLen = l * 8;
    const padLen = ((l + 8) >> 6 << 6) + 64;
    const buf = new Uint8Array(padLen);
    buf.set(bytes);
    buf[l] = 0x80;

    const view = new DataView(buf.buffer);
    view.setUint32(padLen - 4, bitLen >>> 0);
    view.setUint32(padLen - 8, Math.floor(bitLen / 0x100000000) >>> 0);

    const W = new Uint32Array(64);
    for (let i = 0; i < padLen; i += 64) {
      for (let t = 0; t < 16; t++) {
        W[t] = view.getUint32(i + t * 4);
      }
      for (let t = 16; t < 64; t++) {
        const s0 = ((W[t - 15] >>> 7) | (W[t - 15] << 25)) ^ ((W[t - 15] >>> 18) | (W[t - 15] << 14)) ^ (W[t - 15] >>> 3);
        const s1 = ((W[t - 2] >>> 17) | (W[t - 2] << 15)) ^ ((W[t - 2] >>> 19) | (W[t - 2] << 13)) ^ (W[t - 2] >>> 10);
        W[t] = (W[t - 16] + s0 + W[t - 7] + s1) | 0;
      }

      let a = H0, b = H1, c = H2, d = H3, e = H4, f = H5, g = H6, h = H7;
      for (let t = 0; t < 64; t++) {
        const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        const ch = (e & f) ^ ((~e) & g);
        const temp1 = (h + S1 + ch + K[t] + W[t]) | 0;
        const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const temp2 = (S0 + maj) | 0;

        h = g;
        g = f;
        f = e;
        e = (d + temp1) | 0;
        d = c;
        c = b;
        b = a;
        a = (temp1 + temp2) | 0;
      }

      H0 = (H0 + a) | 0;
      H1 = (H1 + b) | 0;
      H2 = (H2 + c) | 0;
      H3 = (H3 + d) | 0;
      H4 = (H4 + e) | 0;
      H5 = (H5 + f) | 0;
      H6 = (H6 + g) | 0;
      H7 = (H7 + h) | 0;
    }

    const out = new Uint8Array(32);
    const outView = new DataView(out.buffer);
    outView.setUint32(0, H0 >>> 0);
    outView.setUint32(4, H1 >>> 0);
    outView.setUint32(8, H2 >>> 0);
    outView.setUint32(12, H3 >>> 0);
    outView.setUint32(16, H4 >>> 0);
    outView.setUint32(20, H5 >>> 0);
    outView.setUint32(24, H6 >>> 0);
    outView.setUint32(28, H7 >>> 0);
    return out;
  },

  /**
   * Helper: Convert UTF-8 string to Uint8Array.
   */
  stringToUtf8Bytes(str) {
    if (typeof TextEncoder !== 'undefined') {
      return new TextEncoder().encode(str);
    }
    const utf8 = [];
    for (let i = 0; i < str.length; i++) {
      let charcode = str.charCodeAt(i);
      if (charcode < 0x80) utf8.push(charcode);
      else if (charcode < 0x800) {
        utf8.push(0xc0 | (charcode >> 6), 0x80 | (charcode & 0x3f));
      } else if (charcode < 0xd800 || charcode >= 0xe000) {
        utf8.push(0xe0 | (charcode >> 12), 0x80 | ((charcode >> 6) & 0x3f), 0x80 | (charcode & 0x3f));
      } else {
        i++;
        charcode = 0x10000 + (((charcode & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
        utf8.push(0xf0 | (charcode >> 18), 0x80 | ((charcode >> 12) & 0x3f), 0x80 | ((charcode >> 6) & 0x3f), 0x80 | (charcode & 0x3f));
      }
    }
    return new Uint8Array(utf8);
  },

  /**
   * Helper: Convert Hex string to Uint8Array.
   */
  hexToBytes(hex) {
    if (typeof hex !== 'string') return null;
    hex = hex.trim();
    if (hex.length % 2 !== 0) return null;
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < bytes.length; i++) {
      const byte = parseInt(hex.substr(i * 2, 2), 16);
      if (isNaN(byte)) return null;
      bytes[i] = byte;
    }
    return bytes;
  },

  /**
   * Helper: Convert Uint8Array to Hex string (lowercase).
   */
  bytesToHex(bytes) {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  },

  /**
   * HMAC-SHA256 calculation.
   * @param {string|Uint8Array} key Hex string (64 chars) or Uint8Array
   * @param {string|Uint8Array} message Text string or Uint8Array
   * @returns {string} 64-char lowercase hex digest
   */
  hmacSha256Hex(key, message) {
    let keyBytes = typeof key === 'string' ? Utils.hexToBytes(key) : key;
    if (!keyBytes) return '';

    const msgBytes = typeof message === 'string' ? Utils.stringToUtf8Bytes(message) : message;

    const blockSize = 64;
    if (keyBytes.length > blockSize) {
      keyBytes = Utils.sha256Bytes(keyBytes);
    }
    const k = new Uint8Array(blockSize);
    k.set(keyBytes);

    const ipad = new Uint8Array(blockSize + msgBytes.length);
    const opad = new Uint8Array(blockSize + 32);

    for (let i = 0; i < blockSize; i++) {
      ipad[i] = k[i] ^ 0x36;
      opad[i] = k[i] ^ 0x5c;
    }
    ipad.set(msgBytes, blockSize);

    const innerHash = Utils.sha256Bytes(ipad);
    opad.set(innerHash, blockSize);

    const outerHash = Utils.sha256Bytes(opad);
    return Utils.bytesToHex(outerHash);
  }
};
