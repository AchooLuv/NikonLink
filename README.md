<h1>
  <img src="docs/brand.png" height="36" alt="NikonLink" />&nbsp;NikonLink
</h1>

**尼康 Z 系列相机 → Android 无线图传。** 手机直连相机 Wi-Fi，走原生 **PTP/IP** 协议取图，不经过电脑、不需要尼康官方 App，也不用数据线。

<p align="center">
  <a href="https://github.com/AchooLuv/NikonLink/releases/latest"><img src="https://img.shields.io/github/v/release/AchooLuv/NikonLink?label=APK&color=FDE205" alt="Release"></a>
  <a href="#下载与安装"><img src="https://img.shields.io/badge/Android-10%2B%20%C2%B7%20arm64-3DDC84" alt="Platform"></a>
  <a href="docs/PROTOCOL.md"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF" alt="Kotlin"></a>
</p>

<p align="center">
  <img src="docs/screenshots/connect-idle.jpg" width="180" alt="连接页">
  <img src="docs/screenshots/connect-connected.jpg" width="180" alt="已连接">
  <img src="docs/screenshots/browse.jpg" width="180" alt="浏览与下载">
  <img src="docs/screenshots/settings.jpg" width="180" alt="设置页">
</p>

---

## 功能一览

<p align="center">
  <a href="#连接页">连接</a> ·
  <a href="#浏览页">浏览</a> ·
  <a href="#遥控拍摄页">遥控拍摄</a> ·
  <a href="#设置页">设置</a>
</p>

### 连接页

| 功能 | 说明 |
|---|---|
| 两种模式 | **AP**（相机热点，用 `WifiNetworkSpecifier` 加入）/ **STA**（同一 Wi-Fi，扫描或手动 IP）；选一次即记住 |
| 自动发现 | STA 下枚举本机网段、48 路并发探测 `15740`；扫不到可手动填相机 IP |
| 稳定连接 | 心跳可调（10 / 15 / 30 / 60 s）、掉线指数退避重连（1→30 s）、前台服务保活，锁屏不中断 |
| 连接信息 | 连上后显示机型、固件、序列号、电量、镜头、存储剩余 |
| 连接教程 | 内置 Z50II / 其它 Z / D 系列的分步菜单路径与排查清单 |
| 进入遥控 | 轻触圆环（整圈可点）进入遥控拍摄页 |

### 浏览页

| 功能 | 说明 |
|---|---|
| 列表 | 按 handle 倒序分页读取存储卡；JPEG 缩略图懒加载 + 内存 LRU 缓存 |
| 筛选 | 全部 / 未下载 / 照片 / 视频 / RAW / 相机标记 |
| 下载 | `GetPartialObject` 分块流式落盘，`.part` 断点续传，完成后校验并写入系统相册 |
| 传输状态 | 进度条显示速度与剩余时间，结束后可「打开相册 / 重试失败项」 |
| 存储位置 | 图片进 `Pictures/NikonLink`，视频进 `Movies/NikonLink`（MediaStore，无需存储权限） |
| 自动拉取 | 监听 `ObjectAdded` 事件，拍摄即传；默认关闭，开启时不会补拉旧照片 |
| 相机标记 | 读取相机「发送到智能设备」队列（`0x9407/0x9408`），一键下载标记项 |
| RAW 配对 | 选中 `DSC_0001.NEF` 时自动带上同名 `JPG`；可在设置里关闭 |
| 未下载筛选 | 只看还没取回的文件，配合「全选」一键搬空新片 |

### 遥控拍摄页

| 功能 | 说明 |
|---|---|
| 参数栏 | 挡位（M / A / P / S / AUTO）、光圈、ISO、快门；进页面与每次拍摄后刷新 |
| 快门 | `0x9207` 拍到相机存储卡；单次触发，长曝光降噪期间不会重复发指令 |
| 成片确认 | 通过 `0x90C7` 事件（`0xC101` / `0x4002`）取回新照片句柄 |
| 预览 | 先显示小图，后台再换成 `0x90C4` 大缩略图 |
| 取景 | 实时取景画面尚未实现（`0x9203` 返回 `NotLiveView`） |

### 设置页

| 功能 | 说明 |
|---|---|
| 传输 | 拍摄后自动拉取开关、分块大小（1 / 2 / 4 / 8 MiB） |
| 连接 | 心跳间隔；「配对相机」按 SnapBridge 流程注册本机，之后相机记住本机与网络 |
| 存储 | 保存位置说明（Pictures / Movies）、清除下载记录 |
| 诊断 | 一键复制机型 / 固件 / 地址 / 最近 150 条协议日志 |
| 关于 | 版本信息、项目主页、作者博客；自动检查更新，App 内下载 APK 并拉起安装器（需允许安装未知应用） |

---

## 下载与安装

1. 到 [Releases](https://github.com/AchooLuv/NikonLink/releases/latest) 下载 `NikonLink-<版本>-arm64.apk`
2. 允许「安装未知来源应用」，安装后打开
3. 首次启动授予附近设备 / 位置权限（Android 连接指定 Wi-Fi 必需）
4. 相机开 Wi-Fi，手机连上同一网络，回到 App 点连接即可

> 装好之后不必再回网页：**设置 → 关于** 会在有新版本时提示，可直接在 App 内下载并安装。

**要求**：Android 10 (API 29) 及以上，arm64-v8a。APK 约 2.3 MB，不含任何广告或统计 SDK。

---

## 文档

| 文档 | 内容 |
|---|---|
| [使用指南](docs/USAGE.md) | 连接步骤（AP / STA）、机型支持、常见问题 |
| [技术实现](docs/PROTOCOL.md) | PTP/IP 细节、已验证的尼康指令表、构建与协议致谢 |

---

## 关键词

尼康图传 · 尼康无线图传 App · Nikon 相机传照片到手机 · Z50II 图传 · 尼康相机 Wi-Fi 传图 · 尼康 STA 模式 · 相机接入路由器传图 · SnapBridge 替代 · PTP/IP Android 客户端 · NEF/RAW 下载 · 断点续传 · Kotlin Compose · Nikon wireless tethering · Nikon camera photo transfer · Z-series photo transfer
