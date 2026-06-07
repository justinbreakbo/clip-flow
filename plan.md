# Clip Flow Plan

Updated: 2026-06-07

## Product Direction

Clip Flow should feel like Universal Clipboard across Windows, Android, and macOS.

The user should only understand four concepts:

- Copy
- Paste
- Recent clips
- Send to device

Networking details such as LAN, relay, port, IP address, VPN, Tailscale, and P2P negotiation should be hidden from the normal product experience.

## Target Experience

First run:

1. Install app.
2. Create or sign in to a private space.
3. Scan a QR code to add another device.
4. Start copying on one device and pasting on another.

Daily use:

- Plain text and URLs sync automatically.
- Images sync automatically with size limits.
- Files require confirmation.
- Sensitive-looking content, such as passwords, verification codes, and API keys, requires confirmation.
- Recent clips are kept briefly, defaulting to 20 items and 24 hours.
- Users can explicitly send content to one device or all devices.

## Current Implementation

This repository now contains a working Windows + Android MVP for text clipboard sync.

### Protocol And Policy

Implemented:

- Clip offer and encrypted payload model.
- AES-256-GCM payload encryption.
- Shared secret based MVP space key.
- Auto delivery for normal text and URLs.
- Offer-first delivery for sensitive-looking text.
- Withheld payload fetch after confirmation.
- Short-lived recent clip model in the prototype layer.
- Node test coverage for policy, relay routing, encryption delivery, long polling, and withheld payload fetch.

Current limitation:

- The shared secret is only an MVP mechanism. Production needs per-space keys, device identity, trust, signing, and revocation.

### Relay

Implemented:

- Node HTTP relay in `src/relay-server.js`.
- Device registration.
- Presence and active-device TTL.
- Encrypted message forwarding.
- Per-device queue.
- Long-poll receive endpoint.
- Withheld payload storage and fetch.
- JSON responses with fixed `Content-Length` for Android compatibility.
- Static test page for the old Android Web simulator.

Current limitation:

- Relay is in-memory and local-development only.
- No user account, private space persistence, auth token, rate limit, TLS termination, metrics, or abuse protection.
- No PostgreSQL or Redis yet.

### Windows

Implemented:

- Windows clipboard agent via `npm run agent`.
- Reads real Windows text clipboard.
- Writes received remote text into real Windows clipboard.
- Polls local clipboard changes.
- Receives remote clips through relay long polling.
- UTF-8 safe clipboard transport through PowerShell Base64 conversion, including Chinese text.
- Avoids simple clipboard echo loops.
- Environment-based configuration for relay URL, secret, device name, polling interval, and auto-accept behavior.

Current limitation:

- No native tray app yet.
- No installer.
- No GUI confirmation dialog for sensitive offers.
- Text clipboard only.

### Android

Implemented:

- Native Android Java project in `android-native/`.
- Debug APK build and install scripts.
- Local Android command-line toolchain under `.tools/`.
- Android Emulator test path with `adb reverse`.
- Connect relay screen.
- Send typed text.
- Send Android clipboard text.
- Poll once.
- Long-poll receive loop.
- Writes received remote text into Android clipboard.
- Android share-sheet text input.
- AES-GCM payload compatibility with Node and Windows.
- Basic handling for Android long-poll connection interruptions.

Current limitation:

- No foreground service yet.
- No automatic background Android clipboard monitoring.
- No accessibility-assisted mode.
- No notification action, quick settings tile, or floating action.
- Text only.
- Debug APK only.

### Verification

Implemented:

- `npm test`
- `npm run verify:single-windows`
- `npm run verify:windows-android-sim`
- Manual Windows agent + Android Emulator test.
- Manual Android native app send/receive test.
- Chinese clipboard round-trip verification on Windows.

Recommended local test topology:

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

Android app:

```text
Relay URL: http://127.0.0.1:42821
Secret: change-this-local-secret
```

## Deprecated Or Removed Parts

- The old Web simulator and prototype UI under `public/` have been removed.
- The old `npm run dev` static prototype server has been removed.
- Port `42820` may still be occupied by older local processes during testing. Use `42821` for clean emulator tests.

## Not Implemented Yet

### Product UX

- First-run onboarding.
- Private space creation.
- QR-code pairing.
- Device list with online/offline status.
- Per-device settings.
- Recent clips panel.
- Explicit "send to device" desktop UI.
- Sensitive-content confirmation UI.
- Remote update notification such as "Copied from Android, 12 seconds ago".

### Desktop Apps

- Windows tray app.
- macOS menu bar app.
- Tauri or native desktop shell.
- Global shortcut panel.
- Installer, auto-update, code signing.
- Background service lifecycle management.

### Android Production App

- Foreground service.
- Optional accessibility mode for stronger automatic sync.
- Safe/manual mode.
- Notification controls.
- Quick settings tile.
- App permission education.
- Real-device LAN testing beyond emulator reverse mapping.

### Network Layer

- LAN direct discovery.
- P2P NAT traversal.
- Tailscale / ZeroTier / WireGuard address detection.
- Automatic transport selection.
- Production encrypted relay.
- Self-hosted relay mode.

### Backend

- API service for private spaces, devices, pairing, and presence.
- PostgreSQL persistence.
- Redis for temporary state and online presence.
- Authenticated relay sessions.
- Device revocation.
- Audit logs and observability.

### Content Types

- Images.
- Files.
- Rich text / HTML.
- Large payload chunking.
- Size limits and transfer progress.

### Security

- Real end-to-end key agreement.
- Device identity keys and signatures.
- Pairing credentials.
- Local encrypted history.
- App blacklist, such as password managers and banking apps.
- Sensitive-content classifier improvements.
- One-click lost-device revoke.

## Implementation Strategy

### Phase 1: Stabilize Current MVP

- Keep relay + Windows agent + Android native app working on one Windows machine with emulator.
- Fix text encoding, long-poll reliability, and test repeatability.
- Keep `plan.md` as the source of truth for implemented and pending work.

### Phase 2: Minimal Real Desktop Experience

- Build a Windows tray app or Tauri shell around the existing agent.
- Show connection status.
- Add start/stop sync controls.
- Add received-content notifications.
- Add sensitive offer confirmation.

### Phase 3: Pairing And Device Trust

- Add private space model.
- Add QR pairing.
- Add device identity keys.
- Replace shared secret input with generated space/device credentials.

### Phase 4: Android Usability

- Add foreground service.
- Add manual/safe mode UX.
- Add notification and quick action.
- Improve real-device LAN setup.

### Phase 5: Production Backend And Transport

- Split API service and relay service.
- Add PostgreSQL and Redis.
- Add authenticated WebSocket or QUIC relay sessions.
- Add LAN/P2P/VPN detection below the UI.

### Phase 6: Rich Clipboard

- Add image sync.
- Add file offers.
- Add chunked encrypted payload transfer.
- Add recent clips panel with encrypted local storage.
