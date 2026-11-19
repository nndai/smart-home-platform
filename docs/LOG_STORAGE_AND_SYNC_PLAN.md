# Đề xuất quản lý lưu trữ và đồng bộ log

Ngày: 19-11-2026. Trạng thái: tài liệu đề xuất, chưa triển khai firmware hoặc app.

## 1. Mục tiêu và phạm vi

Áp dụng cho lịch sử năng lượng (`power`), bật/tắt (`toggle`) và chẩn đoán (`sys`). MCU tiếp tục lưu dữ liệu cục bộ và truyền qua MQTT; app lưu cache riêng theo thiết bị. Không cần dịch vụ cloud mới.

Mục tiêu chính:

- Kiểm tra không có dữ liệu mới bằng một phản hồi nhỏ, không duyệt thư mục.
- Chi phí RAM và công việc mỗi request có giới hạn, không tăng theo toàn bộ lịch sử.
- Dữ liệu mới được đọc tiếp từ offset, không tải lại lịch sử đã có.
- Giới hạn cả dung lượng, số file và số request đang xử lý.
- Hết chỗ, mất điện hoặc app ngắt kết nối không được làm treo điều khiển thiết bị.
- Cách lưu vật lý độc lập với giao diện xem theo ngày của app.

Các ngưỡng dung lượng, số file và thời gian trong tài liệu là cấu hình khởi đầu để đo trên từng MCU, không phải giá trị đã được benchmark.

## 2. Hiện trạng đã đọc từ source

### 2.1. Lưu file

| Loại | Cách ghi hiện tại | Cách giới hạn hiện tại |
|---|---|---|
| Power | `/logs/power/dd-MM-yyyy.log`, dòng `hour\|energyWh`, ghi theo giờ | Tổng dung lượng thư mục |
| Toggle | `/logs/toggle/dd-MM-yyyy.log`, dòng thời gian, nguồn, trạng thái | Tổng dung lượng thư mục |
| Sys | `/logs/sys/dd-MM-yyyy.log`, ghi qua queue | Số file theo ngày và giới hạn dung lượng mỗi file |

Power không ghi khi thời gian chưa đồng bộ. Toggle chưa đồng bộ giờ sẽ tìm file mới nhất để ghi, hoặc dùng `01-01-1970.log`. Sys dùng `nosync.log` rồi chuyển/gộp vào file ngày khi có giờ.

Giới hạn mặc định trong `firmware/include/Config.h`:

| Nền tảng | Sys mỗi file / số file | Toggle cả thư mục | Power cả thư mục |
|---|---|---|---|
| LN882H / nhánh mặc định | 10 KiB / 5 | 100 KiB | 200 KiB |
| ESP8266, ESP32 | 40 KiB / 20 | 400 KiB | 800 KiB |

Các giới hạn này chưa đồng nghĩa filesystem luôn đủ chỗ: còn cấu hình, metadata, file khác và overhead của LittleFS. Cần đối chiếu partition thực tế khi triển khai.

### 2.2. Các điểm tốn tải

1. `FileBrowser::listDir()` có phân trang nhưng vẫn duyệt tất cả file, tạo vector, sort tất cả rồi mới cắt trang. Mỗi trang lặp lại toàn bộ công việc.
2. `compat::DirIterator` trên nhánh không phải ESP8266 mở từng entry bằng `openNextFile()`, lấy size rồi đóng.
3. App dùng trang 20 entry và lấy hết các trang trước khi so size với cache.
4. Power/toggle gọi `_rotateBySize()` trước mỗi lần ghi; hàm này tính lại tổng size bằng cách quét thư mục. Khi xóa phải quét thêm để tìm file cũ nhất.
5. Sys gọi `_rotate()` trước mỗi dòng, kiểm tra số file bằng duyệt thư mục. Khi file hôm nay đạt trần thì bỏ các dòng tiếp theo, chưa mở segment mới trong ngày.
6. `LogBase::getSize()` cũng quét toàn thư mục; cần rà soát các caller khi triển khai.
7. App khởi chạy đọc nhiều file bằng các coroutine riêng, chưa có hàng đợi giới hạn số file đồng thời ở repository này.
8. App đặt `_isSyncing=false` sau xử lý danh sách, dù các file có thể chưa tải xong; timeout cũ cũng cần gắn với đúng phiên đồng bộ.

