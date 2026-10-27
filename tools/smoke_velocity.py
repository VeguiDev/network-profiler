#!/usr/bin/env python3
"""Real Velocity + final shaded plugin smoke test. No backend, EULA acceptance or packet mocks.

Sends real Minecraft status/ping exchanges over TCP and compares the exact transport byte totals.
Use --hold-seconds to inspect the real dashboard while the process is running.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import urllib.request
import zipfile

VELOCITY_SHA256 = "85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f"


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def varint(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        result.append(byte | (128 if value else 0))
        if not value:
            return bytes(result)


def read_exact(sock, size):
    result = b""
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise RuntimeError("Minecraft connection closed early")
        result += chunk
    return result


def read_frame(sock):
    length = prefix = shift = 0
    while True:
        byte = read_exact(sock, 1)[0]
        prefix += 1
        length |= (byte & 127) << shift
        if not byte & 128:
            break
        shift += 7
        if shift > 28:
            raise RuntimeError("Invalid frame length")
    return read_exact(sock, length), prefix + length


def exchange(mc_port, proxy_protocol=False):
    host = b"localhost"
    handshake = b"\x00" + varint(769) + varint(len(host)) + host + struct.pack(">H", mc_port) + b"\x01"
    request = varint(len(handshake)) + handshake + b"\x01\x00"
    if proxy_protocol:
        request = f"PROXY TCP4 127.0.0.1 127.0.0.1 12345 {mc_port}\r\n".encode() + request
    with socket.create_connection(("127.0.0.1", mc_port), timeout=5) as sock:
        sock.sendall(request)
        response, received = read_frame(sock)
        assert response[0] == 0, "Expected Minecraft status response"
        ping = b"\x09\x01" + struct.pack(">q", 123456789)
        sock.sendall(ping)
        pong, pong_bytes = read_frame(sock)
        assert pong == ping[1:], "Ping response changed"
    return len(request) + len(ping), received + pong_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--velocity-jar", type=Path, required=True)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--packetevents", type=Path)
    parser.add_argument("--proxy-protocol", action="store_true")
    parser.add_argument("--hold-seconds", type=int, default=0)
    args = parser.parse_args()
    assert hashlib.sha256(args.velocity_jar.read_bytes()).hexdigest() == VELOCITY_SHA256, "Expected Velocity 4.3.0 build 44"
    directory = Path(tempfile.mkdtemp(prefix="velocity-profiler-", dir="/tmp/opencode"))
    plugins = directory / "plugins"
    plugins.mkdir()
    shutil.copy2(args.plugin, plugins / "network-profiler.jar")
    if args.packetevents:
        shutil.copy2(args.packetevents, plugins / "packetevents.jar")
    mc_port, http_port = port(), port()
    with zipfile.ZipFile(args.velocity_jar) as jar:
        config = jar.read("default-velocity.toml").decode()
    import re
    config = re.sub(r'^bind = .*$', f'bind = "127.0.0.1:{mc_port}"', config, flags=re.M)
    if args.proxy_protocol:
        config = config.replace("haproxy-protocol = false", "haproxy-protocol = true")
    (directory / "velocity.toml").write_text(config)
    data = plugins / "networkprofiler"
    data.mkdir()
    (data / "config.yml").write_text(f"http:\n  port: {http_port}\ntraffic:\n  channel-provider: {'packetevents' if args.packetevents else 'native'}\n")
    log_path = directory / "smoke.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(["java", "-Xms128m", "-Xmx512m", "-jar", str(args.velocity_jar.resolve())],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        root = f"http://127.0.0.1:{http_port}"

        def get(path):
            with urllib.request.urlopen(root + path, timeout=3) as response:
                return json.load(response)

        try:
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Velocity exited: {log_path.read_text()}")
                try:
                    health = get("/api/v1/health")
                    if health["healthy"]:
                        with socket.create_connection(("127.0.0.1", mc_port), timeout=1):
                            pass
                        break
                except (OSError, ValueError):
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError(f"Startup timeout: {log_path.read_text()}")
                time.sleep(0.2)
            before = get("/api/v1/traffic/current")
            sent = received = 0
            for _ in range(12):
                incoming, outgoing = exchange(mc_port, args.proxy_protocol)
                sent += incoming
                received += outgoing
            time.sleep(1.3)
            after = get("/api/v1/traffic/current")
            actual_in = int(after["traffic"]["inbound"]["totalBytes"]) - int(before["traffic"]["inbound"]["totalBytes"])
            actual_out = int(after["traffic"]["outbound"]["totalBytes"]) - int(before["traffic"]["outbound"]["totalBytes"])
            assert (actual_in, actual_out) == (sent, received), f"Byte mismatch: {(actual_in, actual_out)} != {(sent, received)}"
            assert get("/api/v1/traffic/history")["samples"], "History missing"
            with urllib.request.urlopen(root, timeout=3) as response:
                assert b"Network Profiler" in response.read(), "Dashboard missing"
            print(json.dumps({"result": "PASS", "dashboard": root, "inboundBytes": actual_in,
                              "outboundBytes": actual_out, "provider": "packetevents" if args.packetevents else "native",
                              "proxyProtocol": args.proxy_protocol, "log": str(log_path)}, indent=2), flush=True)
            end = time.monotonic() + args.hold_seconds
            while time.monotonic() < end:
                exchange(mc_port, args.proxy_protocol)
                time.sleep(1)
        finally:
            if process.poll() is None:
                process.stdin.write("shutdown\n")
                process.stdin.flush()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
                    raise RuntimeError("Velocity did not stop cleanly")
            assert process.returncode == 0, f"Velocity exit code {process.returncode}; see {log_path}"
            with socket.socket() as sock:
                sock.settimeout(1)
                assert sock.connect_ex(("127.0.0.1", http_port)) != 0, "HTTP port still open"
    print("Clean shutdown verified. Play/login/chunks still require a real backend and client.")


if __name__ == "__main__":
    main()
