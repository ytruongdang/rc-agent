# Backend: hardware monitor của rc-agent (1.4.7+)

Tài liệu cho phần `rc-api` / `rc-ingest` / `rc-web`. Mọi thay đổi trên máy là **cộng thêm**: backend cũ bỏ qua các trường mới và vẫn chạy bình thường. Muốn *dùng* dữ liệu thì cần làm các mục 1–4.

Bối cảnh: một số máy kiosk sau khi chạy lâu thì màn hình vật lý đen/lag/không chạm được, nhưng remote vẫn thao tác tốt. Agent giờ ghi lại chuỗi chỉ số phần cứng *trước* lúc hỏng để tìm nguyên nhân.

## Tóm tắt việc backend cần làm

| # | Việc | Bắt buộc? |
|---|---|---|
| 1 | Lưu các trường mới trong `volatile` của `rc/state/<id>` | Nên |
| 2 | Nhận event `HW_*` trên `rc/event/<id>` | Nên |
| 3 | Gửi lệnh `hw.dump`, nhận kết quả trên `rc/probe/<id>` | Nên (đây là cách lấy chuỗi thời gian) |
| 4 | Cảnh báo máy thiếu quyền (`caps.secureSettings=false`) | Nên |
| 5 | Broker cho phép gói ≥ 128 KB trên `rc/probe/<id>` | Bắt buộc nếu dùng `hw.dump` |

Không có topic mới, không đổi ACL: agent chỉ publish lên `rc/state`, `rc/event`, `rc/probe` như trước.

## 1. `volatile` trong `rc/state/<id>`

Heartbeat mỗi 120 giây (không retained, QoS 0) và state đầy đủ (retained, khi caps đổi) đều có thêm các trường dưới đây trong object `volatile`. Key cũ (`screenOn`, `charging`, `batteryPct`, `net`, `rssi`) không đổi.

| Key | Kiểu | Ý nghĩa |
|---|---|---|
| `thermal` | int | `PowerManager.currentThermalStatus`: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. `-1` = không đọc được (Android < 10) |
| `battTempC` | float | Nhiệt độ pin (°C). **Vắng mặt** nếu không đọc được |
| `memAvailMb` | int | RAM trống (MB) theo `ActivityManager.MemoryInfo` |
| `diskFreeMb` | int | Dung lượng trống của phân vùng app (MB). `-1` = lỗi đọc |
| `cpuPct` | int | % CPU toàn máy. **Vắng mặt** trên đa số máy (Samsung chặn `/proc/stat`) |
| `gpuPct` | int | % GPU. **Vắng mặt** nếu máy không cho đọc sysfs |
| `display` | int | Trạng thái panel (`Display.STATE_*`): 1 OFF, 2 ON, 3 DOZE, 4 DOZE_SUSPEND, 5 VR, 6 ON_SUSPEND, 0 = không rõ |
| `touchDevs` | int | Số thiết bị cảm ứng mà hệ thống thấy. `-1` = lỗi đọc |
| `mainLagMs` | int | Độ trễ main thread của app (ms), trần 5000 (≥ 5000 = coi như treo) |
| `fgPkg` | string | Package app đang foreground (cần accessibility). **Vắng mặt** khi chưa biết |
| `uiIdleMs` | int | ms kể từ sự kiện UI cuối cùng. `-1` = chưa từng thấy (a11y chưa bind) |

Lưu ý khi lưu:
- Phân biệt *vắng mặt/-1* (không đọc được) với *0* (giá trị thật). Đừng coalesce thành 0.
- `volatile` đã có trong state retained, nên payload retained lớn hơn một chút (~200 byte).
- Trường chỉ xuất hiện sau mẫu đầu tiên (vài giây sau khi agent khởi động).

## 2. Event `HW_*` trên `rc/event/<id>`

Payload như event cũ (QoS 1, không retained):

```json
{"t":"event","code":"HW_PANEL_OFF","msg":"thermal=0 batt=25.0 ... display=1 ... touch=2 lag=12ms phase=IDLE","ts":1793007268792}
```

`msg` là chuỗi đọc cho người, **không ổn định để parse**; dữ liệu có cấu trúc lấy bằng `hw.dump` (mục 3).

