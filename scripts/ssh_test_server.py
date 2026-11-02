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
import json
import sys

print("fake app-server diagnostic banner", file=sys.stderr, flush=True)
for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    request_id = message.get("id")
    if request_id is None:
        continue
    if method == "initialize":
        result = {
            "userAgent": "codexdeck-ssh-integration-test",
            "codexHome": "/tmp/codexdeck-test-home",
            "platformFamily": "unix",
            "platformOs": "linux",
        }
    elif method == "thread/list":
        result = {"data": [], "nextCursor": None}
    else:
        result = {}
    print(json.dumps({"id": request_id, "result": result}), flush=True)
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