Ví dụ 365 file, 20 entry/trang: 19 request, khoảng 6.935 lượt duyệt entry và 19 lần sort toàn bộ thư mục cho một lượt kiểm tra. Đây là tính toán theo source, chưa phải số đo thời gian trên thiết bị.

## 3. Thiết kế được đề xuất

Sử dụng **các segment chỉ nối thêm, đánh số tăng dần**, kèm một catalog nhỏ có số entry bị chặn bởi cấu hình. Cả ba loại dùng cùng cơ chế quản lý; khác nhau ở định dạng record, giới hạn và chính sách giữ dữ liệu.

File đã đóng không sửa nội dung nữa. Mọi dữ liệu mới, kể cả bản ghi có thời gian cũ, được nối vào segment đang hoạt động. Nhờ vậy app chỉ cần một vị trí đọc cho mỗi loại dữ liệu.

Không bắt buộc duy trì nhật ký thay đổi riêng cho từng record: cursor theo segment và offset đã đủ cho luồng append. Revision dùng để phát hiện thay đổi tổng quát; generation dùng để nhận biết reset kho dữ liệu.

### 3.1. Cấu trúc vật lý

Ví dụ đường dẫn dự kiến:

```text
/logs/v2/power/000000000001.seg
/logs/v2/power/000000000002.seg
/logs/v2/toggle/000000000001.seg
/logs/v2/sys/000000000001.seg
/logs/v2/power/catalog.a
/logs/v2/power/catalog.b
```

Tên số được đệm đủ chữ số để thứ tự tên giống thứ tự ID. ID tăng dần trong một generation và không tái sử dụng sau khi xóa file cũ. Khi hết miền ID phải tạo generation mới có kiểm soát, không quay vòng âm thầm.

| Loại | Rollover đề xuất | Nội dung tối thiểu |
|---|---|---|
| Power | Đổi tháng khi có giờ hợp lệ hoặc đạt trần byte | Thời điểm bắt đầu khoảng đo, thời lượng, Wh |
| Toggle | Đạt trần byte | Timestamp hoặc boot ID + uptime, nguồn, trạng thái |
| Sys | Đạt trần byte | Timestamp hoặc boot ID + uptime, level, message |

Power thường chỉ có khoảng 24 record/ngày nên đổi tháng giúp xem và giữ lịch sử thuận tiện. Trần byte vẫn bắt buộc, kể cả khi clock sai. Nếu tháng thay đổi do chỉnh giờ, chỉ đóng/mở segment mới; không mở lại file lịch sử để ghi.

Toggle/sys không cần tạo file mới vào mỗi ngày. Record phải tự mang đủ thời gian để app nhóm theo ngày.

### 3.2. Định dạng record

Chọn một định dạng versioned chung: header segment có magic, schema version, generation, stream và segment ID; mỗi record có độ dài, loại record, payload và checksum. Đặt trần kích thước record để không cần buffer lớn.

Payload có thể tiếp tục là text UTF-8 ở giai đoạn đầu để dễ debug. Framing và checksum nằm ngoài payload. Nếu chọn binary payload sau này thì tăng schema version, không âm thầm thay parser cũ.

Lợi ích: phát hiện record cuối ghi dở, không nhận nhầm byte rác là dữ liệu hợp lệ, xác định được ranh giới record và offset đã commit. Checksum chỉ nhằm phát hiện lỗi dữ liệu, không thay thế xác thực giao thức.

Trường hợp chưa có giờ:

