# Clip Flow 协议说明

## 核心消息

### clip.offer

通知目标设备有一条剪贴板内容可用。

包含：

- message id。
- clip id。
- source device id。
- target device id。
- content type。
- size。
- sensitivity。
- delivery mode。
- created at。
- expires at。

不包含明文正文。

### clip.payload

自动同步内容的密文 payload。

仅普通文本、URL 等低风险内容默认随 offer 一起投递。

### withheldPayload

需要确认的内容不会自动下发 payload。

relay 保存端到端加密后的密文 payload，目标设备确认后调用：

```text
POST /payloads/fetch
```

然后本地解密并写入剪贴板。

## 同步流程

### 自动同步

```text
本地复制
-> classify
-> policy: auto
-> encrypt payload
-> relay: clip.offer + clip.payload
-> 目标设备 poll/long-poll 收到
-> 本地解密
-> 写入剪贴板
```

### 敏感内容

```text
本地复制
-> classify
-> policy: offer
-> encrypt payload
-> relay: clip.offer + withheldPayload
-> 目标设备收到 offer
-> 用户确认
-> fetch withheld payload
-> 本地解密
-> 写入剪贴板
```

## Relay 可见内容

relay 当前可见：

- device id。
- message id。
- content type。
- payload 大小相关元数据。
- 加密 payload。

relay 不可见：

- 剪贴板明文。
- 解密密钥。
- 本地剪贴板历史。

## 当前安全限制

当前 MVP 使用共享 `CLIP_FLOW_SECRET` 派生空间密钥，适合本地验证，不适合生产。

生产版本需要：

- 设备身份密钥。
- QR 一次性邀请。
- 设备签名。
- 密钥轮换。
- 被撤销设备拒收。
- relay 请求认证。
