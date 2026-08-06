"""
Remote Pump LN882H Debug Tool
GUI WebSocket monitor + OTA firmware upload + File browser.

Usage:
    python debug_ui.py

Dependencies:
    pip install websocket-client customtkinter
"""

import customtkinter
import websocket
import threading
import json
import time
import queue
import os
import sys


def _dbg(msg):
    print(f"[DBG] {msg}", file=sys.stderr, flush=True)


class FileBrowserTab:
    """File browser UI + protocol. Reusable protocol for Android app."""

    def __init__(self, parent, send_fn):
        self._send = send_fn
        self._path = "/"
        self._entries = []
        self._viewing = None  # None | {"path":..., "data":..., "size":..., "offset":...}

        self._frame = customtkinter.CTkFrame(parent)
        self._frame.grid_columnconfigure(0, weight=1)
        self._frame.grid_rowconfigure(2, weight=1)

        # Breadcrumb
        self._breadcrumb = customtkinter.CTkLabel(self._frame, text="", anchor="w",
                                                    font=("Consolas", 12))
        self._breadcrumb.grid(row=0, column=0, padx=10, pady=(1,1), sticky="ew")

        # Status bar (fs info)
        self._fb_status = customtkinter.CTkLabel(self._frame, text="", anchor="w",
                                                   font=("Consolas", 11))
        self._fb_status.grid(row=1, column=0, padx=10, pady=(0, 5), sticky="ew")

        # Content area (CTkFrame, not scrollable — scrollable wrapper created per-mode)
        self._content = customtkinter.CTkFrame(self._frame)
        self._content.grid(row=2, column=0, padx=10, pady=5, sticky="nsew")
        self._content.grid_columnconfigure(0, weight=1)
        self._content.grid_rowconfigure(0, weight=1)

        # Button bar
        btn_bar = customtkinter.CTkFrame(self._frame)
        btn_bar.grid(row=3, column=0, padx=10, pady=(5, 10), sticky="ew")
        self._refresh_btn = customtkinter.CTkButton(btn_bar, text="Refresh", width=80,
                                                      command=self.refresh)
        self._refresh_btn.pack(side="left", padx=5, pady=5)
        self._delete_btn = customtkinter.CTkButton(btn_bar, text="Delete", width=80,
                                                     command=self._delete_selected)
        self._delete_btn.pack(side="left", padx=5, pady=5)
        self._back_btn = customtkinter.CTkButton(btn_bar, text="Back", width=60,
                                                   command=self._go_back)
        self._back_btn.pack(side="right", padx=5, pady=5)
        self._dl_btn = customtkinter.CTkButton(btn_bar, text="Download", width=90,
                                                 command=self._download)
        self._dl_btn.pack(side="right", padx=5, pady=5)

        self._selected_entry = None
        self._entry_widgets = {}
        self._update_buttons()

    # ── Protocol commands (reusable for Android) ──

    def cmd_list_dir(self, path):
        self._send(json.dumps({"cmd": "listDir", "payload": {"path": path}}))

    def cmd_read_file(self, path, offset=0, limit=1024):
        self._send(json.dumps({"cmd": "readFile", "payload": {
            "path": path, "offset": offset, "limit": limit}}))

    def cmd_download_file(self, path, chunk):
        self._send(json.dumps({"cmd": "downloadFile", "payload": {
            "path": path, "chunk": chunk}}))

    def cmd_file_info(self, path):
        self._send(json.dumps({"cmd": "fileInfo", "payload": {"path": path}}))

    def cmd_delete(self, path):
        self._send(json.dumps({"cmd": "deleteItem", "payload": {"path": path}}))

    def cmd_fs_info(self):
        self._send(json.dumps({"cmd": "fsInfo"}))

    # ── Handle responses (called from main poll loop) ──

    def handle_response(self, data):
        cmd = data.get("cmd")
        if cmd == "listDir":
            self._on_list_dir(data)
        elif cmd == "readFile":
            self._on_read_file(data)
        elif cmd == "downloadFile":
            self._on_download_file(data)
        elif cmd == "fileInfo":
            self._on_file_info(data)
        elif cmd == "deleteItem":
            self._on_delete(data)
        elif cmd == "fsInfo":
            self._on_fs_info(data)

    def _on_list_dir(self, data):
        if data.get("status") != "ok":
            return
        old_sel_name = self._selected_entry["name"] if self._selected_entry else None
        self._path = data.get("path", "/")
        self._entries = data.get("entries", [])
        self._viewing = None
        self._selected_entry = None
        # re-select if still present
        if old_sel_name:
            for e in self._entries:
                if e["name"] == old_sel_name and e["type"] == "file":
                    self._selected_entry = e
                    break
        self._render_dir()

    def _on_read_file(self, data):
        if data.get("status") != "ok":
            return
            
        path = data.get("path")
        offset = data.get("offset", 0)
        chunk_data = data.get("data", "")
        more = data.get("more", False)
        size = data.get("size", 0)

        if self._viewing and self._viewing["path"] == path and offset > 0:
            self._viewing["data"] += chunk_data
            self._viewing["offset"] = offset
            self._viewing["more"] = more
            self._viewing["loading"] = False
            self._append_file_content(chunk_data)
        else:
            self._viewing = {
                "path": path,
                "data": chunk_data,
                "size": size,
                "offset": offset,
                "more": more,
                "loading": False
            }
            self._render_file()

    def _on_file_info(self, data):
        pass  # not used directly in UI

    def _on_delete(self, data):
        if data.get("status") == "ok":
            self.refresh()

    def _on_fs_info(self, data):
        if data.get("status") == "ok":
            used = data.get("usedBytes", 0)
            total = data.get("totalBytes", 0)
            pct = used / total * 100 if total else 0
            self._fb_status.configure(
                text=f"Used: {self._fmt_bytes(used)} / {self._fmt_bytes(total)} ({pct:.0f}%)")

    # ── UI actions ──

    def refresh(self):
        self.cmd_fs_info()
        self.cmd_list_dir(self._path)

    def enter_dir(self, name):
        if self._path.endswith("/"):
            new_path = self._path + name
        else:
            new_path = self._path + "/" + name
        self.cmd_fs_info()
        self.cmd_list_dir(new_path)

    def open_file(self, name):
        path = self._path.rstrip("/") + "/" + name
        self.cmd_read_file(path)

    def _select_file(self, entry):
        self._selected_entry = entry
        for name, (row, label) in self._entry_widgets.items():
            bg = "#3a3a3a" if name == entry["name"] else "transparent"
            label.configure(fg_color=bg)
        self._update_buttons()
        self.open_file(entry["name"])

    def _go_back(self):
        if self._viewing:
            # Back from file view to directory listing
            self._viewing = None
            self._selected_entry = None
            self._render_dir()
            return
        if self._path == "/":
            return
        parent = self._path.rstrip("/").rsplit("/", 1)[0]
        if not parent:
            parent = "/"
        self.cmd_fs_info()
        self.cmd_list_dir(parent)

    def _delete_selected(self):
        if not self._selected_entry:
            return
        path = self._path.rstrip("/") + "/" + self._selected_entry["name"]
        if self._selected_entry["type"] == "dir":
            path += "/"
        self.cmd_delete(path)

    def _download(self):
        if not self._viewing:
            return
        self._dl_btn.configure(state="disabled", text="Downloading...")
        path = self._viewing["path"]
        self._download_state = {
            "path": path,
            "fname": os.path.basename(path),
            "chunks": [],
            "size": self._viewing.get("size", 0),
        }
        self.cmd_download_file(path, 0)

    def _on_download_file(self, data):
        if data.get("status") != "ok":
            self._fb_status.configure(text=f"Download error: {data.get('message','')}")
            self._dl_btn.configure(state="normal", text="Download")
            return

        import base64 as b64mod
        chunk_data = data.get("data", "")
        more = data.get("more", False)
        chunk_idx = data.get("chunk", 0)

        if not hasattr(self, "_download_state"):
            self._dl_btn.configure(state="normal", text="Download")
            return

        st = self._download_state
        st["chunks"].append(b64mod.b64decode(chunk_data) if chunk_data else b"")

        if more:
            self.cmd_download_file(st["path"], chunk_idx + 1)
        else:
            all_data = b"".join(st["chunks"])
            fname = st["fname"]
            with open(fname, "wb") as f:
                f.write(all_data)
            self._fb_status.configure(
                text=f"Downloaded {fname} ({self._fmt_bytes(len(all_data))})")
            self._dl_btn.configure(state="normal", text="Download")
            del self._download_state

    # ── Rendering ──

    def _clear_content(self):
        for w in self._content.winfo_children():
            w.destroy()

    def _render_dir(self):
        self._clear_content()
        self._update_buttons()

        # Breadcrumb
        parts = self._path.strip("/").split("/") if self._path != "/" else []
        bc = "  /  ".join(parts) if parts else "/"
        self._breadcrumb.configure(text=f"    {bc}")

        # Scrollable wrapper for directory entries
        scroll = customtkinter.CTkScrollableFrame(self._content)
        scroll.grid(row=0, column=0, sticky="nsew")

        # Up button (first entry)
        if self._path != "/":
            up_frame = customtkinter.CTkFrame(scroll, fg_color="transparent")
            up_frame.pack(fill="x", padx=5, pady=1)
            up_btn = customtkinter.CTkButton(up_frame, text="  [ .. ]  up",
                                               anchor="w", fg_color="transparent",
                                               hover_color="#2a2a2a", command=self._go_back)
            up_btn.pack(fill="x")

        # Entries
        self._entry_widgets = {}
        for e in self._entries:
            name = e["name"]
            typ = e["type"]
            size = e.get("size", 0)
            is_sel = self._selected_entry and self._selected_entry["name"] == name

            row = customtkinter.CTkFrame(scroll, fg_color="transparent")
            row.pack(fill="x", padx=5, pady=1)

            icon = "  [~] " if typ == "dir" else "  [#] "
            bg = "#3a3a3a" if is_sel and typ == "file" else "transparent"
            label = customtkinter.CTkButton(
                row, text=icon + name,
                anchor="w", fg_color=bg,
                hover_color="#2a2a2a",
                command=lambda n=name, t=typ, entry=e: (
                    self.enter_dir(n) if t == "dir" else self._select_file(entry)))
            label.pack(side="left", fill="x", expand=True)
            self._entry_widgets[name] = (row, label)

            if typ == "file":
                size_lbl = customtkinter.CTkLabel(row, text=self._fmt_bytes(size),
                                                    font=("Consolas", 11), width=80)
                size_lbl.pack(side="right", padx=(0, 10))

    def _render_file(self):
        self._clear_content()
        self._update_buttons()
        v = self._viewing

        # Breadcrumb
        path = v["path"]
        self._breadcrumb.configure(text=f"    {path}")

        # File content - use pack instead of grid inside CTkScrollableFrame
        self._file_textbox = customtkinter.CTkTextbox(self._content, wrap="word", font=("Consolas", 13))
        self._file_textbox.pack(fill="both", expand=True, padx=5, pady=5)
        self._file_textbox.insert("1.0", v["data"])
        self._file_textbox.configure(state="disabled")
        
        self._file_textbox._textbox.bind("<MouseWheel>", self._check_scroll)
        self._file_textbox._textbox.bind("<Button-4>", self._check_scroll)
        self._file_textbox._textbox.bind("<Button-5>", self._check_scroll)
        self._file_textbox._textbox.bind("<B1-Motion>", self._check_scroll)
        self._file_textbox._textbox.bind("<KeyRelease>", self._check_scroll)

    def _append_file_content(self, chunk_data):
        if hasattr(self, "_file_textbox") and self._file_textbox.winfo_exists():
            self._file_textbox.configure(state="normal")
            self._file_textbox.insert("end", chunk_data)
            self._file_textbox.configure(state="disabled")

    def _check_scroll(self, event=None):
        if not self._viewing or not self._viewing.get("more") or self._viewing.get("loading"):
            return
        
        if hasattr(self, "_file_textbox") and self._file_textbox.winfo_exists():
            yview = self._file_textbox._textbox.yview()
            if yview[1] >= 0.95:  # 95% scrolled down
                self._viewing["loading"] = True
                next_offset = len(self._viewing["data"])
                self.cmd_read_file(self._viewing["path"], offset=next_offset)

    def _update_buttons(self):
        viewing = self._viewing is not None
        self._dl_btn.configure(state="normal" if viewing else "disabled")
        self._back_btn.configure(state="normal")
        self._delete_btn.configure(state="normal" if self._selected_entry else "disabled")

    @staticmethod
    def _fmt_bytes(n):
        if n < 1024:
            return f"{n}B"
        elif n < 1024 * 1024:
            return f"{n / 1024:.1f}KB"
        else:
            return f"{n / (1024 * 1024):.1f}MB"


