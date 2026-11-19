# Lưu trữ và đồng bộ log v2

Đã nối writer firmware, binary protocol và app Android. Thiết kế tham khảo nằm trong `LOG_STORAGE_AND_SYNC_PLAN.md`. Các số cấu hình bên dưới là giá trị khởi đầu; chưa có benchmark hay thử cắt nguồn trên MCU thật.

## Thành phần

- `firmware/src/core/log/LogRecordCodec.*`: định dạng byte, framing và CRC32.
- `LogStorageManager.*`: queue, catalog RAM, checkpoint A/B, recovery theo tick, quota, state/sync/list/delete.
- `firmware/src/compat/storage_lock.h`: mutex không chờ và ID ngẫu nhiên theo nền tảng.
- `firmware/src/compat/log_storage_config.h`: giới hạn có thể override bằng build flags.
- `app/.../data/local/SegmentLogCache.kt`: lưu byte và cursor trong cùng transaction SQLite.
- `SegmentLogReader.kt`: kiểm tra record và nhóm theo ngày; giữ dữ liệu chưa xác định giờ.
- `LogSyncCoordinator.kt`, `LegacyLogSync.kt`: request tuần tự, snapshot, retry giới hạn, nhập lịch sử cũ.
- Màn hình log hệ thống có **Quản lý dữ liệu trên thiết bị**, hiển thị khoảng thời gian và xác nhận trước khi xóa segment. Bản đã tải tiếp tục nằm trong cache.

## Giới hạn mặc định

| Tham số | Giá trị |
|---|---:|
| Segment mỗi stream | Tối đa 16 |
| Payload một record | 400 byte UTF-8 |
| Chunk đọc | Tối đa 768 byte |
| Queue power/toggle | 6 record |
| Queue sys | 8 record |
| Worker | 25 ms/tick, một record ghi hoặc một bước recovery |
| Checkpoint định kỳ | Mỗi 30 giây, luân phiên stream đang dirty |
| Segment | Khoảng quota/8; giới hạn 1–32 KiB |
| Free thấp | Lớn hơn hoặc bằng 16 KiB và 5% partition |
| Free mục tiêu | Free thấp + max(8 KiB, 2,5% partition) |
| Dự trữ ngoài quota | 16 KiB bên cạnh free mục tiêu |
| Chia ngân sách log còn lại | Power 55%, toggle 25%, sys 20% |

Override `LOG_V2_MAX_SEGMENTS`, `LOG_V2_IMPORTANT_QUEUE`, `LOG_V2_SYS_QUEUE`, `LOG_V2_TICK_MS`, `LOG_V2_CHECKPOINT_MS`, `LOG_V2_FREE_MIN`, `LOG_V2_SEGMENT_MAX` tại từng env PlatformIO. Trần schema/payload/chunk cần giữ tương thích với parser app. Các macro quota v1 cũ trong `Config.h` không quyết định quota v2.

Queue ưu tiên giữ chỗ riêng cho power/toggle. Producer không chờ flash hoặc mutex storage. Record chưa kịp commit có thể mất khi ngắt nguồn; queue đầy tăng dropped counter. Nếu ghi thiếu hoặc đọc kiểm tra lại thất bại, segment được seal; writer không nối tiếp sau đuôi lỗi.

Cleanup xóa tối đa một segment mỗi bước, ưu tiên sys → toggle → power. Active file được seal/rotate trước khi xóa. Nếu không có đủ dự trữ để tạo segment tiếp theo an toàn, hệ thống bỏ record mới và báo `storage_full`. Checkpoint lưu allocation high-water mark trước khi unlink. Clear tạo segment rỗng của generation mới trước khi dọn generation cũ, giúp phục hồi cả sau khi catalog hỏng.

## Định dạng trên flash

Đường dẫn: `/logs/v2/{power,toggle,sys}/000000000001.seg`. ID là uint32, tên có 12 chữ số; ID không được quay vòng. Tất cả số nguyên trên đĩa là little endian. CRC32 IEEE dùng polynomial đảo `0xEDB88320`.

| Header segment: offset | Nội dung |
|---:|---|
| 0 | `SHL2`, 4 byte |
| 4 | Schema `2`, 1 byte |
| 5 | Stream: power=0, toggle=1, sys=2 |
| 6 | Reserved, 2 byte |
| 8 | Generation, uint32 |
| 12 | Segment ID, uint32 |
| 16 | Reserved, 4 byte |
| 20 | CRC32 của 20 byte đầu |

