# CodexDeck

CodexDeck is a native Android client for using Codex on a remote Linux development
machine. It speaks the Codex app-server protocol directly; the main chat experience
is not an SSH terminal wrapper.

The same app also contains an interactive SSH terminal, backed by the Termux terminal
emulator and the same encrypted Host, Identity, ProxyJump, and connection infrastructure
used by Codex.

> Status: early `v0.1` development build. USB/emulator device validation and a full
> remote SSH/ProxyJump/terminal lab run are still required before calling this a
> production replacement for an SSH workflow.

## Highlights

- Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, Room, and Android Keystore.
- Multiple SSH Hosts with groups, tags, favorites, retries, timeouts, keepalive, and
  password/private-key authentication.
- Ed25519, RSA, and ECDSA key generation/import, encrypted key passphrases, public-key
  viewing/copying/file export, and Keystore-encrypted storage.
- SSH Host Key verification with explicit first-use trust and mismatch rejection.
- Multi-hop ProxyJump using SSH `direct-tcpip`, independent authentication per hop.
- Codex app-server over SSH stdio or direct WebSocket/WSS.
- Optional SSH local-port forwarding to a remote loopback WebSocket app-server.
- Paginated Thread list, remote title/cwd/preview search, archived Threads, resume,
  create, rename, fork, archive/unarchive, compaction, and history pagination.
- Streaming Agent/Reasoning/Plan/command events, Markdown/code fences, command output,
  file changes/aggregate Turn diffs, tool calls, collaboration/Subagent state, and
  approvals that disappear when another connected client resolves them.
- Model, Reasoning Effort, Approval Policy, Sandbox, Permission Profile, Plan mode,
  turn steering, interruption, image attachments, and inline working-tree Review.
- Read-only Skills, MCP server, and Plugin capability inventory with per-capability
  degradation when a server method is unavailable.
- Interactive PTY shell with an upstream Termux emulator, TrueColor, resize, clipboard,
  Ctrl/Alt modifiers, multiple sessions, and a foreground connection service.
- Eight-session LRU UI cache, 120-Turn retention bound, bounded tool output, and
  coalesced streaming updates. Protocol queues and individual messages are bounded.

## Requirements

- Android 8.0 (API 26) or newer.
- A Linux machine running a recent Codex CLI.
- JDK 17 and Android SDK Platform 36 plus Build Tools 35.0.0 to build locally.
- Codex login/configuration must already work on the remote machine. CodexDeck does
  not require ChatGPT login on Android and does not hardcode a provider or model list.

Protocol and source research currently targets OpenAI Codex commit
`68334831d81220374a3b5805b7e41b85f94e88e0` (2026-11-02). The stdio handshake and
Thread listing were exercised with `codex-cli 0.166.0`.

## Configure Codex transport

### SSH stdio

Select `SSH stdio` in the Host editor. CodexDeck executes only:

```sh
exec '<codex executable>' app-server --listen stdio://
```

The executable is shell-quoted as one path. No interactive shell profile is sourced by
CodexDeck, preventing `.bashrc`/`.zshrc` output from corrupting JSONL. Use an absolute
path or ensure the executable is available in the non-interactive SSH `PATH`:

```sh
ssh user@example.com 'command -v codex'
```

The codex executable may be configured per Host, for example
`/opt/codex/bin/codex`.

### Direct WebSocket

For a loopback listener:

```sh
codex app-server --listen ws://127.0.0.1:8765
```

For a non-loopback listener, current Codex requires WebSocket authentication. A
capability token can be configured by hash:

```sh
TOKEN='replace-with-a-long-random-token'
HASH="$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)"
codex app-server \
  --listen ws://0.0.0.0:8765 \
  --ws-auth capability-token \
  --ws-token-sha256 "$HASH"
```

Then configure the Host with:

- URL: `ws://server-address:8765`
- Bearer Token: `$TOKEN`
- Explicitly enable cleartext `ws://` for a non-loopback listener.

