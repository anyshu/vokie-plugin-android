import assert from 'node:assert/strict';
import { access, chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { FrameDecoder, encodeFrame } from '../worker/frame.mjs';
import * as pairingCrypto from '../worker/pairing.mjs';
import { PhoneServer } from '../worker/phone-server.mjs';
import { advertise } from '../worker/mdns.mjs';

const packageDir = new URL('../', import.meta.url);
const workerEntry = fileURLToPath(new URL('../worker/index.mjs', import.meta.url));
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// A spawned stub may need longer than any fixed delay to write its output;
// poll for the file instead of racing a cold process spawn.
async function waitForFile(file, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      return await readFile(file, 'utf8');
    } catch (error) {
      if (error.code !== 'ENOENT' || Date.now() > deadline) throw error;
      await delay(25);
    }
  }
}

function createMessageHub() {
  const messages = [];
  const waiters = [];
  function push(message) {
    messages.push(message);
    for (let index = waiters.length - 1; index >= 0; index--) {
      if (waiters[index].predicate(message)) {
        const waiter = waiters.splice(index, 1)[0];
        clearTimeout(waiter.timer);
        waiter.resolve(message);
      }
    }
  }
  // `after` skips already-received messages so a restarted plugin can be
  // observed without matching its earlier lifecycle events.
  function waitFor(predicate, label, timeoutMs = 5000, after = 0) {
    const found = messages.slice(after).find(predicate);
    if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${label}`)), timeoutMs);
      waiters.push({ predicate, resolve, timer });
    });
  }
  return { messages, push, waitFor };
}

function encodeWsTextFrame(payload) {
  const length = payload.length;
  let header;
  if (length < 126) header = Buffer.from([0x81, length]);
  else if (length < 65536) {
    header = Buffer.alloc(3);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(9);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  return Buffer.concat([header, payload]);
}

/** Minimal RFC 6455 server that plays the Vokie Host for one Worker. */
class FakePluginHost {
  constructor() {
    this.http = createServer();
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.hub = createMessageHub();
    this.http.on('upgrade', (request, socket, head) => {
      const key = request.headers['sec-websocket-key'];
      const accept = createHash('sha1')
        .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
        .digest('base64');
      socket.write(
        `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`
      );
      socket.setNoDelay(true);
      this.socket = socket;
      this.buffer = Buffer.concat([this.buffer, head]);
      this.drain();
      socket.on('data', (chunk) => {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        this.drain();
      });
      socket.on('error', () => {});
    });
  }
  listen() {
    return new Promise((resolve) => this.http.listen(0, '127.0.0.1', resolve));
  }
  get port() {
    return this.http.address().port;
  }
  get messages() {
    return this.hub.messages;
  }
  sendJson(message) {
    this.socket.write(encodeWsTextFrame(Buffer.from(JSON.stringify(message))));
  }
  waitFor(predicate, label, timeoutMs = 5000, after = 0) {
    return this.hub.waitFor(predicate, label, timeoutMs, after);
  }
  drain() {
    for (;;) {
      const frame = this.readFrame();
      if (!frame) return;
      if (frame.opcode === 0x1) {
        try {
          this.hub.push(JSON.parse(frame.payload.toString('utf8')));
        } catch {
          // Malformed frames fail through the test's own waiters.
        }
      } else if (frame.opcode === 0x8) {
        // Echo the close handshake so the Worker's WebSocket fires its close event.
        const code = frame.payload.length >= 2 ? frame.payload.subarray(0, 2) : Buffer.from([0x03, 0xe8]);
        this.socket.write(Buffer.concat([Buffer.from([0x88, code.length]), code]));
        this.socket.end();
      }
    }
  }
  readFrame() {
    const buffer = this.buffer;
    if (buffer.length < 2) return null;
    const opcode = buffer[0] & 0x0f;
    const masked = (buffer[1] & 0x80) !== 0;
    let length = buffer[1] & 0x7f;
    let offset = 2;
    if (length === 126) {
      if (buffer.length < offset + 2) return null;
      length = buffer.readUInt16BE(offset);
      offset += 2;
    } else if (length === 127) {
      if (buffer.length < offset + 8) return null;
      length = Number(buffer.readBigUInt64BE(offset));
      offset += 8;
    }
    let mask = null;
    if (masked) {
      if (buffer.length < offset + 4) return null;
      mask = buffer.subarray(offset, offset + 4);
      offset += 4;
    }
    if (buffer.length < offset + length) return null;
    let payload = buffer.subarray(offset, offset + length);
    if (mask) {
      const unmasked = Buffer.allocUnsafe(length);
      for (let index = 0; index < length; index++) unmasked[index] = payload[index] ^ mask[index % 4];
      payload = unmasked;
    }
    this.buffer = buffer.subarray(offset + length);
    return { opcode, payload };
  }
}

/** Fake Android phone speaking the plugin's v2 TCP framing. */
class FakePhone {
  static async connect(port) {
    const socket = await new Promise((resolve, reject) => {
      const connection = net.connect(port, '127.0.0.1', () => resolve(connection));
      connection.on('error', reject);
    });
    return new FakePhone(socket);
  }
  constructor(socket) {
    this.socket = socket;
    this.hub = createMessageHub();
    this.decoder = new FrameDecoder();
    socket.on('data', (chunk) => {
      try {
        for (const frame of this.decoder.push(chunk)) this.receive(frame);
      } catch {
        // Protocol errors surface through the message waiters.
      }
    });
    socket.on('error', () => {});
  }
  receive(frame) {
    if (frame[0] !== 0x7b) return;
    try {
      this.hub.push(JSON.parse(frame.toString('utf8')));
    } catch {
      // Malformed frames fail through the test's own waiters.
    }
  }
  send(message) {
    this.socket.write(encodeFrame(Buffer.from(JSON.stringify(message))));
  }
  waitFor(predicate, label, timeoutMs = 5000) {
    return this.hub.waitFor(predicate, label, timeoutMs);
  }
}

function waitForExit(child, timeoutMs = 3000) {
  return new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) return resolve();
    const timer = setTimeout(() => resolve(), timeoutMs);
    child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

async function writeDnsSdStub(dir) {
  const stub = path.join(dir, 'dns-sd-stub.sh');
  await writeFile(
    stub,
    '#!/bin/sh\nif [ -n "$VOKIE_MDNS_STUB_OUT" ]; then printf \'%s\\n\' "$@" > "$VOKIE_MDNS_STUB_OUT"; fi\n'
  );
  await chmod(stub, 0o755);
  return stub;
}

async function withWorkerHarness(runOrOptions, maybeRun) {
  const options = typeof runOrOptions === 'function' ? {} : runOrOptions ?? {};
  const run = typeof runOrOptions === 'function' ? runOrOptions : maybeRun;
  const dir = await mkdtemp(path.join(os.tmpdir(), 'vokie-phone-plugin-test-'));
  const dnsSdStub = await writeDnsSdStub(dir);
  const host = new FakePluginHost();
  await host.listen();
  const worker = spawn(process.execPath, [workerEntry], {
    env: {
      ...process.env,
      VOKIE_PLUGIN_WS_URL: `ws://127.0.0.1:${host.port}`,
      VOKIE_PLUGIN_TOKEN: 'test-token',
      VOKIE_PHONE_DATA_DIR: path.join(dir, 'data'),
      VOKIE_PHONE_DNS_SD: dnsSdStub,
      VOKIE_PHONE_REQUIRE_APPROVAL: '',
      VOKIE_PHONE_LISTEN_HOST: '127.0.0.1',
      ...options.env
    },
    stdio: ['ignore', 'ignore', 'pipe']
  });
  let stderr = '';
  worker.stderr.on('data', (chunk) => {
    stderr += String(chunk);
  });
  try {
    await run({ host, worker, dir, stderr: () => stderr });
  } finally {
    worker.kill();
    await waitForExit(worker);
    host.socket?.destroy();
    await new Promise((resolve) => host.http.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
}

async function completeHostHandshake(host) {
  const hello = await host.waitFor((message) => message.type === 'plugin_hello', 'plugin_hello');
  host.sendJson({ type: 'handshake_ok', pluginId: hello.manifest.id, connectionId: 'conn-1' });
  host.sendJson({ type: 'initialize' });
  await host.waitFor((message) => message.type === 'initialized', 'initialized');
  return hello;
}

function loadSdk() {
  const listeners = new Set();
  const requests = [];
  const window = {
    setTimeout,
    clearTimeout,
    addEventListener(type, listener) {
      if (type === 'message') listeners.add(listener);
    },
    parent: {
      postMessage(message) {
        requests.push(message);
      }
    }
  };
  const context = vm.createContext({ window, setTimeout, clearTimeout });
  return {
    window,
    requests,
    respond(message) {
      for (const listener of listeners) listener({ data: message });
    },
    run: async () => {
      const source = await readFile(new URL('assets/vokie-plugin-sdk.js', packageDir), 'utf8');
      vm.runInContext(source, context);
    }
  };
}

test('UI SDK reads the Host top-level state envelope used by PluginDetailDialog', async () => {
  const harness = loadSdk();
  await harness.run();

  const statePromise = harness.window.vokiePlugin.getState();
  assert.equal(harness.requests.length, 1);
  assert.equal(harness.requests[0].action, 'get_state');
  harness.respond({
    type: 'vokie_plugin_response',
    requestId: harness.requests[0].requestId,
    state: {
      status: 'ready',
      transport: 'wifi',
      extensions: { pairingInvite: 'vokie://pair?v=1&host=192.168.1.2&port=4242' }
    }
  });
  assert.deepEqual(await statePromise, {
    type: 'vokie_plugin_response',
    requestId: harness.requests[0].requestId,
    state: {
      status: 'ready',
      transport: 'wifi',
      extensions: { pairingInvite: 'vokie://pair?v=1&host=192.168.1.2&port=4242' }
    }
  });
});

test('Plugin package exposes the required custom UI and Worker entrypoints', async () => {
  const manifest = JSON.parse(await readFile(new URL('vokie.plugin.json', packageDir), 'utf8'));
  assert.equal(manifest.version, '0.1.2');
  for (const relativePath of [manifest.ui.entrypoint, manifest.worker.entrypoint, manifest.icon]) {
    await readFile(new URL(relativePath, packageDir));
  }
  await access(new URL(manifest.worker.entrypoint, packageDir), constants.X_OK);
  const ui = await readFile(new URL(manifest.ui.entrypoint, packageDir), 'utf8');
  assert.match(ui, /src="\.\.\/assets\/icon\.png"/);
});

test('Plugin icon is the Android App launcher artwork', async () => {
  const pluginIcon = await readFile(new URL('assets/icon.png', packageDir));
  const appIcon = await readFile(new URL('../../../app/src/main/res/drawable-nodpi/vokie_logo.png', import.meta.url));
  assert.deepEqual(pluginIcon, appIcon);
});

test('Plugin UI does not render a broken image before the Worker publishes an invite', async () => {
  const html = await readFile(new URL('ui/index.html', packageDir), 'utf8');
  assert.match(html, /id="qr-placeholder"/);
  assert.match(html, /插件启动后显示二维码/);
  assert.match(html, /pairingInvite/);
});

test('Plugin UI shows the pairing verification code from extensions.pairing', async () => {
  const html = await readFile(new URL('ui/index.html', packageDir), 'utf8');
  assert.match(html, /extensions\?\.pairing/);
  assert.match(html, /配对验证码/);
});

test('Plugin UI polls state because Host does not push state changes into the iframe', async () => {
  const html = await readFile(new URL('ui/index.html', packageDir), 'utf8');
  assert.match(html, /setInterval\(refreshState, 1000\)/);
  assert.match(html, /response\?\.state \?\? response/);
});

test('Worker exits after the Host WebSocket closes', async () => {
  const source = await readFile(new URL('worker/index.mjs', packageDir), 'utf8');
  assert.match(source, /server\.stop\(\);[\s\S]*setImmediate\(\(\) => process\.exit\(0\)\)/);
});

test('Generated QR replaces the placeholder instead of stacking below it', async () => {
  const html = await readFile(new URL('ui/index.html', packageDir), 'utf8');
  assert.match(html, /#qr-placeholder\.hidden\s*\{\s*display:\s*none/);
  assert.match(html, /qrPlaceholder\.classList\.add\('hidden'\)/);
});

test('UI keeps the last pairing QR visible after the phone connects', async () => {
  const html = await readFile(new URL('ui/index.html', packageDir), 'utf8');
  assert.match(html, /let lastPairingInvite = ''/);
  assert.match(html, /\| lastPairingInvite/);
  assert.match(html, /value === 'connected' \? '手机已连接'/);
});

test('Phone invite excludes non-LAN virtual IPv4 addresses', async () => {
  const source = await readFile(new URL('worker/phone-server.mjs', packageDir), 'utf8');
  assert.match(source, /isLanIpv4\(entry\.address\)/);
  assert.match(source, /first === 10/);
  assert.match(source, /first === 192 && second === 168/);
  assert.doesNotMatch(source, /198\.18/);
});

test('First pairing is usable without an unavailable Host approval callback', async () => {
  const source = await readFile(new URL('worker/phone-server.mjs', packageDir), 'utf8');
  assert.match(source, /VOKIE_PHONE_REQUIRE_APPROVAL === '1'/);
  assert.match(source, /deviceToken\(client\.secret/);
});

test(
  'Worker handshake echoes vokie.plugin.json and acknowledges configuration_changed',
  { skip: process.platform === 'win32' },
  async () => {
    await withWorkerHarness(async ({ host, worker }) => {
      const hello = await completeHostHandshake(host);
      const manifestFromFile = JSON.parse(
        await readFile(new URL('vokie.plugin.json', packageDir), 'utf8')
      );
      assert.equal(hello.token, 'test-token');
      // The Host deep-compares the echo manifest against the installed
      // vokie.plugin.json; every compared field must match exactly.
      for (const field of [
        'id',
        'name',
        'version',
        'apiVersion',
        'platforms',
        'transports',
        'capabilities',
        'permissions'
      ]) {
        assert.deepEqual(
          hello.manifest[field],
          manifestFromFile[field],
          `echo manifest field ${field}`
        );
      }
      assert.equal(hello.manifest.icon, manifestFromFile.icon, 'echo manifest icon');
      assert.equal(
        hello.manifest.ui?.entrypoint,
        manifestFromFile.ui?.entrypoint,
        'echo manifest ui.entrypoint'
      );
      assert.equal(hello.manifest.version, '0.1.2');

      // The Host sends configuration_changed before start on every
      // registration; a first install carries the empty object.
      host.sendJson({ type: 'configuration_changed', requestId: 'cfg-empty', config: {} });
      await host.waitFor(
        (message) => message.type === 'configured' && message.requestId === 'cfg-empty',
        'configured for empty config'
      );

      // Any config object is confirmed; the requestId is echoed verbatim.
      host.sendJson({
        type: 'configuration_changed',
        requestId: 'cfg-opaque id ',
        config: { unrelated: true }
      });
      await host.waitFor(
        (message) => message.type === 'configured' && message.requestId === 'cfg-opaque id ',
        'configured echoing opaque requestId'
      );

      // Legacy configure without requestId is acknowledged without one.
      host.sendJson({ type: 'configure', config: {} });
      await host.waitFor(
        (message) => message.type === 'configured' && message.requestId === undefined,
        'legacy configured without requestId'
      );

      host.sendJson({ type: 'start' });
      await host.waitFor((message) => message.type === 'ready', 'ready acknowledgement');
      const readyState = await host.waitFor(
        (message) => message.type === 'state' && message.state === 'ready',
        'ready state event'
      );
      assert.equal(typeof readyState.extensions, 'object');
      const invite = readyState.extensions?.pairingInvite;
      if (invite) assert.match(invite, /^vokie:\/\/pair\?/);

      // Repeated start is idempotent: acknowledged again without binding a
      // second listener or creating a session.
      const baseline = host.messages.length;
      host.sendJson({ type: 'start' });
      await host.waitFor((message) => message.type === 'ready', 'second ready', 5000, baseline);
      assert.ok(!host.messages.some((message) => message.type === 'session_start'));

      host.sendJson({ type: 'stop', reason: 'user' });
      await host.waitFor((message) => message.type === 'stopped', 'stopped acknowledgement');
      // Restart after stop binds a fresh listener.
      const restartBaseline = host.messages.length;
      host.sendJson({ type: 'start' });
      await host.waitFor((message) => message.type === 'ready', 'ready after restart', 5000, restartBaseline);

      host.sendJson({ type: 'shutdown' });
      await host.waitFor((message) => message.type === 'destroyed', 'destroyed acknowledgement');
      await waitForExit(worker);
      assert.equal(worker.exitCode, 0);
    });
  }
);

test(
  'A failed start reports an error state and never acknowledges ready',
  { skip: process.platform === 'win32' },
  async () => {
    const blocker = net.createServer();
    await new Promise((resolve) => blocker.listen(0, '0.0.0.0', resolve));
    const takenPort = blocker.address().port;
    try {
      await withWorkerHarness(
        { env: { VOKIE_PHONE_LISTEN_HOST: '0.0.0.0', VOKIE_PHONE_LISTEN_PORT: String(takenPort) } },
        async ({ host }) => {
          await completeHostHandshake(host);
          host.sendJson({ type: 'configuration_changed', requestId: 'cfg-1', config: {} });
          await host.waitFor(
            (message) => message.type === 'configured' && message.requestId === 'cfg-1',
            'configured'
          );
          host.sendJson({ type: 'start' });
          const errorState = await host.waitFor(
            (message) => message.type === 'state' && message.state === 'error',
            'error state after failed start'
          );
          assert.match(errorState.message, /phone server start failed/);
          assert.ok(
            !host.messages.some((message) => message.type === 'ready'),
            'a failed start must not acknowledge ready'
          );
        }
      );
    } finally {
      blocker.close();
    }
  }
);

test(
  'Phone pairing, rejection, and host-side session ends notify the phone',
  { skip: process.platform === 'win32' },
  async () => {
    await withWorkerHarness(async ({ host, dir }) => {
      await completeHostHandshake(host);
      host.sendJson({ type: 'configuration_changed', requestId: 'cfg-1', config: {} });
      await host.waitFor(
        (message) => message.type === 'configured' && message.requestId === 'cfg-1',
        'configured'
      );
      host.sendJson({ type: 'start' });
      await host.waitFor((message) => message.type === 'ready', 'ready');

      const readyState = await host.waitFor(
        (message) =>
          message.type === 'state' &&
          message.state === 'ready' &&
          /listening on \d+/.test(message.message ?? ''),
        'listening state'
      );
      const phonePort = Number(/listening on (\d+)/.exec(readyState.message)[1]);
      const store = JSON.parse(
        await readFile(path.join(dir, 'data', 'phone-pairings.json'), 'utf8')
      );
      const instanceId = store.instanceId;

      // --- First pairing over the real TCP protocol ---
      const phone = await FakePhone.connect(phonePort);
      const keys = pairingCrypto.createKeys();
      const deviceId = 'unit-test-phone';
      phone.send({
        v: 2,
        type: 'hello',
        deviceId,
        deviceName: 'Unit Test Phone',
        platform: 'android',
        hasCredential: false,
        targetInstanceId: instanceId,
        clientPublicKey: keys.publicKey
      });
      const serverHello = await phone.waitFor(
        (message) => message.type === 'server_hello',
        'server_hello'
      );
      assert.equal(serverHello.mode, 'pairing');
      assert.equal(serverHello.instanceId, instanceId);
      const secret = pairingCrypto.secret(
        keys.privateKey,
        pairingCrypto.publicKey(serverHello.serverPublicKey)
      );
      const ctx = pairingCrypto.context(
        instanceId,
        deviceId,
        serverHello.pairingId,
        keys.publicKey,
        serverHello.serverPublicKey
      );

      // The phone confirms pairing readiness; the pairing wait uses the legal
      // `ready` state and the pairing business data travels in
      // extensions.pairing so the PC UI can show the verification code.
      phone.send({ v: 2, type: 'pairing_ready', proof: pairingCrypto.readyProof(secret, ctx) });
      const pairingState = await host.waitFor(
        (message) => message.type === 'state' && message.state === 'ready' && message.extensions?.pairing,
        'pairing state'
      );
      assert.equal(pairingState.extensions.pairing.deviceName, 'Unit Test Phone');
      assert.match(pairingState.extensions.pairing.pairingCode, /^\d{6}$/);
      assert.equal(
        pairingState.extensions.pairing.pairingCode,
        pairingCrypto.pairingCode(secret, ctx)
      );

      const authOk = await phone.waitFor((message) => message.type === 'auth_ok', 'auth_ok');
      assert.equal(authOk.mode, 'paired');
      const token = pairingCrypto.deviceToken(secret, serverHello.pairingId, ctx);
      assert.ok(pairingCrypto.matches(pairingCrypto.authOkProof(token, ctx), authOk.proof));

      const connectedState = await host.waitFor(
        (message) => message.type === 'state' && message.state === 'connected',
        'connected state'
      );
      assert.equal(connectedState.deviceId, deviceId);
      assert.equal(connectedState.deviceName, 'Unit Test Phone');
      assert.ok(!connectedState.extensions?.pairing, 'pairing data cleared after connecting');

      // --- Session rejected as busy: the phone must be told to stop ---
      phone.send({ v: 1, type: 'ptt_down', sessionId: 4242, seq: 7, recordingMode: 'ptt' });
      const startOne = await host.waitFor(
        (message) => message.type === 'session_start',
        'session_start'
      );
      assert.equal(startOne.requestId, 'phone-4242-7');
      assert.equal(startOne.mode, 'ptt');
      assert.deepEqual(startOne.options.audioSource, {
        type: 'stream',
        format: 'pcm_s16le',
        sampleRate: 16000,
        channels: 1
      });
      host.sendJson({ type: 'session_rejected', requestId: startOne.requestId, reason: 'busy' });
      const stoppedOne = await phone.waitFor(
        (message) => message.type === 'recording_stopped' && message.sessionId === 4242,
        'recording_stopped after rejection'
      );
      assert.equal(stoppedOne.v, 2);
      assert.equal(stoppedOne.reason, 'pc_stopped');

      // --- Host aborts an accepted session: same notification ---
      phone.send({ v: 1, type: 'ptt_down', sessionId: 4243, seq: 8, recordingMode: 'long' });
      const startTwo = await host.waitFor(
        (message) => message.type === 'session_start' && message.mode === 'recording',
        'recording session_start'
      );
      host.sendJson({
        type: 'session_accepted',
        requestId: startTwo.requestId,
        sessionId: 'host-session-1',
        mode: 'recording'
      });
      await host.waitFor(
        (message) => message.type === 'state' && message.state === 'recording',
        'recording state'
      );
      host.sendJson({
        type: 'session_state',
        requestId: startTwo.requestId,
        state: 'error',
        message: 'host aborted'
      });
      await phone.waitFor(
        (message) => message.type === 'recording_stopped' && message.sessionId === 4243,
        'recording_stopped after host abort'
      );
      await host.waitFor(
        (message) => message.type === 'state' && message.state === 'connected',
        'connected state after abort'
      );

      // --- Plugin stop while a session is active ---
      phone.send({ v: 1, type: 'ptt_down', sessionId: 4244, seq: 9, recordingMode: 'ptt' });
      const startThree = await host.waitFor(
        (message) => message.type === 'session_start' && message.requestId === 'phone-4244-9',
        'third session_start'
      );
      host.sendJson({
        type: 'session_accepted',
        requestId: startThree.requestId,
        sessionId: 'host-session-2',
        mode: 'ptt'
      });
      host.sendJson({ type: 'stop', reason: 'user' });
      await host.waitFor((message) => message.type === 'stopped', 'stopped acknowledgement');
      await host.waitFor(
        (message) => message.type === 'session_stop' && message.requestId === 'phone-4244-9',
        'session_stop on plugin stop'
      );
      await phone.waitFor(
        (message) => message.type === 'recording_stopped' && message.sessionId === 4244,
        'recording_stopped on plugin stop'
      );

      // --- Restart and reconnect as a trusted device ---
      const baseline = host.messages.length;
      host.sendJson({ type: 'start' });
      await host.waitFor((message) => message.type === 'ready', 'ready after restart', 5000, baseline);
      const restartState = await host.waitFor(
        (message) =>
          message.type === 'state' &&
          message.state === 'ready' &&
          /listening on \d+/.test(message.message ?? ''),
        'restart listening state',
        5000,
        baseline
      );
      const portTwo = Number(/listening on (\d+)/.exec(restartState.message)[1]);
      const phoneTwo = await FakePhone.connect(portTwo);
      const keysTwo = pairingCrypto.createKeys();
      phoneTwo.send({
        v: 2,
        type: 'hello',
        deviceId,
        deviceName: 'Unit Test Phone',
        platform: 'android',
        hasCredential: true,
        targetInstanceId: instanceId,
        clientPublicKey: keysTwo.publicKey
      });
      const serverHelloTwo = await phoneTwo.waitFor(
        (message) => message.type === 'server_hello',
        'trusted server_hello'
      );
      assert.equal(serverHelloTwo.mode, 'trusted');
      const secretTwo = pairingCrypto.secret(
        keysTwo.privateKey,
        pairingCrypto.publicKey(serverHelloTwo.serverPublicKey)
      );
      const ctxTwo = pairingCrypto.context(
        instanceId,
        deviceId,
        serverHelloTwo.pairingId,
        keysTwo.publicKey,
        serverHelloTwo.serverPublicKey
      );
      phoneTwo.send({ v: 2, type: 'auth_proof', proof: pairingCrypto.authProof(token, ctxTwo) });
      const authOkTwo = await phoneTwo.waitFor(
        (message) => message.type === 'auth_ok',
        'trusted auth_ok'
      );
      assert.equal(authOkTwo.mode, 'trusted');

      host.sendJson({ type: 'shutdown' });
      await host.waitFor((message) => message.type === 'destroyed', 'destroyed');
    });
  }
);

test(
  'mDNS advertisement TXT keys match the Android and Harmony parsers',
  { skip: process.platform === 'win32' },
  async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'vokie-phone-mdns-test-'));
    const stub = await writeDnsSdStub(dir);
    const previousDnsSd = process.env.VOKIE_PHONE_DNS_SD;
    const previousOut = process.env.VOKIE_MDNS_STUB_OUT;
    process.env.VOKIE_PHONE_DNS_SD = stub;
    const outOne = path.join(dir, 'args-one.txt');
    process.env.VOKIE_MDNS_STUB_OUT = outOne;
    try {
      const stop = advertise(
        54321,
        'instance-abc',
        { name: 'Unit PC', ipv4: ['192.168.1.5', '10.0.0.2'] },
        () => {}
      );
      const linesOne = (await waitForFile(outOne))
        .split('\n')
        .filter((line) => line.length > 0);
      stop();
      assert.deepEqual(linesOne, [
        '-R',
        'Vokie Unit PC',
        '_vokie-phone._tcp',
        'local',
        '54321',
        'v=2',
        'auth=sas-p256-v2',
        'instance=instance-abc',
        'name=Unit PC',
        'ipv4=192.168.1.5,10.0.0.2'
      ]);

      // Without LAN addresses the ipv4 key is omitted instead of published empty.
      const outTwo = path.join(dir, 'args-two.txt');
      process.env.VOKIE_MDNS_STUB_OUT = outTwo;
      const stopTwo = advertise(54322, 'instance-abc', { name: 'Unit PC', ipv4: [] }, () => {});
      const linesTwo = (await waitForFile(outTwo))
        .split('\n')
        .filter((line) => line.length > 0);
      stopTwo();
      assert.deepEqual(linesTwo, [
        '-R',
        'Vokie Unit PC',
        '_vokie-phone._tcp',
        'local',
        '54322',
        'v=2',
        'auth=sas-p256-v2',
        'instance=instance-abc',
        'name=Unit PC'
      ]);
    } finally {
      if (previousDnsSd === undefined) delete process.env.VOKIE_PHONE_DNS_SD;
      else process.env.VOKIE_PHONE_DNS_SD = previousDnsSd;
      if (previousOut === undefined) delete process.env.VOKIE_MDNS_STUB_OUT;
      else process.env.VOKIE_MDNS_STUB_OUT = previousOut;
      await rm(dir, { recursive: true, force: true });
    }
  }
);

