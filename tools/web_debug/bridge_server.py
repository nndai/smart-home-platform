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

HTTP_PORT = 8000
WS_PORT = 8080

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
        self.mqtt_topic_ota = None
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

    def connect_mqtt(self, broker, port, user, password, topic_pub, topic_sub, topic_ota, control_key=None, sender_id=None):
        self.disconnect(quiet=True)
        self.is_connected = True
        self.connect_id += 1
        current_id = self.connect_id
        self.protocol = 'mqtt'
        self.mqtt_topic_pub = topic_pub
        self.mqtt_topic_ota = topic_ota
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
                                if cmd_id == 50:  # OtaProgress
                                    if self._ota_ack:
                                        self._ota_ack.set()
                                elif cmd_id == 16:  # OtaChunk
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

    def send(self, data):
        if self.protocol == 'ws' and self.device_ws and self.is_connected:
            try:
                if isinstance(data, (bytes, bytearray)):
                    self.device_ws.send_binary(data)
                else:
                    self.device_ws.send(data)
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

    def send_ota_chunk(self, chunk):
        if self.protocol == 'ws' and self.device_ws and self.is_connected:
            try:
                self.device_ws.send_binary(chunk)
            except Exception as e:
                print(f"[BRIDGE] WS send OTA error: {e}")
                self.disconnect()
        elif self.protocol == 'mqtt' and self.mqtt and self.is_connected:
            try:
                target_topic = self.mqtt_topic_ota if self.mqtt_topic_ota else self.mqtt_topic_pub
                self.mqtt.publish(target_topic, chunk)
            except Exception as e:
                print(f"[BRIDGE] MQTT send OTA error: {e}")
                self.disconnect()

    def start_upload(self, data_bytes):
        self.uploading = True
        self._ota_ack = threading.Event() if self.protocol == 'mqtt' else None

        def upload_task():
            chunk_size = 1024
            total = len(data_bytes)
            print(f"[BRIDGE] Đang nạp {total} bytes xuống chip (via {self.protocol})...")

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

            chunk = data_bytes[i:i+chunk_size]
            self.send_ota_chunk(chunk)
            time.sleep(0.01)

            pct = int(((i + len(chunk)) / total) * 100)
            if pct != pre_pct:
                pre_pct = pct
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                    "cmd": "bridgeProgress",
                    "pct": pct,
                    "uploaded": i + len(chunk),
                    "total": total
                })), self.loop)

    def _mqtt_upload(self, data_bytes, chunk_size, total):
        one_pct = max(1, total // 100)
        offset = 0
        batch_idx = 0

        print(f"[BRIDGE] MQTT upload: {total} bytes, 1% = {one_pct} bytes")

        while offset < total and self.uploading and self.is_connected:
            batch_idx += 1
            target_pct = batch_idx * 5 + 1
            if target_pct > 100:
                target_pct = 100
            target_bytes = (target_pct * total + 99) // 100

            while offset < target_bytes and offset < total and self.uploading and self.is_connected:
                chunk_end = min(offset + chunk_size, target_bytes, total)
                self.send_ota_chunk(data_bytes[offset:chunk_end])
                offset = chunk_end
                time.sleep(0.4)
                # Không broadcast bridgeProgress 100% từ bridge
                # (chờ MCU ack mới báo 100%)
                if offset < total:
                    pct = int((offset / total) * 100)
                    asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                        "cmd": "bridgeProgress",
                        "pct": pct,
                        "uploaded": offset,
                        "total": total
                    })), self.loop)

            if offset >= total or target_pct >= 100:
                break

            milestone_pct = batch_idx * 5
            curr_pct = int((offset / total) * 100)

        if self.uploading and offset >= total:
            time.sleep(10)
            print("[BRIDGE] Upload MQTT hoàn tất")
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
                
                if os.path.exists(bin_path):
                    fw_file = "firmware.bin"
                    fw_path = bin_path
                elif os.path.exists(uf2_path):
                    fw_file = "firmware.uf2"
                    fw_path = uf2_path
                
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
