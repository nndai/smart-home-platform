"""
Remote Pump Bridge Server
Combines a Local HTTP Server (for Web UI) and a WebSocket Proxy (for Device).

Requirements:
    pip install websockets

Usage:
    python bridge_server.py
"""

import os
import sys
import json
import time
import asyncio
import threading
import http.server
import socketserver

try:
    import websockets
    import websocket
    import paho.mqtt.client as mqtt_client
except ImportError:
    print("ERROR: Missing 'websockets', 'websocket-client', or 'paho-mqtt' library.")
    print("Please run: pip install websockets websocket-client paho-mqtt")
    sys.exit(1)

import hashlib
import hmac

HTTP_PORT = 8000
WS_PORT = 8080

def create_ota_chunk_frame(chunk_bytes, control_key=None, sender_id="web-debug"):
    """
    Encodes an OTA Chunk binary frame (CommandId::OtaChunk = 16)
    Field Data (ID 82, Type 13 = BYTES)
    Envelope fields: TS (111, UINT32), SRC (113, STRING), HMAC (112, STRING)
    """
    data_len = len(chunk_bytes)
    f_data_hdr = bytearray([
        (82 >> 1) & 0xFF,
        (((82 & 1) << 7) | (13 << 3) | ((data_len >> 8) & 0x07)) & 0xFF,
        data_len & 0xFF
    ])
    payload = bytearray()
    payload.extend(f_data_hdr)
    payload.extend(chunk_bytes)

    if control_key:
        TZ_OFFSET_SEC = 25200
        now_ts = int(time.time()) + TZ_OFFSET_SEC
        src = sender_id or "web-debug"
        src_bytes = src.encode('utf-8')
        src_len = len(src_bytes)

        # Canonical string: ts|cmd||src
        canonical = f"{now_ts}|otaChunk||{src}"
        key_bytes = bytes.fromhex(control_key)
        hmac_hex = hmac.new(key_bytes, canonical.encode('utf-8'), hashlib.sha256).hexdigest()
        hmac_bytes = hmac_hex.encode('ascii')

        # Field TS (ID 111, Type 4 = UINT32)
        f_ts_hdr = bytearray([(111 >> 1) & 0xFF, (((111 & 1) << 7) | (4 << 3)) & 0xFF])
        payload.extend(f_ts_hdr)
        payload.extend(now_ts.to_bytes(4, byteorder='big'))

        # Field SRC (ID 113, Type 12 = STRING)
        f_src_hdr = bytearray([
            (113 >> 1) & 0xFF,
            (((113 & 1) << 7) | (12 << 3) | ((src_len >> 8) & 0x07)) & 0xFF,
            src_len & 0xFF
        ])
        payload.extend(f_src_hdr)
        payload.extend(src_bytes)

        # Field HMAC (ID 112, Type 12 = STRING, len = 64)
        f_hmac_hdr = bytearray([
            (112 >> 1) & 0xFF,
            (((112 & 1) << 7) | (12 << 3) | ((64 >> 8) & 0x07)) & 0xFF,
            64
        ])
        payload.extend(f_hmac_hdr)
        payload.extend(hmac_bytes)

    total_payload_len = len(payload)
    root_hdr = bytearray([
        0x00,
        (14 << 3) | ((total_payload_len >> 8) & 0x07),
        total_payload_len & 0xFF
    ])

    frame = bytearray([0xB7, 0x10])
    frame.extend(root_hdr)
    frame.extend(payload)
    frame.append(0xA5)
    return bytes(frame)

# --- HTTP Server (Background Thread) ---
def run_http_server():
    # Phục vụ thư mục web
    web_dir = os.path.join(os.path.dirname(__file__), "web")
    os.chdir(web_dir)
    
    class QuietHandler(http.server.SimpleHTTPRequestHandler):
        def end_headers(self):
            self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
            self.send_header('Pragma', 'no-cache')
            self.send_header('Expires', '0')
            super().end_headers()

        def log_message(self, format, *args):
            pass # Tắt log HTTP để terminal gọn gàng

    with socketserver.TCPServer(("", HTTP_PORT), QuietHandler) as httpd:
        print(f"[HTTP] Mở trình duyệt tại: http://localhost:{HTTP_PORT}")
        httpd.serve_forever()

# --- WebSocket Proxy (AsyncIO) cho Web UI ---
web_clients = set()

