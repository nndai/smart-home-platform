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
    # Phục vụ thư mục web_debug
    web_dir = os.path.join(os.path.dirname(__file__), "web_debug")
    os.chdir(web_dir)
    
    class QuietHandler(http.server.SimpleHTTPRequestHandler):
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

    def connect_mqtt(self, broker, port, user, password, topic_pub, topic_sub, topic_ota):
        self.disconnect(quiet=True)
        self.is_connected = True
        self.connect_id += 1
        current_id = self.connect_id
        self.protocol = 'mqtt'
        self.mqtt_topic_pub = topic_pub
        self.mqtt_topic_ota = topic_ota
        
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
                            # Assume text payload for Web UI
                            text = msg.payload.decode('utf-8', errors='ignore')
                            # Kiểm tra otaProgress - signal cho upload thread
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
                                    # Bỏ qua otaChunk response để tránh spam
                                    return
                            except:
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
                if isinstance(data, bytes):
                    self.device_ws.send_binary(data)
                else:
                    self.device_ws.send(data)
            except Exception as e:
                print(f"[BRIDGE] WS send error: {e}")
                self.disconnect()
        elif self.protocol == 'mqtt' and self.mqtt and self.is_connected:
            try:
                if isinstance(data, str):
                    self.mqtt.publish(self.mqtt_topic_pub, data)
                else:
                    import base64
                    import json
                    b64 = base64.b64encode(data).decode('ascii')
                    chunk_msg = json.dumps({"cmd": "otaChunk", "payload": {"data": b64}})
                    self.mqtt.publish(self.mqtt_topic_ota, chunk_msg)
            except Exception as e:
                print(f"[BRIDGE] MQTT send error: {e}")
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
            self.send(chunk)
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
                self.send(data_bytes[offset:chunk_end])
                offset = chunk_end
                time.sleep(0.1)
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
            print(f"[BRIDGE] Đã gửi {curr_pct}%, chờ MCU ack (milestone {milestone_pct}%)...")
            self._ota_ack.clear()
            if not self._ota_ack.wait(timeout=60):
                print(f"[BRIDGE] Timeout! MCU không xác nhận milestone {milestone_pct}%")
                asyncio.run_coroutine_threadsafe(self.broadcast(json.dumps({
                    "cmd": "otaError",
                    "message": f"MCU không phản hồi tại {milestone_pct}% (timeout 60s)"
                })), self.loop)
                self.uploading = False
                return

        if self.uploading and offset >= total:
            print(f"[BRIDGE] Đã gửi hết {total} bytes, chờ ack cuối...")
            self._ota_ack.clear()
            self._ota_ack.wait(timeout=60)
            print("[BRIDGE] Upload MQTT hoàn tất")

    def stop_upload(self):
        self.uploading = False

    async def broadcast(self, message):
        for client in list(web_clients):
            try:
                await client.send(message)
            except:
                pass

bridge = BridgeManager()

async def handle_web_client(ws_client, path=None):
    web_clients.add(ws_client)
    print("[WEB] Trình duyệt Web vừa kết nối tới Bridge")
    try:
        async for message in ws_client:
            # 1. Xử lý lệnh điều khiển Bridge từ Web UI (dạng JSON string)
            if isinstance(message, str):
                try:
                    data = json.loads(message)
                    if data.get("cmd") == "bridgeConnect":
                        await ws_client.send(json.dumps({"cmd": "log", "msg": f"[DEVICE] Đang kết nối tới {data['url']}..."}))
                        bridge.connect(data["url"])
                        continue
                    elif data.get("cmd") == "bridgeConnectMqtt":
                        broker = data.get("broker")
                        port = data.get("port")
                        topic_ota = data.get("topic_ota", "pump/otachunk")
                        await ws_client.send(json.dumps({"cmd": "log", "msg": f"[DEVICE] Đang kết nối MQTT tới {broker}:{port}..."}))
                        bridge.connect_mqtt(broker, port, data.get("user"), data.get("password"), data.get("topic_pub"), data.get("topic_sub"), topic_ota)
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
                if isinstance(message, bytes):
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