| `code` | Điều kiện trên máy |
|---|---|
| `HW_THERMAL` | `thermal ≥ 3` hoặc `battTempC ≥ 47` |
| `HW_LOW_MEM` | hệ thống báo low-memory, hoặc RAM trống < 7% tổng |
| `HW_DISK_LOW` | `diskFreeMb < 300` |
| `HW_PANEL_OFF` | hệ thống nghĩ màn hình đang sáng (`screenOn=true`) nhưng panel ở OFF/DOZE, **2 mẫu liên tiếp** |
| `HW_TOUCH_GONE` | `touchDevs` về 0 (hoặc mẫu đầu sau khi agent khởi động mà đã là 0) |
| `HW_MAIN_LAG` | `mainLagMs ≥ 1500` |
| `HW_GPU_PEGGED` / `HW_CPU_PEGGED` | ≥ 95% trong 3 mẫu liên tiếp (chỉ khi máy cho đọc) |
| `HW_ANR_DIALOG` | thấy hộp thoại ANR của hệ thống trong 60 giây gần nhất |
| `HW_FD_LEAK` / `HW_THREAD_LEAK` | số fd ≥ 800 / số thread ≥ 400 của chính agent |

Quy tắc phát: mỗi `code` tối đa **1 lần / 10 phút / máy**, và chỉ tính khi gói thật sự gửi được (đang offline thì lần sau báo lại). Khởi động lại agent đặt lại bộ đếm.

Gợi ý: `HW_PANEL_OFF` và `HW_TOUCH_GONE` khớp trực tiếp triệu chứng "màn hình vật lý đen/không chạm được mà remote vẫn dùng được" — nên có mức cảnh báo cao hơn.

## 3. Lệnh `hw.dump`

Gửi lên `rc/cmd/<id>`:

```json
{"type":"hw.dump","n":60,"nonce":"abc123"}
```

- `n`: số mẫu cuối cần lấy, mặc định 120, **trần 100** (máy tự cắt). Mỗi mẫu ~550 byte nên một gói tối đa ~55 KB.
- `nonce`: tuỳ chọn, trả lại nguyên văn để ghép lệnh với kết quả.

Máy trả lời trên `rc/probe/<id>` (QoS 1, không retained). **Payload này khác probe thường**: không có `caps`/`volatile`, có thêm `hw`.

```json
{"online":true,"agentVer":"1.4.7","hw":[ {…mẫu…}, … ],"ts":1793007400000,"nonce":"abc123"}
```

Consumer của `rc/probe` phải chịu được payload thiếu `caps`/`volatile` (phân biệt bằng sự có mặt của `hw`).

Cấu trúc mỗi mẫu (cũ → mới; mẫu cách nhau ~30 giây; máy chỉ giữ ~400 KB ≈ 700 mẫu ≈ **6 giờ gần nhất**, sống qua việc process bị kill nhưng mất khi gỡ app):

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `ts` | long | epoch ms theo đồng hồ máy |
| `phase` | string | `HEALING`/`IDLE`/`RECONNECTING`/`ACQUIRING`/`STREAMING` |
| `thermal`, `battTempC`, `memAvailMb`, `diskFreeMb`, `cpuPct`, `gpuPct`, `display`, `touchDevs`, `mainLagMs`, `fgPkg`, `uiIdleMs` | | như mục 1; ở đây không đọc được thì là `null` |
| `zoneMaxC` | float\|null | nhiệt độ cao nhất của các thermal zone. **Chỉ để tham khảo, không dùng cảnh báo**: trên SM-S918B có zone đọc ~95 °C khi pin 25 °C |
| `memTotalMb` | int | tổng RAM |
| `lowMem` | bool | cờ low-memory của hệ thống |
| `cpuKHz` | int\|null | tần số CPU cao nhất hiện tại |
| `gpuMHz` | int\|null | xung GPU |
| `interactive` | bool | `PowerManager.isInteractive` (tức `screenOn`) |
| `appPssMb` | int | PSS của chính agent |
| `threads`, `fds` | int | số thread / file descriptor của agent (dò rò rỉ) |
| `lockTask` | int | 0 none, 1 locked, 2 pinned (kiosk lock-task); -1 không rõ |
| `dimmed` | bool | màn hình đang được app làm mờ do không có tương tác (xem cuối tài liệu) |
| `anomaly` | string[] | các mã bất thường của mẫu này, không có tiền tố `HW_` |

