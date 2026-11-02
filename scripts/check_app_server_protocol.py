#!/usr/bin/env python3
"""Smoke-test the installed Codex app-server over its native JSONL stdio transport."""

from __future__ import annotations

import argparse
import json
import queue
import subprocess
import sys
import threading
import time
from typing import Any


def read_json_line(lines: queue.Queue[str], timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Timed out waiting for app-server JSON output")
        try:
            line = lines.get(timeout=remaining)
        except queue.Empty as error:
            raise TimeoutError("Timed out waiting for app-server JSON output") from error
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            print(f"Ignored non-JSON stdout line: {line[:200]!r}", file=sys.stderr)
            continue
        if isinstance(message, dict):
            return message


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()

    process = subprocess.Popen(
        [args.codex, "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    assert process.stdin and process.stdout and process.stderr
    lines: queue.Queue[str] = queue.Queue()

    def drain_stdout() -> None:
        assert process.stdout
        for line in process.stdout:
            lines.put(line)

    stderr_tail: list[str] = []

    def drain_stderr() -> None:
        assert process.stderr
        for line in process.stderr:
            stderr_tail.append(line.rstrip())
            del stderr_tail[:-30]

    threading.Thread(target=drain_stdout, daemon=True).start()
    threading.Thread(target=drain_stderr, daemon=True).start()

    def send(message: dict[str, Any]) -> None:
        process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        process.stdin.flush()

    try:
        send(
            {
                "id": 1,
                "method": "initialize",
                "params": {
                    "clientInfo": {
                        "name": "codex_deck_protocol_check",
                        "title": "CodexDeck protocol check",
                        "version": "0.1",
                    },
                    "capabilities": {"experimentalApi": True},
                },
            }
        )
        initialize = wait_for_response(lines, 1, args.timeout)
        if "error" in initialize:
            raise RuntimeError(f"initialize failed: {initialize['error']}")
        result = initialize.get("result")
        if not isinstance(result, dict):
            raise RuntimeError("initialize returned no object result")
        print(
            "initialize OK:",
            result.get("userAgent", "unknown"),
            result.get("platformFamily", "unknown"),
            result.get("platformOs", "unknown"),
        )

        send({"method": "initialized", "params": {}})
        send(
            {
                "id": 2,
                "method": "thread/list",
                "params": {
                    "limit": 1,
                    "sortKey": "updated_at",
                    "sortDirection": "desc",
                    "sourceKinds": [],
                },
            }
        )
        thread_page = wait_for_response(lines, 2, args.timeout)
        if "error" in thread_page:
            raise RuntimeError(f"thread/list failed: {thread_page['error']}")
        page = thread_page.get("result")
        if not isinstance(page, dict) or not isinstance(page.get("data"), list):
            raise RuntimeError("thread/list returned an invalid result")
        print(f"thread/list OK: {len(page['data'])} visible thread(s) on first page")
        return 0
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        if process.returncode not in (0, None) and stderr_tail:
            print("app-server stderr tail:", file=sys.stderr)
            print("\n".join(stderr_tail), file=sys.stderr)


def wait_for_response(
    lines: queue.Queue[str],
    request_id: int,
    timeout: float,
) -> dict[str, Any]:
    while True:
        message = read_json_line(lines, timeout)
        if message.get("id") == request_id and ("result" in message or "error" in message):
            return message


if __name__ == "__main__":
    raise SystemExit(main())