test('PhoneServer caps pending unauthenticated connections at four', async () => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'vokie-phone-server-test-'));
  const previousDataDir = process.env.VOKIE_PHONE_DATA_DIR;
  const previousDnsSd = process.env.VOKIE_PHONE_DNS_SD;
  process.env.VOKIE_PHONE_DATA_DIR = dataDir;
  process.env.VOKIE_PHONE_DNS_SD = path.join(dataDir, 'missing-dns-sd');
  try {
    const server = new PhoneServer({
      onSession: () => {},
      onAudio: () => {},
      onStop: () => {},
      onState: () => {},
      onCommand: () => {},
      listen: { host: '127.0.0.1', port: 0 }
    });
    await server.start();
    const port = server.server.address().port;
    const sockets = [];
    for (let index = 0; index < 4; index++) {
      sockets.push(
        await new Promise((resolve, reject) => {
          const socket = net.connect(port, '127.0.0.1', () => resolve(socket));
          socket.on('error', reject);
        })
      );
    }
    await delay(200); // Let the server register all pending clients.
    const fifth = net.connect(port, '127.0.0.1');
    await new Promise((resolve) => {
      fifth.on('close', resolve);
      setTimeout(resolve, 1500);
    });
    assert.ok(fifth.destroyed, 'the fifth unauthenticated connection is dropped');
    for (const socket of sockets) {
      assert.ok(!socket.destroyed, 'the four pending connections stay connected');
    }
    server.stop();
    for (const socket of sockets) socket.destroy();
    fifth.destroy();
  } finally {
    if (previousDataDir === undefined) delete process.env.VOKIE_PHONE_DATA_DIR;
    else process.env.VOKIE_PHONE_DATA_DIR = previousDataDir;
    if (previousDnsSd === undefined) delete process.env.VOKIE_PHONE_DNS_SD;
    else process.env.VOKIE_PHONE_DNS_SD = previousDnsSd;
    await rm(dataDir, { recursive: true, force: true });
  }
});