- Dùng `bootId + uptime` và cờ thời gian chưa xác định; không gắn giả vào ngày 1970.
- Khi đồng bộ giờ, ghi record ánh xạ uptime sang epoch của boot hiện tại.
- App chỉ quy đổi khi có đủ ánh xạ; dữ liệu boot trước không suy ra được giờ thì hiển thị chưa xác định.
- Với power, cần chốt riêng chính sách giữ khoảng đo chưa có giờ; không tự gán thành giờ lịch sử chính xác. Có thể giữ hành vi bỏ qua như hiện tại ở giai đoạn đầu.

## 4. Catalog, metadata và chi phí RAM

### 4.1. Metadata mỗi stream

| Trường | Ý nghĩa |
|---|---|
| `generation` | Định danh kho dữ liệu; đổi khi clear/reset/migrate tạo kho mới |
| `revision` | Tăng sau append/rotate/delete thành công trong phiên chạy |
| `bootId` | Phân biệt phiên chạy; revision RAM có thể khởi tạo lại sau reboot |
| `oldestSegmentId` | Segment cũ nhất còn giữ |
| `activeSegmentId` | Segment đang ghi |
| `committedSize` | Offset cuối chứa record hoàn chỉnh mà reader được phép đọc |
| `logicalBytes`, `fileCount` | Tổng byte file log và số segment đang quản lý |
| `catalogRevision` | Thay đổi khi thêm, đóng, xóa hoặc sửa metadata catalog |
| `droppedRecords`, `storageState` | Số record bị bỏ và trạng thái đầy/lỗi |

Token kiểm tra thay đổi là `(generation, bootId, revision)`, không dùng revision riêng lẻ. Sau reboot, app hỏi lại trạng thái và xác nhận cursor; không mặc định dữ liệu đã mất.

`logicalBytes` phục vụ quota từng stream. Dung lượng vật lý filesystem phải lấy từ API filesystem theo lịch bảo trì hoặc lúc cần cấp phát, vì overhead không bằng tổng size file.

### 4.2. Catalog có giới hạn

Mỗi entry chứa ID, size đã commit, trạng thái active/sealed và khoảng thời gian nếu biết. Nếu thời gian có thể đi lùi, dùng min/max timestamp thực sự quan sát được; không giả định file tăng ID thì timestamp luôn tăng.

- Giới hạn số segment từng stream bằng `maxSegments` cấu hình.
- Giữ entry dạng số cố định, tránh nhiều `String` cấp phát riêng.
- Tính RAM theo `số entry tối đa × sizeof(entry)` thực tế, cộng buffer và queue; kiểm tra trên LN882H/ESP8266 trước khi chốt.
- Không giữ toàn bộ nội dung log trong RAM.
- Catalog trên flash là checkpoint, không ghi lại sau từng dòng sys.
- Dùng hai slot A/B có sequence, length và checksum; chỉ chọn checkpoint hoàn chỉnh hợp lệ. Không coi một chuỗi thao tác ghi nhiều file là giao dịch nguyên tử.

Khi khởi động: đọc checkpoint, đối chiếu thư mục theo từng bước, khôi phục active tail và các entry thiếu. Có thể phải scan một lần sau boot; không được scan lại cho mỗi request hoặc mỗi dòng log. Nếu catalog hỏng cả hai slot thì rebuild theo từng tick, không format filesystem tự động.

Metadata ngày của file đóng có thể lưu ở footer để rebuild không phải parse toàn bộ nội dung. Nếu footer thiếu thì đánh dấu range chưa biết và phục hồi nền khi cần.

## 5. Luồng ghi file

Một thành phần quản lý storage sở hữu các thao tác thay đổi filesystem log. Điều khiển relay không chờ ghi flash trực tiếp; gửi record vào queue có giới hạn.

