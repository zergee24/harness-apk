# Harness Codex Remote

Harness Codex Remote is a private control path between the Harness Android app and Codex on one Mac:

```text
Harness APK <-- HTTPS/WSS --> Alibaba Cloud relay <-- WSS --> Mac bridge <-- stdio --> codex app-server
```

The relay authenticates endpoints and forwards opaque AES-256-GCM messages. Pairing secrets, prompts, results, approvals, and Codex events are never sent to the relay in plaintext. Aliyun Push receives only a generic `wake` signal; Harness decrypts the event locally before showing completion or approval text.

## 1. Deploy the relay

Create a long random bootstrap token and place these values in `remote/deploy/.env` on the server:

```dotenv
HARNESS_RELAY_BOOTSTRAP_TOKEN=replace-with-at-least-32-random-bytes
ALIYUN_ACCESS_KEY_ID=
ALIYUN_ACCESS_KEY_SECRET=
ALIYUN_PUSH_APP_KEY=
```

The Aliyun values are optional. Use a RAM user limited to the Cloud Push actions needed by this application. Then run:

```bash
cd remote/deploy
docker compose up -d --build
```

The compose file binds the relay to `127.0.0.1:8080`. Put Caddy, Nginx, or your existing gateway in front of it and expose only `https://your-domain.example`; WebSocket upgrade must be enabled for `/v1/ws`. Do not expose port 8080 publicly. Verify:

```bash
curl https://your-domain.example/healthz
```

The bootstrap token can register exactly one host. The relay persists only hashed host/device credentials, one-use pairing tickets, routing metadata, and opaque ciphertext in `relay-data`.

## 2. Install the Mac bridge

Build on the Mac (Go 1.23 or newer), copy the binary, and register the host once:

```bash
cd remote
go build -trimpath -o harness-bridge ./cmd/bridge
sudo install -m 0755 harness-bridge /usr/local/bin/harness-bridge

harness-bridge init \
  --relay https://your-domain.example \
  --host-id personal-mac \
  --name "Studio Mac" \
  --bootstrap-token "$HARNESS_RELAY_BOOTSTRAP_TOKEN"
```

The command prints a recovery code once. Store it outside the Mac and remove the bootstrap token from the server environment after registration. To recover after losing the bridge state:

```bash
harness-bridge recover \
  --relay https://your-domain.example \
  --host-id personal-mac \
  --recovery-code 'offline-code'
```

Recovery rotates host credentials and revokes every paired phone. A new recovery code is printed and all phones must pair again.

Install `deploy/com.harnessapk.remote-bridge.plist` in `~/Library/LaunchAgents/`, adjust the binary path if necessary, then load it:

```bash
cp deploy/com.harnessapk.remote-bridge.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.harnessapk.remote-bridge.plist
```

The bridge launches `codex app-server --listen stdio://`. The normal Codex login and workspace permissions on that Mac remain authoritative.

## 3. Pair Harness

Generate a five-minute, one-use QR code on the Mac:

```bash
harness-bridge pair --qr ~/Desktop/harness-codex-pairing.png
```

In Harness open **Settings > Codex Remote Node**, scan the QR, and enroll the device. The device token and pairing secret are encrypted with the Android Keystore. Removing the node erases the local credentials. To revoke a lost phone immediately, use host recovery and pair the remaining phone again.

## 4. Enable Aliyun notifications

The Android build works without Cloud Push while Harness or its foreground task service is connected. For background wake-ups, configure the same Aliyun Push application in the relay and APK:

```properties
# ~/.gradle/gradle.properties or an untracked local gradle.properties
aliyunPushAppKey=...
aliyunPushAppSecret=...
```

Never commit these values. Build and install the APK, then grant notification permission. Completion, failure, and approval notifications are high importance; the persistent connection notification is low importance.

## Operations

- Relay health: `GET /healthz`
- Relay state backup: back up the Docker `relay-data` volume with server-side encryption.
- Bridge logs: `/tmp/harness-remote-bridge.log` and `/tmp/harness-remote-bridge.error.log`
- Pairing expires after five minutes and cannot be reused.
- Wire messages expire after five minutes and are authenticated against routing metadata to prevent tampering.
- The relay accepts messages up to 1 MiB and JSON HTTP bodies up to 64 KiB.

Run checks from `remote/`:

```bash
go test ./...
go vet ./...
go build ./cmd/relay ./cmd/bridge
```
