#!/usr/bin/env python3
"""Verify that codex app-server proxy carries a WebSocket to a local daemon socket."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import time


def read_exact(stream, count: int) -> bytes:
    data = b""
    while len(data) < count:
        chunk = stream.read(count - len(data))
        if not chunk:
            raise EOFError("unexpected EOF")
        data += chunk
    return data


def send_text_frame(stream, text: str) -> None:
    payload = text.encode("utf-8")
    mask = secrets.token_bytes(4)
    header = bytearray([0x81])
    if len(payload) < 126:
        header.append(0x80 | len(payload))
    elif len(payload) <= 0xFFFF:
        header.append(0x80 | 126)
        header.extend(struct.pack(">H", len(payload)))
    else:
        header.append(0x80 | 127)
        header.extend(struct.pack(">Q", len(payload)))
    header.extend(mask)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    stream.write(header + masked)
    stream.flush()


def receive_text_frame(stream) -> str:
    fragments = bytearray()
    while True:
        first, second = read_exact(stream, 2)
        opcode = first & 0x0F
        size = second & 0x7F
        if size == 126:
            size = struct.unpack(">H", read_exact(stream, 2))[0]
        elif size == 127:
            size = struct.unpack(">Q", read_exact(stream, 8))[0]
        mask = read_exact(stream, 4) if second & 0x80 else b""
        payload = bytearray(read_exact(stream, size))
        if mask:
            for index in range(len(payload)):
                payload[index] ^= mask[index % 4]
        if opcode == 0x8:
            raise EOFError("WebSocket closed")
        if opcode == 0x9:
            continue
        if opcode in (0x1, 0x0):
            fragments.extend(payload)
        if first & 0x80:
            return fragments.decode("utf-8")


def wait_for_response(stream, request_id: int) -> dict:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        message = json.loads(receive_text_frame(stream))
        if message.get("id") == request_id:
            return message
    raise TimeoutError(f"timed out waiting for response {request_id}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codex", default="codex")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="codexdeck-proxy-check-") as directory:
        root = Path(directory)
        codex_home = root / "codex-home"
        codex_home.mkdir()
        socket_path = codex_home / "app-server-control" / "app-server-control.sock"
        socket_path.parent.mkdir(mode=0o700)
        socket_path.parent.chmod(0o700)
        environment = os.environ.copy()
        environment["CODEX_HOME"] = str(codex_home)
        server_stderr = (root / "app-server.stderr.log").open("wb")
        server = subprocess.Popen(
            [args.codex, "app-server", "--listen", f"unix://{socket_path}"],
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=server_stderr,
        )
        proxy = None
        try:
            for _ in range(200):
                if server.poll() is not None:
                    server_stderr.flush()
                    details = (root / "app-server.stderr.log").read_text(
                        encoding="utf-8",
                        errors="replace",
                    )
                    raise RuntimeError(
                        f"app-server exited before creating its control socket: {details}"
                    )
                if socket_path.exists():
                    break
                time.sleep(0.05)
            else:
                raise TimeoutError("app-server control socket was not created")

            proxy = subprocess.Popen(
                [args.codex, "app-server", "proxy", "--sock", str(socket_path)],
                env=environment,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            assert proxy.stdin is not None and proxy.stdout is not None
            key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
            request = (
                "GET /rpc HTTP/1.1\r\n"
                "Host: localhost\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\n"
                "Sec-WebSocket-Version: 13\r\n"
                "\r\n"
            )
            proxy.stdin.write(request.encode("ascii"))
            proxy.stdin.flush()
            response = b""
            while b"\r\n\r\n" not in response:
                chunk = proxy.stdout.read(1)
                if not chunk:
                    stderr = proxy.stderr.read().decode("utf-8", errors="replace")
                    raise RuntimeError(f"proxy closed during upgrade: {stderr}")
                response += chunk
            if not response.startswith(b"HTTP/1.1 101 "):
                raise RuntimeError(f"WebSocket upgrade rejected: {response!r}")
            expected = base64.b64encode(
                hashlib.sha1(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
                ).digest()
            )
            if expected not in response:
                raise RuntimeError("WebSocket accept key was invalid")

            send_text_frame(
                proxy.stdin,
                json.dumps(
                    {
                        "method": "initialize",
                        "id": 1,
                        "params": {
                            "clientInfo": {
                                "name": "codexdeck_proxy_check",
                                "title": "CodexDeck proxy check",
                                "version": "0.1",
                            }
                        },
                    }
                ),
            )
            initialized = wait_for_response(proxy.stdout, 1)
            if "error" in initialized:
                raise RuntimeError(f"initialize failed: {initialized['error']}")
            send_text_frame(
                proxy.stdin,
                json.dumps({"method": "initialized", "params": {}}),
            )
            send_text_frame(
                proxy.stdin,
                json.dumps({"method": "thread/list", "id": 2, "params": {"limit": 1}}),
            )
            listed = wait_for_response(proxy.stdout, 2)
            if "error" in listed:
                raise RuntimeError(f"thread/list failed: {listed['error']}")
            print(
                "app-server proxy WebSocket OK:",
                initialized["result"].get("userAgent"),
            )
            return 0
        finally:
            if proxy is not None and proxy.poll() is None:
                proxy.terminate()
                try:
                    proxy.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proxy.kill()
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
            server_stderr.close()


if __name__ == "__main__":
    raise SystemExit(main())
