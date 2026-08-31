# Smart Home Platform — Tài Liệu Giao Thức Nhị Phân (Binary Protocol)

Thư mục `firmware/src/protocol` chứa toàn bộ kiến trúc giao thức truyền thông nhị phân tối ưu hóa cao dành riêng cho hệ sinh thái IoT ($0 Cost Ecosystem). Giao thức thay thế hoàn toàn JSON trên MCU, giúp:
1. **Tiết kiệm 70–85% băng thông mạng** truyền tải qua MQTT và WebSocket.
2. **Loại bỏ phân mảnh heap & giảm tải CPU**: Không còn parse chuỗi string json nặng nề.
3. **Cấp phát bộ nhớ động tự động kiểu `std::vector`** (100 byte ban đầu, nhân đôi, max +500 byte/lần, tự động giải phóng RAII).
4. **Real-time Auto-Header & Ready-to-Send**: Tự động mở Root Object, tự động cập nhật độ dài mọi Object/Array cha và tự động gắn `MAGIC_END (0xA5)` sau mỗi lệnh ghi. Bạn có thể lấy `writer.data()` gửi đi ngay lập tức mà không cần gọi `end()` hay `finalizeFrame()`.

---

## 1. Cấu Trúc Khung Tin (Frame & TLV Layout)

Mọi gói tin giao tiếp qua MQTT (`devices/{id}/cmd`, `devices/{id}/up`, `devices/{id}/log`) và WebSocket đều có cấu trúc chuẩn:

```
+-------------------+--------------------+----------------------------+-----------------+
| MAGIC_START (1B)  |   CommandId (1B)   |   TLV Encoded Body (N B)   |  MAGIC_END (1B) |
|      0xB7         |     0x00..0xFF     |     Payload các trường     |      0xA5       |
+-------------------+--------------------+----------------------------+-----------------+
```

### Định dạng từng trường TLV (Type-Length-Value)

Mỗi trường dữ liệu được mã hóa header siêu nhỏ gọn:
- **Kiểu dữ liệu cố định (Fixed-size)**: Header 2 bytes `[ID: 9-bit | Type: 4-bit | Reserved: 3-bit]` + Dữ liệu.
- **Kiểu dữ liệu biến thiên (Variable-size: String, Bytes, Object, Array)**: Header 3 bytes `[ID: 9-bit | Type: 4-bit | LenHigh: 3-bit] [LenLow: 8-bit]` + Dữ liệu (hỗ trợ dung lượng đến 2047 bytes/field).
- **Phần tử không có ID (Root Object / Array Items)**: Mang `FieldId::None` (giá trị `0`).

---

## 2. Danh Sách Các File & Nhiệm Vụ Chi Tiết

| File | Nhiệm vụ chính |
|---|---|
| `BinaryProtocol.h` | Hằng số Magic byte (`0xB7`, `0xA5`) & tự động include `BinaryFrame.h` |
| `BinaryProtocolTypes.h` | Enum các kiểu dữ liệu `BinaryType` và mã lỗi `BinaryError` |
| `BinaryCommandIds.h` | Enum 36 mã lệnh `CommandId` và hàm chuyển đổi string ↔ enum |
| `BinaryFieldIds.h` | Enum 127 mã trường `FieldId` định danh các thuộc tính (`None = 0`) |
| `BinaryFrame.h` | Các hàm tiện ích kiểm tra, đóng gói, phân giải frame nhị phân |
| `BinaryWriter.h / .cpp` | Serializer nhị phân tự động đồng bộ header real-time và duy trì frame hoàn chỉnh (RAII) |
| `BinaryReader.h / .cpp` | Deserializer nhị phân đọc tuần tự không cấp phát bộ nhớ (Zero-allocation) |
| `CommandContext.h` | Abstract Interface chuẩn cho việc đọc `CommandRequest` và ghi `CommandResponse` |
| `CommandContextBinary.h` | Implementation cụ thể nối `CommandRequest` với `BinaryReader` và `CommandResponse` với `BinaryWriter` |

---

## 3. Chi Tiết `BinaryWriter` — Cơ Chế Auto-Header Real-Time

### Khởi tạo & Trạng thái:
- `BinaryWriter(CommandId cmdId)`: Tự động ghi `0xB7`, `CommandId`, mở Root Object Header, và đặt `0xA5` ở cuối. Gói tin hợp lệ và sẵn sàng gửi ngay từ lúc khởi tạo.
- `writer.writeBool(FieldId id, bool value)`
- `writer.writeU8 / writeU16 / writeU32 / writeU64(FieldId id, ...)`
- `writer.writeI8 / writeI16 / writeI32 / writeI64(FieldId id, ...)`
- `writer.writeFloat32 / writeFloat64(FieldId id, ...)`
- `writer.writeString(FieldId id, const char* str)` / `writeString(FieldId id, const String& str)`
- `writer.writeBytes(FieldId id, const uint8_t* data, uint16_t len)`
- `writer.beginObject(FieldId id = FieldId::None)` / `writer.beginArray(FieldId id = FieldId::None)`
- `writer.data()` / `writer.size()`: Luôn luôn là một frame nhị phân hoàn chỉnh gồm `[0xB7] [CmdId] [Root Header chuẩn độ dài] [...] [0xA5]`.

> 🚀 **Không cần gọi bất kỳ hàm kết thúc nào**:
> Không cần gọi `root.end()`, `endObject()`, `endArray()`, hay `finalizeFrame()`. Mỗi lần gọi hàm `write*` hoặc `beginObject/Array`, `BinaryWriter` tự động tính và cập nhật ngược độ dài của tất cả các container cha lên tới Root và dời byte `0xA5` về cuối!

---

## 4. Hướng Dẫn Sử Dụng Thực Tế

### Ví dụ 1: Gửi lệnh/bản tin đơn giản
```cpp
protocol::BinaryWriter writer(protocol::CommandId::SetRelay);
writer.writeBool(protocol::FieldId::State, on);
writer.writeU32(protocol::FieldId::Ts, ts);

// Gửi đi luôn!
mqttClient.publishBinary(topic, writer.data(), writer.size());
```

---

### Ví dụ 2: Object và Mảng lồng nhau (Nested Objects & Arrays)
```cpp
protocol::BinaryWriter writer(protocol::CommandId::GetStatus);
writer.writeString(protocol::FieldId::Status, "ok");

// Mở object con 'pump'
auto pump = writer.beginObject(protocol::FieldId::Pump);
pump.writeBool(protocol::FieldId::Relay, true);
pump.writeFloat32(protocol::FieldId::Power, 250.0f);
// Không cần pump.end()! Cả pump và root đều đã được cập nhật length chính xác!

// Mở mảng 'fields'
auto fields = writer.beginArray(protocol::FieldId::Fields);
fields.writeString(protocol::FieldId::None, "ap");
fields.writeString(protocol::FieldId::None, "ws");
// Không cần fields.end()!

// Gửi đi luôn!
mqttClient.publishBinary(topic, writer.data(), writer.size());
```

---

### Ví dụ 3: Xử lý lệnh trong Device Driver bằng `CommandRequest` & `CommandResponse`
```cpp
bool PumpDriver::handleCmd(const char* cmd, const protocol::CommandRequest& req, protocol::CommandResponse& resp) {
    if (strcmp(cmd, "setRelay") == 0) {
        bool on = false;
        if (!req.getBool(protocol::FieldId::State, on)) {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Missing state");
            return true;
        }

        setRelay(on);

        // Trả lời phản hồi
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::State, on ? "on" : "off");
        return true;
    }
    return false;
}
```
