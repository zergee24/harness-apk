# DSH web remote (Mac runner + relay entry)

Puts the DSH browser UI behind the user's existing relay without exposing the
`dsh web` launch token to the phone.

```text
phone WebView (ZCode-style shell)
   │  https://relay.zerg.work/dsh-<random>/  + Caddy basic auth
   ▼
relay Caddy
   ├─ /dsh-<random>/api/remote.mux*  → 127.0.0.1:58233 (no Basic auth; the
   │                                   browser never sends Basic on a WS
   │                                   handshake, so the proxy gate cookie
   │                                   authenticates this path)
   └─ /dsh-<random>/*                → 127.0.0.1:58233 (Basic auth)
                                      → SSH -R tunnel from the Mac
   ▼
Mac dsh-web-remote runner
   ├─ proxy :58233  injects the `dsh web` launch token only when the browser
   │                has no dsh cookie; retries one 401 on GET/HEAD with the
   │                token; shortens the dsh cookie to DSH_COOKIE_MAX_AGE;
   │                mints an HttpOnly `dsh-web-gate` cookie (HMAC, same TTL)
   │                on authenticated HTTP and requires it on WebSocket upgrades
   └─ dsh web :58232 (0.2.1-rc.3) with --public-url/--trusted-host
```

The launch token stays in the runner's memory (and in `dsh web` stdout, which
the runner parses); it is never written to the browser URL, the proxy logs, the
runner logs, or `status.json`.

## Files

| file | purpose |
| --- | --- |
| `proxy.mjs` | token injection, cookie rewriting, WebSocket gate, WS passthrough |
| `dsh-web-remote.mjs` | spawns `dsh web`, parses the token, runs the proxy, restarts on exit |
| `selftest.mjs` | dummy-upstream + real `dsh web` contract tests (43 checks) |

## Runner environment

```bash
export DSH_WEB_PORT=58232
export DSH_PROXY_PORT=58233
export DSH_PUBLIC_URL=https://relay.zerg.work/dsh-<random>/
export DSH_TRUSTED_HOST=relay.zerg.work
export DSH_COOKIE_MAX_AGE=86400
export DSH_LOG_DIR="$HOME/.dsh-web-remote"
export DSH_WEB_PROFILE=web          # never inherit DSH_PROFILE (Desktop profile)
node dsh-web-remote.mjs
```

`start-runner.sh` in the state directory wires these from `deploy.json`.
`start-tunnel.sh` keeps the reverse tunnel up:

```bash
ssh -i ~/.ssh/harness-relay-ecs.pem -N -R 127.0.0.1:58233:127.0.0.1:58233 \
  ecs-user@<relay-host>
```

Two LaunchAgents (`com.harnessapk.dsh-web-remote`, `com.harnessapk.dsh-web-tunnel`)
keep both alive; the relay Caddy route and a successful `curl`/WebSocket test are
the deployment check.

## Relay Caddy route

The route uses a random path, Basic auth for HTTP, a redacted access log, and a
WebSocket exception. Basic auth cannot cover the WebSocket handshake (Chrome/
Android WebView do not send HTTP auth on it), so the proxy's gate cookie is the
WS credential; the HTTP page sets that cookie after Basic auth succeeds.

```caddyfile
relay.zerg.work {
	log {
		output file /var/log/caddy/dsh-access.log
		format filter {
			wrap json
			fields {
				request>headers>Authorization delete
				request>headers>Cookie delete
			}
		}
	}
	handle_path /dsh-<random>/* {
		@notws not path /api/remote.mux*
		basic_auth @notws {
			<basic-user> <bcrypt-hash>
		}
		reverse_proxy 127.0.0.1:58233
	}
	...
}
```

The access log redacts both request and response cookie values; the Basic auth
`user_id` remains for audit.

## One-time phone setup string

```text
https://<basic-user>:<basic-password>@relay.zerg.work/dsh-<random>/
```

The app parses the userinfo into app-private preferences, strips it from the
URL, and answers the Basic challenge from `DshWebRemoteActivity`. The string is
a credential: keep it out of chat/screenshots; rotate by changing the Caddy
basic-auth pair and reloading Caddy.

## Tests

```bash
node selftest.mjs
```

The suite covers token-free redirects, shortened cookies, gate cookies, WS
rejection without the gate, WS upgrade with the gate, stale-cookie reauth,
token rotation, upstream 502, log redaction, and the real `dsh web` page.
