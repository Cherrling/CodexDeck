# CodexDeck Architecture

## Scope

CodexDeck is a native Android client for a remote Codex app-server. It supports two
user-facing remote surfaces over shared SSH infrastructure:

1. A structured Codex Thread/Turn/Approval UI over app-server JSON-RPC.
2. An interactive PTY terminal for shells, tmux, editors, monitoring tools, and the
   Codex TUI.

The app does not parse terminal output to reconstruct Codex state. Agent messages,
commands, diffs, tool calls, approvals, and Thread metadata come from app-server
notifications and requests.

## Researched revisions

| Source | Revision | Role |
| --- | --- | --- |
| OpenAI Codex schema pin | `68334831d81220374a3b5805b7e41b85f94e88e0` | Generated protocol authority |
| OpenAI Codex upstream check | `00df7f65abb7b953cb2498281d451d3e06a34e64` | Current app-server-protocol equality check |
| ShellDeck | `8ad081a76e4cdb6b3ed65a550e6ae30402f1a22c` | SSH, identity, Host, terminal, UI experience |
| codex-remote-android | `372581ad5cc66677d5322a9a447a03277d36ce15` | Feature inventory and UX reference |

Codex app-server protocol v2 is the source of truth. Reference-project protocol names
and compatibility behavior are not copied blindly. Generated current schemas are
checked in under `schemas/`. The `codex-rs/app-server-protocol` tree is identical at the
two recorded Codex revisions.

## Module and package boundaries

```text
:app
  ui/                 Compose screens, dialogs, navigation
  AppController.kt    Process-scoped orchestration and UI state
  transport/          OkHttp WebSocket JSON transport
  ssh/                Host trust, authentication, routes, exec, PTY, forwarding
  terminal/           Termux emulator adapter and Android View lifecycle
  data/               Room, preferences, encrypted credential vault
:protocol
  JsonLineTransport   Transport-neutral inbound/outbound JSON boundary
  AppServerClient     Envelopes, IDs, requests, approvals, events, parsing
:terminal-emulator    Upstream Termux emulator plus minimal reviewed patches
:terminal-view        Upstream Termux Android View plus minimal reviewed patches
```

Compose code talks to `AppController` and `AppState`. It never owns an SSH channel,
Socket, app-server request ID, or plaintext credential.

## Runtime data flow

```text
Compose UI
    |
    v
AppController ---- Room / SettingsStore / CredentialVault
    |
    +---- AppServerClient ---- JsonLineTransport
    |                              |
    |                              +---- SshJsonLineTransport
    |                              |       command stdin/stdout JSONL
    |                              |       command stderr -> diagnostics
    |                              |
    |                              +---- WebSocketJsonLineTransport
    |                                      one JSON message per text frame
    |
    +---- SshConnectionManager
             |
             +---- SharedSshConnection
                     +---- app-server exec channel
                     +---- interactive PTY channel(s)
                     +---- local direct-tcpip forwarding for a WebSocket
```

The transport contract is intentionally small:

```kotlin
interface JsonLineTransport {
    val incoming: Flow<String>
    suspend fun send(line: String)
    suspend fun close()
}
```

SSH stdio lines and WebSocket text frames both enter the same `AppServerClient`.
Business/UI code is unchanged when transport mode changes.

## SSH infrastructure

### Hosts, Identities, and credentials

- A Host references an Identity instead of copying private keys per Host.
- Multiple Hosts can share one Identity.
- Password and private-key authentication are supported per Host and per jump hop.
- Direct WebSocket Hosts validate only the WebSocket endpoint and do not require unused
  SSH usernames or identities. SSH fields become required when stdio or an SSH tunnel
  is selected.
- Configurable timeout, keepalive, and retry count are persisted with the Host.
- Host and Identity records never store password/private-key plaintext.

`CredentialVault` uses an Android Keystore AES-256 key and AES-GCM. A version byte,
random IV, and authenticated context string are stored with each ciphertext. Contexts
such as `host-password:<id>`, `identity:<id>`, and `appserver-token:<id>` prevent
ciphertext substitution between records.

Imported OpenSSH/PEM/PKCS#8 keys are parsed with SSHJ/Bouncy Castle. Encrypted key
passphrases are requested only during authentication and are not persisted. Generated
keys are encoded to PKCS#8, optionally encrypted, then encrypted again by the local
vault for storage.

### Host Key verification

Each route hop has its own verifier and known-host record keyed by Host and port. First
use presents algorithm and SHA-256 fingerprint with Cancel / Trust Once / Trust & Save.
A changed pinned key raises a visible security failure and is never silently replaced.

### ProxyJump

`jumpRoute()` walks `Host.jumpHostId` references, rejects cycles, limits the route to
four jump hops, and returns outer gateway -> target order. SSHJ
`newDirectConnection(host, port)` connects each next SSH transport through the previous
authenticated transport. No shell command such as an `ssh ...` string is constructed to
simulate ProxyJump.

The final `SharedSshConnection` owns every SSH client in the route. It multiplexes an
app-server exec channel, multiple PTY shell channels, and optional local TCP forwarding.
Closing one shell does not close the shared route; closing the route closes all owned
channels and clients.

### stdio isolation

The SSH exec command is:

```text
exec '<configured path>' app-server --listen stdio://
```

