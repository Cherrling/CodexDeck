# CodexDeck Feature Parity

This matrix tracks observable capability, not file presence. A feature is only marked
"Verified" when the cited path was exercised in this repository. Device-only behavior
remains unverified until an emulator or physical Android device runs the test.

Status vocabulary:

- **Verified**: exercised locally against the real dependency or generated schema.
- **Implemented**: production path exists and has unit coverage, but the full external
  environment was unavailable.
- **Partial**: a useful subset exists; the missing server capability or UX is explicit.
- **Not implemented**: no production behavior is claimed.

Researched revisions:

| Source | Revision |
| --- | --- |
| OpenAI Codex schema pin | `68334831d81220374a3b5805b7e41b85f94e88e0` |
| OpenAI Codex current upstream check | `00df7f65abb7b953cb2498281d451d3e06a34e64` |
| ShellDeck | `8ad081a76e4cdb6b3ed65a550e6ae30402f1a22c` |
| codex-remote-android | `372581ad5cc66677d5322a9a447a03277d36ce15` |

## SSH and credentials

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| Multiple Host CRUD, grouping, tags, favorites, copy, delete | Implemented | Room Host records and Compose Host editor/list; JVM validation tests pass. |
| Host search and recent-use ordering | Implemented | In-memory Host search; Room orders by favorite and `lastUsedAt`. |
| Password authentication | Implemented | Password bytes are AES-GCM encrypted in `CredentialVault`; runtime prompt fallback exists. |
| SSH Key authentication | Implemented | OpenSSH, PEM, and PKCS#8 import; key parser forced to parse before save. |
| Ed25519 / RSA / ECDSA | Verified | OpenSSH loopback integration test loads unencrypted and encrypted Ed25519, RSA, ECDSA, PEM, and PKCS#8 fixtures. |
| Encrypted private key and passphrase prompt | Verified | Loopback test uses an encrypted Ed25519 key and resolves the blocking SSHJ prompt through `ConnectionPromptGateway`. |
| Generate Ed25519 / RSA 3072 / ECDSA P-256 | Implemented | Generated PKCS#8 keys are re-parsed before encrypted Room storage. Device Keystore generation still needs device validation. |
| Public key view, copy, file export | Implemented | Compose dialog copies or writes an `authorized_keys` line through the system document picker. |
| Multiple Hosts reuse one Identity | Implemented | Host references Identity ID; deletion is restricted while any Host references it. |
| Android Keystore protected local vault | Implemented | AES-256-GCM key remains in AndroidKeyStore; ciphertext is context-bound. JVM tests use a test cipher because Keystore is device-only. |
| Strict Host Key verification | Verified | Loopback test records two independent hop pins; unit tests prove changed fingerprints require the warning decision and are not replaced on Cancel. |
| ProxyJump / multi-hop `direct-tcpip` | Verified | Loopback test connects to a second SSH endpoint through the first authenticated transport without shell command construction. |
| Jump Host independent username, port, auth | Implemented | Each route entry resolves its own Host fields, Identity/password, timeout, keepalive, and known-host pin. |
| Jump cycle/missing-hop/limit validation | Implemented | Unit tests cover cycles and route construction; four jump hops maximum. |
| Shared SSH route for Codex and Shell | Verified | The same loopback route opens an app-server exec channel and a PTY channel in one test. |
| Multiple simultaneous Host runtimes | Implemented, device-unverified | Manual connections have independent app-server clients and per-Host Thread/model/approval/Turn snapshots; switching does not disconnect other Hosts. A two-server device lab remains pending. |
| Explicit-connect startup behavior | Implemented | Launch starts on the Server/Hosts screen and performs no network connection unless the user opts into the default-off launch setting. |
| Retry, timeout, keepalive | Implemented | Persisted per Host; non-idempotent app-server RPCs are never automatically replayed. |
| SSH Agent, FIDO/passkey, hardware-backed key | Not implemented | ShellDeck at the researched revision has no SSH Agent/FIDO/passkey implementation to port. |
| SFTP/Mosh/SOCKS proxy parity | Not implemented | Outside the stated CodexDeck acceptance scope; no simulated support is shown in UI. |

## Codex transport and protocol

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| Codex app-server JSONL over SSH stdio | Verified | Official `codex-cli 0.166.0` initialize + Thread list passed; loopback SSH test also runs an app-server-shaped fixture. |
| Existing daemon over SSH (`app-server proxy`) | Verified protocol path + Implemented Android bridge | `scripts/check_app_server_proxy.py` passes real WebSocket Upgrade, initialize, and Thread list through `codex-cli 0.166.0 app-server proxy`; the loopback SSH fixture also exercises OkHttp framing through the proxy. Deployment against the user's production daemon and Android device remains pending. |
| stdout/stderr isolation | Verified | stdout JSONL and stderr diagnostic readers are separate; non-JSON stdout is rejected as protocol data. |
| JSON-RPC-like request IDs, timeout, concurrent requests | Implemented | Typed numeric/textual IDs preserve the wire type, 30/60 second timeouts, bounded event queue, and protocol tests. |
| Server notifications and server-initiated requests | Implemented | Item, Turn, Thread, settings, diff, warning, error, approval, user-input, permission, and MCP elicitation handling. |
| Clean and exceptional disconnect propagation | Implemented | Both EOF and failures emit a disconnect event; UI distinguishes connection state from server-side Turn truth. |
| Direct WebSocket / WSS | Partial | Authenticated loopback `ws://` passed against the official app-server; TLS/WSS and public deployment remain unverified. |
| SSH tunnel to loopback WebSocket | Implemented | Local loopback listener forwarded through SSHJ `direct-tcpip`; route code is shared with stdio/PTY. |
| Capability detection and graceful degradation | Implemented | Model, collaboration, permission, Skills, MCP, and Plugin calls fail independently and expose unavailable state. |
| Stable + experimental schema pinning | Verified | Stable and `--experimental` bundles are generated from Codex CLI 0.166.0. |

