# Clip Flow 计划

更新日期：2026-06-13

## 产品方向

Clip Flow 的目标是做一个接近 Universal Clipboard 体验的跨设备剪贴板产品，覆盖 Windows、Android，后续扩展到 macOS。

用户应该只需要理解四个概念：

- 复制
- 粘贴
- 最近剪贴板
- 发送到设备

局域网、relay、端口、IP、VPN、Tailscale、P2P 协商等网络细节都应该由产品自动处理，不暴露给普通用户。

## 目标体验

首次使用：

1. 安装 App。
2. 创建或登录私人空间。
3. 扫描二维码添加另一台设备。
4. 开始在一台设备复制，在另一台设备粘贴。

日常同步：

- 普通文本和 URL 默认自动同步。
- 图片默认自动同步，但需要大小限制。
- 文件默认需要确认。
- 疑似密码、验证码、API Key 等敏感内容默认需要确认。
- 最近剪贴板默认保留 20 条，24 小时过期。
- 用户可以显式发送内容到某台设备或所有设备。

## 当前已实现

当前仓库已经有一个可工作的 Windows + Android 文本剪贴板 MVP。

当前主链路已经在 Windows 桌面端 + Android 真机上跑通：

```text
Android 前台同步服务 <-> 本地加密 relay <-> Windows 桌面托盘入口/剪贴板 agent
```

### 协议与策略

已实现：

- Clip offer 和加密 payload 模型。
- AES-256-GCM 加密 payload。
- 基于共享 Secret 的 MVP 空间密钥。
- 普通文本和 URL 自动投递。
- 敏感文本走 offer-first 流程。
- 确认后拉取 withheld payload。
- 原型层有短期最近剪贴板模型。
- Node 测试覆盖策略、relay 路由、加密投递、长轮询、withheld payload 拉取。

当前限制：

- 共享 Secret 只是 MVP 方案。生产版本需要空间密钥、设备身份、信任关系、签名和撤销机制。

### Relay

已实现：

- Node HTTP relay：`src/relay-server.js`。
- 设备注册。
- 在线状态和活跃设备 TTL。
- 加密消息转发。
- 按设备维护消息队列。
- 长轮询接收接口。
- withheld payload 暂存和拉取。
- JSON 响应带固定 `Content-Length`，兼容 Android 长轮询。

当前限制：

- relay 是内存版，只适合本地开发和验证。
- 没有用户账号、私人空间持久化、认证 token、限流、TLS、指标和滥用防护。
- 还没有 PostgreSQL 和 Redis。

### Windows

已实现：

- Windows 桌面托盘入口：`npm run desktop`。
- 可双击启动：`Clip Flow Desktop.vbs` / `Clip Flow Desktop.cmd`。
- 托盘菜单支持 Start / Stop / Restart 同步。
- Settings 设置页支持配置 relay URL、Secret、设备名、轮询间隔、是否启动后自动同步。
- 启动后可自动运行本地 relay 和 Windows agent。
- 桌面入口日志和配置写入 `.clip-flow/`。
- Windows 剪贴板 agent：`npm run agent`。
- 读取真实 Windows 文本剪贴板。
- 将远端收到的文本写入真实 Windows 剪贴板。
- 轮询本地剪贴板变化。
- 通过 relay 长轮询接收远端内容。
- PowerShell Base64 方式读写剪贴板，已支持 UTF-8 中文内容。
- 避免简单的剪贴板回环。
- 支持通过环境变量配置 relay URL、Secret、设备名、轮询间隔和 offer 自动接收。

当前限制：

- 当前桌面入口是 PowerShell + WinForms 托盘壳，还不是 Tauri/原生安装包。
- 还没有安装包、自动更新和代码签名。
- 还没有敏感内容确认弹窗。
- 目前只支持文本剪贴板。

### Android

已实现：

- 原生 Android Java 工程：`android-native/`。
- Debug APK 构建和安装脚本。
- `npm run android:build` 构建可安装 Debug APK。
- `npm run android:install` 通过 adb 安装到手机或模拟器。
- 项目内置本地 Android 命令行工具链：`.tools/`。
- Android Emulator 测试路径，使用 `adb reverse`。
- 设置页可配置 relay URL、Secret、设备名、是否自动接收 offer、是否启动后自动同步。
- 发送输入框文本。
- 发送 Android 剪贴板文本。
- 手动拉取一次。
- 前台同步服务：`ClipSyncService`。
- 启动后可通过 `Start Auto Sync` 开启通知栏前台服务。
- 前台服务轮询本机文本剪贴板变化并发送到 relay。
- 前台服务通过 relay 长轮询接收远端内容。
- 收到远端文本后写入 Android 剪贴板。
- 通知栏 Stop 操作和 App 内 Stop Sync。
- 支持 Android 分享菜单传入文本。
- Android 与 Node / Windows 使用兼容的 AES-GCM payload。
- 对 Android 长轮询连接中断做了基础容错。
- Android 真机局域网同步已跑通。