1. Nhận record, kiểm tra độ dài và encode vào buffer giới hạn.
2. Kiểm tra `activeSize + recordSize`, quota stream, số file và dự trữ vật lý.
3. Nếu cần, lên lịch xóa/rotate qua các bước nhỏ. Không chạy vòng xóa không giới hạn trong task điều khiển.
4. Ghi record, kiểm tra số byte trả về và kết quả flush/close nếu API cung cấp.
5. Chỉ tăng `committedSize`, revision và counter sau khi record hoàn chỉnh được xác nhận theo khả năng API.
6. Checkpoint theo batch, khi rotate hoặc mốc thời gian cấu hình. Sau mất điện, tail scan khôi phục phần chưa checkpoint.

Không hứa bảo toàn record chỉ còn nằm trong RAM khi mất điện. Cửa sổ mất dữ liệu phụ thuộc chính sách flush và phải đo thực tế. Cần phân biệt record đã enqueue và record đã commit trong telemetry.

Sys có thể ghi batch để giảm open/close. Toggle/power ưu tiên flush sớm hơn do giá trị lịch sử cao. Queue có ưu tiên và phần dung lượng dành riêng để sys không chiếm hết; nếu buộc bỏ record thì tăng counter. Không ghi thông báo storage lỗi trở lại chính queue đang lỗi gây đệ quy.

Giới hạn độ dài message sys và số record xử lý mỗi tick. Không sửa SDK/core library để thực hiện các thay đổi này.

## 6. Xóa khi đầy và bảo vệ filesystem

### 6.1. Hai tầng giới hạn

| Tầng | Điều kiện | Hành động |
|---|---|---|
| Stream | Vượt quota byte hoặc `maxSegments` | Xóa segment sealed cũ nhất của stream |
| Filesystem | Dưới mức free dự trữ | Dọn theo ưu tiên toàn cục cho tới mức free mục tiêu |

Cấu hình `freeLowWatermark` và `freeTarget` khác nhau để tránh vừa ghi vừa dọn liên tục. Tính từ partition thực tế; có vùng dự trữ cho config, catalog và thao tác filesystem. Tổng quota cấu hình phải được kiểm tra với dung lượng khả dụng, không chỉ dựa vào loại chip.

Ưu tiên dọn toàn cục đề xuất: sys cũ → toggle cũ → power cũ. Mỗi loại vẫn có mức giữ tối thiểu nếu đủ chỗ; trong tình trạng cạn tuyệt, dừng nhận log ưu tiên thấp trước khi xâm phạm file cấu hình.

### 6.2. Quy trình xóa

1. Chọn ứng viên từ catalog; không scan tìm file cũ nhất mỗi lần.
2. Không xóa active segment hoặc segment đang được request đọc giữ trong thời gian ngắn.
3. Xóa file, kiểm tra kết quả; chỉ khi thành công mới trừ byte/count và tăng revision.
4. Cập nhật catalog RAM, checkpoint theo chính sách.
5. Mỗi tick chỉ xóa số file giới hạn; tiếp tục ở tick sau nếu cần.

Chỉ giữ file trong thời gian xử lý một chunk đọc, không giữ vô thời hạn cho app đang offline. Snapshot không được cấm cleanup lâu dài; request sau có thể nhận `cursor_expired`.

Nếu chỉ còn active file và cần giải phóng: seal/rotate khi có đủ dự trữ, rồi áp dụng retention; nếu không thực hiện an toàn được thì bỏ record mới, báo `storage_full`, thử lại có backoff. Không xóa config, không format, không retry nóng vô hạn.

Partial write phải làm active segment chuyển trạng thái cần phục hồi. Reader chỉ thấy phần committed. Nếu adapter không hỗ trợ truncate an toàn thì seal phần hợp lệ theo catalog và mở segment mới khi đủ chỗ; không append tiếp sau đuôi hỏng.

### 6.3. Xóa thủ công

