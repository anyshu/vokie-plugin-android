import assert from 'node:assert/strict';
import { access, readFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const packageDir = new URL('../', import.meta.url);

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
  assert.equal(manifest.version, '0.1.1');
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
