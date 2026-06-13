# Android 客户端技术说明

## 模块结构

Android 原生端位于：

```text
android-native/
```

核心类：

```text
MainActivity.java      设置页、权限引导、pending 剪贴板前台复制
ClipSyncService.java  前台同步服务、长轮询接收、通知确认写入
ClipRelayClient.java  Android 端 relay HTTP client
CryptoBox.java        Android 端 AES-GCM 加解密
IsoClock.java         ISO 时间工具
```

## 启动流程

App 打开后默认自动启动同步服务，不需要用户手动点击启动：

```text
MainActivity.onCreate
-> restoreDefaults
-> handle share / pending intent
-> startSyncService
-> ensureClipboardAlertNotifications
```

界面中的 `Restart Sync` 只用于用户修改配置后手动重启同步服务并重新加载配置。

## 配置存储

Android 端配置使用 `SharedPreferences`，名称为：

```text
clip-flow
```

主要键：

```text
relay                     relay URL
secret                    shared secret
name                      device name
deviceId                  stable Android device id
autoAccept                是否自动接收 offer
autoStart                 保留兼容字段；当前默认自动启动
appForeground             MainActivity 是否在前台
pendingRemoteText         后台收到的远端待复制文本
pendingRemoteSource       待复制文本来源设备
pendingRemoteCreatedAt    待复制文本收到时间
```

## 同步服务

`ClipSyncService` 是 Android 前台服务，声明在：

```text
android-native/app/src/main/AndroidManifest.xml
```

权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

服务类型：

```xml
android:foregroundServiceType="dataSync"
```

服务启动后运行两个循环：

```text
localClipboardLoop   轮询本机文本剪贴板，变化后加密发送到 relay
remoteRelayLoop      长轮询 relay，收到远端 payload 后解密处理
```

## 前台/后台写入策略

Android 后台剪贴板访问受系统限制，所以 Android 端区分前台和后台行为。

前台：

```text
收到远端文本
-> decrypt
-> setPrimaryClip
-> 更新 UI 日志
```

后台：

```text
收到远端文本
-> decrypt
-> 保存 pendingRemoteText / pendingRemoteSource
-> 发送高优先级通知
-> 用户点击通知
-> ClipSyncService ACTION_APPLY_PENDING
-> setPrimaryClip
-> 清除 pending
```

通知点击使用 `PendingIntent.getService(...)`，不会打开 `MainActivity`。

## 通知渠道

Android 端使用两个通知渠道。

### Clip Flow Sync

渠道 ID：

```text
clip_flow_sync
```

用途：

```text
前台服务常驻通知
```

重要性：

```text
IMPORTANCE_LOW
```

### Clip Flow Clipboard Alerts

渠道 ID：

```text
clip_flow_pending_v2
```

显示名称：

```text
Clip Flow Clipboard Alerts
```

用途：

```text
后台收到远端剪贴板后的高优先级提醒
```

重要性：

```text
IMPORTANCE_HIGH
```

该通知还设置了：

```text
CATEGORY_MESSAGE
PRIORITY_HIGH
DEFAULT_ALL
notification sound
vibration pattern
public lockscreen visibility
```

Android 是否真正显示顶部横幅仍取决于系统通知权限、渠道设置、勿扰模式和厂商 ROM 的悬浮通知策略。

## 通知权限引导

`MainActivity` 启动时会调用：

```text
ensureClipboardAlertNotifications
```

检查内容：

```text
Android 13+ POST_NOTIFICATIONS 权限
App 总通知权限
Clip Flow Clipboard Alerts 渠道是否存在
Clip Flow Clipboard Alerts 渠道 importance 是否 >= IMPORTANCE_HIGH
```

如果通知权限或渠道重要性不足，App 会弹出对话框，并通过以下 Intent 跳转系统设置：

```text
Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
Settings.EXTRA_APP_PACKAGE
Settings.EXTRA_CHANNEL_ID
```

Android 8 以下 fallback 到应用详情页。

## Android 平台限制

当前实现不承诺所有后台场景都能静默读写剪贴板。

原因：

- Android 10+ 对后台剪贴板读取有系统级限制。
- 厂商 ROM 可能额外限制前台服务、后台网络或悬浮通知。
- 通知横幅是否弹出由系统通知渠道和用户设置共同决定。

因此当前默认产品策略是：

```text
前台直接写入
后台通知确认写入
通知点击直接复制，不跳 App
```

后续增强路线见：

```text
docs/android-background-sync-strategy.md
```
