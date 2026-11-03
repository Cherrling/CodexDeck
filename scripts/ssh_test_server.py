#!/usr/bin/env python3
"""Run a command against an isolated public-key-only loopback OpenSSH server."""

from __future__ import annotations

import base64
import getpass
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time


FAKE_APP_SERVER = r"""#!/usr/bin/env python3
import base64
import hashlib
import json
import struct
import sys

print("fake app-server diagnostic banner", file=sys.stderr, flush=True)


def result_for(method):
    if method == "initialize":
        return {
            "userAgent": "codexdeck-ssh-integration-test",
            "codexHome": "/tmp/codexdeck-test-home",
            "platformFamily": "unix",
            "platformOs": "linux",
        }
    if method == "thread/list":
        return {"data": [], "nextCursor": None}
    return {}


def respond(message):
    request_id = message.get("id")
    if request_id is not None:
        return {"id": request_id, "result": result_for(message.get("method"))}
    return None


def run_jsonl():
    for line in sys.stdin:
        response = respond(json.loads(line))
        if response is not None:
            print(json.dumps(response), flush=True)


def read_exact(stream, count):
    data = b""
    while len(data) < count:
        chunk = stream.read(count - len(data))
        if not chunk:
            raise EOFError("unexpected EOF")
        data += chunk
    return data


def send_frame(payload, opcode=1):
    header = bytearray([0x80 | opcode])
    size = len(payload)
    if size < 126:
        header.append(size)
    elif size <= 0xFFFF:
        header.append(126)
        header.extend(struct.pack(">H", size))
    else:
        header.append(127)
        header.extend(struct.pack(">Q", size))
    sys.stdout.buffer.write(header + payload)
    sys.stdout.buffer.flush()


def receive_frame():
    first, second = read_exact(sys.stdin.buffer, 2)
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    size = second & 0x7F
    if size == 126:
        size = struct.unpack(">H", read_exact(sys.stdin.buffer, 2))[0]
    elif size == 127:
        size = struct.unpack(">Q", read_exact(sys.stdin.buffer, 8))[0]
    mask = read_exact(sys.stdin.buffer, 4) if masked else b""
    payload = bytearray(read_exact(sys.stdin.buffer, size))
    if masked:
        for index in range(len(payload)):
            payload[index] ^= mask[index % 4]
    return opcode, bytes(payload)


def run_websocket_proxy():
    request = b""
    while b"\r\n\r\n" not in request:
        byte = sys.stdin.buffer.read(1)
        if not byte:
            raise EOFError("EOF during WebSocket handshake")
        request += byte
    headers = {}
    for line in request.decode("latin-1").split("\r\n")[1:]:
        if ":" in line:
            name, value = line.split(":", 1)
            headers[name.strip().lower()] = value.strip()
    key = headers.get("sec-websocket-key")
    if not key:
        raise RuntimeError("missing Sec-WebSocket-Key")
    if not headers.get("x-codexdeck-bridge"):
        raise RuntimeError("missing local bridge authentication")
    accept = base64.b64encode(
        hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
        ).digest()
    ).decode("ascii")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n"
        "\r\n"
    )
    sys.stdout.buffer.write(response.encode("ascii"))
    sys.stdout.buffer.flush()
    while True:
        opcode, payload = receive_frame()
        if opcode == 0x8:
            send_frame(b"", opcode=0x8)
            return
        if opcode == 0x9:
            send_frame(payload, opcode=0xA)
            continue
        if opcode != 0x1:
            continue
        response = respond(json.loads(payload.decode("utf-8")))
        if response is not None:
            send_frame(json.dumps(response).encode("utf-8"))


if len(sys.argv) >= 3 and sys.argv[1:3] == ["app-server", "proxy"]:
    run_websocket_proxy()
    raise SystemExit(0)

run_jsonl()
"""


