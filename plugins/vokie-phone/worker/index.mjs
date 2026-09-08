#!/usr/bin/env node
import { PhoneServer } from './phone-server.mjs';
import { encodePluginAudio } from './frame.mjs';
import { randomUUID } from 'node:crypto';

// Echo of vokie.plugin.json. The Host deep-compares id/name/version/apiVersion/
// platforms/transports/capabilities/permissions (plus normalized icon and
// ui.entrypoint) during the plugin_hello handshake and rejects any mismatch
// with close code 1008, so these fields must stay identical to the manifest.
const manifest = {
  id: 'd7d3a0dd-2d5b-4b8f-bc4a-4b9b2f1cbb2f',
  name: 'Vokie Phone Wi-Fi',
  device: { type: 'Vokie Phone', model: 'Android companion app' },
  version: '0.1.2',
  apiVersion: '1',
  platforms: ['darwin', 'win32', 'linux'],
  transports: ['wifi'],
  capabilities: { ptt: true, handsfree: true, longRecording: true, sendEnter: true, undoLastOutput: true },
  permissions: ['network-lan'],
  icon: 'assets/icon.png',
  ui: { entrypoint: 'ui/index.html' },
  worker: { entrypoint: 'worker/index.mjs', args: [] }
};

const wsUrl = process.env.VOKIE_PLUGIN_WS_URL;
const token = process.env.VOKIE_PLUGIN_TOKEN;
if (!wsUrl || !token) throw new Error('VOKIE_PLUGIN_WS_URL and VOKIE_PLUGIN_TOKEN are required');

let socket;
let started = false;
let phoneRunning = false;
let startPromise = null;
let current = null;
let sequence = 0;

function send(message) { if (socket?.readyState === 1) socket.send(JSON.stringify(message)); }
function state(value, details = {}) { send({ type: 'state', state: value, transport: 'wifi', ...details }); }
function modeFor(value) { return value === 'handsfree' ? 'handsfree-ptt' : value === 'long' ? 'recording' : 'ptt'; }

// The Host correlates configuration acknowledgements by the exact requestId
// value. Trim only to decide whether an ID is present; never rewrite a valid
// opaque ID, and omit the field entirely for legacy `configure` commands.
function optionalRequestId(message) {
  if (typeof message?.requestId !== 'string') return null;
  return message.requestId.trim() ? message.requestId : null;
}

function startPhoneSession(message) {
  if (!started || current || !message || message.type !== 'ptt_down') return;
  const requestId = `phone-${message.sessionId}-${message.seq}`;
  current = { requestId, phoneSessionId: message.sessionId, accepted: false };
  sequence = 0;
  send({ type: 'session_start', requestId, mode: modeFor(message.recordingMode), timestampMs: Date.now(), options: { audioSource: { type: 'stream', format: 'pcm_s16le', sampleRate: 16000, channels: 1 } } });
}
function stopPhoneSession(message, reason = 'device') {
  if (!current || (message?.sessionId !== undefined && message.sessionId !== current.phoneSessionId)) return;
  send({ type: 'session_stop', requestId: current.requestId, timestampMs: Date.now(), reason });
  current = null;
  sequence = 0;
}
// Tell the connected phone to leave its recording UI. Mirrors the exact
// recording_stopped control message of the native phone Wi-Fi service, which
// the Android app's WifiPhoneTransport already handles.
function notifyPhoneRecordingStopped() {
  if (current) server.notifyRecordingStopped(current.phoneSessionId);
}

const server = new PhoneServer({
  onSession: (message) => {
    if (message?.type === 'ptt_down') startPhoneSession(message);
    else if (message?.type === 'ptt_up') stopPhoneSession(message);
    else if (message?.type === 'send_enter') send({ type: 'command', command: 'send_enter', requestId: randomUUID(), timestampMs: Date.now() });
    else if (message?.type === 'undo_last_output') send({ type: 'command', command: 'undo_last_output', requestId: randomUUID(), timestampMs: Date.now() });
  },
  onAudio: (phoneSessionId, _phoneSequence, pcm) => {
    if (!current || !current.accepted || phoneSessionId !== current.phoneSessionId) return;
    socket?.send(encodePluginAudio(current.requestId, sequence++, pcm));
  },
  onStop: () => stopPhoneSession(null, 'disconnect'),
  onState: (value, details = {}) => {
    // Plugin-owned pairing data travels in extensions; the Host replaces the
    // whole extensions object on every state event.
    const { extensions, ...core } = details;
    state(value, { ...core, ...(extensions ? { extensions } : {}) });
  },
  onCommand: () => {}
});

function handleHostMessage(message) {
  if (message.type === 'initialize') return send({ type: 'initialized' });
  if (message.type === 'start') {
    started = true;
    if (startPromise) return; // Start already in flight; it will acknowledge.
    if (phoneRunning) return send({ type: 'ready' }); // Idempotent: keep the single listener and advertisement.
    startPromise = server.start()
      .then(() => { phoneRunning = true; })
      .finally(() => { startPromise = null; })
      .then(() => { if (started) send({ type: 'ready' }); })
      .catch((error) => {
        // A failed start must surface as an error state and must never
        // acknowledge ready, otherwise the Host treats a dead server as live.
        if (started) state('error', { message: `phone server start failed: ${error.message}` });
      });
    return;
  }
  if (message.type === 'stop') {
    started = false;
    phoneRunning = false;
    notifyPhoneRecordingStopped();
    stopPhoneSession(null, 'disconnect');
    server.stop();
    return send({ type: 'stopped' });
  }
  if (message.type === 'shutdown') {
    started = false;
    phoneRunning = false;
    notifyPhoneRecordingStopped();
    stopPhoneSession(null, 'disconnect');
    server.stop();
    send({ type: 'destroyed' });
    return socket.close();
  }
  if (message.type === 'configure' || message.type === 'configuration_changed') {
    // The Host sends configuration_changed after every registration and waits
    // up to five seconds for the acknowledgement. This plugin exposes no
    // user-configurable options, so any config object (including the empty
    // object of a first install) is confirmed as-is.
    const requestId = optionalRequestId(message);
    return send({ type: 'configured', ...(requestId ? { requestId } : {}) });
  }
  if (message.type === 'session_accepted' && current?.requestId === message.requestId) { current.accepted = true; state('recording', { audioSource: 'stream' }); return; }
  if (message.type === 'session_rejected' && current?.requestId === message.requestId) {
    // The Host refused the session (e.g. busy). The phone is still showing its
    // recording UI, so it must be told to stop recording.
    server.notifyRecordingStopped(current.phoneSessionId);
    current = null;
    sequence = 0;
    return;
  }
  if (message.type === 'session_state' && current?.requestId === message.requestId && (message.state === 'success' || message.state === 'error')) {
    // The Host finished or aborted the session on its own; same as above.
    server.notifyRecordingStopped(current.phoneSessionId);
    current = null;
    sequence = 0;
    state('connected');
    return;
  }
}

socket = new WebSocket(wsUrl);
socket.addEventListener('open', () => send({ type: 'plugin_hello', token, manifest }));
socket.addEventListener('message', (event) => { if (typeof event.data !== 'string') return; try { handleHostMessage(JSON.parse(event.data)); } catch (error) { state('error', { message: error.message }); } });
socket.addEventListener('close', () => {
  server.stop();
  // A disconnected Worker must not remain alive with a stale TCP listener.
  setImmediate(() => process.exit(0));
});
socket.addEventListener('error', (error) => console.error('[VokiePhone] Plugin WebSocket', error));
