# IP Webcam + On-Device YOLO

把一部闲置 Android 手机变成**家庭安防摄像头**：MJPEG 实时推流、锁屏/息屏不中断、端侧 YOLO 目标检测（ONNX Runtime）、HTTP 接口取检测结果与设备状态，无需任何云服务。

An Android app that turns a spare phone into an always-on IP camera: MJPEG streaming that survives screen-off, on-device YOLO detection (ONNX Runtime), and a small HTTP API for detections, settings and device status. No cloud involved.

*Based on [Sameerakhatoon/IPWebCamVideo](https://github.com/Sameerakhatoon/IPWebCamVideo) — the camera/server skeleton comes from there; the foreground service, YOLO pipeline and HTTP API were built on top.*

## 功能特性

- **实时 MJPEG 推流**：`http://<手机IP>:8080/video`，浏览器 / VLC / OpenCV `cv2.VideoCapture` 直接可用，约 24–30 fps @ 1280×720
- **锁屏息屏不中断**：摄像头型前台服务 + WakeLock/WifiLock + 断线 3 秒自动重连（重试去重，不会风暴）
- **竖屏/横屏传输**：设置里一键切换，视频流、截图、检测框坐标三者方向始终一致
- **端侧 YOLO**：yolo11n ONNX（416 输入，CPU 2 线程，约 4 次/秒），`/detections` 返回 bbox + 类别 + 置信度
- **管理接口**：`/settings` 远程改设置（端口不可改）、`/status` 设备电量/温度/网络/出帧心跳、`/docs` 在线文档
- **屏幕画框**：YOLO 开启后可在预览上叠加检测框（设置里开关，依赖 YOLO）

## HTTP 接口

完整文档（路由 / 入参 / 出参）见 **[docs/API.md](docs/API.md)**，手机上也有：`GET http://<手机IP>:8080/docs`

| 路由 | 说明 |
|---|---|
| `/video` | MJPEG 实时流（长连接） |
| `/snapshot[?inferred]` | 最新一帧 JPEG（`?inferred` 取 YOLO 最近推理那帧） |
| `/detections` | YOLO 最新结果 JSON（bbox 为帧像素坐标） |
| `/settings` | GET 读 / POST 改 `portrait`、`yolo`、`draw_boxes`（全有或全无校验） |
| `/status` | 电量、充电、温度、屏幕、Wi-Fi、服务心跳 |
| `/docs` | 本文档（构建时从 `docs/` 打包进 APK） |

Python 端示例：

```python
import cv2, requests

cap = cv2.VideoCapture("http://192.168.110.66:8080/video")   # 实时流，长连接不受单次请求延迟影响
session = requests.Session()                                  # 轮询请复用连接
detections = session.get("http://192.168.110.66:8080/detections", timeout=5).json()
```

## 构建与安装

```bash
./gradlew assembleDebug          # 需要 JDK 17 + Android SDK，minSdk 24
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动授予相机权限；设置页可改端口、切换竖屏传输、开关 YOLO/画框。自定义模型：把 `model.onnx` + `classes.txt` 放到应用外部目录 `yolo/` 下即可热替换（详见 docs/API.md）。

## 已知限制

- 息屏状态下冷启动 App（进程被杀后系统拉起）时，Android 摄像头策略会拒绝访问，屏幕亮起一次后自动恢复；相机连接建立后息屏推流不受影响
- 模型检测参数当前固定（置信度 0.30 / NMS IoU 0.45 / 最多 30 目标），见 `YoloDecoder`

## License

The upstream project's README states the MIT license (no LICENSE file is included); this repository keeps the upstream commit history for attribution and is intended for learning and personal use.