- Mọi delete/clear đi qua storage manager để cập nhật catalog và revision.
- Xóa sealed segment: trả kết quả sau khi xóa thành công.
- Xóa active segment: đóng và chuyển sang segment mới có kiểm soát trước; không để writer tiếp tục ghi qua handle cũ.
- Clear cả stream: tạo generation mới, vô hiệu cursor cũ và dọn file cũ theo từng bước.
- V2 xóa theo segment có thể ảnh hưởng nhiều ngày. UI phải hiển thị khoảng dữ liệu bị xóa; không giữ nút “xóa một ngày” mà thực tế xóa cả segment.
- Nếu cần xóa chính xác một ngày, đó là tác vụ compact/rewrite riêng, cần chỗ trống và thiết kế bổ sung; chưa đưa vào giai đoạn đầu.

## 7. Kiểm tra và tải dữ liệu mới

Tên lệnh dưới đây là đề xuất ngữ nghĩa. Khi triển khai phải cấp command/field ID trong binary protocol và kiểm tra kích thước payload; không mặc định giao thức JSON hiện có hỗ trợ sẵn.

### 7.1. `getLogState`

Một request có thể hỏi cả ba stream. Trả token thay đổi, oldest ID, active ID, committed size và trạng thái storage từ RAM.

- Token giống lần app đã xác nhận: không tải gì.
- Token khác: kiểm tra cursor bằng `syncLog`.
- Generation khác: không nối dữ liệu mới vào byte cache của generation cũ.
- Boot khác: revision không còn so trực tiếp; xác nhận lại segment/offset đang giữ.

Không gọi `listDir`, tính checksum toàn file hoặc `fsUsedBytes()` cho mỗi request này. Poll theo vòng đời màn hình và backoff khi offline; notification MQTT chỉ là tín hiệu gợi ý vì app có thể bỏ lỡ.

### 7.2. Cursor app

Mỗi stream lưu `(generation, segmentId, offset)`. Offset là số byte đã nhận và kiểm tra hợp lệ, không phải số ký tự UTF-8 và không lấy từ size mục tiêu do MCU thông báo.

App giữ thêm trạng thái parser nếu một record bị chia giữa hai chunk; chỉ đưa record hoàn chỉnh qua kiểm tra checksum vào dữ liệu hiển thị. Cursor tải byte và vị trí record đã áp dụng phải được lưu nhất quán để phục hồi sau app crash.

### 7.3. `syncLog`

App gửi cursor và giới hạn byte. MCU trả một chunk cùng segment ID, offset bắt đầu/kết thúc, đích snapshot và cursor tiếp theo.

| Trạng thái cursor | Phản hồi |
|---|---|
| Cùng segment, offset nhỏ hơn đích | Trả phần byte còn thiếu |
| Hết segment sealed | Chuyển tới segment kế tiếp còn giữ |
| Đã đạt snapshot | `up_to_date` cho snapshot đó |
| Segment đã bị retention/manual delete xóa | `cursor_expired`, báo oldest hiện có và phần bị thiếu |
| Generation khác | `generation_changed` |
| Offset vượt committed size | `invalid_cursor`, yêu cầu đối chiếu/tải lại segment |
| Storage đang phục hồi | `recovering`, kèm thời gian retry đề xuất |

Khi bắt đầu một lượt sync, chốt đích `(generation, endSegmentId, endOffset)`. Không đuổi theo file đang tăng vô hạn. Dữ liệu ghi sau đích để lượt kế tiếp tải.

Nếu app offline qua nhiều lần rotate, MCU đi qua các segment từ cursor đến đích theo catalog. File đã mất phải báo khoảng thiếu rõ ràng, không tự báo đã đồng bộ đầy đủ.

App kiểm tra `reqId + generation + segmentId + offset`, bỏ response trễ/sai phiên. Gửi lại cùng request đọc không được tạo record trùng; lưu dữ liệu theo vị trí record hoặc khóa ổn định. Với power, dùng thời điểm khoảng đo làm khóa nghiệp vụ và quy tắc xử lý record hiệu chỉnh rõ ràng.