当前限制：

- 当前只有 Debug APK，还没有 release 签名包。
- Android 10+ 对后台读取剪贴板有限制；前台服务会尽力轮询，但部分系统/厂商 ROM 可能仍限制后台读取本机剪贴板。
- 还没有无障碍增强模式。
- 还没有快捷设置磁贴或悬浮按钮。
- 目前只支持文本。

### 验证能力

已实现：

- `npm test`
- `npm run android:build`
- `npm run android:install`
- `npm run desktop`
- `npm run verify:single-windows`
- `npm run verify:windows-android-sim`
- 手动 Windows 桌面托盘入口 + Android 真机局域网同步测试。
- 手动 Windows agent + Android Emulator 测试。
- 手动 Android 原生 App 收发测试。
- Windows 中文剪贴板 round-trip 验证。

推荐本地测试拓扑：

```powershell
$env:PORT="42821"
npm run relay
```

```powershell
$env:CLIP_FLOW_SECRET="change-this-local-secret"
$env:CLIP_FLOW_RELAY="http://localhost:42821"
$env:CLIP_FLOW_AUTO_ACCEPT_OFFERS="true"
npm run agent
```

```powershell
.\scripts\use-android-env.ps1
adb reverse tcp:42821 tcp:42821
```

Android App 中填写：

```text
Relay URL: http://127.0.0.1:42821
Secret: change-this-local-secret
```

## 已废弃或已删除

- `public/` 下旧的 Web 模拟器和 prototype UI 已删除。
- 旧的 `npm run dev` 静态原型服务器已删除。
- `42820` 端口可能仍被旧本地进程占用。Android Emulator 测试建议使用 `42821`。

## 待实现功能

### 产品体验

- 首次启动引导。
- 私人空间创建。
- 二维码配对。
- 设备列表和在线/离线状态。
- 单设备设置。
- 最近剪贴板面板。
- 桌面端“发送到设备”界面。
- 敏感内容确认界面。
- 远端更新提示，例如“来自 Android，12 秒前”。

### 桌面端

- 更完整的 Windows 桌面 App 壳。
- macOS 菜单栏 App。
- Tauri 或原生桌面壳。
- 全局快捷键面板。
- 安装包、自动更新、代码签名。
- 后台服务生命周期管理。

### Android 生产能力

- 可选无障碍模式，用于更强的自动同步。
- 安全模式和手动模式。
- 更完整的通知栏控制。
- 快捷设置磁贴。
- 系统权限引导。
- 更稳定的真机局域网连接体验。

### 网络层

- 局域网直连发现。
- P2P NAT 穿透。
- Tailscale / ZeroTier / WireGuard 地址检测。
- 自动选择最佳传输方式。
- 生产级加密 relay。
- 自托管 relay 模式。

### 后端

- API 服务：私人空间、设备、配对、presence。
- PostgreSQL 持久化。
- Redis 存放临时状态和在线状态。
- relay 会话认证。
- 设备撤销。
- 审计日志和可观测性。

### 内容类型

- 图片。
- 文件。
- 富文本 / HTML。
- 大 payload 分片传输。
- 大小限制和传输进度。

### 安全

- 真实端到端密钥协商。
- 设备身份密钥和签名。
- 配对凭证。
- 本地历史加密。
- App 黑名单，例如密码管理器、银行 App。
- 敏感内容识别增强。
- 设备丢失后一键撤销。

## 实施策略

### 阶段 1：稳定当前 MVP

- 保持 relay + Windows agent + Android 原生 App 可以在一台 Windows 机器和模拟器上跑通。
- 修复文本编码、长轮询稳定性、测试可重复性。
- 保持 `plan.md` 作为“已实现/未实现”的唯一事实来源。

### 阶段 2：最小真实桌面体验

- 围绕现有 agent 做 Windows 托盘 App 或 Tauri 壳。
- 显示连接状态。
- 增加开始/停止同步控制。
- 增加收到内容的通知。
- 增加敏感 offer 确认。

### 阶段 3：配对和设备信任

- 增加私人空间模型。
- 增加二维码配对。
- 增加设备身份密钥。
- 用生成的空间/设备凭证替代手动共享 Secret。

### 阶段 4：Android 可用性

- 增加前台服务。
- 增加手动/安全模式 UX。
- 增加通知和快捷操作。
- 改进真机局域网连接体验。

### 阶段 5：生产后端和传输层

- 拆分 API 服务和 relay 服务。
- 增加 PostgreSQL 和 Redis。
- 增加认证的 WebSocket 或 QUIC relay 会话。
- 在 UI 下方自动处理 LAN / P2P / VPN 检测。

### 阶段 6：富剪贴板

- 增加图片同步。
- 增加文件 offer。
- 增加加密分片 payload 传输。
- 增加带本地加密存储的最近剪贴板面板。
