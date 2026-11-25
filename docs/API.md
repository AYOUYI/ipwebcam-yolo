# IP Webcam HTTP 接口文档

服务由手机上的前台服务（`CameraStreamService`）内置的 NanoHTTPD 提供，与手机在同一 Wi-Fi 下即可访问。

**基地址**：`http://<手机IP>:<端口>`，端口在 App 的设置页配置（默认 `8080`，**只能**在手机设置页改，接口改不了）。

**通用约定**

- `/detections`、`/snapshot`、`/settings` 的响应带 `Cache-Control: no-store` 和 `Access-Control-Allow-Origin: *`（浏览器跨域可直接调用；`/settings` 另支持 OPTIONS 预检）。
- 画面方向由设置里的 `portrait` 决定：横屏（默认）`width>height`，竖屏则 `height>width`。**视频流、截图、YOLO 推理坐标三者方向始终一致**。
- 所有"最新帧"类接口都带一个单调递增的帧号 `frame_seq`（从 1 开始），可用于对齐 `/video`、`/snapshot`、`/detections` 三者。

---

## GET `/`

未知路径也会落到这里。纯文本，列出可用路由：

```text
IP Webcam Running!
/video       MJPEG stream
/snapshot    latest frame as JPEG
/detections  what YOLO last found, as JSON
/settings    read the settings with GET, change them with POST
/docs        the API document, as markdown
/status      battery, screen, network and service status, as JSON
```

## GET `/docs` — 本文档

```
GET /docs
```

| 项目 | 说明 |
|---|---|
| 入参 | 无 |
| 出参 | `200`，`text/markdown; charset=utf-8`，内容就是这份 `docs/API.md`（构建时从仓库打包进 APK，两者同源） |
| 失败 | `404`，纯文本 `the API document is not bundled in this build` |

浏览器直接打开 `http://<手机IP>:<端口>/docs` 即可阅读。

## `/status` — 设备与服务状态

```
GET /status
```

| 项目 | 说明 |
|---|---|
| 入参 | 无 |
| 出参 | `200`，`application/json`，只读，不改变任何状态 |

```json
{
  "device":  { "manufacturer": "OPPO", "model": "PCAM00", "android": "11", "sdk": 30 },
  "battery": { "percent": 87, "charging": true, "status": "charging", "plugged": "ac",
               "health": "good", "temperature_c": 36.7, "voltage_mv": 4350, "technology": "Li-poly" },
  "thermal": { "status": "none" },
  "screen": "off",
  "wifi":    { "ip": "192.168.110.66", "rssi_dbm": -20, "link_speed_mbps": 866, "frequency_mhz": 5765 },
  "uptime_s": 123456,
  "service": { "port": 8080, "uptime_s": 123, "frames_captured": 4567, "frame_width": 1280, "frame_height": 720 },
  "timestamp": 1764050000000
}
```

| 字段 | 说明 |
|---|---|
| `device` | 厂商、型号、Android 版本与 SDK 级别 |
| `battery.percent` | 电量百分比（系统广播值） |
| `battery.charging` / `status` | 是否正在充电；状态为 `charging` / `full` / `discharging` / `not charging` / `unknown` |
| `battery.plugged` | 供电来源 `ac` / `usb` / `wireless` / `dock` / `none` |
| `battery.health` | `good` / `overheat` / `dead` / `over voltage` / `cold` / `unknown` |
| `battery.temperature_c` | 电池温度（℃）；系统未报时为 `null` |
| `battery.voltage_mv` / `technology` | 电压（mV）与电池类型字符串 |
| `thermal.status` | 系统热状态 `none` / `light` / `moderate` / `severe` / `critical` / `emergency` / `shutdown`（Android 10 以下为 `unknown`） |
| `screen` | `on` / `off`（锁屏推流时可据此确认屏幕确实关着） |
| `wifi` | 服务端看到的 Wi-Fi：IP、信号强度（dBm）、链路速率与频段（MHz） |
| `uptime_s` | 设备自开机以来的秒数 |
| `service.port` / `service.uptime_s` | 端口与前台服务已运行秒数 |
| `service.frames_captured` | 服务启动以来累计取到的帧数（= 最新 `frame_seq`；持续增长说明摄像头正常出帧） |
| `service.frame_width` / `frame_height` | 当前帧尺寸，随 `portrait` 设置变化 |
| `timestamp` | 服务器当前时间（Unix 毫秒） |