### 7.4. Lần đầu và lịch sử cũ

- App mới có thể chọn lấy toàn bộ dữ liệu còn giữ hoặc chỉ khoảng gần đây.
- Catalog trả metadata theo trang nhỏ; app chọn segment giao khoảng thời gian cần xem.
- Lần tải toàn bộ vẫn tốn thời gian theo lượng dữ liệu thực, nhưng xử lý theo queue và có thể resume.
- Khoảng thời gian chưa xác định không được lọc bỏ âm thầm.
- Cache đã tải có thể tiếp tục xem sau khi MCU retention xóa bản gốc; phân biệt “có trong cache” và “còn trên thiết bị”.

## 8. Duyệt file và đọc file tổng quát

Tách API duyệt filesystem phục vụ chẩn đoán khỏi API đồng bộ log. Mở màn hình biểu đồ không được kéo theo duyệt toàn filesystem.

### 8.1. Duyệt log

`listLogSegments` đọc catalog RAM, có giới hạn entry/trang và token catalog. Không mở từng file để lấy size, không sort lại mỗi request. Catalog sắp theo segment ID và có giới hạn số entry.

Nếu catalog đổi trong lúc app lấy trang, trả `snapshot_expired` hoặc yêu cầu bắt đầu lại với token mới; app backoff khi thay đổi liên tục. Không dùng tổng số entry như bằng chứng duy nhất rằng nội dung danh sách không đổi.

### 8.2. File browser chẩn đoán

- Bỏ ý nghĩa trả vô hạn khi `limit=0`; áp dụng hard limit trên MCU.
- Hỗ trợ chế độ chỉ tên/type; size chỉ lấy khi người dùng cần.
- Duyệt theo thứ tự filesystem hoặc cursor iterator có TTL nếu adapter hỗ trợ. Không giữ iterator vô hạn khi có mutation.
- Nếu API nền tảng chỉ có offset và phải bỏ qua entry cũ, ghi rõ vẫn còn chi phí scan; không gọi đó là phân trang O(page) thực sự.
- Sort giao diện ở app khi phù hợp. Không cấp phát vector toàn thư mục trên MCU.
- Ràng buộc path vào vùng được phép, chuẩn hóa path, từ chối traversal và thao tác lên file cấu hình/catalog nội bộ.
- Hết TTL, reboot hoặc mutation gây mất hiệu lực thì trả lỗi cursor rõ ràng.

Đọc file có giới hạn chunk như hiện tại là nền tảng phù hợp. Đối với log v2, chỉ đọc đến committed size/snapshot; đường file và ID phải được đối chiếu với catalog, không để client đọc đuôi ghi dở.

## 9. Điều phối task và request

| Thành phần | Trách nhiệm |
|---|---|
| `LogStorageManager` | Sở hữu writer, quota, lifecycle segment và thao tác xóa |
| `LogCatalog` | Metadata RAM, checkpoint, rebuild có giới hạn |
| `LogRecordCodec` | Header, framing, checksum, giới hạn record |
| `LogSyncService` | State, snapshot, cursor và chunk đọc |
| Adapter filesystem trong `compat/` | Khác biệt API giữa MCU |
| App repository | Cache, queue sync, retry và cập nhật dữ liệu UI |

Đây là trách nhiệm dự kiến, không buộc phải tạo từng class nếu dẫn đến chia nhỏ quá mức. `core/` không thêm `#ifdef`; khác biệt nền tảng đi qua `compat/` hoặc `chip/` đúng quy tắc dự án.