Direct WebSocket Hosts do not require unused SSH usernames, identities, or executable
paths. Those fields are required only for SSH stdio or SSH-tunneled WebSocket mode.

Public deployments should terminate TLS and use `wss://`. CodexDeck validates the
system TLS trust chain and disables redirects so the Bearer token cannot be redirected
to a different origin. Do not expose an unauthenticated app-server to the Internet.

### WebSocket through SSH

This is the preferred way to reach a loopback app-server without publishing a port:

1. Keep `codex app-server --listen ws://127.0.0.1:8765` running.
2. Configure a Host with its normal SSH settings.
3. Choose `WebSocket`, URL `ws://127.0.0.1:8765`, and enable
   `通过此 Host 的 SSH 链路建立本地端口转发`.

CodexDeck opens a local loopback ServerSocket, forwards TCP through SSHJ
`direct-tcpip` over the existing SSH route, and connects OkHttp to the local forwarded
port. ProxyJump is inherited from the same Host connection manager.

Run the app-server in `tmux`, systemd, or another supervisor if it must outlive the
phone connection. An SSH exec channel ending, an app-server process ending, and an
Android network interruption are distinct failure modes.

## Android setup

1. Open **服务器** and add a Host.
2. Import or generate an SSH Identity.
3. Choose password or key authentication.
4. On first connection, compare and explicitly trust the displayed Host Key
   fingerprint.
5. Select the Codex transport and connect.
6. Open a Thread or create one with an absolute remote `cwd`.

The default debug package is `cc.cherr.codexdeck.debug`. A release-signed package uses
`cc.cherr.codexdeck`.

## Security model

- Private keys, SSH passwords, and app-server Bearer tokens are never placed in Room as
  plaintext. AES-256-GCM content encryption keys live in Android Keystore; additional
  authenticated data binds each ciphertext to its Host/Identity context.
- Android Keystore protects the local encryption key, not arbitrary OpenSSH/PEM/private
  key formats directly. Imported private-key bytes are encrypted with that vault key.
- No credential is sent to logs. App-server stderr is delivered separately and is never
  mixed into JSON-RPC.
- Known Host Key changes are rejected. There is no global “accept any host key” mode.
- `ws://` is accepted only for loopback, SSH-tunneled loopback, or after an explicit
  per-Host cleartext opt-in. `wss://` uses Android/platform TLS verification.
- Android backup is disabled. A lost or invalidated Keystore key makes encrypted
  credentials unrecoverable by design; re-import/re-enter them.

See [ARCHITECTURE.md](docs/ARCHITECTURE.md), [PROTOCOL.md](docs/PROTOCOL.md), and
[FEATURE_PARITY.md](docs/FEATURE_PARITY.md) for implementation and protocol details.

## Build and test

```sh
export ANDROID_HOME="$HOME/opt/android-sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew \
  :protocol:test \
  :app:testDebugUnitTest \
  :terminal-emulator:testDebugUnitTest \
  :terminal-view:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Real official-server checks:

```sh
# SSH stdio handshake and Thread listing through JSONL
mkdir -p /tmp/codexdeck-codex-home
CODEX_HOME=/tmp/codexdeck-codex-home \
python3 scripts/check_app_server_protocol.py

# Opt-in WebSocket integration test against a running authenticated server
CODEXDECK_WS_TEST_URL=ws://127.0.0.1:8765 \
CODEXDECK_WS_TEST_TOKEN=your-token \
./gradlew :app:testDebugUnitTest \
  --tests 'cc.cherr.codexdeck.transport.WebSocketAppServerSmokeTest'

# Real OpenSSH test: encrypted key formats, two-hop direct-tcpip,
# SSH stdio app-server initialization, and a shared PTY. The isolated Docker
# sshd is used because some container/non-root hosts cannot allocate PTYs from
# a host-side sshd.
docker build \
  --file scripts/ssh-test.Dockerfile \
  --tag codexdeck-ssh-test:local \
  .
