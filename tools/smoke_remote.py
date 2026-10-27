"""Test-only hosted relay/approved browser setup shared by real Velocity smokes."""
import http.cookiejar
import atexit
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.error

REPO = Path(__file__).resolve().parent.parent


class HostedRelay:
    def __init__(self, directory, port, cloudflare=False):
        self.root = f'http://127.0.0.1:{port}'
        self.directory = directory
        self.log = (directory / 'relay.log').open('w')
        env = dict(os.environ, PUBLIC_ORIGIN=self.root, PORT=str(port), HOST='127.0.0.1', DATA_DIRECTORY=str(directory / 'relay-data'), WRANGLER_SEND_METRICS='false')
        backend = REPO / 'profiler-backend'
        command = ['node', 'node_modules/tsx/dist/cli.mjs', 'src/node.ts']
        if cloudflare:
            command = ['node', 'node_modules/wrangler/bin/wrangler.js', 'dev', '--port', str(port), '--ip', '127.0.0.1', '--var', 'PUBLIC_ORIGIN:' + self.root, '--persist-to', str(directory / 'cloudflare')]
        self.process = subprocess.Popen(command, cwd=backend, env=env, stdout=self.log, stderr=subprocess.STDOUT)
        atexit.register(self.close)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(self.root + '/', timeout=1) as response:
                    assert b'Vegui Network Profiler' in response.read()
                    return
            except OSError:
                if self.process.poll() is not None:
                    raise RuntimeError((directory / 'relay.log').read_text())
                time.sleep(.2)
        raise RuntimeError('Relay startup timeout: ' + str(directory / 'relay.log'))

    def config(self):
        return f'relay:\n  endpoint: "{self.root}"\n  public-web-url: "{self.root}"\n'

    def close(self):
        if self.log.closed:
            return
        self.process.terminate()
        try:
            self.process.wait(10)
        except subprocess.TimeoutExpired:
            self.process.kill(); self.process.wait()
        self.log.close()


class RemoteDashboard:
    def __init__(self, relay, process, log_path):
        self.relay = relay
        self.process = process
        self.log_path = log_path
        self.viewer = None
        deadline = time.monotonic() + 45
        while 'Done (' not in log_path.read_text():
            if process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('Proxy startup failed: ' + log_path.read_text())
            time.sleep(.1)
        offset = len(log_path.read_text())
        process.stdin.write('vnp dashboard\n'); process.stdin.flush()
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            links = re.findall(re.escape(relay.root) + r'/([a-f0-9-]{36}\.[a-f0-9]{48})', log_path.read_text()[offset:])
            if links:
                self.id = links[-1]; break
            if process.poll() is not None:
                raise RuntimeError(log_path.read_text())
            time.sleep(.2)
        else:
            raise RuntimeError('Dashboard command timeout: ' + log_path.read_text())
        self.root = relay.root + '/' + self.id
        self.base = relay.root + '/api/v1/dashboard/' + self.id
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.session = self.request('/viewer', {}, proxy=False)
        assert self.session['state'] == 'PENDING'
        try:
            self.get('/api/v1/status')
            raise AssertionError('Unapproved browser read metrics')
        except urllib.error.HTTPError as e:
            assert e.code == 403, e.code
        self.viewer = subprocess.Popen(['node', str(REPO / 'tools/smoke_viewer.mjs')], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        atexit.register(self.close)
        cookie = '; '.join(c.name + '=' + c.value for c in self.cookies)
        self.viewer.stdin.write(json.dumps({'url': self.base.replace('http', 'ws', 1) + '/events', 'cookie': cookie, 'origin': relay.root}) + '\n'); self.viewer.stdin.flush()
        assert self.viewer.stdout.readline().strip() == 'CONNECTED', 'Viewer WS failed'
        process.stdin.write('vnp trustSession ' + self.session['approvalCode'] + '\n'); process.stdin.flush()
        while time.monotonic() < deadline:
            self.session = self.request('/session', proxy=False)
            if self.session['state'] == 'APPROVED':
                return
            time.sleep(.1)
        raise AssertionError('Approval timeout')

    def request(self, path, body=None, proxy=True):
        url = self.base + ('/proxy/' + path.removeprefix('/api/v1/') if proxy else path)
        headers = {'Origin': self.relay.root}
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'X-VNP-CSRF': getattr(self, 'session', {}).get('csrfToken', '')})
        req = urllib.request.Request(url, None if body is None else json.dumps(body).encode(), headers, method='GET' if body is None else 'POST')
        with self.http.open(req, timeout=65) as response:
            return json.load(response)

    def get(self, path):
        return self.request(path)

    def post(self, path, body=None):
        return self.request(path, body or {})

    def close(self):
        if self.viewer:
            if not self.viewer.stdin.closed:
                self.viewer.stdin.close()
            try:
                self.viewer.wait(5)
            except subprocess.TimeoutExpired:
                self.viewer.kill(); self.viewer.wait()