class BridgeManager:
    def __init__(self):
        self.device_ws = None
        self.recv_thread = None
        self.is_connected = False
        self.upload_thread = None
        self.uploading = False
        self.loop = None
        self.connect_id = 0
        self.protocol = 'ws'
        self.mqtt = None
        self.mqtt_topic_pub = None
        self.control_key = None
        self.sender_id = "web-debug"
        self.seq = 0
        self._ota_ack = None

    def connect(self, url):
        self.disconnect(quiet=True)
        self.is_connected = True
        self.connect_id += 1
        current_id = self.connect_id
        self.protocol = 'ws'
        
        def run_ws():
            try:
                # Dùng websocket-client ĐỒNG BỘ y như debug_ui.py
                self.device_ws = websocket.create_connection(url, timeout=10)
                if self.connect_id != current_id:
                    self.device_ws.close()
                    return
                    
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "bridgeConnected"})), self.loop)
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "log", "msg": "[DEVICE] Đã kết nối thành công (Dùng websocket-client)!"})), self.loop)
                
                while self.is_connected and self.connect_id == current_id:
                    try:
                        msg = self.device_ws.recv()
                        if msg:
                            if self.uploading:
                                if isinstance(msg, (bytes, bytearray)) and len(msg) >= 3 and msg[0] == 0xB7 and msg[-1] == 0xA5:
                                    cmd_id = msg[1]
                                    if b'error' in msg and cmd_id in (13, 14, 16, 51):
                                        print(f"[BRIDGE] MCU reported OTA error on cmd {cmd_id}! Stopping upload immediately.")
                                        self.stop_upload()
                                        asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "otaError", "message": f"MCU OTA error (cmd {cmd_id})"})), self.loop)
                                elif isinstance(msg, str) and '"error"' in msg:
                                    if any(k in msg for k in ('ota', 'upload', 'flash', 'chunk')):
                                        print(f"[BRIDGE] MCU reported OTA error in JSON! Stopping upload immediately.")
                                        self.stop_upload()
                                        asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "otaError", "message": "MCU OTA error"})), self.loop)

                            asyncio.run_coroutine_threadsafe(self.broadcast(msg), self.loop)
                    except websocket.WebSocketTimeoutException:
                        continue
                    except websocket.WebSocketException as e:
                        if "cannot decode" in str(e):
                            print(f"[BRIDGE] Bỏ qua log rác (lỗi UTF-8): {e}")
                            continue
                        print(f"[BRIDGE] recv error (WebSocketException): {e}")
                        break
                    except Exception as e:
                        print(f"[BRIDGE] recv error: {e}")
                        break
            except Exception as e:
                if self.connect_id == current_id:
                    asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "log", "msg": f"[DEVICE] Lỗi kết nối: {e}"})), self.loop)
            finally:
                if self.connect_id == current_id:
                    self.disconnect()

        self.recv_thread = threading.Thread(target=run_ws, daemon=True)
        self.recv_thread.start()

    def connect_mqtt(self, broker, port, user, password, topic_pub, topic_sub, topic_ota=None, control_key=None, sender_id=None):
        self.disconnect(quiet=True)
        self.is_connected = True
        self.connect_id += 1
        current_id = self.connect_id
        self.protocol = 'mqtt'
        self.mqtt_topic_pub = topic_pub
        self.control_key = control_key.strip() if control_key else None
        self.sender_id = sender_id.strip() if sender_id else "web-debug"
        
        def run_mqtt():
            try:
                import uuid
                client_id = f"web_bridge_{uuid.uuid4().hex[:8]}"
                # Support paho-mqtt v1 and v2
                try:
                    from paho.mqtt.enums import CallbackAPIVersion
                    self.mqtt = mqtt_client.Client(CallbackAPIVersion.VERSION2, client_id=client_id)
                except ImportError:
                    self.mqtt = mqtt_client.Client(client_id=client_id)

                if user or password:
                    self.mqtt.username_pw_set(user, password)

                if port == 8883:
                    import ssl
                    self.mqtt.tls_set()
                
                def on_connect(client, userdata, flags, *args, **kwargs):
                    # args[0] is rc (v1) or reason_code (v2)
                    rc = args[0] if args else 0
                    is_success = False
                    if hasattr(rc, 'is_failure'):
                        is_success = not rc.is_failure
                    else:
                        is_success = (rc == 0)

                    if is_success:
                        client.subscribe(topic_sub)
                        if self.loop:
                            asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "bridgeConnected"})), self.loop)
                            asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "log", "msg": "[DEVICE] Đã kết nối MQTT thành công!"})), self.loop)
                    else:
                        if self.loop:
                            asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "log", "msg": f"[DEVICE] Lỗi kết nối MQTT (rc={rc})" })), self.loop)
                
                def on_disconnect(client, userdata, *args, **kwargs):
                    # Stop reconnect loop if disconnected by broker
                    if self.connect_id == current_id:
                        self.disconnect()
                        try:
                            client.disconnect()
                        except:
                            pass
                
                def on_message(client, userdata, msg):
                    if self.is_connected and self.connect_id == current_id and self.loop:
                        try:
                            payload = msg.payload
                            # 1. Binary Protocol Frame
                            if payload and len(payload) >= 3 and payload[0] == 0xB7 and payload[-1] == 0xA5:
                                cmd_id = payload[1]
                                if self.uploading and b'error' in payload and cmd_id in (13, 14, 16, 51):
                                    print(f"[BRIDGE] MCU reported OTA error on MQTT cmd {cmd_id}! Stopping upload immediately.")
                                    self.stop_upload()
                                    asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "otaError", "message": f"MCU OTA error (cmd {cmd_id})"})), self.loop)

                                if cmd_id == 50:  # OtaProgress
                                    if self._ota_ack:
                                        self._ota_ack.set()
                                elif cmd_id == 16:  # OtaChunk
                                    if b'error' not in payload:
                                        return
                                asyncio.run_coroutine_threadsafe(self.broadcast(payload), self.loop)
                                return

                            # 2. Text / JSON payload
                            text = payload.decode('utf-8', errors='ignore')
                            try:
                                data = json.loads(text)
                                cmd = data.get('cmd', '')
                                if cmd == 'otaProgress':
                                    if self._ota_ack:
                                        self._ota_ack.set()
                                    pct = data.get('pct', 0)
                                    progress = data.get('progress', 0)
                                    total = data.get('total', 0)
                                    bridge_progress = json.dumps({
                                        "cmd": "bridgeProgress",
                                        "pct": pct,
                                        "uploaded": progress,
                                        "total": total
                                    })
                                    asyncio.run_coroutine_threadsafe(self.broadcast(bridge_progress), self.loop)
                                    return
                                if cmd == 'otaChunk':
                                    return
                            except Exception:
                                pass
                            asyncio.run_coroutine_threadsafe(self.broadcast(text), self.loop)
                        except Exception as e:
                            print(f"[BRIDGE] Lỗi parse MQTT: {e}")

                self.mqtt.on_connect = on_connect
                self.mqtt.on_message = on_message
                self.mqtt.on_disconnect = on_disconnect
                
                self.mqtt.connect(broker, port, 60)
                self.mqtt.loop_forever()
            except Exception as e:
                if self.connect_id == current_id and self.loop:
                    asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "log", "msg": f"[DEVICE] Lỗi kết nối MQTT: {e}"})), self.loop)
            finally:
                if self.connect_id == current_id:
                    self.disconnect()

        self.recv_thread = threading.Thread(target=run_mqtt, daemon=True)
        self.recv_thread.start()

    def disconnect(self, quiet=False):
        self.is_connected = False
        self.connect_id += 1
        if self.device_ws:
            try:
                self.device_ws.close()
            except:
                pass
            self.device_ws = None
        if self.mqtt:
            try:
                self.mqtt.disconnect()
            except:
                pass
            self.mqtt = None
        if self.loop and not quiet:
            asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({"cmd": "bridgeDisconnected"})), self.loop)

    def _send_ws_data(self, data):
        if not self.device_ws or not self.is_connected:
            return
        if isinstance(data, (bytes, bytearray)):
            if hasattr(self.device_ws, 'send_binary'):
                self.device_ws.send_binary(data)
            else:
                self.device_ws.send(data, opcode=websocket.ABNF.OPCODE_BINARY)
        else:
            self.device_ws.send(data)

    def send(self, data):
        if self.protocol == 'ws' and self.device_ws and self.is_connected:
            try:
                self._send_ws_data(data)
            except Exception as e:
                print(f"[BRIDGE] WS send error: {e}")
                self.disconnect()
        elif self.protocol == 'mqtt' and self.mqtt and self.is_connected:
            try:
                if isinstance(data, (bytes, bytearray)):
                    self.mqtt.publish(self.mqtt_topic_pub, data)
                elif isinstance(data, str):
                    self.mqtt.publish(self.mqtt_topic_pub, data)
            except Exception as e:
                print(f"[BRIDGE] MQTT send error: {e}")
                self.disconnect()

    def start_upload(self, data_bytes):
        self.uploading = True
        self._ota_ack = threading.Event() if self.protocol == 'mqtt' else None

        def upload_task():
            chunk_size = 1024
            total = len(data_bytes)
            target_dest = self.mqtt_topic_pub if self.protocol == 'mqtt' else 'WebSocket'
            print(f"[BRIDGE] Đang nạp {total} bytes xuống chip qua lệnh otaChunk ({self.protocol} -> {target_dest})...")

            if self.protocol == 'mqtt':
                self._mqtt_upload(data_bytes, chunk_size, total)
            else:
                self._ws_upload(data_bytes, chunk_size, total)

            self.uploading = False

        self.upload_thread = threading.Thread(target=upload_task, daemon=True)
        self.upload_thread.start()

    def _ws_upload(self, data_bytes, chunk_size, total):
        pre_pct = 0
        for i in range(0, total, chunk_size):
            if not self.uploading or not self.is_connected:
                print("[BRIDGE] Đã hủy tiến trình nạp Firmware!")
                break

            raw_chunk = data_bytes[i:i+chunk_size]
            chunk_frame = create_ota_chunk_frame(raw_chunk, None, self.sender_id)
            if self.device_ws and self.is_connected:
                try:
                    self._send_ws_data(chunk_frame)
                except Exception as e:
                    print(f"[BRIDGE] WS send OTA error: {e}")
                    self.disconnect()
            time.sleep(0.01)

            pct = int(((i + len(raw_chunk)) / total) * 100)
            if pct != pre_pct:
                pre_pct = pct
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                    "cmd": "bridgeProgress",
                    "pct": pct,
                    "uploaded": i + len(raw_chunk),
                    "total": total
                })), self.loop)

    def _mqtt_upload(self, data_bytes, chunk_size, total):
        offset = 0
        pre_pct = -1
        print(f"[BRIDGE] MQTT OTA streaming (Command otaChunk): {total} bytes to '{self.mqtt_topic_pub}', chunk size: {chunk_size}")

        while offset < total and self.uploading and self.is_connected:
            chunk_end = min(offset + chunk_size, total)
            raw_chunk = data_bytes[offset:chunk_end]
            chunk_frame = create_ota_chunk_frame(raw_chunk, self.control_key, self.sender_id)

            if self.mqtt and self.is_connected:
                try:
                    self.mqtt.publish(self.mqtt_topic_pub, chunk_frame)
                except Exception as e:
                    print(f"[BRIDGE] MQTT send OTA error: {e}")
                    self.disconnect()

            offset = chunk_end
            time.sleep(0.2)  # ####

            pct = int((offset / total) * 100)
            if pct != pre_pct:
                pre_pct = pct
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                    "cmd": "bridgeProgress",
                    "pct": pct,
                    "uploaded": offset,
                    "total": total
                })), self.loop)

        if self.uploading and offset >= total:
            time.sleep(10)  # ####
            print("[BRIDGE] Upload MQTT hoàn tất (Đã đẩy 100% chunks)")
            asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                "cmd": "bridgeProgress",
                "pct": 100,
                "uploaded": total,
                "total": total
            })), self.loop)

    def stop_upload(self):
        self.uploading = False

    async def broadcast(self, message):
        for client in list(web_clients):
            try:
                await client.send(message)
            except:
                pass

