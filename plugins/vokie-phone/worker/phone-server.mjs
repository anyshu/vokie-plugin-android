import net from 'node:net';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { FrameDecoder, encodeFrame, parseJson, parsePhoneAudio } from './frame.mjs';
import * as crypto from './pairing.mjs';
import { PairingStore } from './store.mjs';
import { advertise } from './mdns.mjs';

// Same pending-connection cap as the native phone Wi-Fi service.
const MAX_PENDING_CLIENTS = 4;

export class PhoneServer {
  constructor({ onSession, onAudio, onStop, onState, onCommand, listen }) {
    this.callbacks = { onSession, onAudio, onStop, onState, onCommand };
    this.store = new PairingStore();
    this.clients = new Set();
    this.active = null;
    this.pairingClient = null;
    this.server = null;
    this.starting = null;
    this.mdnsStop = null;
    this.generation = 0;
    this.invite = '';
    this.extensions = {};
    this.listen = listen ?? {
      host: process.env.VOKIE_PHONE_LISTEN_HOST || '0.0.0.0',
      port: Number(process.env.VOKIE_PHONE_LISTEN_PORT || 0)
    };
  }

  // Idempotent start: a repeated `start` from the Host must not bind a second
  // TCP listener or publish a second mDNS advertisement. Failures reject so
  // the Worker can report an error state instead of acknowledging ready.
  async start() {
    if (this.starting) return this.starting;
    if (this.server) return;
    this.starting = this.startServer();
    try { await this.starting; } finally { this.starting = null; }
  }

