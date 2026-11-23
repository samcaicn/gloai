# Self-hosted remote CLI / MCP gateway

Optional, single-owner and single-phone. Mika opens an outbound WebSocket connection; clients use HTTPS /mcp or /api/v1 on the gateway. No VPN app or router forwarding to the phone is needed. The gateway can read tasks and results; phone lock credentials stay on the phone.

## Deploy

Requirements: Docker Compose, a domain pointing to the server, and inbound TCP 80/443. Use one gateway instance without load balancing. From this directory:

```sh
cp .env.example .env
# Edit .env: set MIKA_DOMAIN to your domain, without a scheme or path.
docker compose build
docker build --target caddy-check .
docker compose run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose up -d
docker compose cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

Read the owner-key file privately. It is the administrator credential, not an MCP token; do not put it in chats, client configs or issues. Initialization refuses to overwrite an existing key. Keep the named data volumes: they store the key, paired device identity and Caddy certificates. Only Caddy publishes host ports; the relay HTTP listener stays on the container network. The domain must be reachable for certificate issuance.

Open the gateway's HTTPS address in a browser, enter the owner key and choose **Create pairing code**. The code expires in five minutes and works once. An existing phone must be revoked before pairing another one.

In Mika, open **Settings → Connect a computer → Remote**, enter the gateway origin and code, and tap **Pair gateway**. Pairing leaves access off. Explicitly enable **Allow remote access**. Allow appropriate background operation and keep Mika's progress notification available.

### Existing Nginx server

If Nginx already owns ports 80/443, use the standalone [compose.nginx.yaml](compose.nginx.yaml) instead of the default Compose file. It publishes the relay only on 127.0.0.1:18787; keep Nginx on the same host. Use a separate domain and a valid certificate covering it, with renewal and Nginx reload already arranged.

```sh
cp .env.example .env
# Set MIKA_DOMAIN, then edit nginx.conf.example: domain and certificate paths.
docker compose -p mika-relay -f compose.nginx.yaml build
docker compose -p mika-relay -f compose.nginx.yaml run --rm relay -init-owner-key -owner-key-file /data/owner-key
docker compose -p mika-relay -f compose.nginx.yaml up -d
docker compose -p mika-relay -f compose.nginx.yaml cp relay:/data/owner-key ./owner-key
chmod 600 owner-key
```

Install the edited [nginx.conf.example](nginx.conf.example) as a new virtual-host configuration in the existing Nginx http context. Check for duplicate domain/map definitions and a port collision on 18787. Run nginx -t before a graceful reload; if validation fails, remove only the newly added configuration and leave existing services running. The example preserves WebSocket upgrades, disables response buffering and proxy retries, and omits access logs for forwarded requests.

Use the same project name and -f argument for subsequent Compose commands. For rollback, disable this virtual host, validate and reload Nginx, then run docker compose -p mika-relay -f compose.nginx.yaml down. Keep the data volume unless you intentionally want to discard pairing state. Do not publish the backend on 0.0.0.0 or reuse the default Compose file's Caddy ports on this deployment.

## Connect clients

The **phone** authenticates every forwarded request using its existing LAN access token (`McpSettings`). The gateway does not generate, validate, hash, synchronize or persist that access token. It forwards the Authorization header to `RemoteAccess` on the phone. Turning LAN access off does not disable an explicitly enabled remote connection. The USB-only temporary token remains limited to loopback and is not accepted remotely. Failed-token cooldowns are kept per caller: the gateway sends the caller's address (IPv4, or the IPv6 /64) with each request, and the phone counts failures per gateway caller, separately from local addresses. A caller that keeps guessing only locks itself out, and while locked out even the right token is refused, so the cooldown never confirms a correct guess. Behind a reverse proxy every connection comes from the proxy, so run the gateway with `-trust-proxy`: it then takes the caller from the last X-Forwarded-For entry, which the proxy appends, and only when the direct peer is a loopback or private address. Both Compose files pass it; the Nginx example overwrites the header with `$remote_addr`. The gateway's own owner-key, pairing and forwarding limits use the same caller identity.

For MCP, select **MCP → Remote** in Mika and copy the config into a client that supports custom Authorization headers. The endpoint is `https://mika.example.com/mcp`. For CLI, select **CLI / Skill → Remote**, copy the phone token into a private file on the computer, and use:

```sh
chmod 600 /path/to/token
mika connect --url https://mika.example.com --token-file /path/to/token
mika --url https://mika.example.com --token-file /path/to/token doctor
mika --url https://mika.example.com --token-file /path/to/token task list --limit 5
```

Pass `--url` and `--token-file` on every remote invocation; `mika connect` verifies the connection and does not save a remote profile. Neither the command copied by Mika nor the gateway's Web UI embeds the phone token. MCP and CLI see the same tasks: creation, status, waiting, answering, steering and cancellation use the existing phone services. Forwarding adds no new phone permissions.

Wake and unlock are **off over the gateway by default**. Over the LAN only devices on the same network can reach the phone; through the gateway the whole internet can, and the gateway itself sees the token. Requests arriving through the gateway therefore do not see the screen capabilities at all (`mika_device_state`, `mika_wake_device`, `mika_unlock_device`, `/api/v1/device`, and `device_state` in `/api/v1` capabilities). To use them remotely, turn on all three on the phone: **Allow remote access**, **Allow wake and unlock through the gateway** on the Remote page (off by default, reset to off on every pairing), and the existing **Allow remote wake and unlock**. LAN and USB behaviour is unchanged.

