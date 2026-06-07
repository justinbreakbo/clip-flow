# Clip Flow

Clip Flow is a Universal Clipboard-style MVP for Windows and Android.

The current working path is:

```text
Android native app <-> local encrypted relay <-> Windows clipboard agent
```

For current status, implemented features, pending features, and rollout strategy, see [plan.md](./plan.md).

## Quick Test

Use port `42821` for clean Android Emulator testing.

Terminal 1:

```powershell
$env:PORT="42821"
npm run relay
```

Terminal 2:

```powershell
$env:CLIP_FLOW_SECRET="change-this-local-secret"
$env:CLIP_FLOW_RELAY="http://localhost:42821"
$env:CLIP_FLOW_AUTO_ACCEPT_OFFERS="true"
npm run agent
```

Emulator mapping:

```powershell
.\scripts\use-android-env.ps1
adb reverse tcp:42821 tcp:42821
```

Android app settings:

```text
Relay URL: http://127.0.0.1:42821
Secret: change-this-local-secret
```

## Useful Commands

```powershell
npm test
npm run verify:single-windows
npm run verify:windows-android-sim
.\scripts\build-android-debug.ps1
.\scripts\install-android-apk.ps1
```

## Main Modules

```text
src/relay-server.js       Local HTTP relay
src/agent.js              Windows clipboard agent
src/clipboard-windows.js  Windows clipboard adapter
src/clip-client.js        Shared relay client
src/android-sim.js        CLI Android simulator
android-native/           Native Android MVP
docs/protocol.md          Protocol notes
```