def get_build_targets():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(base_dir, "..", ".."))
    
    candidates = [
        os.path.join(project_root, "firmware", ".pio", "build"),
        os.path.join(project_root, ".pio", "build")
    ]
    
    build_dir = None
    for cand in candidates:
        if os.path.isdir(cand):
            build_dir = cand
            break
            
    if not build_dir:
        build_dir = candidates[0]

    targets = []
    if os.path.isdir(build_dir):
        for entry in sorted(os.listdir(build_dir)):
            sub_path = os.path.join(build_dir, entry)
            if os.path.isdir(sub_path) and not entry.startswith('.'):
                bin_path = os.path.join(sub_path, "firmware.bin")
                uf2_path = os.path.join(sub_path, "firmware.uf2")
                
                fw_file = None
                fw_path = None
                
                if os.path.exists(uf2_path):
                    fw_file = "firmware.uf2"
                    fw_path = uf2_path
                elif os.path.exists(bin_path):
                    fw_file = "firmware.bin"
                    fw_path = bin_path
                
                if fw_path and os.path.exists(fw_path):
                    st = os.stat(fw_path)
                    size = st.st_size
                    mtime = st.st_mtime
                    mtime_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(mtime))
                    targets.append({
                        "name": entry,
                        "file": fw_file,
                        "path": fw_path.replace("\\", "/"),
                        "relPath": os.path.relpath(fw_path, project_root).replace("\\", "/"),
                        "size": size,
                        "mtime": mtime,
                        "mtimeStr": mtime_str,
                        "exists": True
                    })
                else:
                    targets.append({
                        "name": entry,
                        "file": "firmware.bin",
                        "path": bin_path.replace("\\", "/"),
                        "relPath": os.path.relpath(bin_path, project_root).replace("\\", "/"),
                        "size": 0,
                        "mtime": 0,
                        "mtimeStr": "N/A",
                        "exists": False
                    })
                    
    rel_build_dir = os.path.relpath(build_dir, project_root).replace("\\", "/") if os.path.exists(build_dir) else "firmware/.pio/build"
    return {
        "cmd": "bridgeBuildTargets",
        "buildDir": rel_build_dir,
        "targets": targets
    }

