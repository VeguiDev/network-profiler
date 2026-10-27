#!/usr/bin/env python3
"""Actual offline login/transfers through pinned Velocity, with two minimal protocol peers.

Not a full Minecraft backend/gameplay benchmark. No EULA acceptance or production server changes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid
import zipfile
from smoke_velocity import VELOCITY_SHA256, port, varint, read_frame, shutdown


def string(value):
    encoded = value.encode()
    return varint(len(encoded)) + encoded


def send(sock, payload):
    sock.sendall(varint(len(payload)) + payload)


class Backend:
    def __init__(self, name):
        self.name = name
        self.sock = socket.socket()
        self.sock.bind(('127.0.0.1', 0))
        self.sock.listen()
        self.port = self.sock.getsockname()[1]
        self.connections = []
        self.stop = threading.Event()
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while not self.stop.is_set():
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            self.connections.append(conn)
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        try:
            conn.settimeout(300)
            read_frame(conn)  # Handshake
            login, _ = read_frame(conn)
            name = login[2:].decode()  # Protocol 47 login start, short ASCII name.
            player_id = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))
            send(conn, b'\x02' + string(player_id) + string(name))
            # 1.8 JoinGame: entity ID, mode, dimension, difficulty, max players, level type, reduced debug.
            send(conn, b'\x01' + struct.pack('>i', 1) + b'\x00\x00\x01\x14' + string('default') + b'\x00')
            while not self.stop.is_set():
                read_frame(conn)
        except (OSError, RuntimeError):
            pass
        finally:
            conn.close()

    def chat(self, text):
        for conn in list(self.connections):
            try:
                send(conn, b'\x02' + string(json.dumps({'text': text})) + b'\x00')
            except OSError:
                pass

    def close(self):
        self.stop.set()
        self.sock.close()
        for conn in self.connections:
            conn.close()


class Client:
    def __init__(self, mc_port, name):
        self.sock = socket.create_connection(('127.0.0.1', mc_port), timeout=5)
        self.errors = []
        send(self.sock, b'\x00' + varint(47) + string('localhost') + struct.pack('>H', mc_port) + b'\x02')
        send(self.sock, b'\x00' + string(name))
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        try:
            self.sock.settimeout(60)
            while True:
                packet, _ = read_frame(self.sock)
                if packet[0] == 0:  # Play keepalive (login disconnect would also be caught in logs).
                    send(self.sock, packet)
        except (OSError, RuntimeError) as error:
            self.errors.append(str(error))

    def chat(self, text):
        send(self.sock, b'\x01' + string(text))

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--velocity-jar', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--packetevents', type=Path, required=True)
    parser.add_argument('--hold-seconds', type=int, default=0)
    args = parser.parse_args()
    assert hashlib.sha256(args.velocity_jar.read_bytes()).hexdigest() == VELOCITY_SHA256
    directory = Path(tempfile.mkdtemp(prefix='velocity-attribution-', dir='/tmp/opencode'))
    plugins = directory / 'plugins'
    plugins.mkdir()
    shutil.copy2(args.plugin, plugins / 'profiler.jar')
    shutil.copy2(args.packetevents, plugins / 'packetevents.jar')
    lobby, survival = Backend('lobby'), Backend('survival')
    mc_port, http_port = port(), port()
    with zipfile.ZipFile(args.velocity_jar) as jar:
        config = jar.read('default-velocity.toml').decode()
    config = re.sub(r'^bind = .*$', f'bind = "127.0.0.1:{mc_port}"', config, flags=re.M)
    config = config.replace('online-mode = true', 'online-mode = false')
    config = re.sub(r'^player-info-forwarding-mode = .*$', 'player-info-forwarding-mode = "none"', config, flags=re.M)
    config = re.sub(r'^compression-threshold = .*$', 'compression-threshold = -1', config, flags=re.M)
    config = re.sub(r'^login-ratelimit = .*$', 'login-ratelimit = 0', config, flags=re.M)
    config = re.sub(r'^lobby = .*$', f'lobby = "127.0.0.1:{lobby.port}"', config, flags=re.M)
    config = re.sub(r'^factions = .*$', f'survival = "127.0.0.1:{survival.port}"', config, flags=re.M)
    config = config.replace('"factions"', '"survival"')
    (directory / 'velocity.toml').write_text(config)
    data = plugins / 'networkprofiler'
    data.mkdir()
    # Shortened publication interval for tests only; production default remains 30s.
    (data / 'config.yml').write_text(f'http:\n  port: {http_port}\nstatistics:\n  interval-seconds: 1\n')
    root = f'http://127.0.0.1:{http_port}'

    def get(path):
        with urllib.request.urlopen(root + path, timeout=5) as response:
            return json.load(response)

    def post(path, body=None):
        token = get('/api/v1/captures')['csrfToken']
        req = urllib.request.Request(root + path, json.dumps(body or {}).encode(),
                                     {'Content-Type': 'application/json', 'Origin': root, 'X-MNP-CSRF': token}, method='POST')
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    def wait(action, message, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            assert process.poll() is None, f'Velocity exited; log: {log_path}'
            try:
                result = action()
                if result:
                    return result
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.1)
        raise AssertionError(message + '; log: ' + str(log_path))

    log_path = directory / 'smoke.log'
    clients = []
    with log_path.open('w') as log:
        process = subprocess.Popen(['java', '-Xms128m', '-Xmx512m', '-jar', str(args.velocity_jar.resolve())],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        try:
            wait(lambda: get('/api/v1/health')['healthy'] and get('/api/v1/captures')['available'], 'startup', 45)
            alice = Client(mc_port, 'Alice'); clients.append(alice)

            def player_row(name, server):
                return next((r for r in get('/api/v1/connections')['rows'] if r['playerName'] == name and r['serverId'] == server and r['active']), None)

            row = wait(lambda: player_row('Alice', 'lobby'), 'Alice did not associate with lobby')
            player, connection = row['playerId'], row['id']
            capture = post('/api/v1/captures', {'scope': {'type': 'PLAYER', 'id': player}})
            lobby.chat('lobby-before-transfer')
            def membership(report_id, player_id, server):
                return any(s['population'] and s['population']['players'].get(player_id) == server
                           for s in get('/api/v1/captures/' + report_id)['timeline']['samples'])
            wait(lambda: membership(capture['id'], player, 'lobby'), 'No sampled lobby player population')
            alice.chat('/server survival')
            wait(lambda: player_row('Alice', 'survival'), 'Alice did not transfer')
            survival.chat('survival-after-transfer')
            wait(lambda: membership(capture['id'], player, 'survival'), 'No sampled survival player population')
            final = post('/api/v1/captures/' + capture['id'] + '/stop')
            assert final['schemaVersion'] == 3 and final['timeline']['complete'], final
            assert sum(int(s['inboundBytes']) + int(s['outboundBytes']) for s in final['timeline']['samples']) == int(final['totalBytes']), final
            assert any(s['population'] and s['population']['global'] == 1 for s in final['timeline']['samples']), final
            servers = {a['context']['serverId'] for a in final['attributions']}
            assert {'lobby', 'survival'} <= servers, final
            assert all(a['context']['playerId'] == player and a['context']['connectionId'] == connection for a in final['attributions']), final
            assert final['scope'] == {'type': 'PLAYER', 'id': player} and final['attributionComplete'], final
            # Confirm a server-scoped capture excludes packets from another player's server.
            bob = Client(mc_port, 'Bob'); clients.append(bob)
            wait(lambda: player_row('Bob', 'lobby'), 'Bob did not associate')
            scoped = post('/api/v1/captures', {'scope': {'type': 'SERVER', 'id': 'survival'}})
            lobby.chat('excluded-lobby'); survival.chat('included-survival'); time.sleep(.3)
            wait(lambda: any(s['population'] and s['population']['global'] == 2 and s['population']['servers'].get('lobby') == 1
                             and s['population']['servers'].get('survival') == 1 for s in get('/api/v1/captures/' + scoped['id'])['timeline']['samples']), 'Incorrect scoped/global player counts')
            scoped = post('/api/v1/captures/' + scoped['id'] + '/stop')
            assert scoped['attributions'] and all(a['context']['serverId'] == 'survival' and a['context']['playerId'] == player for a in scoped['attributions']), scoped
            assert get('/api/v1/captures/' + final['id']) == final, 'Historic report mutated after transfer'
            # Physical-connection capture finalizes automatically on disconnect.
            physical = post('/api/v1/captures', {'scope': {'type': 'CONNECTION', 'id': connection}})
            alice.close()
            wait(lambda: get('/api/v1/captures/' + physical['id'])['state'] == 'COMPLETED', 'Connection capture did not stop')
            # A player capture follows UUID through a later reconnect, but gets a new connection ID.
            bobrow = player_row('Bob', 'lobby')
            follow = post('/api/v1/captures', {'scope': {'type': 'PLAYER', 'id': bobrow['playerId']}})
            lobby.chat('before-reconnect'); time.sleep(.2); bob.close()
            wait(lambda: not player_row('Bob', 'lobby'), 'Bob did not disconnect')
            bob2 = Client(mc_port, 'Bob'); clients.append(bob2)
            wait(lambda: (r := player_row('Bob', 'lobby')) and r['id'] != bobrow['id'], 'Bob did not reconnect')
            lobby.chat('after-reconnect'); time.sleep(.2)
            follow = post('/api/v1/captures/' + follow['id'] + '/stop')
            assert len({a['context']['connectionId'] for a in follow['attributions']}) == 2, follow
            assert all(a['context']['playerId'] == bobrow['playerId'] for a in follow['attributions']), follow
            page = get('/api/v1/servers')
            assert page['ready'] and page['complete'] and page['observationErrors'] == '0', page
            assert any(r['id'] == 'lobby' and int(r['totalBytes']) > 0 for r in page['rows'])
            assert any(r['id'] == 'survival' and int(r['totalBytes']) > 0 for r in page['rows'])
            settings = post('/api/v1/settings', {'inboundMbps': 100, 'outboundMbps': 50})
            assert settings['bandwidth'] == {'inboundMbps': 100, 'outboundMbps': 50}, settings
            assert json.loads((data / 'dashboard-settings.json').read_text()) == settings['bandwidth']
            wait(lambda: get('/api/v1/traffic/current')['players'] == 1, 'Overview player count includes disconnected players')
            print(json.dumps({'result': 'PASS', 'dashboard': root, 'reportId': final['id'], 'playerId': player,
                              'transfer': sorted(servers), 'reconnectConnectionCount': 2, 'log': str(log_path)}, indent=2), flush=True)
            deadline = time.monotonic() + args.hold_seconds
            while time.monotonic() < deadline:
                lobby.chat('browser-live-traffic'); bob2.chat('browser-client-traffic'); time.sleep(1)
        finally:
            for client in clients:
                client.close()
            shutdown(process, http_port, log_path)
            lobby.close(); survival.close()
    print('Clean shutdown verified. Full modern backend/gameplay testing remains separate.')


if __name__ == '__main__':
    main()
