# Codex App Server Schemas

The checked-in schemas are generated artifacts from:

```text
codex-cli 0.166.0
openai/codex source commit 68334831d81220374a3b5805b7e41b85f94e88e0
generated 2026-11-03 Asia/Shanghai
```

Files:

- `app-server.schema.json`: generated stable protocol bundle.
- `app-server-v2.schema.json`: generated stable v2 protocol bundle.
- `app-server-experimental.schema.json`: generated bundle including experimental APIs.
- `app-server-v2-experimental.schema.json`: generated v2 bundle including experimental
  APIs. CodexDeck initializes with `experimentalApi: true`, so fields such as named
  permission profiles and collaboration mode must be validated against this file.

Regenerate all four with the same Codex CLI version used by the development host:

```sh
stable_dir="$(mktemp -d)"
experimental_dir="$(mktemp -d)"
codex app-server generate-json-schema --out "$stable_dir"
codex app-server generate-json-schema --experimental --out "$experimental_dir"
cp "$stable_dir/codex_app_server_protocol.schemas.json" \
  schemas/app-server.schema.json
cp "$stable_dir/codex_app_server_protocol.v2.schemas.json" \
  schemas/app-server-v2.schema.json
cp "$experimental_dir/codex_app_server_protocol.schemas.json" \
  schemas/app-server-experimental.schema.json
cp "$experimental_dir/codex_app_server_protocol.v2.schemas.json" \
  schemas/app-server-v2-experimental.schema.json
rm -rf "$stable_dir" "$experimental_dir"
```

Schemas are versioned with Codex, not with CodexDeck. When a remote host upgrades
Codex across protocol changes, update these files and run the protocol smoke test
and Android unit tests before publishing a rolling build.
