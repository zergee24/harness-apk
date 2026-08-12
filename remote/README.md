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

The launch agent uses a restrictive umask and throttles restart loops. If `codex` is not available on the launchd `PATH`, add `--codex /absolute/path/to/codex` to `ProgramArguments`; do not copy login credentials into the plist.

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

### Bridge state v1 -> v2

Bridge v2 adds an encrypted Logical Event journal plus command, route and workspace ledgers beside `bridge.json` in `~/.harness-remote`. On first load it preserves the host token, device secrets and transport sequences. Legacy pending outbound ciphertext has no stable Logical Event identity, so migration discards that queue and forces a Gap + Snapshot instead of guessing delivery.

Before upgrading, stop the launch agent and take a permission-preserving backup of the whole state directory:

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.harnessapk.remote-bridge.plist
BRIDGE_STATE_DIR="${HOME}/.harness-remote"
BRIDGE_BACKUP_DIR="${HOME}/.harness-remote-backup-$(date +%Y%m%d-%H%M%S)"
cp -a "$BRIDGE_STATE_DIR" "$BRIDGE_BACKUP_DIR"
chmod -R go-rwx "$BRIDGE_BACKUP_DIR"
```

Install the v2 binary, bootstrap the launch agent, then verify `bridge.json` has `schemaVersion: 2`, the logs contain no decode error, and the phone completes resume/Snapshot reconciliation. Keep the backup until one complete Run and one approval round trip have succeeded.

Rollback must use a Bridge build that understands state v2. Stop the launch agent, move the current directory aside, restore the complete backup atomically at the directory level, and bootstrap the compatible build. Never delete only `logical-events.log`, `commands.json` or `routes.json`; the ledgers form one recovery set. A legacy v1-only binary is not a valid rollback target after migration.

### M2 automated acceptance

The automated suite refuses ADB 5037 and requires an isolated emulator server containing exactly one declared serial:

```bash
export ANDROID_HOME=/absolute/path/to/Android/sdk
export HARNESS_M2_ADB_SERVER_PORT=5039
export HARNESS_M2_SERIAL=emulator-15662
export ADB_LOCAL_TRANSPORT_MAX_PORT=5553
remote/scripts/m2-automated-acceptance.sh
```

The scenario manifest is `remote/testdata/m2-fault-matrix.json`. Entries marked `manualRequired` still require the target Honor phone, real Relay, Mac Bridge and Codex app-server; emulator results must not be recorded as substitutes for Push, OEM background limits, lock-screen approval or the real ten-minute disconnect.

Run checks from `remote/`:

```bash
go test ./...
go vet ./...
go build ./cmd/relay ./cmd/bridge
```
