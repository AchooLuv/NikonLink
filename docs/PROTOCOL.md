# 技术实现

[← 返回 README](../README.md)

- **PTP/IP（ISO 15740 over TCP，端口 15740）**：双 TCP 连接（命令/数据 + 事件），`Init_Command_Request/Ack`、`Init_Event_Request/Ack` 握手，`OpenSession` 后执行 PTP 事务
- 用到的操作：`GetDeviceInfo`、`GetStorageIDs`、`GetObjectHandles`、`GetObjectInfo`、`GetThumb`、`GetPartialObject`，以及尼康厂商操作（配对、快门、事件队列、大缩略图，详见下表）
- **流式传输**：按 PTP 包长度从 socket 直接读入 256 KiB 缓冲并写入 `RandomAccessFile`，单文件内存占用与文件大小无关
- **STA 发现**：枚举手机自己的 IPv4 网段，48 路并发探测 `15740` 端口，失败可手动指定 IP；AP 模式则用 `WifiNetworkSpecifier` 加入相机热点
- **断点续传**：`.part` 文件 + `GetPartialObject(handle, offset, 4 MiB)`，恢复时按 `.part` 长度定位
- **稳定性**：`TCP_NODELAY`、4 MiB 接收缓冲、15 s 心跳、事件线程监测断链、指数退避重连、前台服务保活
- **UI**：Jetpack Compose + Material 3，尼康黄（`#FDE205`）单一强调色，深色底；R8 收缩后整包约 2.3 MB

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
| `0x9203` | 无 | GetLiveViewImage | ❌ 返回 `0xA00B`（`NotLiveView`） |
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

**取景现状**：`0x90C2(1)` + `0x9201` 后 `LiveViewStatus(0xD1A2)=1`、`LiveViewProhibitCondition(0xD1A4)=0`，
但 `0x9203` 始终不返回帧（`NotLiveView`）；短超时会读乱 PTP 流，因此当前版本不做取景。

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