## Threads, Turns, and multi-client behavior

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| Paginated Thread list | Verified | Official stdio and authenticated WebSocket servers returned Thread pages. |
| Local/remote search and archived Threads | Implemented | Debounced `thread/list` search, archived toggle, and local visible-list filtering. |
| Group by remote cwd and recent sort | Implemented | Normalized-cwd project grouping with tests; server sort by `updated_at`. |
| Collapsible per-Server project groups | Implemented | Sessions are grouped by normalized remote `cwd`; collapse state is keyed by Host plus directory and Thread rows open directly into Chat. |
| Stable Session identity | Implemented | Repeated Thread IDs are merged while preserving newest metadata and active runtime status; subagent and other non-user thread records are kept out of top-level Session rows. |
| Create, resume, fork, rename, archive | Implemented | Current v2 methods; resume and first history page are separate calls. |
| Lazy transcript history | Implemented, device-unverified | Resume excludes Turns; initial load is five latest Turn summaries plus 40 Items. A deliberate top drag loads one 50-Item page through the global Item cursor, with a 20-Turn bounded fallback for older servers. |
| Multiple Android Thread UI states | Implemented | Eight-view LRU with per-Thread Turn list, running state, and history cursor. |
| Project cwd and Git branch context | Implemented | Threads are grouped by authoritative remote `cwd`; parsed `gitInfo.branch` is shown in the session row. |
| Full Git worktree inventory | Partial | Current Thread payload contributes cwd/branch/origin only; broader worktree discovery needs explicit server methods or shell-backed, opt-in inspection. |
| Same-process PC/Android Thread observation | Partial | Source establishes subscriptions on resume and single-client live streaming passed; a simultaneous two-client takeover lab is still required. Separate app-server processes do not share active Turns. |
| `thread/settings/updated` from another client | Implemented | Model, effort, approval, sandbox/profile, cwd, and collaboration mode update cached and visible state. |
| Approval resolved by another client | Implemented | `serverRequest/resolved` removes the matching Android dialog; duplicate local submissions are blocked. |
| Simultaneous-client approval precedence | Partial | Source and protocol show multiple subscribers and resolution notifications; exact ownership/race policy still needs a two-client lab on the deployed Codex build. |
| Reconnect without replaying `turn/start` | Verified by design + protocol tests | Reconnect only calls resume and history sync; non-idempotent request is not queued for replay. |
| Read exact PC active runtime from a different app-server process | Not implemented | Impossible by protocol design; persisted history remains accessible but active execution is process-local. |

## Agent features

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| Streaming Agent text | Implemented | 40 ms coalesced append with 524,288-character bound per field. |
| Reasoning state | Implemented | Summary/text deltas retained separately and editable reasoning cards. |
| Plan mode and plan updates | Implemented | Experimental collaboration mode payload plus Plan item and `turn/plan/updated` rendering. |
| Command execution events and output | Implemented | Command cards default to one summary row with status/exit code; full command and output are composed only after expansion. Bounded output deltas and terminal stdin interaction remain supported. |
| File changes and Turn aggregate diff | Implemented | File update patches, item diffs, and `turn/diff/updated`; expandable diff rendering. |
| Tool and MCP call cards | Implemented | MCP/dynamic tool arguments, result/error, and collaboration Agent state render from protocol items. |
| Subagent/collaboration events | Partial | Current item types are rendered; deep per-subagent drill-down and lifecycle filtering are limited by what the server exposes. |
| Basic approvals | Verified | Protocol tests verify exact server request ID echo and command decision payload. |
| Permission, file, user-input, MCP elicitation approvals | Partial | Payloads and response shapes are implemented; rich typed MCP form rendering and policy race testing remain incomplete. |
| Model and Reasoning Effort | Implemented | Values and options come from server `model/list`, never a hard-coded provider/model catalog. |
| Approval Policy / Sandbox / named Permission Profile | Implemented | One permission selector uses server profiles when available and legacy Sandbox only as an older-server fallback; request fields remain mutually exclusive. |
| Approval reviewer routing | Partial | Current `user` and experimental `auto_review` choices are offered; deprecated `guardian_subagent` and `untrusted` are display-only compatibility values and never selectable. |
| Turn steering and interrupt | Implemented | `turn/steer` uses the active Turn precondition; `turn/interrupt` uses Thread + Turn IDs. |
| Thread fork and context compaction | Implemented | V2 methods wired to UI. |
| Image attachments | Implemented | Up to four inline local images, 5 MiB each and 12 MiB encoded total, sent as v2 `UserInput` image data URLs. |
| Remote image URLs | Not implemented | Current server rejects remote URLs unless supplied as inline data. |
| Code Review | Partial | Inline uncommitted-change review is implemented; other review targets need dedicated UI. |
| Skills / MCP / Plugins inventory | Implemented | Read-only server-provided catalogs with per-capability degradation. |
| MCP OAuth, plugin install/remove, typed MCP app/resource UI | Not implemented | Read-only inventory only; no fake mutation controls. |
| Markdown | Implemented, device-unverified | Asynchronous GitHub-Flavored Markdown through `multiplatform-markdown-renderer-m3`: headings, lists, emphasis, links, tables, block quotes, task lists, inline code, and fenced code. Remote images and syntax highlighting remain disabled. |