class DebugInfoTab:
    def __init__(self, parent, send_fn):
        self._send = send_fn
        self._after_id = None

        self._frame = customtkinter.CTkFrame(parent)
        self._frame.grid_columnconfigure(0, weight=1)
        self._frame.grid_rowconfigure(3, weight=1)

        # Field checkboxes
        check_frame = customtkinter.CTkFrame(self._frame)
        check_frame.grid(row=0, column=0, padx=10, pady=(10, 5), sticky="ew")

        fields = ["system", "memory", "tasks", "wifi", "storage", "pump"]
        self._check_vars = {}
        for i, f in enumerate(fields):
            var = customtkinter.BooleanVar(value=True)
            self._check_vars[f] = var
            cb = customtkinter.CTkCheckBox(check_frame, text=f, variable=var)
            cb.grid(row=i // 3, column=i % 3, padx=5, pady=2, sticky="w")

        # Button bar
        btn_bar = customtkinter.CTkFrame(self._frame)
        btn_bar.grid(row=1, column=0, padx=10, pady=(5, 5), sticky="ew")

        self._refresh_btn = customtkinter.CTkButton(btn_bar, text="Refresh", width=80,
                                                      command=self._refresh)
        self._refresh_btn.pack(side="left", padx=5, pady=5)

        self._auto_var = customtkinter.BooleanVar(value=False)
        self._auto_cb = customtkinter.CTkCheckBox(btn_bar, text="Auto 3s",
                                                    variable=self._auto_var,
                                                    command=self._toggle_auto)
        self._auto_cb.pack(side="left", padx=5, pady=5)

        self._status_label = customtkinter.CTkLabel(btn_bar, text="", anchor="e")
        self._status_label.pack(side="right", padx=10, pady=5)

        # Display area
        self._display = customtkinter.CTkTextbox(self._frame, wrap="word",
                                                   font=("Consolas", 12))
        self._display.grid(row=3, column=0, padx=10, pady=5, sticky="nsew")
        self._display.insert("1.0", "Click Refresh to fetch debug info")
        self._display.configure(state="disabled")

    def _refresh(self):
        selected = [f for f, v in self._check_vars.items() if v.get()]
        if not selected:
            selected = ["system", "memory", "tasks"]
        payload = json.dumps({"cmd": "getDebugInfo",
                              "payload": {"fields": selected}})
        self._send(payload)
        self._status_label.configure(text="Request sent...")

    def handle_response(self, data):
        self._display.configure(state="normal")
        self._display.delete("1.0", "end")
        display_data = {k: v for k, v in data.items()
                        if k not in ("cmd", "status")}
        text = json.dumps(display_data, indent=2, ensure_ascii=False)
        self._display.insert("1.0", text)
        self._display.configure(state="disabled")
        self._status_label.configure(text="")

    def _toggle_auto(self):
        if self._auto_var.get():
            self._schedule_auto()
        else:
            if self._after_id:
                self._frame.after_cancel(self._after_id)
                self._after_id = None

    def _schedule_auto(self):
        if not self._auto_var.get():
            return
        if not self._frame.winfo_viewable():
            self._after_id = self._frame.after(500, self._schedule_auto)
            return
        self._refresh()
        self._after_id = self._frame.after(3000, self._schedule_auto)

    def destroy(self):
        if self._after_id:
            self._frame.after_cancel(self._after_id)


class DebugTool(customtkinter.CTk):
    def __init__(self):
        super().__init__()
        self.title("Remote Pump Debug Tool")
        self.geometry("700x550")

        self._ws_url = "ws://192.168.137.111:82"
        self._ws = None          # WebSocketApp for monitoring
        self._ws_thread = None   # daemon thread for run_forever
        self._msg_queue = queue.Queue()
        self._connected = False
        self._uploading = False
        self._upload_failed = threading.Event()
        self._want_connect = False
        self._stop_evt = threading.Event()

        self._build_ui()
        self._after_id = self.after(50, self._poll_queue)

    def _build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(1, weight=1)

        # ── Connection bar ──
        conn_frame = customtkinter.CTkFrame(self)
        conn_frame.grid(row=0, column=0, padx=10, pady=(10, 0), sticky="ew")
        conn_frame.grid_columnconfigure(1, weight=1)

        customtkinter.CTkLabel(conn_frame, text="Device WS:").grid(row=0, column=0, padx=(10, 5), pady=8)
        self._ip_entry = customtkinter.CTkEntry(conn_frame, placeholder_text="ws://192.168.1.100:82")
        self._ip_entry.grid(row=0, column=1, padx=5, pady=8, sticky="ew")
        self._ip_entry.insert(0, self._ws_url)

        self._connect_btn = customtkinter.CTkButton(conn_frame, text="Connect", width=90,
                                                      command=self._toggle_connect)
        self._connect_btn.grid(row=0, column=2, padx=5, pady=8)

        self._status_label = customtkinter.CTkLabel(conn_frame, text="Status: Disconnected",
                                                      text_color="red")
        self._status_label.grid(row=0, column=3, padx=(10, 10), pady=8)

        # ── Tab view ──
        self._tabview = customtkinter.CTkTabview(self, anchor="nw")
        self._tabview.grid(row=1, column=0, padx=10, pady=0, sticky="nsew")

        # Logs tab
        logs_tab = self._tabview.add("Logs")
        logs_tab.grid_columnconfigure(0, weight=1)
        logs_tab.grid_rowconfigure(0, weight=1)

        self._log_display = customtkinter.CTkTextbox(logs_tab, wrap="word", font=("Consolas", 13))
        self._log_display.grid(row=0, column=0, padx=5, pady=5, sticky="nsew")
        self._log_display._textbox.tag_config("error", foreground="red")
        self._log_display._textbox.tag_config("warn", foreground="orange")
        self._log_display._textbox.tag_config("info", foreground="#00CCCC")
        self._log_display._textbox.tag_config("debug", foreground="gray")
        self._log_display._textbox.tag_config("sent", foreground="#88FF88")
        self._log_display._textbox.tag_config("upload", foreground="magenta")
        self._log_display._textbox.tag_config("success", foreground="lime")

        btn_frame = customtkinter.CTkFrame(logs_tab)
        btn_frame.grid(row=1, column=0, padx=5, pady=(0, 5), sticky="ew")
        customtkinter.CTkButton(btn_frame, text="Clear Log", width=80,
                                  command=self._clear_log).pack(side="left", padx=5, pady=5)

        # Files tab
        files_tab = self._tabview.add("Files")
        files_tab.grid_columnconfigure(0, weight=1)
        files_tab.grid_rowconfigure(0, weight=1)

        self._file_browser = FileBrowserTab(files_tab, self._send_raw)
        self._file_browser._frame.grid(row=0, column=0, sticky="nsew")

        # Debug tab
        debug_tab = self._tabview.add("Debug")
        debug_tab.grid_columnconfigure(0, weight=1)
        debug_tab.grid_rowconfigure(0, weight=1)

        self._debug_tab = DebugInfoTab(debug_tab, self._send_raw)
        self._debug_tab._frame.grid(row=0, column=0, sticky="nsew")

        # ── Command + Upload bar ──
        bar_frame = customtkinter.CTkFrame(self)
        bar_frame.grid(row=2, column=0, padx=10, pady=(0, 10), sticky="ew")
        bar_frame.grid_columnconfigure(1, weight=1)

        customtkinter.CTkLabel(bar_frame, text="Command:").grid(row=0, column=0, padx=(10, 5), pady=8)
        self._cmd_entry = customtkinter.CTkEntry(bar_frame, placeholder_text='{"cmd":"getStatus"}')
        self._cmd_entry.grid(row=0, column=1, padx=5, pady=8, sticky="ew")
        self._cmd_entry.bind("<Return>", lambda e: self._send_cmd())

        self._send_btn = customtkinter.CTkButton(bar_frame, text="Send", width=70,
                                                   state="disabled", command=self._send_cmd)
        self._send_btn.grid(row=0, column=2, padx=5, pady=8)

        self._upload_btn = customtkinter.CTkButton(bar_frame, text="Upload Firmware", width=130,
                                                     state="disabled", command=self._start_upload)
        self._upload_btn.grid(row=0, column=3, padx=(5, 10), pady=8)

        # ── Progress bar ──
        self._progress_frame = customtkinter.CTkFrame(self)
        self._progress_frame.grid(row=3, column=0, padx=10, pady=(0, 5), sticky="ew")
        self._progress_frame.grid_columnconfigure(0, weight=1)
        self._progress_frame.grid_remove()

        self._progress_bar = customtkinter.CTkProgressBar(self._progress_frame, height=14)
        self._progress_bar.grid(row=0, column=0, padx=(15, 10), pady=5, sticky="ew")
        self._progress_bar.set(0)

        self._progress_label = customtkinter.CTkLabel(self._progress_frame,
                                                         text="0% (0B/0B) 0B/s",
                                                         font=("Consolas", 12))
        self._progress_label.grid(row=0, column=1, padx=(5, 15), pady=5)

    # ── Connection ──

    def _toggle_connect(self):
        if self._connected:
            _dbg("Disconnecting...")
            self._log("[INFO] Disconnecting...\n", "info")
            self._want_connect = False
            self._stop_evt.set()
            if self._ws:
                try:
                    self._ws.close()
                except Exception:
                    pass
                self._ws = None
            self._connected = False
            self._update_conn_ui()
            return

        self._ws_url = self._ip_entry.get().strip()
        if not self._ws_url:
            self._ws_url = "ws://192.168.137.111:82"
        if not self._ws_url.startswith("ws://"):
            self._ws_url = "ws://" + self._ws_url

        _dbg(f"Connecting to {self._ws_url}")
        self._log(f"[INFO] Connecting to {self._ws_url}\n", "info")

        self._want_connect = True
        self._stop_evt.clear()
        self._ws_thread = threading.Thread(target=self._ws_run, daemon=True)
        self._ws_thread.start()

    def _ws_run(self):
        _dbg("WS thread started")
        while self._want_connect and not self._stop_evt.is_set():
            try:
                _dbg("Creating WebSocketApp...")
                self._ws = websocket.WebSocketApp(
                    self._ws_url,
                    on_open=self._on_open,
                    on_message=self._on_message,
                    on_error=self._on_error,
                    on_close=self._on_close,
                )
                _dbg("Calling run_forever...")
                self._ws.run_forever(ping_interval=10, ping_timeout=5)
                _dbg("run_forever ended")
            except Exception as e:
                _dbg(f"WS exception: {e}")
                self._msg_queue.put(f"[ERROR] Connection error: {e}\n")

            if self._want_connect and not self._stop_evt.is_set():
                _dbg("Will retry in 3s...")
                self._msg_queue.put("[INFO] Reconnecting in 3s...\n")
                time.sleep(3)
            else:
                break

        _dbg("WS thread exiting")

    def _on_open(self, ws):
        _dbg("WebSocket OPENED")
        self._connected = True
        self._msg_queue.put("[INFO] Connected\n")
        self.after(0, self._update_conn_ui)
        self.after(500, self._file_browser.refresh)

    def _on_message(self, ws, message):
        self._msg_queue.put(message)

    def _on_error(self, ws, error):
        _dbg(f"WebSocket ERROR: {error}")
        if self._uploading:
            self._upload_failed.set()
        self._connected = False
        self.after(0, self._update_conn_ui)

    def _on_close(self, ws, code, reason):
        _dbg(f"WebSocket CLOSED (code={code}, reason={reason})")
        if self._uploading:
            self._upload_failed.set()
        self._connected = False
        self.after(0, self._update_conn_ui)

    def _update_conn_ui(self):
        if self._connected:
            self._status_label.configure(text="Status: Connected", text_color="lime")
            self._send_btn.configure(state="normal")
            self._upload_btn.configure(state="normal")
            self._connect_btn.configure(text="Disconnect")
        else:
            self._status_label.configure(text="Status: Disconnected", text_color="red")
            self._send_btn.configure(state="disabled")
            self._upload_btn.configure(state="disabled")
            self._connect_btn.configure(text="Connect")

    # ── Log display ──

    def _log(self, text, tag=None):
        if tag:
            self._msg_queue.put((text, tag))
        else:
            self._msg_queue.put(text)

    def _is_log_at_bottom(self):
        """Return True if the log textbox is scrolled to (or near) the bottom."""
        return self._log_display._textbox.yview()[1] >= 0.98

    def _clear_log(self):
        self._log_display.configure(state="normal")
        self._log_display.delete("1.0", "end")
        self._log_display.configure(state="disabled")

    def _poll_queue(self):
        while not self._msg_queue.empty():
            item = self._msg_queue.get_nowait()

            if isinstance(item, tuple):
                text, tag = item
                at_bottom = self._is_log_at_bottom()
                self._log_display.configure(state="normal")
                self._log_display._textbox.insert("end", text, tag)
                if not text.endswith("\n"):
                    self._log_display._textbox.insert("end", "\n")
                if at_bottom:
                    self._log_display.see("end")
                self._log_display.configure(state="disabled")
                continue

            message = item

            # Try JSON
            try:
                data = json.loads(message)
                if isinstance(data, dict):
                    cmd = data.get("cmd")

                    # File browser responses
                    file_cmds = {"listDir", "readFile", "fileInfo", "deleteItem", "fsInfo", "downloadFile"}
                    if cmd in file_cmds:
                        self._file_browser.handle_response(data)
                        continue

                    if cmd == "getDebugInfo":
                        self._debug_tab.handle_response(data)
                        continue

                    # Upload responses
                    if cmd == "beginUploadFirmwareSuccess":
                        self._log("[INFO] Device ready for firmware data\n", "info")
                    elif cmd == "beginUploadFirmwareFailed":
                        self._log(f"[ERROR] Device rejected: {data.get('message','')}\n", "error")
                        self._upload_failed.set()
                    elif cmd == "uploadFirmwareSuccess":
                        self._log("[SUCCESS] Firmware flashed!\n", "success")
                        self._uploading = False
                        self.after(0, self._reset_upload_ui)
                    elif cmd == "uploadFirmwareFailed":
                        self._log(f"[ERROR] Flash failed: {data.get('message','')}\n", "error")
                        self._upload_failed.set()
                    elif cmd == "log":
                        log_msg = data.get("msg", "")
                        tag = "info"
                        if "[ERROR]" in log_msg: tag = "error"
                        elif "[WARN]" in log_msg: tag = "warn"
                        elif "[DEBUG]" in log_msg: tag = "debug"
                        at_bottom = self._is_log_at_bottom()
                        self._log_display.configure(state="normal")
                        self._log_display._textbox.insert("end", log_msg + "\n", tag)
                        if at_bottom:
                            self._log_display.see("end")
                        self._log_display.configure(state="disabled")
                        continue
                    elif cmd:
                        at_bottom = self._is_log_at_bottom()
                        self._log_display.configure(state="normal")
                        self._log_display._textbox.insert("end",
                                                             json.dumps(data, indent=2) + "\n")
                        if at_bottom:
                            self._log_display.see("end")
                        self._log_display.configure(state="disabled")
                        continue
            except json.JSONDecodeError:
                pass

            at_bottom = self._is_log_at_bottom()
            self._log_display.configure(state="normal")
            self._log_display._textbox.insert("end", message + "\n")
            if at_bottom:
                self._log_display.see("end")
            self._log_display.configure(state="disabled")

        self._after_id = self.after(50, self._poll_queue)

    # ── Send command ──

    def _send_raw(self, text):
        if self._connected and self._ws:
            try:
                self._ws.send(text)
            except Exception:
                pass

    def _send_cmd(self):
        if not self._connected or not self._ws:
            return
        text = self._cmd_entry.get().strip()
        if not text:
            return
        try:
            self._ws.send(text)
            self._log(f">>> {text}\n", "sent")
            self._cmd_entry.delete(0, "end")
        except Exception as e:
            self._log(f"[ERROR] Send failed: {e}\n", "error")

    # ── Firmware upload ──

    def _start_upload(self):
        if not self._connected:
            self._log("[WARNING] Not connected\n", "warn")
            return
        if self._uploading:
            self._log("[WARNING] Upload already in progress\n", "warn")
            return

        FILE_PATH = ".pio/build/generic-ln882h/firmware.uf2"
        if not os.path.exists(FILE_PATH):
            self._log(f"[ERROR] Firmware not found: {FILE_PATH}\n", "error")
            return

        size = os.path.getsize(FILE_PATH)
        self._log(f"[INFO] Firmware: {FILE_PATH} ({self._fmt_bytes(size)})\n", "info")

        self._uploading = True
        self._upload_failed.clear()
        self._send_btn.configure(state="disabled")
        self._upload_btn.configure(text="Stop Upload", command=self._stop_upload)
        self._progress_bar.set(0)
        self._progress_label.configure(text="0% (0B/0B) 0B/s")
        self._progress_frame.grid()

        t = threading.Thread(target=self._run_upload, args=(FILE_PATH,), daemon=True)
        t.start()

    def _stop_upload(self):
        self._log("[WARNING] Upload cancelled by user\n", "warn")
        self._upload_failed.set()
        self._uploading = False
        self.after(0, self._reset_upload_ui)

    def _run_upload(self, file_path):
        ws_up = None
        try:
            ws_up = websocket.create_connection(self._ws_url, timeout=10)
            file_size = os.path.getsize(file_path)

            start_cmd = json.dumps({"cmd": "uploadFirmwareStart", "payload": {"size": file_size}})
            ws_up.send(start_cmd)
            self._log("[INFO] Sent uploadFirmwareStart\n", "info")

            start_resp = self._wait_for_response(ws_up, "beginUploadFirmwareSuccess",
                                                  "beginUploadFirmwareFailed")
            if start_resp is None:
                return
            if start_resp.get("cmd") == "beginUploadFirmwareFailed":
                self._log(f"[ERROR] Device refused: {start_resp.get('message','')}\n", "error")
                self._upload_failed.set()
                return

            uploaded = 0
            chunk_size = 1000
            start_time = time.time()

            with open(file_path, "rb") as f:
                while True:
                    if self._upload_failed.is_set():
                        break
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break

                    ws_up.send_binary(chunk)

                    uploaded += len(chunk)
                    elapsed = time.time() - start_time
                    progress = uploaded / file_size if file_size else 0
                    pct = int(progress * 100)
                    speed = f"{self._fmt_bytes(uploaded / elapsed)}/s" if elapsed > 0.1 else "0B/s"

                    self.after(0, lambda p=progress: self._progress_bar.set(p))
                    self.after(0, lambda pp=pct, ub=uploaded, fs=file_size, sp=speed:
                               self._progress_label.configure(
                                   text=f"{pp}% [{self._fmt_bytes(ub)}/{self._fmt_bytes(fs)}] {sp}"))

            time.sleep(0.1)

            if not self._upload_failed.is_set():
                end_cmd = json.dumps({"cmd": "uploadFirmwareEnd"})
                ws_up.send(end_cmd)
                self._log("[INFO] Sent uploadFirmwareEnd\n", "info")

                end_resp = self._wait_for_response(ws_up, "uploadFirmwareSuccess",
                                                    "uploadFirmwareFailed")
                if end_resp is None:
                    self._log("[ERROR] No response after upload\n", "error")
                elif end_resp.get("cmd") == "uploadFirmwareSuccess":
                    self._log("[SUCCESS] Firmware uploaded and flashed!\n", "success")
                else:
                    self._log(f"[ERROR] Flash failed: {end_resp.get('message','')}\n", "error")
            else:
                self._log("[ERROR] Upload cancelled or failed\n", "error")

        except Exception as e:
            self._log(f"[ERROR] Upload exception: {e}\n", "error")
            self._upload_failed.set()
        finally:
            if ws_up:
                ws_up.close()
            self._uploading = False
            self.after(0, self._reset_upload_ui)

    def _reset_upload_ui(self):
        self._progress_frame.grid_remove()
        self._progress_bar.set(0)
        self._progress_label.configure(text="0% (0B/0B) 0B/s")
        self._send_btn.configure(state="normal" if self._connected else "disabled")
        self._upload_btn.configure(text="Upload Firmware", command=self._start_upload,
                                    state="normal" if self._connected else "disabled")

    def _wait_for_response(self, ws, *expected_cmds):
        timeout = 10
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self._upload_failed.is_set():
                return None
            try:
                resp = ws.recv()
            except Exception as e:
                self._log(f"[ERROR] WS recv error: {e}\n", "error")
                return None
            if not resp:
                continue
            try:
                data = json.loads(resp)
                if isinstance(data, dict) and data.get("cmd") in expected_cmds:
                    return data
            except json.JSONDecodeError:
                pass
        self._log("[ERROR] Timeout waiting for response\n", "error")
        return None

    @staticmethod
    def _fmt_bytes(n):
        if n < 1024:
            return f"{n}B"
        elif n < 1024 * 1024:
            return f"{n / 1024:.1f}KB"
        else:
            return f"{n / (1024 * 1024):.1f}MB"


if __name__ == "__main__":
    customtkinter.set_appearance_mode("System")
    customtkinter.set_default_color_theme("blue")
    app = DebugTool()
    app.mainloop()