| Header record: offset | Nội dung |
|---:|---|
| 0 | Magic `0x52` |
| 1 | Schema `2` |
| 2 | Type: data=1, time anchor=2 |
| 3 | Reserved |
| 4 | Độ dài payload, uint32, tối đa 400 |
| 8 | Boot ID, uint32 |
| 12 | Uptime theo giây, uint32; worker mở rộng các vòng `millis()` |
| 16 | Epoch hoặc 0 nếu chưa biết giờ, uint32 |
| 20 | Payload UTF-8 |
| 20 + độ dài | CRC32 của header record và payload |

Payload power: `hour|energyWh|durationSeconds`; duration hiện là 3600. Toggle: `source|state`. Sys: message đã giới hạn độ dài, giữ prefix level nếu nguồn cung cấp. Power không ghi khi chưa đồng bộ giờ, giữ chính sách cũ. Mỗi lần đồng bộ giờ đầu tiên trong boot, firmware enqueue time anchor cho ba stream. App chỉ ánh xạ record không có epoch khi có anchor cùng generation/boot; trường hợp khác hiển thị **Chưa xác định**. Định dạng epoch giữ cách dùng giờ hiện có của `TimeManager` và nhóm bằng UTC trong app.

Worker lấy mẫu đồng hồ mỗi tick, giữ phần dư millisecond và mở rộng vòng `millis()` trước khi encode uptime theo giây. Producer đọc một snapshot đồng hồ bằng số lượt thử có giới hạn; không chờ mutex ghi flash.

Checkpoint A/B có sequence, generation, next ID, boot, dropped count, entry cố định và CRC32. Boot đọc hai slot, đối chiếu các header segment rồi kiểm tra record theo từng tick. Hiện recovery kiểm tra lại cả các segment được giữ; thời gian khởi động storage tăng theo số record, nhưng mỗi tick chỉ xử lý một record. Catalog hỏng không dẫn tới format.

Các segment đều được seal sau reboot. Bản ghi mới luôn đi vào segment mới. File cũ chưa có trong catalog hoặc thuộc generation trước được dọn theo từng entry sau recovery/clear; không tiếp tục quét nền vô hạn. Khi storage còn recovery, điều khiển vẫn chạy và log chỉ dùng queue giới hạn.

## Binary API

Các command dùng cơ chế xác thực/envelope và `reqId` đang có. Cả C++, Kotlin và web debug dùng cùng bảng ID.

| Command | ID | Request |
|---|---:|---|
| `getLogState` | 36 | Không có stream: hỏi cả ba; hoặc `stream` |
| `syncLog` | 37 | `stream`, `generation`, `bootId`, `segmentId`, `offset`, `snapshotId`, `snapshotSize`, `limit` |
| `listLogSegments` | 38 | `stream`, `offset`; trang tiếp theo gửi lại `bootId`, `catalogRevision` |
| `deleteLogSegment` | 39 | `stream`, `generation`, `segmentId` |

Field mới dùng ID 180–199; xem `BinaryFieldIds.h`. State trả schema, token `(generation,bootId,revision)`, oldest ID, ID/size cuối, logical bytes, count, storage state và dropped count. Metadata lấy từ RAM, không list thư mục hoặc tính used bytes trong request này.

App lấy `segmentId/size` trong state làm snapshot đích cố định. Cursor 0/0 bắt đầu từ segment cũ nhất. Khi đọc xong một segment sealed, request tiếp theo chuyển sang segment kế tiếp. Response có `segmentId`, `offset`, `nextOffset`, `snapshotId`, `snapshotSize`, `data`, `more`, `sealed`. `sealed=true` trong response chunk nghĩa là đã đến ranh giới hoàn chỉnh của phần snapshot trong segment; không có nghĩa active file đã được đóng vật lý.

