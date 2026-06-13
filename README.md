# Clip Flow

Clip Flow 是一个类 Universal Clipboard 的跨设备剪贴板 MVP。

当前已经跑通的主链路是：

```text
Android 原生 App <-> 本地加密 relay <-> Windows 桌面托盘入口/剪贴板 agent
```

当前实现状态、已完成能力、待实现能力和阶段计划见 [plan.md](./plan.md)。

## 当前可用能力

- Windows 桌面托盘入口：启动后自动运行本地 relay 和 Windows agent。
- Windows 文本剪贴板自动同步：本地复制后发送到 relay，收到远端文本后写入剪贴板。
- Android 可安装 Debug APK：打开 App 后默认自动启动前台同步服务。
- Android 文本剪贴板自动同步：前台服务轮询本机文本剪贴板、长轮询接收远端文本；后台收到远端文本时走通知确认写入。
- Android 真机局域网同步：手机填写 Windows 局域网 IP 后可与桌面端同步。
- Android Emulator 同步：使用 `adb reverse` 后可通过 `127.0.0.1:42821` 连接桌面 relay。
- AES-256-GCM 加密 payload。
- 普通文本/URL 自动投递，疑似敏感内容走 offer-first 流程。

## Windows 桌面端

推荐用桌面托盘入口启动当前 MVP：

```powershell
npm run desktop
```

也可以在资源管理器里双击：

```text
Clip Flow Desktop.vbs
```

如果希望保留命令行窗口用于排查问题，可以双击：

```text
Clip Flow Desktop.cmd
```

启动后会自动运行本地 relay 和 Windows agent。右键系统托盘里的 Clip Flow 图标可以：

- 查看运行状态。
- Start / Stop / Restart 同步。
- 打开 Settings 设置页面。
- 打开配置文件。
- 打开日志目录。
- 打开 README。
- 退出并停止后台进程。

桌面入口的配置文件会自动创建在：

```text
.clip-flow/desktop-config.json
```

默认配置：

```json
{
  "port": 42821,
  "relayUrl": "http://localhost:42821",
  "secret": "change-this-local-secret",
  "deviceName": "Windows PC",
  "autoAcceptOffers": true,
  "startSyncOnLaunch": true,
  "clipboardPollMs": 1000,
  "relayPollTimeoutMs": 25000
}
```

## Android APK

构建可安装的 Debug APK：

```powershell
npm run android:build
```

APK 输出位置：

```text
android-native/app/build/outputs/apk/debug/app-debug.apk
```

连接手机或模拟器后安装：

```powershell
npm run android:install
```

首次打开 App 后填写：

```text
Relay URL: http://<Windows-IP>:42821
Secret: change-this-local-secret
Device name: Android
```

其中 `Relay URL` 需要按测试环境填写：

- Android 真机：填写 Windows 的局域网 IP，例如 `http://192.168.1.23:42821`。
- Android Emulator：先执行 `adb reverse`，再填写 `http://127.0.0.1:42821`。

打开 App 后，Android 会默认自动启动前台同步服务，并在通知栏显示 Clip Flow。服务会：

- 轮询本机文本剪贴板变化并发送到 relay。
- 长轮询接收远端文本。
- App 在前台时直接写入 Android 剪贴板。
- App 在后台时保存为待处理剪贴板，并弹出高优先级通知；点击通知后直接复制到 Android 剪贴板，不打开 App。
- 自动重试 relay 连接。
- 可通过通知里的 Stop 或 App 里的 Stop Sync 停止。

远端剪贴板提醒使用单独的通知渠道：

```text
Clip Flow Clipboard Alerts
```

如果系统没有显示顶部横幅，请在 Android 系统设置中检查 Clip Flow 的通知权限，并把这个渠道设置为“重要/弹出/允许悬浮通知”。不同厂商 ROM 的命名会略有不同。

App 启动时会自动检查这个通知渠道。如果通知总权限没有开启，或者 `Clip Flow Clipboard Alerts` 渠道不是高优先级，App 会提示并跳转到对应的系统通知设置页面。

注意：Android 10+ 对后台读取剪贴板有限制，不同系统和厂商 ROM 行为可能不同。当前前台服务方案已经能保持远端接收和尽力轮询本机剪贴板；如果某些手机在 App 退到后台后不允许读取本机剪贴板，下一步需要增加无障碍增强模式。

## 真机连接

真机和 Windows 必须连接同一个 Wi-Fi。

1. 在 Windows 上启动桌面端：

```powershell
npm run desktop
```

2. 获取 Windows 局域网 IP：

```powershell
ipconfig
```

找到当前 Wi-Fi 或以太网下面的 IPv4 地址，例如：

```text
192.168.1.23
```

3. Android App 中填写：

```text
Relay URL: http://192.168.0.113:42821
Secret: change-this-local-secret
```

4. 点击 `Save` 保存配置。App 会默认自动启动同步服务；如果服务已经在运行，可以点 `Restart Sync` 重新加载配置。

如果连接失败，优先检查：

- 手机和 Windows 是否在同一个 Wi-Fi。
- Windows 防火墙是否拦截了 Node.js / 42821 端口。
- 桌面端 relay 是否还在运行。
- Android App 里的 Secret 是否和桌面端一致。
- 手机浏览器是否能打开 `http://<Windows-IP>:42821/health`。

## 模拟器连接

如果使用 Android Emulator，需要建立端口映射：

```powershell
.\scripts\use-android-env.ps1
adb reverse tcp:42821 tcp:42821
```

Android Emulator 中的 App 填：

```text
Relay URL: http://127.0.0.1:42821
Secret: change-this-local-secret
```

## 手动进程模式

桌面托盘入口会自动启动 relay 和 Windows agent。需要排查底层问题时，也可以手动启动两个进程。

PowerShell 窗口 1：

```powershell
$env:PORT="42821"
npm run relay
```

PowerShell 窗口 2：

```powershell
$env:CLIP_FLOW_SECRET="change-this-local-secret"
$env:CLIP_FLOW_RELAY="http://localhost:42821"
$env:CLIP_FLOW_AUTO_ACCEPT_OFFERS="true"
npm run agent
```

## 常用命令

```powershell
npm test
npm run desktop
npm run android:build
npm run android:install
npm run verify:single-windows
npm run verify:windows-android-sim
.\scripts\build-android-debug.ps1
.\scripts\install-android-apk.ps1
.\scripts\start-android-emulator.ps1
```

## 主要模块

```text
scripts/clip-flow-desktop.ps1  Windows 桌面托盘入口
src/relay-server.js            本地 HTTP relay
src/agent.js                   Windows 剪贴板 agent
src/clipboard-windows.js       Windows 剪贴板适配器
src/clip-client.js             共享 relay client
android-native/                原生 Android App
docs/protocol.md               协议说明
docs/android-client.md         Android 客户端技术说明
docs/android-background-sync-strategy.md  Android 后台同步产品路径
```