## Interactive Shell

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| PTY shell, ANSI/TrueColor, history, resize | Verified core parser + Implemented UI | ShellDeck-patched Termux emulator with its upstream tests; loopback test opens PTY and runs a command. |
| tmux, htop, vim, Codex TUI compatibility | Implemented, device-unverified | xterm-256color PTY and mature Termux parser are used; no device/IME/TUI lab was available. |
| Ctrl / Alt / Esc / Tab / arrows / Page keys | Implemented | Modifier state and terminal key bar. Device IME behavior remains unverified. |
| Copy, paste, text selection | Implemented | Android clipboard and Termux selection mode; device UX remains unverified. |
| Multiple Terminal sessions | Implemented | Process-owned session handles and terminal switcher. |
| Disconnect and reconnect | Partial | Closing/reopening shell is supported; process-death PTY restoration is impossible and not claimed. |
| Shared ProxyJump route | Verified | Same loopback two-hop route starts both App Server and PTY. |
| Unicode/Chinese input | Implemented, device-unverified | Termux IME callbacks are retained; physical device IME coverage is pending. |

## Reliability, UI, and delivery

| Capability | Status | Implementation and evidence |
| --- | --- | --- |
| Material 3 dark-first Compose UI | Implemented | Dynamic color, dark default, high-density chat, Hosts/Identities/Sessions/Settings/Terminal screens. |
| Bounded memory and streaming performance | Implemented | LRU views, Turn limit, field/queue/frame bounds, batched deltas, LazyColumn stable keys. |
| Opt-in diagnostic logging | Implemented, UI device-unverified | Default off; immediate disable, bounded 256 KiB/2,000-line rotation, credential/private-key redaction, synchronous crash capture, and in-app view/copy/share/clear. JVM tests cover disabled writes, redaction, fatal writes, and rotation. |
| Foreground connection service | Implemented, device-unverified | Data-sync service with one low-importance notification, Disconnect action, and API 35 timeout handling; no wakelock or polling. |
| Automatic last Host/Thread restore | Implemented, default off | Persisted non-secret restore marker is read only when the user enables launch auto-connect; device lifecycle behavior remains unverified. |
| Main CI on push | Verified | GitHub run `41511422653` passed protocol/app/terminal tests, Docker OpenSSH/ProxyJump/PTY, lint, APK build/signature verification, and artifact upload. |
| Pull Request CI | Implemented, event-unverified | The workflow declares `pull_request`; no external PR event has run yet. The same job path passed on `main`. |
| Rolling pre-release workflow | Verified | Runs `41510727317`, `41512239111`, and `41512833496` updated the single public `rolling` Pre-release with matching Commit, Beijing time, monotonic versionCode, byte-identical APKs, SHA256, and verified signatures. |
| GitHub Actions cache reuse | Verified | Logs show restored Node/Gradle caches, `FROM-CACHE` tasks, Docker `CACHED`/GHA manifest import, and a subsequent `codexdeck-rolling-debug-keystore-v1` cache hit with unchanged certificate SHA-256. |
| Android device E2E | Not verified | No emulator or physical device in the current environment. |
| External production SSH/ProxyJump/WebSocket-tunnel E2E | Partial | Real isolated Docker OpenSSH two-hop test passes; production DNS/firewall/TLS/TUI validation remains pending. |

## Known deliberate limitations

- A rolled-back `GITHUB_RUN_NUMBER` is not supported; GitHub run numbers are monotonic in
  a normal repository workflow. Release verification asserts the published
  `versionCode` and Commit.
- A debug fallback is explicitly named and uses `.debug`; it cannot update a
  release-signed installation.
- GitHub Actions cache eviction can remove the debug keystore; configure release signing
  secrets for an identity that does not depend on cache retention.
- There is no cross-device credential export/backup. Keystore loss intentionally makes
  encrypted credentials unrecoverable.
- Room migration files are checked manually/exported; no instrumentation migration test
  has run without an emulator or device.
- Experimental API fields can change after the pinned Codex revision. Capability errors
  are surfaced instead of replaced with local fake behavior.
