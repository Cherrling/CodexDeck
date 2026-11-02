# Codex App Server Schemas

The checked-in schemas are generated artifacts from:

```text
codex-cli 0.166.0
openai/codex source commit 68334831d81220374a3b5805b7e41b85f94e88e0
generated 2026-11-03 Asia/Shanghai
```

Files:

- `app-server.schema.json`: complete generated schema bundle.
- `app-server-v2.schema.json`: generated v2 schema bundle.

Regenerate both with the same Codex CLI version used by the development host:

```sh
tmp_dir="$(mktemp -d)"
codex app-server generate-json-schema --out "$tmp_dir"
cp "$tmp_dir/codex_app_server_protocol.schemas.json" schemas/app-server.schema.json
cp "$tmp_dir/codex_app_server_protocol.v2.schemas.json" schemas/app-server-v2.schema.json
rm -rf "$tmp_dir"
```

Schemas are versioned with Codex, not with CodexDeck. When a remote host upgrades
Codex across protocol changes, update these files and run the protocol smoke test
and Android unit tests before publishing a rolling build.