def main() -> int:
    command = sys.argv[1:]
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        raise SystemExit("Usage: ssh_test_server.py -- command [args...]")

    docker_image = os.environ.get("SSH_TEST_DOCKER_IMAGE", "")
    sshd = shutil.which("sshd") or "/usr/sbin/sshd"
    with tempfile.TemporaryDirectory(prefix="codexdeck-sshd-", dir="/tmp") as folder:
        root = Path(folder)
        remote_root = Path("/fixture") if docker_image else root
        for name, algorithm, password in [
            ("host", "ed25519", ""),
            ("ed25519", "ed25519", ""),
            ("ed25519-encrypted", "ed25519", "test-passphrase"),
            ("rsa", "rsa", ""),
            ("rsa-encrypted", "rsa", "test-passphrase"),
            ("ecdsa", "ecdsa", ""),
            ("unauthorized", "ed25519", ""),
        ]:
            subprocess.run(
                ["ssh-keygen", "-q", "-t", algorithm, "-N", password, "-f", str(root / name)],
                check=True,
            )

        for name, password in [("rsa-pem", ""), ("rsa-pem-encrypted", "test-passphrase")]:
            shutil.copyfile(root / "rsa", root / name)
            (root / name).chmod(0o600)
            subprocess.run(
                [
                    "ssh-keygen", "-q", "-p", "-m", "PEM",
                    "-P", "", "-N", password, "-f", str(root / name),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )
        for name, password in [("rsa-pkcs8", ""), ("rsa-pkcs8-encrypted", "test-passphrase")]:
            options = ["-passout", "pass:test-passphrase"] if password else ["-nocrypt"]
            subprocess.run(
                [
                    "openssl", "pkcs8", "-topk8",
                    "-in", str(root / "rsa-pem"), "-out", str(root / name),
                    *options,
                ],
                check=True,
            )
        subprocess.run(
            [
                "openssl", "genpkey", "-algorithm", "ED25519",
                "-out", str(root / "ed25519-pkcs8"),
            ],
            check=True,
        )
        subprocess.run(
            [
                "openssl", "pkcs8", "-topk8",
                "-in", str(root / "ed25519-pkcs8"),
                "-out", str(root / "ed25519-pkcs8-encrypted"),
                "-passout", "pass:test-passphrase",
            ],
            check=True,
        )
        public_der = subprocess.check_output(
            [
                "openssl", "pkey", "-in", str(root / "ed25519-pkcs8"),
                "-pubout", "-outform", "DER",
            ]
        )
        if len(public_der) != 44 or not public_der.startswith(bytes.fromhex("302a300506032b6570032100")):
            raise RuntimeError("Unexpected Ed25519 SubjectPublicKeyInfo")
        public_wire = (
            struct.pack(">I", 11) + b"ssh-ed25519" +
            struct.pack(">I", 32) + public_der[12:]
        )
        (root / "ed25519-pkcs8.pub").write_text(
            "ssh-ed25519 " + base64.b64encode(public_wire).decode() + "\n"
        )
        shutil.copyfile(root / "ecdsa", root / "ecdsa-pem")
        (root / "ecdsa-pem").chmod(0o600)
        subprocess.run(
            [
                "ssh-keygen", "-q", "-p", "-m", "PEM",
                "-P", "", "-N", "", "-f", str(root / "ecdsa-pem"),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        subprocess.run(
            [
                "openssl", "ec", "-in", str(root / "ecdsa-pem"),
                "-no_public", "-out", str(root / "ecdsa-no-public"),
            ],
            check=True,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            [
                "openssl", "pkcs8", "-topk8", "-nocrypt",
                "-in", str(root / "ecdsa-no-public"),
                "-out", str(root / "ecdsa-pkcs8"),
            ],
            check=True,
        )

        authorized = ["ed25519", "ed25519-encrypted", "rsa", "rsa-encrypted", "ecdsa"]
        (root / "authorized_keys").write_text(
            "".join((root / f"{name}.pub").read_text() for name in authorized)
        )
        (root / "authorized_keys").chmod(0o600)
        fake_server = root / "fake-codex"
        fake_server.write_text(FAKE_APP_SERVER)
        fake_server.chmod(0o700)
        test_shell = root / "test-shell"
        test_shell.write_text(
            "#!/bin/sh\n"
            "if [ -n \"$SSH_ORIGINAL_COMMAND\" ]; then\n"
            "  exec /bin/sh -c \"$SSH_ORIGINAL_COMMAND\"\n"
            "fi\n"
            "exec /bin/bash --noprofile --norc -i\n"
        )
        test_shell.chmod(0o700)

        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        username = getpass.getuser()
        config = root / "sshd_config"
        remote = lambda name: str(remote_root / name)
        debug = os.environ.get("SSH_TEST_DEBUG") == "1"
        config.write_text(
            f"""ListenAddress {"0.0.0.0" if docker_image else "127.0.0.1"}
Port {port}
HostKey {remote("host")}
PidFile {"/tmp/codexdeck-test-sshd.pid" if docker_image else remote("pid")}
AuthorizedKeysFile {remote("authorized_keys")}
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
UsePAM no
AllowUsers {"root" if docker_image else username}
PermitRootLogin prohibit-password
AllowTcpForwarding yes
GatewayPorts no
X11Forwarding no
PermitTunnel no
PrintMotd no
ForceCommand {remote(test_shell.name)}
LogLevel {"DEBUG1" if debug else "ERROR"}
"""
        )
        config.chmod(0o600)
        log = (root / "server.log").open("w")
        container_name = f"codexdeck-ssh-test-{root.name}"
        if docker_image:
            server_command = [
                "docker",
                "run",
                "--rm",
                "--name",
                container_name,
                "--publish",
                f"127.0.0.1:{port}:{port}",
                "--volume",
                f"{root}:/fixture:ro",
                "--entrypoint",
                "/bin/sh",
                docker_image,
                "-c",
                "mkdir -p /run/sshd && "
                f"exec /usr/sbin/sshd -D -e -f {remote('sshd_config')}",
            ]
        else:
            server_command = [sshd, "-D", "-e", "-f", str(config)]
        server = subprocess.Popen(server_command, stdout=log, stderr=log)
        try:
            for _ in range(100):
                if server.poll() is not None:
                    log.flush()
                    raise RuntimeError((root / "server.log").read_text())
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise RuntimeError("Test SSH server did not start")

            environment = os.environ.copy()
            environment.update(
                SSH_TEST_DIR=str(root),
                SSH_TEST_PORT=str(port),
                SSH_TEST_USER="root" if docker_image else username,
                SSH_TEST_REMOTE_DIR=str(remote_root),
            )
            result = subprocess.run(command, env=environment)
            if result.returncode != 0 and debug:
                log.flush()
                sys.stderr.write("\n--- isolated sshd log ---\n")
                sys.stderr.write((root / "server.log").read_text())
                sys.stderr.write("--- end isolated sshd log ---\n")
            return result.returncode
        finally:
            if docker_image:
                subprocess.run(
                    ["docker", "rm", "--force", container_name],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            else:
                server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())