做无人值守监控时，`/status` 适合当心跳：定时读它确认电量在涨（插着电）、`frames_captured` 在涨、`thermal` 没有 `severe` 以上。

**已知限制**：息屏状态下冷启动 App（进程被杀后系统拉起、或开机自启）时，Android 的摄像头策略会拒绝它拿摄像头（`frames_captured` 停在 0，`/status` 其余字段正常）——系统要求摄像头型前台服务必须在界面可见时启动过一次才带息屏豁免。此时服务本身存活并每 3 秒自动重试，屏幕亮起一次后摄像头随即恢复，之后息屏推流不再受影响（已实测）。

## `/video` — 实时视频流

```
GET /video
```

| 项目 | 说明 |
|---|---|
| 入参 | 无 |
| 出参 | `200`，`multipart/x-mixed-replace; boundary=myboundary`，每个 part 为一段 `image/jpeg`，无限推送 |
| 行为 | 每个观众独占一份流；服务器推完一帧后阻塞等待**新**帧再推，摄像头不可用时**不发旧帧重播** |
| 客户端 | `cv2.VideoCapture("http://<ip>:<端口>/video")`、浏览器 `<img src=...>`、VLC 均可 |

注意：这是一条长连接，读取函数会一直阻塞，请设好超时并按 MJPEG 解析（OpenCV 直接支持）。

## `/snapshot` — 取单帧

```
GET /snapshot          最新一帧
GET /snapshot?inferred  YOLO 最近一次推理所用的那一帧（可能与最新帧差几帧）
```

| 项目 | 说明 |
|---|---|
| 入参 | `inferred`（可选，**只要带上就生效**，值任意） |
| 出参 | `200`，`image/jpeg`；响应头 `X-Frame-Seq: <帧号>` |
| 失败 | `503`，纯文本 `No frame yet`（摄像头还没出帧） |

画框不烧进快照里——`?inferred` 拿到的帧配合 `/detections` 可在客户端自行叠加框。

## `/detections` — YOLO 最新推理结果

```
GET /detections
```

| 项目 | 说明 |
|---|---|
| 入参 | 无 |
| 出参 | `200`，`application/json`（YOLO 未开启时也返回 200，见 `status`） |