SSH_TEST_DOCKER_IMAGE=codexdeck-ssh-test:local \
SSH_TEST_DEBUG=1 \
python3 scripts/ssh_test_server.py -- \
  ./gradlew :app:testDebugUnitTest \
    --tests 'cc.cherr.codexdeck.SshConnectionIntegrationTest'
```

Schema files under `schemas/` are generated from the pinned official source; see
[schemas/README.md](schemas/README.md).

## CI and Rolling Pre-release

`.github/workflows/ci.yml` runs protocol/app/terminal tests, a real isolated Docker
OpenSSH/ProxyJump/PTY integration test, lint, and a debug APK build for every Pull
Request and push to `main`.

Gradle dependencies and local build-cache entries are shared through the GitHub Actions
cache backend. The Docker OpenSSH fixture uses Buildx `type=gha,mode=max`, so the base
image and the OpenSSH installation layer are reused by both CI and Rolling Release.
The rolling Debug-fallback keystore is cached separately for update-compatible debug
installs. Android SDK files and generated APKs are deliberately not cached.

`.github/workflows/rolling-release.yml` is manually triggered. It:

1. runs protocol, app, terminal, and real Docker OpenSSH/ProxyJump/PTY tests plus lint;
2. builds a signed release APK or an explicitly named Debug fallback;
3. derives `v0.1-<commit7>-<Asia/Shanghai timestamp>`;
4. uses `100000 + GITHUB_RUN_NUMBER` as the positive monotonic `versionCode`;
5. uploads versioned and fixed-name APKs, `SHA256SUMS`, and `build-info.json`;
6. moves only the fixed `rolling` Tag after the build succeeds;
7. updates the single `Rolling Pre-release` Release and removes stale APK assets.

### Release signing secrets

Configure all four repository secrets, or none:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

`ANDROID_KEYSTORE_BASE64` is the Base64 encoding of the keystore file. If all four are
absent, the workflow publishes an APK whose filename ends in `-debug.apk`; its
applicationId is `.debug`, and Release Notes explicitly mark it as a Debug fallback.
Partial signing configuration fails the workflow.

The workflow caches the generated debug keystore on a best-effort basis so Rolling
debug builds are usually update-compatible. Cache eviction can still change the
signature. Configure the four release secrets for a guaranteed stable installation
identity and an explicitly signed release APK.

Keep the same release key for future Rolling builds. Android cannot update an installed
APK with a different signing key; switching keys requires uninstalling the old app and
loses non-exported local state.

## Known limitations

- SDK compilation, unit tests, lint, APK assembly, real stdio protocol, and direct
  authenticated WebSocket protocol have been verified locally.
- A loopback OpenSSH lab verifies encrypted key formats, two-hop `direct-tcpip`,
  SSH-stdio app-server initialization, and a shared PTY.
- No Android device/emulator was available in the current environment, so IME,
  AndroidView lifecycle, foreground-service behavior, true-device streaming, and
  process-death restoration still require device validation.
- No external production SSH server, network interruption lab, or Android device was
  available; those paths still need real-device and deployment validation.
- The current AGP 8.13 toolchain compiles and targets API 36. The CI runner also exposes
  API 37, so `OldTargetApi` is explicitly suppressed until the separate AGP 9/Kotlin
  migration is completed and device-tested.
- MCP, Skills, and Plugins are currently inventory views. MCP OAuth, plugin
  install/remove, Skills configuration, and rich app/resource UIs are not complete.
- Markdown rendering handles paragraphs and fenced code but does not yet provide full
  CommonMark tables/images/links or syntax highlighting.
- A different app-server process does not share live Turn execution. PC and Android can
  observe the same active Thread only when connected to the same app-server process.

## License

CodexDeck is licensed under GPL-3.0. Termux-derived components and other third-party
terms are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
`third-party/termux/`.