- Một request đọc chunk đang thực thi mỗi thiết bị là cấu hình khởi đầu; giữa các chunk ưu tiên điều khiển và writer.
- Đặt giới hạn queue cho nhiều app/client; trả `busy` và retry có backoff khi vượt tải.
- Không giữ mutex filesystem qua lúc publish MQTT hoặc chờ response mạng.
- Không thực hiện scan dài trong callback MQTT. Đưa công việc xuống storage task/state machine.
- Trên nền tảng scheduler hợp tác, chia scan/recovery/cleanup thành nhiều tick. Một syscall flash vẫn có thể block; phải đo worst-case trên phần cứng.
- App chỉ kết thúc phiên sync khi các stream đã tới snapshot hoặc thất bại rõ ràng; timeout gắn session ID, hủy job cũ khi phiên kết thúc.

## 10. Khởi động và phục hồi mất điện

1. Mount filesystem; không tự format nếu mount thất bại.
2. Đọc hai checkpoint, chọn bản hợp lệ mới nhất.
3. Reconcile thư mục theo từng bước, kiểm tra file đã xóa nhưng checkpoint còn nhắc tới và file đã tạo nhưng chưa checkpoint.
4. Kiểm tra header/record tail của segment active, chốt committed boundary cuối hợp lệ.
5. Xử lý active segment có đuôi hỏng, phục hồi byte/count thực tế.
6. Tạo boot ID mới, công bố storage ready và checkpoint sau phục hồi.

Điều khiển thiết bị phải tiếp tục hoạt động khi storage đang recovering. Queue giai đoạn này có giới hạn; quá tải thì ghi nhận dropped counter. Chỉ phục vụ metadata chính xác khi phục hồi xong, hoặc trả trạng thái recovering rõ ràng.

## 11. Chuyển đổi dữ liệu hiện có

Không xóa hay chuyển đổi toàn bộ file cũ ngay lúc nâng cấp.

1. Thêm khả năng nhận biết protocol/schema v2; app/firmware cũ có đường fallback được kiểm thử.
2. Bắt đầu ghi mới vào `/logs/v2/`, giữ v1 ở chế độ chỉ đọc khi có đủ dung lượng dự trữ.
3. App nhập dữ liệu v1 bằng queue giới hạn; đánh dấu nguồn và tiến độ để tránh nhập trùng.
4. Tính cả file v1 vào tổng dung lượng vật lý và chính sách cleanup. Không giả định luôn có chỗ giữ hai bản dữ liệu.
5. Khi thiếu chỗ, migration phải tạm dừng hoặc dọn theo chính sách đã công bố; không tự xóa toàn bộ v1 chỉ vì v2 đã tồn tại.
6. Chuyển đổi offline trên app thường phù hợp hơn rewrite cả kho trên MCU. Chỉ compact trên MCU nếu có yêu cầu riêng và đủ chỗ tạm.

Firmware v2 và app v1 không tự nhiên tương thích với segment mới. Cần capability negotiation và quyết định thời gian hỗ trợ cũ trước khi release.

## 12. Lộ trình triển khai

### Giai đoạn 1 — Giảm tải trên định dạng hiện tại

- Đo thời gian scan, heap thấp nhất, queue và latency điều khiển khi sync.
- Thêm queue tải app, sửa lifecycle sync/timeout và tách nhu cầu sync power/toggle/sys.
- Cache metadata file có giới hạn; phục hồi một lần sau boot, cập nhật khi ghi/xóa thành công.
- Thêm state token để bỏ qua listDir khi không đổi. Khi chỉ file đang ghi tăng, đọc tiếp trực tiếp.
- Mọi đường delete/clear/migrate phải invalidation đúng cache. Chưa có change journal thì fallback đối chiếu danh sách khi topology đổi hoặc mất phiên.
- Ngừng scan quota trước từng dòng, dùng counter RAM và reconciliation khi cần.

Giai đoạn này giảm vấn đề trước mắt nhưng số file theo ngày vẫn tăng; không được dùng cache RAM không giới hạn làm giải pháp lâu dài.

### Giai đoạn 2 — Segment và cursor v2

