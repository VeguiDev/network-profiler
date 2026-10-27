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


def exchange(mc_port, proxy_protocol=False, packets=None):
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
    if packets is not None:
        for key, size in [(('HANDSHAKING', 'INBOUND', 'HANDSHAKE'), len(handshake)),
                          (('STATUS', 'INBOUND', 'REQUEST'), 1), (('STATUS', 'INBOUND', 'PING'), 9),
                          (('STATUS', 'OUTBOUND', 'RESPONSE'), len(response)), (('STATUS', 'OUTBOUND', 'PONG'), len(pong))]:
            previous = packets.setdefault(key, [0, 0])
            previous[0] += size
            previous[1] += 1
    return len(request) + len(ping), received + pong_bytes


def shutdown(process, http_port, log_path):
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
    assert "Failed to uninject during termination" not in log_path.read_text(), f"Initializer cleanup failed; see {log_path}"
    with socket.socket() as sock:
        sock.settimeout(1)
        assert sock.connect_ex(("127.0.0.1", http_port)) != 0, "HTTP port still open"


def main():
    from smoke_remote import HostedRelay, RemoteDashboard
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--velocity-jar", type=Path, required=True)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--packetevents", type=Path)
    parser.add_argument("--proxy-protocol", action="store_true")
    parser.add_argument("--channel-provider", choices=("native", "packetevents"))
    parser.add_argument("--packet-capture", action="store_true", help="Test real per-type captures, UI/command sharing and immutable persistence (requires PacketEvents)")
    parser.add_argument("--hold-seconds", type=int, default=0)
    parser.add_argument("--cloudflare", action="store_true", help="Use local Wrangler Durable Objects instead of Node")
    args = parser.parse_args()
    if (args.packet_capture or args.channel_provider == "packetevents") and not args.packetevents:
        parser.error("Packet captures / PacketEvents discovery require --packetevents")
    provider = args.channel_provider or ("packetevents" if args.packetevents else "native")
    assert hashlib.sha256(args.velocity_jar.read_bytes()).hexdigest() == VELOCITY_SHA256, "Expected Velocity 4.3.0 build 44"
    directory = Path(tempfile.mkdtemp(prefix="velocity-profiler-", dir="/tmp/opencode"))
    plugins = directory / "plugins"
    plugins.mkdir()
    shutil.copy2(args.plugin, plugins / "vnp.jar")
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
    data = plugins / "vnp"
    data.mkdir()
    relay = HostedRelay(directory, http_port, args.cloudflare)
    (data / "config.yml").write_text(relay.config() + f"traffic:\n  channel-provider: {provider}\n")
    log_path = directory / "smoke.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(["java", "-Xms128m", "-Xmx512m", "-jar", str(args.velocity_jar.resolve())],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        dashboard = None
        try:
            dashboard = RemoteDashboard(relay, process, log_path)
            root, get, post = dashboard.root, dashboard.get, dashboard.post
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
            capture = None
            expected_packets = {} if args.packet_capture else None
            if args.packet_capture:
                index = get("/api/v1/captures")
                assert index["available"], index["detail"]
                capture = post("/api/v1/captures", {"durationSeconds": 60})
            sent = received = 0
            for _ in range(12):
                incoming, outgoing = exchange(mc_port, args.proxy_protocol, expected_packets)
                sent += incoming
                received += outgoing
            time.sleep(1.3)
            after = get("/api/v1/traffic/current")
            actual_in = int(after["traffic"]["inbound"]["totalBytes"]) - int(before["traffic"]["inbound"]["totalBytes"])
            actual_out = int(after["traffic"]["outbound"]["totalBytes"]) - int(before["traffic"]["outbound"]["totalBytes"])
            assert (actual_in, actual_out) == (sent, received), f"Byte mismatch: {(actual_in, actual_out)} != {(sent, received)}"
            assert get("/api/v1/traffic/history")["samples"], "History missing"
            if capture:
                report = get("/api/v1/captures/" + capture["id"])
                actual_packets = {(r["protocolState"], r["direction"], r["name"]): [int(r["totalBytes"]), int(r["packetCount"])] for r in report["packets"]}
                assert actual_packets == expected_packets, f"Packet mismatch: {actual_packets} != {expected_packets}"
                assert report["state"] == "RUNNING" and report["observationErrors"] == "0", report
                assert int(report["packetCount"]) == 60 and report["cancelledPackets"] == "0", report
                assert report["schemaVersion"] == 3 and report["timeline"]["samples"], report
                assert after["players"] == 0, "Status sockets were incorrectly counted as players"
                # Real console command stops the capture started by the dashboard API.
                process.stdin.write("vnp stop\n")
                process.stdin.flush()
                stop_deadline = time.monotonic() + 5
                while True:
                    final = get("/api/v1/captures/" + capture["id"])
                    if final["state"] == "COMPLETED":
                        break
                    assert time.monotonic() < stop_deadline, f"Command failed to stop: {log_path.read_text()}"
                    time.sleep(0.05)
                assert final["packets"] == report["packets"], "Final report counters changed unexpectedly"
                assert sum(int(s["inboundBytes"]) + int(s["outboundBytes"]) for s in final["timeline"]["samples"]) == int(final["totalBytes"]), final
                assert json.loads((data / "reports" / (capture["id"] + ".json")).read_text()) == final
                exchange(mc_port, args.proxy_protocol)
                assert get("/api/v1/captures/" + capture["id"]) == final, "Completed report was modified by later traffic"
                # And the inverse: start by command, stop through HTTP.
                process.stdin.write("vnp start 30\n")
                process.stdin.flush()
                start_deadline = time.monotonic() + 5
                while True:
                    active = [r for r in get("/api/v1/captures")["reports"] if r["state"] == "RUNNING"]
                    if active:
                        break
                    assert time.monotonic() < start_deadline, f"Command failed to start: {log_path.read_text()}"
                    time.sleep(0.05)
                exchange(mc_port, args.proxy_protocol)
                second = post("/api/v1/captures/" + active[0]["id"] + "/stop")
                assert second["packetCount"] == "5", second
                assert second["id"] != final["id"] and second["state"] == "COMPLETED", second
                saved_settings = post("/api/v1/settings", {"inboundMbps": 100, "outboundMbps": 50})["bandwidth"]
            with urllib.request.urlopen(root, timeout=3) as response:
                assert b"Vegui Network Profiler" in response.read(), "Dashboard missing"
            print(json.dumps({"result": "PASS", "dashboard": root, "inboundBytes": actual_in,
                              "outboundBytes": actual_out, "provider": provider,
                              "packetCapture": {"packetCount": report["packetCount"], "totalBytes": report["totalBytes"], "types": len(report["packets"]), "reportId": report["id"]} if capture else None,
                              "proxyProtocol": args.proxy_protocol, "log": str(log_path)}, indent=2), flush=True)
            end = time.monotonic() + args.hold_seconds
            while time.monotonic() < end:
                exchange(mc_port, args.proxy_protocol)
                time.sleep(1)
        finally:
            shutdown(process, mc_port, log_path)
            if dashboard:
                assert dashboard.request('/session', proxy=False)['serverConnected'] is False
                assert dashboard.get('/api/v1/traffic/history')['samples'], 'Offline temporary history missing'
                dashboard.close()
    if args.packet_capture:
        with log_path.open("a") as log:
            process = subprocess.Popen(["java", "-Xms128m", "-Xmx512m", "-jar", str(args.velocity_jar.resolve())],
                                       cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
            try:
                dashboard = RemoteDashboard(relay, process, log_path)
                get, post = dashboard.get, dashboard.post
                deadline = time.monotonic() + 45
                while True:
                    try:
                        if get("/api/v1/health")["healthy"] and get("/api/v1/captures")["available"]:
                            break
                    except (OSError, ValueError):
                        pass
                    assert process.poll() is None and time.monotonic() < deadline, f"Restart failed: {log_path.read_text()}"
                    time.sleep(0.2)
                assert get("/api/v1/captures/" + final["id"]) == final, "Final report changed or disappeared after restart"
                assert get("/api/v1/captures/" + second["id"]) == second, "Second report missing after restart"
                assert not any(r["state"] == "RUNNING" for r in get("/api/v1/captures")["reports"]), "Capture resumed unexpectedly"
                assert get("/api/v1/settings")["bandwidth"] == saved_settings, "Shared settings changed after restart"
            finally:
                shutdown(process, mc_port, log_path)
                dashboard.close()
        print("Report persistence across a real Velocity restart verified.")
    relay.close()
    print("Outgoing relay, browser approval, offline history and clean shutdown verified. Full gameplay remains manual.")


if __name__ == "__main__":
    main()