The executable is shell-quoted as a single path. stdout is treated as JSONL. stderr has
a separate reader and diagnostic flow. Non-JSON stdout is reported as a diagnostic and
is not interpreted as terminal content or JSON-RPC.

## App-server transports

### SSH stdio

- JSON object per newline-terminated line.
- stdout/stderr separated at the SSH channel.
- Background reader threads push messages into Flow/Channel boundaries.
- The client supports numeric/string request IDs, concurrent requests, timeouts, server
  requests, notifications, and connection-close propagation.

### Direct WebSocket

- One JSON document per WebSocket text frame.
- Default TLS verification; redirects and connection retries are disabled.
- Optional `Authorization: Bearer ...` capability token.
- Ping every 20 seconds; no high-frequency application polling.
- Outbound queue failure or inbound overflow closes the transport rather than silently
  dropping protocol events.

### SSH-forwarded WebSocket

For `ws://127.0.0.1:PORT`:

```text
OkHttp -> 127.0.0.1:<ephemeral>
       -> SSHJ LocalPortForwarder
       -> direct-tcpip on the target SSH route
       -> 127.0.0.1:PORT app-server
```

The forwarder is bound to an ephemeral IPv4 loopback port and tracked by
`SharedSshConnection`. It closes with the app-server connection or SSH route.

## State model

The app deliberately distinguishes four concepts:

1. **Persisted Thread history**: rollout/session data owned by the remote Codex
   installation.
2. **Loaded Core Thread**: a Thread resident inside a particular app-server process.
3. **Active Turn**: execution currently running inside that loaded Thread.
4. **Android UI cache**: bounded, non-authoritative `ThreadViewState` used for fast
   switching and display.

`thread/resume` establishes a subscription to the selected Thread. App-server can
return history while also subscribing atomically to subsequent updates. After a
transport loss, CodexDeck reconnects and resumes the Thread again. It never treats the
Android cache as proof that a Turn is still running and never blindly re-sends
`turn/start`, because that request is not idempotent.

Resume does not overwrite persisted model, approval, sandbox, or permission settings.
The returned authoritative settings populate the controls. Later
`thread/settings/updated` notifications from another client update the cached Thread and
visible controls; `serverRequest/resolved` removes approvals already handled elsewhere.

`threadViews` keeps at most eight recently used Thread snapshots. Each snapshot has its
own Turn list, active Turn ID, running flag, and older-history cursor. Events for a
non-current subscribed Thread update its cache instead of being inserted into another
Thread's visible timeline.

## Event processing and UI performance

- Deltas are accumulated for 40 ms and applied as a batch.
- Item lifecycle and Turn boundaries flush pending deltas before updating status.
- Any event with a non-matching Thread ID updates only that Thread's cache.
- The active Thread retains at most 120 Turns.
- A single text/output field is capped at 524,288 characters with an explicit truncation
  marker.
- LazyColumn keys are stable by Turn/item ID.
- Database I/O and Base64 image file reads run off the main thread.
- Message attachment limits are four images, 5 MiB per image, and 12 MiB of encoded data
  per send.
- SSH stdout uses a 256-message bounded queue and a 16 MiB line limit. WebSocket uses a
  512-message bounded queue and a 16 MiB text-frame limit; overflow closes the transport.

## Lifecycle and background behavior

`AppController` is process-scoped in `CodexDeckApplication`, independent of Compose
navigation or Activity recreation. A low-importance foreground service is active only
while a connection/retry or terminal session needs it. It has a visible notification
and a Disconnect action. It does not acquire a wakelock and performs no polling.
Opening or closing a Shell does not rewrite the Codex connection phase; the foreground
service is driven by live terminal sessions and the independent Codex transport state.
On Android versions that enforce the `dataSync` foreground-service time limit, the
service handles `onTimeout` by disconnecting and stopping instead of continuing past the
platform boundary.

SSH clients, app-server transports, and terminal channels are closed on
`Dispatchers.IO` through process-scoped cleanup scopes. Detaching a route first removes
it from the connection-manager map, so no new executor can acquire a connection that is
being closed. UI/state transitions never synchronously wait for socket shutdown.

Thread creation and Turn submission use synchronous busy/sending claims before a
coroutine is launched. This closes the Compose double-click race that could otherwise
submit two non-idempotent `turn/start` requests. A completion callback only clears the
composer if it still belongs to the state that initiated the send.

The last Host/Thread identity is remembered outside credential records. Opening the app
after process death attempts a single visible reconnect and Thread resume. Manual
Disconnect clears that restore marker. Android may still terminate a foreground
data-sync service under platform policy; the server-side Agent truth is never inferred
from the phone process.

## Persistence

Room schema v2 stores Hosts, Identities, known Host keys, and lightweight Thread list
cache records. Migration 1 -> 2 adds app-server transport, URL, encrypted Bearer token,
SSH-tunnel, and explicit cleartext settings. The exported Room schemas are checked for
matching columns and defaults. Instrumentation-based migration execution remains
device-unverified in this environment.

Android backup and data extraction are disabled. There is currently no cross-device
credential backup/export/import flow.

## Deliberate non-goals

- No embedded Android-hosted Linux userspace.
- No fake Agent events or static Session success state.
- No assumption that separate app-server processes share active execution.
- No automatic trust-on-first-use without user confirmation.
- No storing generated keys or passwords in SharedPreferences/logs.
- No permanent public WebSocket listener without server authentication.