test('A PhoneServer start failure rejects and stays retryable', async () => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'vokie-phone-server-test-'));
  const previousDataDir = process.env.VOKIE_PHONE_DATA_DIR;
  const previousDnsSd = process.env.VOKIE_PHONE_DNS_SD;
  process.env.VOKIE_PHONE_DATA_DIR = dataDir;
  process.env.VOKIE_PHONE_DNS_SD = path.join(dataDir, 'missing-dns-sd');
  try {
    const states = [];
    const server = new PhoneServer({
      onSession: () => {},
      onAudio: () => {},
      onStop: () => {},
      onState: (value, details) => states.push([value, details]),
      onCommand: () => {},
      listen: { host: 'not-a-real-listen-host.invalid', port: 0 }
    });
    await assert.rejects(server.start());
    assert.equal(server.server, null);
    assert.ok(!states.some(([value]) => value === 'ready'), 'a failed start must not report ready');
    // The failure stays retryable for the next Host start.
    await assert.rejects(server.start());
    assert.equal(server.server, null);
  } finally {
    if (previousDataDir === undefined) delete process.env.VOKIE_PHONE_DATA_DIR;
    else process.env.VOKIE_PHONE_DATA_DIR = previousDataDir;
    if (previousDnsSd === undefined) delete process.env.VOKIE_PHONE_DNS_SD;
    else process.env.VOKIE_PHONE_DNS_SD = previousDnsSd;
    await rm(dataDir, { recursive: true, force: true });
  }
});