```json
{
  "enabled": true,
  "status": "running",
  "detail": "",
  "model": "yolo11n_416.onnx (416, cpu, 2 threads)",
  "timestamp": 1764050000000,
  "frame_seq": 12345,
  "width": 1280,
  "height": 720,
  "latency_ms": 212,
  "fps": 4.1,
  "detections": [
    { "class_id": 0,  "label": "person", "confidence": 0.87, "box": [312, 45, 640, 710] }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `enabled` | bool | YOLO 开关当前状态 |
| `status` | string | `off` 未开启 / `loading` 模型加载中 / `running` 正常推理 / `error` 出错（原因在 `detail`） |
| `detail` | string | `error` 时的错误说明，其余为空 |
| `model` | string | 模型文件与运行配置（输入尺寸、执行后端、线程数） |
| `timestamp` | int | 服务器当前时间（Unix 毫秒） |
| `frame_seq` | int | 该结果对应的帧号 |
| `width` / `height` | int | 推理帧尺寸（随 `portrait` 变化），**box 的坐标系** |
| `latency_ms` | number | 上一次单次推理耗时 |
| `fps` | number | 推理频率（次/秒） |
| `detections[].class_id` | int | COCO 类别号（0–79） |
| `detections[].label` | string | 类别名 |
| `detections[].confidence` | number | 置信度 0–1 |
| `detections[].box` | number[4] | `[x1, y1, x2, y2]`，**帧像素坐标**，左上/右下角，已裁剪到画面内 |

检测参数（当前固定值）：置信度阈值 0.30，NMS IoU 0.45（按类别独立），最多 30 个目标；输入 416，CPU 推理、2 线程，实测约 4 次/秒。`/detections` 返回的是最近一次结果，不是每次请求都推理。

## `/settings` — 读取/修改设置

```
GET     /settings            读当前设置
POST    /settings            改设置（JSON 体、表单体、URL 参数均可）
OPTIONS /settings            浏览器预检，返回 204
```

**GET / POST 响应**（`application/json`）：

```json
{
  "port": 8080, "portrait": false, "yolo": true, "draw_boxes": true,
  "yolo_status": "running", "model": "yolo11n_416.onnx (416, cpu, 2 threads)",
  "error": null
}
```

| 字段 | 说明 |
|---|---|
| `port` | 当前端口，**只读**，通过本接口不可修改 |
| `portrait` / `yolo` / `draw_boxes` | 三个开关的当前状态 |
| `yolo_status` | 同 `/detections` 的 `status` |
| `model` | 模型描述，YOLO 关闭时为空字符串 |
| `error` | 请求成功为 `null`；失败为中文/英文原因说明 |

状态码：成功 `200`，请求被拒 `400`（原因在 `error`），预检 `204`。

**POST 可写的键**：`portrait`、`yolo`、`draw_boxes`，布尔值接受 `true/false/1/0/on/off/yes/no`（大小写不敏感）。三种传参方式：

```bash
# JSON 体
curl -X POST http://192.168.110.66:8080/settings \
     -H "Content-Type: application/json" \
     -d '{"yolo": true, "draw_boxes": true}'

# 表单体
curl -X POST http://192.168.110.66:8080/settings -d "yolo=on&draw_boxes=on"

# URL 参数
curl -X POST "http://192.168.110.66:8080/settings?yolo=true"
```

**校验规则（全部先整体校验、后统一生效，非法请求不产生任何部分修改）**：

| 请求 | 结果 |
|---|---|
| 带 `port` 且值 ≠ 当前端口 | 400：`port cannot be changed through /settings, it stays 8080` |
| 带 `port` 且值 = 当前端口 | 合法，视为未改动 |
| 未知键 | 400：`unknown setting 'xxx'; the settings are portrait, yolo and draw_boxes, each true or false` |
| 值不是合法布尔 | 400：`'maybe' is not a value for yolo; ...` |
| `draw_boxes=true` 而 `yolo` 为关（含未显式传入时当前为关） | 400：`draw_boxes needs yolo switched on` |
| 只传 `yolo=false` | 合法，且 `draw_boxes` 自动一起关 |
| 请求体无法解析 | 400：`could not read the request body: ...` |
| 非 GET/POST/OPTIONS 方法 | 400：`use GET to read the settings or POST to change them` |

设置改动会持久化（与 App 设置页共用同一份存储），重启 App / 前台服务后仍生效；App 设置页与本接口看到的是同一组开关。

---

## 调用建议

- **轮询类请求（`/detections`、`/settings`）请复用连接**（Python 用 `requests.Session()` 或自建 `http.client` 长连接）。服务端本身单请求约 20 ms；每请求新建连接要多花约 100 ms，而 `Connection: close` 风格的客户端还会等连接关闭，遇 Wi-Fi 偶发丢包时单次停顿可放大到秒级（服务端收包即答，卡顿都在链路上）。
- **视频流不受此影响**：`/video` 是一条长连接，`cv2.VideoCapture` 照常用即可。
- 需要"帧 + 同帧检测框"时：`GET /snapshot?inferred` 取 `X-Frame-Seq`，再 `GET /detections` 校验 `frame_seq` 一致后画框。
