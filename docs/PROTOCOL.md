# Codex App Server Protocol Notes

## Authority and version

Implementation is based on:

- OpenAI Codex schema pin: `68334831d81220374a3b5805b7e41b85f94e88e0`
- OpenAI Codex current upstream check: `00df7f65abb7b953cb2498281d451d3e06a34e64`
- Codex app-server protocol v2 source and generated schemas
- `codex-cli 0.166.0` runtime smoke testing

The `codex-rs/app-server-protocol` tree is byte-for-byte unchanged between those two
commits, so the checked-in schemas remain current for that protocol tree. Later upstream
changes may still affect app-server runtime behavior; re-run the generated-schema and
live-server checks when updating the pin.

Checked-in schemas:

- `schemas/app-server.schema.json` and `schemas/app-server-v2.schema.json` for stable
  fields;
- `schemas/app-server-experimental.schema.json` and
  `schemas/app-server-v2-experimental.schema.json` for the experimental API enabled by
  the `initialize` request.

Regeneration instructions are in `schemas/README.md`.

Do not infer request field names from older clients. Experimental and even stable fields
change; validate against the current Rust structs and schema.

## Framing and envelope

### stdio

One JSON object per line. App-server usually omits the optional top-level
`"jsonrpc":"2.0"` member on its wire format even though request/response semantics are
JSON-RPC-like.

The preferred “existing App Server” Host runs:

```text
exec '<codex>' app-server proxy --sock "${CODEX_HOME:-$HOME/.codex}/app-server-control/app-server-control.sock"
```

`proxy` transparently bridges these JSONL bytes to the running local Unix control
socket. It preserves request IDs and notification framing, but does not create a second
runtime. A separate `--listen stdio://` process remains available for an intentionally
isolated Thread environment.

### WebSocket

One JSON document per WebSocket text frame. CodexDeck does not append a newline or split
one frame into multiple messages. Binary frames are rejected.

### Request

```json
{"id":1,"method":"thread/list","params":{"limit":60}}
```

### Success response

```json
{"id":1,"result":{"data":[],"nextCursor":null}}
```

### Error response

```json
{"id":1,"error":{"code":-32602,"message":"...","data":{}}}
```

### Notification

```json
{"method":"turn/started","params":{"threadId":"...","turn":{}}}
```

### Server-initiated request

```json
{"id":"server-id","method":"item/commandExecution/requestApproval","params":{}}
```

The client responds using the exact original request ID and never uses a new ID.

## Initialization

Every transport connection starts with:

1. `initialize` request containing `clientInfo` and capabilities.
2. Wait for the matching initialize response.
3. Send `initialized` notification.
4. Issue normal requests.

CodexDeck opts into `experimentalApi: true` because several required features are marked
experimental, including named permission profiles, collaboration modes, and capability
discovery. UI features that add experimental fields must remain capability-aware.

`requestAttestation` is disabled because this client does not use a platform
attestation provider.

## Permission and collaboration fields

Current `thread/start`, `thread/resume`, and `turn/start` accept a named permissions
profile as `permissions: "<profile-id>"`. `thread/resume` and `thread/start` use
`sandbox` for the legacy sandbox mode. `turn/start` uses a structured
`sandboxPolicy` object. A named profile and a legacy sandbox/policy field are mutually
exclusive.

CodexDeck selects exactly one form: when a named profile is active it sends
`permissions`; otherwise it sends the legacy sandbox field. Plan mode is sent through
the experimental `collaborationMode` payload on the next Turn.

`approvalPolicy` is retained as a JSON value rather than narrowed to a string. Clients
must not explicitly submit the retired `untrusted` policy. Current choices are
`on-request`, `never`, or an experimental `{"granular": {...}}` object containing
`sandbox_approval`, `rules`,
`skill_approval`, `request_permissions`, and `mcp_elicitations`. Re-encoding only the
enum name would silently discard a granular policy selected by the server or another
client.

`sandboxPolicy` is retained as the complete JSON object. Current variants include
`readOnly`, `workspaceWrite`, `dangerFullAccess`, and `externalSandbox`; fields such as
`networkAccess`, `writableRoots`, `excludeSlashTmp`, and `excludeTmpdirEnvVar` are
authoritative. The client must not synthesize a simplified policy that loses them.
`approvalsReviewer` is synchronized independently and supports `user` or the
experimental `auto_review` routing mode. `guardian_subagent` remains an accepted
wire alias for `auto_review` compatibility, but it is not a separate selectable mode.

