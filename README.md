# NikonLink

**尼康 Z 系列相机 → Android 无线图传。** 手机直连相机 Wi-Fi，走原生 **PTP/IP** 协议取图，不经过电脑、不需要尼康官方 App，也不用数据线。

[![Release](https://img.shields.io/github/v/release/AchooLuv/NikonLink?label=APK&color=FFCD00)](https://github.com/AchooLuv/NikonLink/releases/latest)
[![Platform](https://img.shields.io/badge/Android-10%2B%20%C2%B7%20arm64-3DDC84)](#下载与安装)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)](#技术实现)

<p align="center">
  <img src="docs/screenshot-connect.png" width="240" alt="连接页">
  <img src="docs/screenshot-tutorial.png" width="240" alt="连接教程">
  <img src="docs/screenshot-settings.png" width="240" alt="设置页">
</p>

## 它解决什么

SnapBridge 传图慢、后台易断、RAW 体验差，第三方方案大多要电脑。NikonLink 只做一件事：**把相机的照片、视频和 RAW 稳定地搬到手机里**，顺便把连接这一步做到尽量少点几下。

- 🎯 目标机型：**Nikon Z50II（Z 系列）**；共用 PTP/IP 的尼康 Wi-Fi 机型（Z50 / Z6 / Z7 / D5300 / D5600 / D750 / D850 等）同样适用
- 📱 手机端只需连接、浏览、设置三个页面
- 🖼️ JPEG 直接预览；**RAW（NEF）原样完整下载**，App 内不解码，预览统一用相机内嵌 JPEG 缩略图
- 📦 视频、RAW 大文件走 **4 MiB 分块 + 流式写盘**，内存占用恒定，不会 OOM
- ⏯️ **断点续传**：中断后重新下载，从已写入的字节继续
- 📸 **拍摄后自动拉取**（默认关闭），开启后相机新拍的照片会自动落到手机

## 功能一览

| | |
|---|---|
| 连接 | 一键加入相机热点（`WifiNetworkSpecifier`），自动探测 `192.168.1.1:15740`，PTP/IP 握手 + 会话建立 |
| 稳定 | 15 秒轻量心跳、掉线指数退避重连（1→30 s）、前台服务保活，锁屏不中断 |
| 教程 | 内置分步连接教程（Z50II / 其它 Z / D 系列菜单路径），连不上时的排查清单 |
| 浏览 | 按 handle 倒序分页读取存储卡，JPEG 缩略图懒加载 + 内存 LRU 缓存，按全部 / 照片 / 视频 / RAW 筛选 |
| 下载 | 分块 `GetPartialObject` 流式落盘，`.part` 断点文件，完成后校验大小并写入系统相册 |
| 存储 | 图片进 `Pictures/NikonLink`，视频进 `Movies/NikonLink`（MediaStore，无需存储权限） |
| 自动拉取 | 监听相机 `ObjectAdded` 事件，拍摄即传；默认关闭，开启时不会把旧照片一起拉下来 |
| 相机标记 | 读取相机上「发送到智能设备」的标记队列（尼康 `0x9407/0x9408`），在「相机标记」筛选里一键全部下载 |
| 诊断 | 一键复制相机型号 / 固件 / 连接地址 / 最近 150 条协议日志，方便反馈问题 |

## 下载与安装

1. 到 [Releases](https://github.com/AchooLuv/NikonLink/releases/latest) 下载 `NikonLink-<版本>-arm64.apk`
2. 允许「安装未知来源应用」，安装后打开
3. 首次启动授予附近设备 / 位置权限（Android 连接指定 Wi-Fi 必需）

要求：**Android 10 (API 29) 及以上，arm64-v8a**。APK 约 2 MB，不含任何广告或统计 SDK。

## 连接步骤

1. **相机**：`MENU → 网络菜单 → 连接至智能设备 → Wi-Fi 连接 → 建立连接`（其它 Z 机型在「设定菜单」，D 系列在「设定菜单 → Wi-Fi」）
2. 记下相机屏幕上的 `NIKON_...` 网络名和 8 位密码
3. **手机**：系统 Wi-Fi 里连上该网络（提示「无法访问互联网」时选**保持连接**），或回到 App 点「连接相机」输入 SSID/密码由 App 代连
4. **App**：点「连接相机」，看到型号与电量即成功；随后在「浏览」里选文件下载

> 同一时间只能有一个客户端连相机：请先完全退出 SnapBridge。

## 常见问题

**连不上 / InitFail** — 相机被其它设备占用，关掉 SnapBridge 后重开相机 Wi-Fi。

**相机不在 192.168.1.x 网段** — 「手动 SSID」里把相机 IP 填上（例如 `192.168.0.1`），App 会直接连这个地址。

**传输中自动断开** — 关闭手机「WLAN+ / 智能网络切换」，它会把没有互联网的相机网络切走。

**相机一会儿就断** — 设定菜单里把「自动关机时间」调长；半按快门可唤醒。

**RAW 没有缩略图** — 部分机型不对 NEF 返回缩略图，格子里会显示 `RAW` 占位，不影响完整下载。

**下载一半失败** — 直接重试同一个文件，会从 `.part` 的断点继续。

**连不上、想反馈** — 设置页 →「复制诊断信息」，把复制到的内容贴到 [Issues](https://github.com/AchooLuv/NikonLink/issues)，里面已包含相机型号、固件与最近的协议日志。

## 技术实现

- **PTP/IP（ISO 15740 over TCP，端口 15740）**：双 TCP 连接（命令/数据 + 事件），`Init_Command_Request/Ack`、`Init_Event_Request/Ack` 握手，`OpenSession` 后执行 PTP 事务
- 用到的操作：`GetDeviceInfo`、`GetStorageIDs`、`GetObjectHandles`、`GetObjectInfo`、`GetThumb`、`GetPartialObject`，以及尼康厂商操作 `0x90C4`（大缩略图）、`0x9407/0x9408`（「发送到智能设备」队列）
- **流式传输**：按 PTP 包长度从 socket 直接读入 256 KiB 缓冲并写入 `RandomAccessFile`，单文件内存占用与文件大小无关
- **断点续传**：`.part` 文件 + `GetPartialObject(handle, offset, 4 MiB)`，恢复时按 `.part` 长度定位
- **稳定性**：`TCP_NODELAY`、4 MiB 接收缓冲、15 s 心跳、事件线程监测断链、指数退避重连、前台服务保活
- **UI**：Jetpack Compose + Material 3，尼康黄（`#FFCD00`）单一强调色，深色底；R8 收缩后整包约 2 MB

## 构建

```bash
# JDK 17 + Android SDK 35
./gradlew :app:assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore/nikonlink-release.jks` 为发布签名（口令写在 `app/build.gradle.kts`），用于让后续版本可以覆盖安装。

## 更新日志

**v0.1.3**
- 新增「相机标记」筛选：读取机身上标记的「发送到智能设备」队列，可全选下载（尼康厂商操作 0x9407/0x9408）
- 「手动 SSID」对话框支持填写相机 IP，应对相机不在 `192.168.1.x` 网段的情况
- 连接失败时给出可操作的中文提示，并提供「换 IP 重试 / 复制诊断信息」两个入口，不再直接抛英文 socket 文本

**v0.1.2**
- 分块被相机拒绝时自动降级（4 MiB → 2 MiB → 1 MiB → 512 KiB），并回滚该块已写入的半截数据，不会污染 `.part`
- 事件通道容错：相机不接受 PTP/IP 事件连接时仍可正常浏览与下载，设置页会标注「自动拉取不可用」
- 新增「复制诊断信息」：一键导出相机型号 / 固件 / 连接步骤 / 最近 150 条协议日志，便于定位连接问题

**v0.1.1**
- 缩略图改为 32 MB 有界缓存，几千张的卡滚动浏览也不会累积内存
- 自动拉取改用 handle 基线：开启后只拉之后拍的照片；换卡或格式化会自动校正
- 传输支持真正取消：当前分块结束即停，已下载部分保留为断点
- 连接时把 Wi-Fi 的 DHCP 网关也作为候选地址，非常规网段同样能自动找到相机
- 下载完成后的「已下载」标记立即刷新
- 新增 PTP/IP 协议单元测试（8 项，覆盖帧格式、握手编码、数据集解析）

**v0.1.0** — 首个公开版本：连接 / 浏览 / 设置三页，PTP/IP 直连，分块下载与断点续传。

## 协议致谢

PTP/IP 传输与尼康厂商操作的细节参考了开源项目 [AeroShutter](https://github.com/subhashraveendran/aero-shutter)（MIT），本项目以自己的 Kotlin 实现重写。PTP 基础协议见 ISO 15740。

## 免责声明

非尼康官方产品，与尼康公司无关联。使用前请自行确认相机固件已开启 Wi-Fi 功能；因使用本工具造成的任何数据或设备问题由使用者自行承担。

## 关键词

尼康无线图传 · Nikon 相机传照片到手机 · Z50II 图传 · SnapBridge 替代 · PTP/IP Android 客户端 · NEF/RAW 下载 · 相机 Wi-Fi 传图 · 断点续传 · Kotlin Compose · Nikon wireless tethering · Z-series photo transfer