Máy đang **offline** thì không nhận được lệnh (`rc/cmd` subscribe QoS 1 nhưng agent kết nối `cleanStart=true`, `sessionExpiry=0`, nên lệnh gửi lúc máy offline sẽ mất).

## 4. Máy thiếu quyền

`caps` trong state retained đã có sẵn `secureSettings` và `overlay`. Máy `secureSettings=false` không tự bật lại accessibility khi service bị tắt; `overlay=false` có thể không tự mở lại kiosk được sau cập nhật. Quyền chỉ cấp được bằng ADB (`pm grant`, `appops`) lúc chuẩn bị máy, **không** cấp được từ app hay (đã thử) qua Headwind. Đề nghị rc-web có bộ lọc/ cột "thiếu quyền" cho các máy này.

## 5. Gợi ý lưu trữ và cách đọc

- `volatile` mới (mỗi 2 phút) → bảng chuỗi thời gian theo `deviceId`; giữ ngắn (vài tuần).
- Event `HW_*` → bảng event/ cảnh báo riêng, gắn tới máy.
- Vì máy chỉ giữ ~6 giờ, muốn lịch sử dài hơn thì backend phải tự lấy `hw.dump` định kỳ hoặc khi có event.
- `hw.dump`: gọi **theo yêu cầu** (nút trên rc-web) hoặc tự động ngay khi nhận `HW_PANEL_OFF`/`HW_TOUCH_GONE`/`HW_MAIN_LAG`, để lưu chuỗi trước lúc hỏng.

Cách đọc khi máy bị đen mà remote vẫn dùng được:

| Quan sát | Hướng nguyên nhân |
|---|---|
| `display ≠ 2` trong khi `interactive=true` | panel/backlight/tiến trình hiển thị |
| `touchDevs` về 0 | driver/phần cứng cảm ứng |
| `mainLagMs` cao, `HW_ANR_DIALOG`, `fgPkg` đổi, `lockTask`→0 | app kiosk treo/crash/thoát lock |
| `thermal ≥ 3`, `battTempC` cao | giảm xung do nóng |
| `memAvailMb` thấp, `lowMem=true` | thiếu RAM / bị kill |
| `diskFreeMb` thấp | đầy bộ nhớ gây lag |
| Mọi thứ bình thường | không phải các nguyên nhân trên; cần thêm chỉ số |

## Giới hạn đã biết

- CPU% và GPU% **thường là `null`** trên Samsung (SELinux chặn). Đừng dựa vào chúng.
- Chỉ số chỉ có khi agent chạy; nếu cả hệ thống chết thì chuỗi dừng đột ngột — chính điểm dừng đó cũng là dữ liệu.
- `fgPkg`/`uiIdleMs` cần accessibility đang bật.
- Đồng hồ máy có thể lệch; sắp xếp theo `ts` của máy, đối chiếu thời điểm nhận ở server khi cần.

## Thử nhanh

Dùng `mosquitto` với tài khoản của thiết bị thử (không đưa host/mật khẩu vào repo):

```bash
# lắng nghe
mosquitto_sub -h <broker> -p 8883 --cafile <ca> -u <user> -P <pass> -t 'rc/event/<id>' -t 'rc/probe/<id>' -v
# lấy 10 mẫu cuối
mosquitto_pub -h <broker> -p 8883 --cafile <ca> -u <user> -P <pass> \
  -t 'rc/cmd/<id>' -m '{"type":"hw.dump","n":10,"nonce":"t1"}'
```

Trên máy (không cần backend): `adb logcat -s RC:V | grep -E 'hw anomaly|pub rc/'`.

## Cấu hình MDM liên quan (không đụng backend)

`kiosk_pkg` trong Application Settings của `com.you.rcagent` (value = package browser kiosk) để app tự trả màn hình về kiosk sau khi MDM cập nhật. Backend không tham gia.

Làm mờ khi không có tương tác (mặc định tắt), cũng đặt trong Application Settings của `com.you.rcagent`:

| Key | Giá trị | Ý nghĩa |
|---|---|---|
| `dim_after_min` | số phút, hoặc `off` | Sau chừng đó phút không ai chạm màn hình thì làm mờ. Chưa đặt = tắt. Muốn tắt lại sau khi đã bật: đặt `off` (hoặc số âm). **Để trống hoặc `0` KHÔNG tắt**: Headwind coi là "không có giá trị" nên máy giữ giá trị cũ |
| `dim_level` | 5–80 (mặc định 30) | Độ sáng còn lại (%) |

Cách làm: một lớp đen bán trong suốt phủ lên (tối đa 80% đen: Android 12+ chặn chạm xuyên qua overlay đục hơn 80%), không đổi độ sáng hệ thống nên không cần `WRITE_SETTINGS`. Tác dụng giảm công suất rõ trên màn OLED; trên LCD đèn nền không giảm. Chạm lần đầu gỡ mờ ngay, và không mờ khi đang có phiên remote. Mẫu monitor có cờ `dimmed` để đối chiếu với nhiệt độ/lỗi.

## Chẩn đoán kết nối MQTT (agent 1.4.8+)

Mục tiêu: biết **vì sao** máy rớt rồi nối lại liên tục.

### Phía broker (không cần agent mới)

EMQX phát `$SYS/brokers/<node>/clients/<clientid>/(connected|disconnected)` với `reason`: `discarded` (clientId bị kết nối khác thay thế, tức trùng `device_id`), `takenover`, `keepalive_timeout`, `tcp_closed`/`sock_closed`, `kicked`, `idle_timeout`, `ssl_error`, `normal`. Payload có thêm `connected_at`, `disconnected_at`, `ipaddress`, `keepalive`, `clean_start`.

Bắt buộc: user `ingest` phải được subscribe `$SYS/#`. `#` **không** bao gồm topic bắt đầu bằng `$`, nên thêm vào `docker/emqx/acl.conf`:

```
{allow, {username, "ingest"}, subscribe, ["$SYS/#"]}.
```

rồi khởi động lại EMQX. Nếu không, lệnh subscribe bị từ chối âm thầm (`Unspecified error`) và không có dữ liệu nào.

Sau nginx (`stream`), `ipaddress` sẽ là địa chỉ của nginx chứ không phải của thiết bị, trừ khi bật PROXY protocol ở cả nginx và EMQX.

### Phía agent

Agent giữ `rc_conn.log` (~60 KB, sống qua kill). Mỗi dòng: `ts`, `ev` (`connect`, `connect_ok`, `connect_fail`, `disconnected`, `replaced`, `force`, `coalesced`), `reason`, và một số trường (`attempt`, `upMs`, `delayMs`, `net`, `rssi`, `phase`, `by` = ai ép nối lại). Được gửi:

- Trong phản hồi `hw.dump` (trường `conn`, tối đa 50 dòng): backend tự gọi `hw.dump` khi có `MQTT_FLAP`.
- Event `MQTT_FLAP` trên `rc/event/<id>` khi ≥ 4 lần rớt trong 10 phút (tối đa 1 lần / 30 phút), `msg` là tóm tắt lý do.

### Backend

Bảng `device_conn_events` (`docker/postgres/05-conn-events.sql`; chạy tay trên DB đã có). Chỉ ghi cho máy có trong `devices`. Giới hạn 600 sự kiện/giờ/máy; giữ 30 ngày (`CONN_EVENT_RETENTION_DAYS`).

API: `GET /devices/:id/conn?hours=24` (số lần rớt theo lý do, thời gian giữ kết nối, dòng thời gian) và `GET /fleet/connections?hours=24` (lý do phổ biến toàn fleet và 20 máy rớt nhiều nhất). Giao diện: thẻ "Connection" ở trang HW và "Connection drops" ở trang Fleet.

### Đọc kết quả

| `reason` chiếm đa số | Hướng điều tra |
|---|---|
| `discarded` / `takenover` | Hai máy trùng `device_id`, hoặc agent tự nối lại chồng lên chính nó |
| `keepalive_timeout` | Mạng chập chờn, NAT/proxy cắt kết nối rảnh, hoặc hệ điều hành làm agent không gửi ping kịp |
| `tcp_closed` / `sock_closed` | Đứt mạng, đổi Wi-Fi/4G, proxy cắt, hoặc agent bị kill (xem `upMs` và `net`) |
| `normal` | Agent chủ động ngắt: xem `by` trong nhật ký agent để biết nguồn ép nối lại |