Granular approval policies and `auto_review` are experimental. CodexDeck preserves and
displays returned values but does not claim that strict automatic-review decision
handling or every typed approval form is complete.

## Request IDs and timeout handling

- IDs are monotonically increasing signed 64-bit numbers.
- Concurrent requests use a pending-ID map.
- Incoming server requests may use a numeric ID or a textual ID. The response echoes
  the original JSON type exactly; numeric `1` and string `"1"` are distinct identities.
- Default request timeout is 30 seconds; `turn/start` uses 60 seconds.
- Timeout removes the local pending request but cannot cancel a server-side operation.
- Therefore timed-out non-idempotent operations are never automatically retried.
- In particular, CodexDeck must not re-send `turn/start` after reconnect or timeout.
- A reconnect resynchronizes authoritative state through `thread/resume` and
  `thread/turns/list`.

Optional diagnostics trace only RPC metadata: method, request ID, completion/failure,
duration, error code, and error message. The trace callback has no request/response body
fields. This makes timeouts and errors such as `Thread ... already has an active writer`
diagnosable without persisting chat input, tool output, authorization headers, or image
data. Server-initiated request methods are traced for approval troubleshooting; their
params and the user's decision payload are not sent to the trace log.

## Implemented client methods

| Area | Methods |
| --- | --- |
| Lifecycle | `initialize`, `initialized` |
| Threads | `thread/list`, `thread/read`, `thread/loaded/list`, `thread/start`, `thread/resume`, `thread/fork` |
| Thread mutation | `thread/name/set`, `thread/archive`, `thread/unarchive`, `thread/compact/start` |
| History | `thread/turns/list` |
| Turns | `turn/start`, `turn/steer`, `turn/interrupt` |
| Review | `review/start` with inline uncommitted-changes target |
| Models/options | `model/list`, `collaborationMode/list`, `permissionProfile/list` |
| Capabilities | `skills/list`, `mcpServerStatus/list`, `plugin/list` |
| Approvals | Responses to command/file/permission/user-input/MCP elicitation requests |

Unsupported or unavailable capability methods are caught independently and shown as a
per-capability unavailable state.

## Thread list and history

`thread/list` uses cursor pagination with:

- `limit`
- `sortKey: "updated_at"`
- `sortDirection: "desc"`
- `archived`
- `searchTerm`

CodexDeck initially loads one page, exposes explicit load-more, and debounces remote
search. Archived Threads use the same method with `archived: true`.

Recent Turn history uses `thread/turns/list` after `thread/resume` with
`excludeTurns: true`. The first page is requested descending and converted to
chronological display order. Older pages use the returned cursor. Full-history
hydration is intentionally avoided because current app-server marks it deprecated for
paginated Threads.

## Thread resume and subscriptions

Official `ThreadResumeParams` docs state:

- A running Thread is rejoined by `threadId`.
- For a non-running Thread, history/path/id precedence applies.
- `excludeTurns` returns metadata and live-resume state without full historical Turns.

The app-server source comments and tests establish that resuming a running Thread
returns its history while atomically subscribing the client to subsequent updates.
`thread/start` and `thread/fork` also subscribe the calling connection to the returned
Thread.

CodexDeck therefore:

1. calls `thread/resume`;
2. pages recent Turns separately;
3. applies live notifications only to their matching Thread;
4. on connection loss, reconnects and resumes rather than restarting the Turn.

Resume is deliberately called without model, approval, sandbox, or permissions
overrides. This avoids changing a Thread already open in another client merely because
Android resumed it. Server-returned settings are authoritative for the UI, and
`thread/settings/updated` keeps them synchronized after another client changes them.

## Multi-client behavior

The protocol supports multiple client connections to one app-server process and
multiple subscriptions to a loaded Thread. This is the mechanism behind shared PC/mobile
observation.

For a local managed daemon, use the SSH Host mode `SSH_EXISTING`. CodexDeck executes
`codex app-server proxy` over the SSH exec channel, reaching the same app-server process
without exposing a TCP port. Do not replace this with a new
`codex app-server --listen stdio://` process when shared active Turn state is required.

To connect the PC TUI to the same live runtime:

```sh
export CODEX_REMOTE_TOKEN='the capability token'
codex \
  --remote wss://codex.example.com \
  --remote-auth-token-env CODEX_REMOTE_TOKEN \
  resume <thread-id>
```

For a direct loopback WebSocket listener, use an SSH local forward or the app's
SSH-tunneled mode.

Important boundaries:

- Two separate app-server processes do not share active in-memory Turn execution.
- Connecting to the same process is required to see/control the same active Turn.
- Persisted rollout history can still be read later by another process.
- Closing a viewer connection does not prove that the Agent stopped.
- The phone should show UI connection state separately from server-side execution
  state.
- Approval ownership and policy precedence across simultaneous clients must be tested
  for the target Codex version rather than assumed.

## Approvals and server requests

Implemented server-initiated request families:

- `item/commandExecution/requestApproval` / `execCommandApproval`
- `item/fileChange/requestApproval` / `applyPatchApproval`
- `item/permissions/requestApproval`
- `item/tool/requestUserInput` / `tool/requestUserInput`
- `mcpServer/elicitation/request`
- Unknown requests are surfaced and can be rejected with a JSON-RPC error

Command/file approvals return `decision` values such as `accept`,
`acceptForSession`, or `decline`. Permission approvals return the requested/granted
permission profile plus `turn` or `session` scope. User-input responses map question IDs
to `{ "answers": [...] }`.

`error` notifications include `willRetry`. A retryable error means the Agent may still
be running, so CodexDeck records the error without marking the active Turn stopped or
interrupted. Only explicit Turn lifecycle notifications establish terminal state.

Official resume tests replay pending command and file-change approval requests when a
client rejoins a running Thread. This is why reconnect must keep the same thread ID and
must not fabricate a completed/interrupted state on the phone.

`serverRequest/resolved` removes a pending approval from every subscribed client after
one client or server-side reviewer resolves it. CodexDeck removes the dialog on this
notification and also guards against duplicate local button presses. Approval delivery
policy across simultaneous clients remains a server-version behavior that must be
validated against the deployed Codex build.

## WebSocket authentication and security

Current Codex accepts these listener forms:

```text
stdio://
unix://
unix://PATH
ws://IP:PORT
off
```

For non-loopback WebSocket listeners, app-server refuses to start without a configured
authentication policy. Supported client authentication uses:

```http
Authorization: Bearer <token>
```

Capability-token mode can read a token file or compare against a SHA-256 digest.
CodexDeck stores a configured token encrypted with Android Keystore and applies:

- no redirects;
- no automatic retry on connection failure;
- TLS verification for `wss://`;
- explicit per-Host opt-in for remote cleartext `ws://`;
- loopback/SSH-tunnel `ws://` without requiring global insecure opt-in;
- 20-second WebSocket Ping;
- bounded inbound queue and fail-closed overflow behavior.

WSS is expected to be provided by a TLS reverse proxy or equivalent deployment layer.
Do not place an unauthenticated `ws://` listener on a public interface.

## Experimental and compatibility detection

Protocol areas marked experimental in current source include:

- named permission profiles;
- collaboration/Plan mode payloads;
- app-server WebSocket remote clients;
- some active-Thread resume fields;
- skill/plugin catalog APIs;
- aspects of user verification.

The client advertises experimental capability explicitly. Optional discovery calls are
individually guarded so older servers can still provide Thread/Chat functionality. A
missing method is displayed as unavailable rather than simulated.

The generated schemas are the validation reference when adding fields. Protocol tests
must include unknown-field tolerance because newer servers add fields frequently.

## Verification commands

Unit and build validation:

```sh
./gradlew \
  :protocol:test \
  :app:testDebugUnitTest \
  :terminal-emulator:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Real stdio server:

```sh
mkdir -p /tmp/codexdeck-codex-home
CODEX_HOME=/tmp/codexdeck-codex-home \
python3 scripts/check_app_server_protocol.py
```

Real authenticated WebSocket server:

```sh
TOKEN='codexdeck-test'
HASH="$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)"
CODEX_HOME=/tmp/codexdeck-codex-home codex app-server \
  --listen ws://127.0.0.1:18766 \
  --ws-auth capability-token \
  --ws-token-sha256 "$HASH"

CODEXDECK_WS_TEST_URL=ws://127.0.0.1:18766 \
CODEXDECK_WS_TEST_TOKEN="$TOKEN" \
./gradlew :app:testDebugUnitTest \
  --tests 'cc.cherr.codexdeck.transport.WebSocketAppServerSmokeTest'
```

Real loopback OpenSSH route, encrypted key formats, App Server initialization, and PTY:

```sh
python3 scripts/ssh_test_server.py -- \
  ./gradlew :app:testDebugUnitTest \
  --tests 'cc.cherr.codexdeck.SshConnectionIntegrationTest'
```

The current smoke test covers initialize, Thread listing, Skills, MCP status, and Plugin
catalog calls. SSH/ProxyJump forwarding still requires a separate real-server lab run.
