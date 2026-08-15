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
- The relay accepts encrypted Wire messages up to 8 MiB and JSON HTTP bodies up to 64 KiB. Bridge projections keep mobile thread history and live events substantially below that transport ceiling.

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

### M3 capabilities and completion ledger

M3 keeps Wire v1 and Logical Event v1. Capability negotiation is an additive encrypted payload on `host.status`:

```json
{
  "schemaVersion": 1,
  "capabilities": [
    "workspace.candidates.v1",
    "run.lifecycle.v1",
    "logical-replay.v1",
    "completion-evidence.v2",
    "turn-command-idempotency.v1",
    "thread-history-pagination.v1",
    "thread-latest-user-message.v1",
    "thread-execution-status.v1"
  ]
}
```

Older phones may ignore this payload. Newer phones require all three M2 run capabilities before enabling project Run, and must fail closed for an unsupported payload schema. Missing `completion-evidence.v2`, `turn-command-idempotency.v1`, `thread-history-pagination.v1`, `thread-latest-user-message.v1`, or `thread-execution-status.v1` means the corresponding behavior must stay disabled; capability absence is not permission to guess support. The latest-user-message and execution-status capabilities share a bounded `thread/turns/list` summary request. Android requests it lazily only for visible cards, polls active cards every three seconds, and stops after a terminal state; it does not load every thread's full history during list refresh. Execution states are additive `RUNNING`, `COMPLETED`, `FAILED`, `INTERRUPTED`, and fail-closed `UNKNOWN`. An app-server turn persisted as `interrupted` without `completedAt` is treated as still running because an external Codex writer may still own it; a persisted `interrupted` turn with `completedAt` is terminal.

Bridge state v2 now treats these files as one recovery set beside `bridge.json`:

- `logical-events.log`: encrypted Logical Event journal and replay source.
- `commands.json`: durable command idempotency, including legacy `turn.start` `UNKNOWN` reconciliation.
- `routes.json`: `(runId, threadId, turnId, deviceId)` routing and approval ownership.
- `workspaces.json`: registered/candidate workspace bindings.
- `terminal-runs.json`: first terminal status, frozen completion JSON/SHA-256/workspace locator, terminal observations and journal publication markers.

On the first terminal observation, Bridge persists reconciliation before reading final evidence. A completed Run is frozen before `run.completed` is journaled. Startup, WebSocket reconnect and phone `sync.resume` retry pending reconciliation; if the ledger is frozen but the journal event is absent, Bridge republishes the same stable event identity. Gap/Snapshot reads only frozen terminal values. It never reconstructs a terminal completion from the current Mac workspace.

Workspace or Git inspection failure produces `UNVERIFIED` evidence with a reason, never a synthetic `CLEAN`. If a ledger rename succeeds but directory Sync reports an uncertain result, Bridge keeps the first terminal value and stops later writes in that process rather than risk overwriting it.

For legacy `turn.start`, `CommandID` (or `RequestID` when no command ID exists) is the durable idempotency boundary and is also sent as `clientUserMessageId`. A persisted thread can be listed or read without being loaded into the current app-server process. If the first `turn/start` is definitively rejected with `thread not found`, Bridge first reads metadata with `includeTurns=false`. A rollout up to 256 MiB is resumed under the same thread ID, then `turn/start` is safely retried once with the same client message identity. A larger rollout fails immediately with an actionable mobile error; Bridge preserves the original thread and never creates a replacement or starts an unbounded app-server restore. If Codex app-server succeeds but TurnID parsing or route persistence fails, Bridge returns `UNKNOWN/RECONCILING` with `retrySafe=false`, saves the app-server result when available, and retries only the local route update. It does not call `turn/start` a second time for an ambiguous outcome.

Operational checks:

1. Never print or upload the raw state files: they can contain private completion text, commands, paths and encrypted event data.
2. Check Bridge logs for ledger decode/hash errors, persistent `UNKNOWN`, directory Sync failures, or repeated reconciliation failures.
3. For a pending terminal or route reconciliation, keep the compatible Bridge running and reconnect the phone or issue normal `sync.resume`; do not edit JSON or delete a single ledger.
4. Mac-only files remain Mac-only. M3 adds no Relay file transfer and no Bridge file write-back; use explicit Git Fetch or a separately confirmed import workflow.

Before any M3 Bridge upgrade or rollback, stop the launch agent and take a permission-preserving backup of the whole `~/.harness-remote` directory as shown above. A valid rollback binary must understand Bridge state v2, completion evidence v2, `terminal-runs.json` schema v2, terminal reconciliation/publication markers and turn-command idempotency. If no compatible rollback binary is available, leave the current Bridge stopped or running with the mobile M3 entry disabled; do not start a legacy binary against the directory.

After restoring a complete compatible backup, verify in order: Bridge state opens without decode/hash errors, `host.status` reports only capabilities the binary actually implements, phone resume/Snapshot converges, one Run completes with an unchanged frozen completion after reconnect, and a repeated `turn.start` identity does not create a second Turn. Keep both the pre-change and failed-attempt directories until those checks finish. Never restore `terminal-runs.json`, `commands.json` or `logical-events.log` independently.

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