- Thêm record codec, catalog bounded, recovery và quota hai tầng.
- Chuyển writer sang segment, bổ sung state/sync/list theo snapshot.
- App lưu cursor và parser v2, nhóm dữ liệu theo ngày độc lập file.
- Thêm capability negotiation và đường đọc lịch sử v1.

### Giai đoạn 3 — Kiểm chứng trên phần cứng

- Benchmark LN882H, ESP8266 và ESP32 với dữ liệu gần đầy.
- Chốt trần segment, số entry, queue, flush và free watermarks theo partition thực tế.
- Chỉ bật notification MQTT hoặc batch lớn hơn khi số đo chứng minh có ích.

## 13. Tiêu chí kiểm chứng

| Tình huống | Kết quả cần đạt |
|---|---|
| Mở app, không có dữ liệu mới | Một state request, không duyệt thư mục log, không đọc nội dung |
| Thêm một record | Tải phần byte mới và metadata nhỏ, không list toàn bộ lịch sử |
| Offline qua nhiều segment | Resume tuần tự từ cursor tới snapshot |
| Cursor đã bị xóa | Báo dữ liệu thiếu và oldest còn giữ, không báo sync đủ |
| Sys ghi liên tục | Lượt sync kết thúc ở snapshot, không chạy mãi |
| Full quota stream | Xóa sealed segment cũ, counter chỉ đổi khi thành công |
| Full filesystem | Dọn theo ưu tiên hoặc bỏ record có kiểm soát, điều khiển vẫn đáp ứng |
| Mất điện giữa record/checkpoint/rotate/delete | Phục hồi phần hợp lệ, không nhận đuôi hỏng |
| Chỉnh giờ tiến/lùi hoặc chưa có NTP | ID không đảo/reuse, record giữ thông tin thời gian phù hợp |
| Hai app sync cùng lúc | Request được giới hạn, không tạo tải không kiểm soát |
| Response trùng/trễ và app crash | Không nối sai offset hoặc tạo record hiển thị trùng |
| Clear/reset kho | Generation mới, cache cũ không nối vào stream mới |
| Catalog hỏng | Rebuild có giới hạn, không tự format |
| Xóa file đang đọc/ghi | Không dùng handle lỗi; báo retry/cursor expired theo tình huống |

Số đo cần thu: thời gian mỗi request, số entry scan/open, heap thấp nhất, queue high-water mark, dropped records, thời gian flash block và latency điều khiển. Mục tiêu “không quét khi không đổi” có thể kiểm tra trực tiếp bằng counter, còn ngưỡng latency cụ thể phải chốt theo yêu cầu thiết bị và benchmark.

## 14. Quyết định mặc định đề xuất

- Hướng dài hạn: segment append-only, catalog bounded và cursor `(generation, segmentId, offset)`.
- Power rollover theo tháng kèm trần byte; toggle/sys rollover theo byte.
- Xóa theo segment; app hiển thị đúng phạm vi bị xóa.
- Dọn theo quota riêng và free space toàn cục, ưu tiên giữ power hơn sys.
- Kiểm tra mới bằng state RAM; không scan hoặc hash cả lịch sử mỗi lần.
- Sync theo snapshot, hàng đợi đọc có giới hạn và resume được.
- Phục hồi sau reboot bằng checkpoint + reconcile/tail scan theo từng bước.
- Giữ dữ liệu v1 trong quá trình chuyển đổi có kiểm soát; chưa sửa code trong tài liệu này.


## 15. Triển khai source ngày 19-11-2026

Writer segment v2, storage manager, binary API và app cursor/cache đã được triển khai. Chi tiết cấu hình, định dạng byte, tương thích v1 và kết quả kiểm tra nằm trong [LOG_STORAGE_V2.md](LOG_STORAGE_V2.md). Benchmark và kiểm chứng cắt nguồn trên phần cứng vẫn cần thực hiện; các thông số đề xuất ở trên không phải số đo thực nghiệm.
