# 技术实现

[← 返回 README](../README.md)

- **PTP/IP（ISO 15740 over TCP，端口 15740）**：双 TCP 连接（命令/数据 + 事件），`Init_Command_Request/Ack`、`Init_Event_Request/Ack` 握手，`OpenSession` 后执行 PTP 事务
- 用到的操作：`GetDeviceInfo`、`GetStorageIDs`、`GetObjectHandles`、`GetObjectInfo`、`GetThumb`、`GetPartialObject`，以及尼康厂商操作（配对、快门、事件队列、大缩略图，详见下表）
- **流式传输**：按 PTP 包长度从 socket 直接读入 256 KiB 缓冲并写入 `RandomAccessFile`，单文件内存占用与文件大小无关
- **STA 发现**：枚举手机自己的 IPv4 网段，48 路并发探测 `15740` 端口，失败可手动指定 IP；AP 模式则用 `WifiNetworkSpecifier` 加入相机热点
- **断点续传**：`.part` 文件 + `GetPartialObject(handle, offset, 4 MiB)`，恢复时按 `.part` 长度定位
- **稳定性**：`TCP_NODELAY`、4 MiB 接收缓冲、15 s 心跳、事件线程监测断链、指数退避重连、前台服务保活
- **UI**：Jetpack Compose + Material 3，尼康黄（`#FDE804`）单一强调色，深色底；R8 收缩后整包约 2.3 MB

### 已验证的尼康厂商指令（Z50II，固件 C 1.11）

配对、快门与取景均通过 PTP/IP 的厂商操作完成，参数以 libgphoto2 的
[`ptp.h`](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h) 为准：

| 指令 / 事件 | 参数 | 作用 | 状态 |
|---|---|---|---|
| `0x9439` | 9 / 13 | 进入 / 退出智能设备配对模式 | ✅ |
| `0x952B` | 无 | 读取一次性配对码 | ✅ |
| `0x935A` | `0x2001`（OK）/ `0x2002`（失败） | 完成配对，相机保存网络 | ✅ |
| `0x9201` | 无 | StartLiveView | ✅ 返回 OK |
| `0x90C2` | 1=交给主机 / 0=交还机身 | ChangeCameraMode | ✅ |
| `0x9202` | 无 | EndLiveView（**不是** DeviceReady） | ✅ |
| `0x9203` | 无 | GetLiveViewImage（老机型取帧） | 兜底（Z50II 回 `0xA00B`） |
| `0x9428` | 无 | GetLiveViewImageEx（Z 系取帧） | ✅ 实测 33 KB/帧 |
| `0x9435` | 1=主机控制 / 0=退出 | ChangeApplicationMode | ✅ 遥控写入门 |
| `0x941C` | 无 | GetEventEx（Z 系多参数事件） | ✅ |
| `0x9461` | 无 | ClearEvent | ✅ |
| `0xD1AC` / `0xD1BC` | u8 | 取景尺寸 / 压缩档 | ✅ |
| `0xD1B8` / `0xD1BB` | u8 | CameraLiveViewStatus / LiveViewImageStatus | ✅ |
| `0x90C7` | 无 | GetEvent：相机事件队列 | ✅ 见下方事件格式 |
| 事件 `0xC101` | param1=句柄 | ObjectAddedInSDRAM | ✅ 遥控照片句柄来源 |
| 事件 `0x4002` | param1=句柄 | ObjectAdded（卡） | ✅ 存卡照片句柄来源 |
| `0x9207` | `0xFFFFFFFF`=不 AF / `0xFFFFFFFE`=先 AF；`0`=卡 / `1`=SDRAM | InitiateCaptureRecInMedia（快门） | ✅ 实测存卡成功 |
| `0x90C0` | `0xFFFFFFFF` | InitiateCaptureRecInSdram | 备用（未采用） |
| `0x90C8` | 无 | DeviceReady | ✅ |
| `0x90C3` | 0=全部 / 句柄 | 删除 SDRAM 图像 | ✅ |
| `0x9209` | 无 | 厂商存储 ID | 实测仅返回卡 |
| `0x1016` | 属性码 + data-out | SetDevicePropValue（`0xD1A6` 取景选择器 = 0） | ✅ |
| `0x9407` / `0x9408` | 3/0；无 | 「发送到智能设备」队列锁定 / 读取 | ✅ |
| `0x90C4` | 句柄 | 大缩略图（约 1600×1200） | ✅ |

**事件格式**：`0x90C7` 返回 `u16 事件数` + 每条 6 字节（`u16 code` + `u32 param1`）；
`0xC101`/`0x4002` 的 param1 就是新照片的对象句柄，**不会**出现在 `GetObjectHandles` 里。

**取景（Z50II 固件 C 1.11 已打通）**：`0x9435(1)` → `0x90C2(1)` → `0xD1A6=0` →
`0x9201` → 轮询 `0x90C8` 至就绪（上限 2 s）→ 循环 `0x9428` 取帧（XGA + Basic，实测 10–13 fps，单帧约 33 KB）；
`0x9428` 被拒时回落 `0x9203`。退出时 `0x9202` + `0x90C2(0)` 还机身自由。
单帧载荷可能是「display-info 头 + JPEG」，客户端从 `FF D8 FF` 起截取。

**参数读写（Z50II 固件 C 1.11 实测）**：`0x500E` 挡位（1=M / 2=AUTO / 3=A / 4=S / 5=P）、
`0x5007` 光圈（f×100）、`0x500F` ISO 可写；`0x500D` 快门**只读**，单位是 **0.1 ms**（80 → 8 ms → 1/125）；
`0xD100` 同样是只读，写入返回 `0x201C`。`0x5008` 焦段只读（光学变焦）。
写参数用 `0x1016 SetDevicePropValue`，写入后间隔约 450 ms 再回读，避免读回旧值。
只读属性在界面上置灰；AUTO/P 等自动挡下相机会把光圈、ISO 报告为不可写。

官方取景支持列表（NX Tether）：Z9 / Z8 / Z7II / Z7 / Z6III / Z6II / Z6 / Z5II / Z5IIC / Z50II / Z30 / Zf / ZR / D6 / D780；
不支持：Z5 / Z50 / Zfc。

配对后相机改由本机身份（`NikonLink (Android)`）识别，无需每次重走连接向导。

## 构建

```bash
# JDK 17 + Android SDK 35
./gradlew :app:assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # 51 项测试，含进程内假相机的 PTP/IP 回环测试
```

`keystore/nikonlink-release.jks` 为发布签名（口令写在 `app/build.gradle.kts`），用于让后续版本可以覆盖安装。

## 协议致谢

PTP/IP 传输与尼康厂商操作的细节参考了开源项目 [AeroShutter](https://github.com/subhashraveendran/aero-shutter)（MIT），本项目以自己的 Kotlin 实现重写。PTP 基础协议见 ISO 15740。