The gateway does not provide OAuth registration, discovery, authorization or refresh tokens. Clients that require those flows without accepting a custom Bearer header are outside this version's supported client set. Browser-origin protocol requests are rejected by the phone, matching LAN access. The existing MCP endpoint returns JSON without SSE or session IDs.

## Read-only web console

The gateway also serves a read-only console at `https://mika.example.com/app`. Sign in with the phone access token; the browser receives only an HttpOnly session cookie, and the gateway keeps the token in memory (never on disk). The console lists conversations and their turns, plus tasks that are running on the phone, including tasks started on the phone itself.

Only these reads are forwarded: `/api/v1`, `/api/v1/tasks`, `/api/v1/tasks/{id}`, `/api/v1/running`, `/api/v1/conversations`, `/api/v1/conversations/{id}`. It cannot start, answer, steer or cancel tasks, approve payments, or change phone settings. Browser `Origin` and cookies are stripped before forwarding; the phone still authenticates every request with the stored token.

Implementation and the expected phone contract: [CONSOLE.md](CONSOLE.md). The console needs a Mika build whose `/api/v1` capabilities include `conversations` and `running`; an older phone gets a clear "phone update required" message instead of a broken page.

## Pairing, tokens and recovery

There are three separate roles:

| Credential | Purpose | Stored by |
| --- | --- | --- |
| Phone access token | Common LAN and remote CLI/MCP request authentication | Phone; configured clients |
| Device credential | Authenticates the paired outbound tunnel and unpair request | Encrypted on phone; hash on gateway |
| Owner key | Creates pairing codes and unpairs the phone | Private key file on gateway; hash in process memory |

Changing the phone access token immediately invalidates the old token for LAN and remote requests, without contacting or updating the gateway. Update client configs afterward. Unpairing disconnects the tunnel and removes its device identity; it does **not** rotate the phone access token. Pairing again restores access to clients holding that token. Rotate it on the phone if those clients should lose access.

Turning remote access off in Mika closes the tunnel while preserving pairing. **Unpair gateway** disables access first, then asks the gateway to remove the device. If the network call fails, access stays off and pairing remains saved for retry. **Unpair phone** in the Web UI requires confirmation and disconnects the device.

Offline requests are not queued. `device_offline` means no device connection was available. `delivery_unknown` means a request may already have executed: reconnect and inspect task status; never automatically replay task creation, steering or unlock. Cancelling a wait cannot undo an action already performed. The CLI conservatively reports `outcome_unknown` for mutation responses with HTTP 5xx; it does not automatically retry them.

## Web UI

Go templates render one management page; embedded local CSS and a small progressive-enhancement script provide the layout and confirmation dialog. There is no Node build, framework, CDN or browser storage for credentials. Pairing and confirmation also work without JavaScript. All styles follow the App's semantic colors, type scale, grouped cards, spacing and button sizes from `app/ui/theme`, with system light/dark mode, visible keyboard focus and a narrow-screen layout.

The three catalogs in `web/` cover the App's UI languages: English, Simplified Chinese and Traditional Chinese. Browser language preferences select the initial language; a user choice is stored in a language-only cookie. Server errors and required-field messages are localized. The App's own language setting and this browser preference are independent.

## Operate and test

Use one gateway process; pairing is single-owner and single-device. `/healthz` checks the process, not phone connectivity. Preserve public Host, WebSocket upgrades and protocol headers through the reverse proxy, and set X-Forwarded-For to the caller's address. Timeouts must exceed 65 seconds. Keep the HTTP backend private and use normal trusted HTTPS/WSS certificates. The gateway does not log credentials or request bodies; configure proxies likewise.

HTTPS terminates at the gateway/proxy. It can technically read forwarded headers and task contents in memory, so use a gateway you trust. This is transparent forwarding with phone authentication, not client-to-phone end-to-end encryption. Phone lock credentials stay on the phone. Android Doze, manufacturer restrictions, force-stop, reboot and network loss can interrupt access; test standby and Wi-Fi/mobile switching on the target device.

Back up the device state, owner key and certificates consistently. Restoring old state may restore a revoked device identity. Changing the owner key requires replacing its private file with a random secret of at least 32 characters and restarting; it does not rotate the phone access token or unpair the device.

```sh
go test -race ./...
go vet ./...
go build -trimpath -o mika-relay .
```

Go 1.23 or later builds the service; use a supported toolchain for deployment. The Docker builder and dependency checksums are pinned. From the repository root:

```sh
./gradlew :core:test :app:testDebugUnitTest :core:cliTest :core:relayTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdevInstall
```

`relayTest` starts the real Go gateway, connects the production Kotlin tunnel and phone authentication, and exercises the real Python CLI and MCP against shared tasks, token rotation and unpairing. Its explicit HTTP mode allows only loopback URLs/listeners; Android accepts HTTPS origins only. UI tests include `RelaySectionTest` and `ComputerAccessScreenTest`. Deploy/device checks cover production certificates and standby separately.

The tunnel forwards only `/mcp`, `/api/v1` and `/api/v1/…`, using GET/POST/DELETE; phone routing enforces each endpoint's methods and parameters. Query strings and an explicit header allowlist pass unchanged. Bodies are bounded to 1 MiB, headers/query to 8 KiB and concurrent requests to eight. Oversized phone responses return an error without revoking pairing. No arbitrary URL/TCP proxy or offline queue is provided.

Source is under the repository's Apache-2.0 license. The WebSocket dependency, github.com/coder/websocket, uses the ISC license in LICENSE.coder-websocket.txt.
