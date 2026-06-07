# Clip Flow

Clip Flow 是一个类 Universal Clipboard 的跨设备剪贴板 MVP。

当前已经跑通的主链路是：

```text
Android 原生 App <-> 本地加密 relay <-> Windows 剪贴板 agent
```

当前实现状态、已完成能力、待实现能力和阶段计划见 [plan.md](./plan.md)。

## 运行前先理解三个角色

当前 MVP 不是一个完整桌面 App，而是三个进程/应用配合工作：

- relay：中继服务，负责设备注册、在线状态和加密消息转发。
- Windows agent：Windows 剪贴板程序，负责读取和写入 Windows 剪贴板。
- Android App：原生 Android APK，负责发送/接收 Android 文本剪贴板。

所以测试时通常需要打开两个 PowerShell 窗口：

- 窗口 1 跑 relay。
- 窗口 2 跑 Windows agent。

如果使用 Android Emulator，还需要额外执行一次 `adb reverse`。

## 方式一：Android Emulator 测试

推荐使用端口 `42821`，避免和旧的 `42820` 进程冲突。

### 1. 启动 relay

PowerShell 窗口 1：

```powershell
$env:PORT="42821"
npm run relay
```

这个窗口不要关闭。

### 2. 启动 Windows agent

PowerShell 窗口 2：

```powershell
$env:CLIP_FLOW_SECRET="change-this-local-secret"
$env:CLIP_FLOW_RELAY="http://localhost:42821"
$env:CLIP_FLOW_AUTO_ACCEPT_OFFERS="true"
npm run agent
```

这个窗口也不要关闭。

这些环境变量的作用：

- `CLIP_FLOW_SECRET`：和 Android App 里的 Secret 保持一致，用来加密/解密内容。
- `CLIP_FLOW_RELAY`：告诉 Windows agent 连接哪个 relay。
- `CLIP_FLOW_AUTO_ACCEPT_OFFERS`：测试阶段自动接收敏感内容 offer。

### 3. 启动模拟器

如果当前没有模拟器，请先启动：

```powershell
.\scripts\start-android-emulator.ps1
```


### 4. 建立端口映射

模拟器里访问 `127.0.0.1` 指的是模拟器自己，不是 Windows。为了让 Android 模拟器里的 App 能访问 Windows 上的 relay 服务，需要执行：

```powershell
.tools\android-sdk\platform-tools\adb.exe reverse tcp:42821 tcp:42821
```



```powershell
.\scripts\use-android-env.ps1
adb reverse tcp:42821 tcp:42821
```

### 5. Android App 填写

Android Emulator 中的 App 填：

```text
Relay URL: http://127.0.0.1:42821
Secret: change-this-local-secret
```

测试路径：

1. 【连接 Relay】
2. 点击【发送测试文本】，Windows 上粘贴，验证 Android -> Windows
3. Windows 复制文本后，在 Android 点【拉取一次】，验证 Windows -> Android

## 方式二：Android 真机测试

真机测试不需要 `adb reverse`。

真机和 Windows 必须连接同一个 Wi-Fi。

### 1. 启动 relay

PowerShell 窗口 1：

```powershell
cd C:\Users\justinbreakbo\Documents\Sources\clip-flow

$env:PORT="42821"
npm run relay
```

### 2. 启动 Windows agent

PowerShell 窗口 2：

```powershell
cd C:\Users\justinbreakbo\Documents\Sources\clip-flow

$env:CLIP_FLOW_SECRET="change-this-local-secret"
$env:CLIP_FLOW_RELAY="http://localhost:42821"
$env:CLIP_FLOW_AUTO_ACCEPT_OFFERS="true"
npm run agent
```

### 3. 获取 Windows 局域网 IP

```powershell
ipconfig
```

找到当前 Wi-Fi 或以太网下面的 IPv4 地址，例如：

```text
192.168.1.23
```

### 4. Android App 填写

真机不要填 `127.0.0.1`。

应该填 Windows 的局域网 IP：

```text
Relay URL: http://192.168.1.23:42821
Secret: change-this-local-secret
```

如果连接失败，优先检查：

- 手机和 Windows 是否在同一个 Wi-Fi。
- Windows 防火墙是否拦截了 Node/42821 端口。
- relay 是否还在运行。
- Android App 里的 Secret 是否和 Windows agent 一致。

## 常用命令

```powershell
npm test
npm run verify:single-windows
npm run verify:windows-android-sim
.\scripts\build-android-debug.ps1
.\scripts\install-android-apk.ps1
.\scripts\start-android-emulator.ps1
```

## 主要模块

```text
src/relay-server.js       本地 HTTP relay
src/agent.js              Windows 剪贴板 agent
src/clipboard-windows.js  Windows 剪贴板适配器
src/clip-client.js        共享 relay client
src/android-sim.js        命令行 Android 模拟器
android-native/           原生 Android MVP
docs/protocol.md          协议说明
```