| Status | Xử lý |
|---|---|
| `ok` | Kiểm tra ID, offset, checksum, lưu byte/cursor trong một transaction |
| `up_to_date` | Đã tới snapshot, ghi nhận token state đã bắt đầu phiên |
| `busy`, `recovering` | Retry có backoff, tối đa 5 lượt |
| `generation_changed` | Hỏi lại state; không nối byte vào generation cũ |
| `snapshot_expired` | Boot/catalog đã đổi, bắt đầu phiên mới |
| `cursor_expired` | Báo lịch sử thiếu; giữ cache và đọc lại từ oldest còn giữ |
| `invalid_cursor` | Không chấp nhận offset ngoài committed range |
| `io_error`, `storage_full` | Báo lỗi; không retry nóng vô hạn |

Snapshot là stateless, không giữ file handle và không khóa retention giữa các chunk. File bị xóa có thể khiến request tiếp theo nhận `cursor_expired`. App chỉ kết thúc trạng thái sync sau khi các stream được chọn hoàn tất hoặc có lỗi/timeout. Response trễ hoặc trùng không khớp request đang đợi được bỏ qua. Hàng đợi yêu cầu sync ở app có một phần tử chờ, yêu cầu mới thay phần tử chờ cũ.

`scanEntries`, `readChunks`, `queueDepth`, `queueHighWater`, `droppedRecords` phục vụ đo trên thiết bị. Counter scan có thể tăng do recovery/cleanup riêng; gọi state không tự tăng scan hoặc open file.

## Dữ liệu v1 và file browser

Nâng cấp không chuyển đổi hay xóa toàn bộ `/logs/power`, `/logs/toggle`, `/logs/sys`. App mới nhập dữ liệu ngày cũ và `nosync.log` tuần tự, giới hạn một request đọc mỗi lúc, ghi nhớ đã nhập theo stream. Firmware v1 được nhận biết qua phản hồi **Unknown command** cho `getLogState`; app tiếp tục dùng v1. Timeout không bị hiểu nhầm là firmware cũ.

Firmware v2 chỉ ghi mới vào v2. App v1 vẫn đọc được lịch sử v1 nhưng không thấy record mới của v2; cần nâng cấp app cùng firmware. Xóa theo ngày của dữ liệu v2 bị từ chối và hướng tới màn hình quản lý segment. `clearSysLog` clear kho sys v2; các file v1 vẫn được giữ riêng.

File browser giới hạn 20 entry/trang; `limit=0` dùng mặc định 20. Không giữ vector toàn thư mục hoặc sort trên MCU. Offset phân trang vẫn phải duyệt prefix, tối đa offset 4096; đây là công cụ nhập v1/chẩn đoán. API chỉ mở vùng log v1, chặn traversal, config, catalog v2 và xóa đệ quy. Đọc/xóa segment v2 đi qua storage API.

Startup và adapter mount không tự format khi mount thất bại. Thiết bị chưa có filesystem hợp lệ cần được khởi tạo bằng thao tác bảo trì rõ ràng trước khi dùng storage.

## Kiểm chứng

Chạy không cần MCU hoặc mạng:

```powershell
python tools/check_log_storage.py
node tools/tests/log_protocol_test.cjs
```

Kiểm tra parser Kotlin bằng compiler đã có trong Gradle cache, không thêm dependency test vào app:

```powershell
python tools/check_log_storage.py --kotlin --gradle-cache <gradle-user-home> --java <java-executable>
```

Đã chạy thành công trên máy phát triển: state không scan/open, append tăng dần, snapshot không chạy theo writer, offset sai, ghi thiếu, generation sau clear, quota/queue, reboot, rebuild khi hỏng cả hai catalog, đuôi ghi dở; fixture C++ đọc bằng Kotlin với UTF-8, mọi ranh giới chunk, lật từng byte và sai identity; parity protocol C++/Kotlin/JS và round trip binary. 17 file Kotlin mới/thay đổi đã biên dịch kiểm tra với compiler/dependency cache; đây không phải build APK đầy đủ.

Build đầy đủ chưa hoàn tất trong phiên triển khai: LibreTiny thiếu dependency Python `ltchiptool` và không tải được từ mạng; toolchain ESP8266 gặp lỗi DLL relocation của Windows; Gradle không kết nối được daemon. Cần chạy lại build LN882H/ESP8266/ESP32 và Android trước khi phát hành, sau đó đo heap/stack, thời gian flash block và latency điều khiển trên filesystem gần đầy, nhiều client, chỉnh giờ và cắt nguồn thật. Các kiểm tra host không thay thế bước này.