bridge = BridgeManager()

async def handle_web_client(ws_client, path=None):
    web_clients.add(ws_client)
    print("[WEB] Trình duyệt Web vừa kết nối tới Bridge")
    try:
        # Tự động gửi danh sách build targets khi client vừa kết nối
        try:
            await ws_client.send(json.dumps(get_build_targets()))
        except Exception:
            pass

        async for message in ws_client:
            # 1. Xử lý lệnh điều khiển Bridge từ Web UI (dạng JSON string)
            if isinstance(message, str):
                try:
                    data = json.loads(message)
                    if data.get("cmd") == "bridgeScanBuildTargets":
                        await ws_client.send(json.dumps(get_build_targets()))
                        continue
                    elif data.get("cmd") == "bridgeConnect":
                        await ws_client.send(json.dumps({"cmd": "log", "msg": f"[DEVICE] Đang kết nối tới {data['url']}..."}))
                        bridge.connect(data["url"])
                        continue
                    elif data.get("cmd") == "bridgeConnectMqtt":
                        broker = data.get("broker")
                        port = data.get("port")
                        topic_ota = data.get("topic_ota", "pump/otachunk")
                        control_key = data.get("control_key")
                        sender_id = data.get("sender_id")
                        await ws_client.send(json.dumps({"cmd": "log", "msg": f"[DEVICE] Đang kết nối MQTT tới {broker}:{port}..."}))
                        bridge.connect_mqtt(broker, port, data.get("user"), data.get("password"), data.get("topic_pub"), data.get("topic_sub"), topic_ota, control_key=control_key, sender_id=sender_id)
                        continue
                    elif data.get("cmd") == "bridgeDisconnect":
                        bridge.disconnect()
                        continue
                    elif data.get("cmd") == "bridgeCancelUpload":
                        bridge.stop_upload()
                        continue
                    elif data.get("cmd") == "bridgeStartUploadLocal":
                        try:
                            file_path = data.get("path", "")
                            if not os.path.exists(file_path):
                                await ws_client.send(json.dumps({"cmd": "log", "msg": f"[ERROR] File not found: {file_path}"}))
                                continue
                            with open(file_path, "rb") as f:
                                file_bytes = f.read()
                            bridge.start_upload(file_bytes)
                        except Exception as e:
                            await ws_client.send(json.dumps({"cmd": "log", "msg": f"[ERROR] Could not read file: {e}"}))
                        continue
                    elif data.get("cmd") == "bridgeGetFileInfo":
                        try:
                            file_path = data.get("path", "")
                            if os.path.exists(file_path):
                                size = os.path.getsize(file_path)
                                await ws_client.send(json.dumps({"cmd": "bridgeFileInfo", "size": size, "path": file_path}))
                            else:
                                await ws_client.send(json.dumps({"cmd": "bridgeFileInfoError", "msg": "File not found"}))
                        except Exception as e:
                            await ws_client.send(json.dumps({"cmd": "bridgeFileInfoError", "msg": str(e)}))
                        continue
                except json.JSONDecodeError:
                    pass

            # 2. Xử lý dữ liệu ném xuống MCU
            if bridge.is_connected:
                if isinstance(message, (bytes, bytearray)):
                    if len(message) >= 3 and message[0] == 0xB7 and message[-1] == 0xA5:
                        await asyncio.to_thread(bridge.send, message)
                    else:
                        bridge.start_upload(message)
                else:
                    # Ném lệnh send sang Thread pool để không block AsyncIO
                    await asyncio.to_thread(bridge.send, message)
            else:
                pass
                
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        web_clients.remove(ws_client)
        print("[WEB] Trình duyệt Web ngắt kết nối")

async def main():
    bridge.loop = asyncio.get_running_loop()
    print(f"[WS] Mở cổng Localhost Proxy tại ws://localhost:{WS_PORT}")
    server = await websockets.serve(handle_web_client, "0.0.0.0", WS_PORT, max_size=None)
    await server.wait_closed()

if __name__ == "__main__":
    t = threading.Thread(target=run_http_server, daemon=True)
    t.start()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nĐã tắt Server.")