  async startServer() {
    this.generation += 1;
    const generation = this.generation;
    const server = net.createServer((socket) => this.accept(socket));
    // Swallow late server errors (for example close() on a socket that never
    // listened) so a failed start cannot crash the Worker process.
    server.on('error', () => {});
    this.server = server;
    let port;
    try {
      port = await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(this.listen.port, this.listen.host, () => resolve(server.address().port));
      });
    } catch (error) {
      this.server = null;
      server.close();
      throw error;
    }
    if (generation !== this.generation) {
      // stop() ran while the listener was still binding; do not advertise.
      this.server = null;
      server.close();
      throw new Error('phone server stopped during start');
    }
    const ipv4 = lanIpv4Addresses();
    const name = pcDisplayName();
    this.invite = buildInvite(port, ipv4, this.store.instanceId, name);
    this.extensions = this.invite ? { pairingInvite: this.invite } : {};
    this.mdnsStop = advertise(port, this.store.instanceId, { name, ipv4 }, (message) => this.emitState('error', { message }));
    this.emitState('ready', { message: `listening on ${port}` });
  }

  // Exact message shape of the native phoneWifiServerService.notifyRecordingStopped;
  // the Android app's WifiPhoneTransport leaves its recording UI on this message.
  notifyRecordingStopped(sessionId) {
    const client = this.active;
    if (!client || client.phase !== 'authenticated') return;
    this.send(client, { v: 2, type: 'recording_stopped', sessionId, reason: 'pc_stopped' });
  }

  stop() {
    this.generation += 1;
    this.active = null;
    this.pairingClient = null;
    this.extensions = {};
    this.invite = '';
    for (const client of this.clients) client.socket.destroy();
    this.clients.clear();
    this.mdnsStop?.();
    this.mdnsStop = null;
    this.server?.close();
    this.server = null;
  }

  // The Host replaces the whole extensions object on every state event, so
  // every state emission carries the full current extension payload.
  emitState(value, core = {}) {
    this.callbacks.onState(value, { ...core, extensions: { ...this.extensions } });
  }

  accept(socket) {
    if (this.clients.size >= MAX_PENDING_CLIENTS) {
      socket.destroy();
      return;
    }
    const client = { socket, phase: 'hello', decoder: new FrameDecoder(), hello: null, ctx: null, secret: null, token: null, pairingId: null, fragments: new Map() };
    this.clients.add(client);
    socket.setKeepAlive(true, 15000);
    socket.setTimeout(30000, () => { if (client.phase !== 'authenticated') socket.destroy(); });
    socket.on('data', (chunk) => { try { for (const frame of client.decoder.push(chunk)) this.frame(client, frame); } catch (error) { socket.destroy(error); } });
    socket.on('close', () => this.close(client));
  }
  frame(client, frame) { if (client.phase === 'hello') return this.hello(client, frame); if (client.phase !== 'authenticated') return this.proof(client, frame); if (frame[0] === 0x7b) return this.control(client, parseJson(frame)); const audio = parsePhoneAudio(frame); if (audio) this.audio(client, audio); }
  hello(client, frame) { const message = parseJson(frame); if (!message || message.v !== 2 || message.type !== 'hello' || !message.deviceId || message.targetInstanceId !== this.store.instanceId) return this.reject(client, 'invalid_hello'); try { const keys = crypto.createKeys(); client.hello = message; client.pairingId = randomUUID(); client.secret = crypto.secret(keys.privateKey, crypto.publicKey(message.clientPublicKey)); client.ctx = crypto.context(this.store.instanceId, message.deviceId, client.pairingId, message.clientPublicKey, keys.publicKey); client.token = message.hasCredential ? this.store.getToken(message.deviceId) : null; client.serverPublicKey = keys.publicKey; client.serverKeys = keys; client.phase = client.token ? 'trusted-proof' : 'pairing-proof'; this.send(client, { v: 2, type: 'server_hello', mode: client.token ? 'trusted' : 'pairing', instanceId: this.store.instanceId, pcName: pcDisplayName(), pairingId: client.pairingId, serverPublicKey: keys.publicKey }); } catch { this.reject(client, 'key_agreement_failed'); } }
  proof(client, frame) {
    const message = parseJson(frame);
    if (!message || !client.hello) return this.reject(client, 'invalid_proof');
    if (client.phase === 'trusted-proof') { if (message.type !== 'auth_proof' || !client.token || !crypto.matches(crypto.authProof(client.token, client.ctx), message.proof)) return this.reject(client, 'credential_rejected'); this.store.touch(client.hello.deviceId); return this.authenticate(client, client.token, 'trusted'); }
    if (message.type !== 'pairing_ready' || !crypto.matches(crypto.readyProof(client.secret, client.ctx), message.proof)) return this.reject(client, 'pairing_proof_rejected');
    const code = crypto.pairingCode(client.secret, client.ctx);
    // The pairing wait is reported as the legal `ready` state (server up,
    // waiting for the phone); the pairing business data lives in extensions.
    this.pairingClient = client;
    this.extensions.pairing = { deviceName: client.hello.deviceName, pairingCode: code };
    this.emitState('ready', { message: 'waiting for pairing confirmation' });
    if (process.env.VOKIE_PHONE_REQUIRE_APPROVAL === '1' && process.env.VOKIE_PHONE_AUTO_APPROVE !== '1') return this.reject(client, 'pairing_approval_required');
    client.token = crypto.deviceToken(client.secret, client.pairingId, client.ctx);
    this.store.trust(client.hello.deviceId, client.hello.deviceName, client.hello.platform, client.token);
    this.authenticate(client, client.token, 'paired');
  }
  authenticate(client, token, mode) {
    client.phase = 'authenticated';
    if (this.active) this.active.socket.destroy();
    this.active = client;
    this.pairingClient = null;
    delete this.extensions.pairing;
    this.extensions.device = { platform: client.hello.platform };
    this.emitState('connected', { deviceId: client.hello.deviceId, deviceName: client.hello.deviceName });
    this.send(client, { v: 2, type: 'auth_ok', mode, instanceId: this.store.instanceId, pcName: pcDisplayName(), proof: crypto.authOkProof(token, client.ctx) });
  }
  control(client, message) { if (!message || typeof message.type !== 'string') return this.reject(client, 'invalid_control'); if (message.type === 'open_vokie') return this.send(client, { v: 2, type: 'open_vokie_ok' }); if (message.type === 'forget_device') { this.store.revoke(client.hello.deviceId); this.send(client, { v: 2, type: 'forget_device_ok' }); return client.socket.destroy(); } this.callbacks.onSession(message); }
  audio(client, audio) { if (client !== this.active) return; let group = client.fragments.get(audio.sequence); if (!group) client.fragments.set(audio.sequence, group = { count: audio.fragmentCount, parts: [] }); group.parts[audio.fragmentIndex] = audio.payload; if (group.parts.filter(Boolean).length === group.count) { client.fragments.delete(audio.sequence); this.callbacks.onAudio(audio.sessionId, audio.sequence, Buffer.concat(group.parts)); } }
  send(client, message) { client.socket.write(encodeFrame(Buffer.from(JSON.stringify(message)))); }
  reject(client, reason) { this.send(client, { v: 2, type: 'auth_error', reason }); client.socket.destroy(); }
  close(client) {
    this.clients.delete(client);
    if (client === this.active) {
      // The server keeps listening, so it is back to waiting for a phone.
      this.active = null;
      delete this.extensions.device;
      this.callbacks.onStop('disconnect');
      this.emitState('ready', { deviceName: '', message: 'phone disconnected, waiting for reconnection' });
    } else if (client === this.pairingClient) {
      this.pairingClient = null;
      if (this.extensions.pairing) {
        delete this.extensions.pairing;
        this.emitState('ready', { message: 'pairing cancelled' });
      }
    }
  }
}

function pcDisplayName() {
  const name = os.hostname().trim().replace(/\.local$/i, '');
  return name || 'Vokie PC';
}

function lanIpv4Addresses() {
  const addresses = Object.values(os.networkInterfaces()).flat()
    .filter((entry) => entry && entry.family === 'IPv4' && !entry.internal && isLanIpv4(entry.address))
    .map((entry) => entry.address);
  return [...new Set(addresses)];
}

function buildInvite(port, hosts, instanceId, name) {
  const host = hosts[0];
  if (!host) return '';
  const query = new URLSearchParams({ v: '1', instance_id: instanceId, host, hosts: hosts.join(','), port: String(port), name });
  return `vokie://pair?${query}`;
}

function isLanIpv4(address) {
  const octets = address.split('.').map(Number);
  if (octets.length !== 4 || octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false;
  const [first, second] = octets;
  return first === 10 || (first === 172 && second >= 16 && second <= 31) || (first === 192 && second === 168);
}
