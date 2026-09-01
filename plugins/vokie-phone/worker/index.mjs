#!/usr/bin/env node
import { PhoneServer } from './phone-server.mjs';
import { encodePluginAudio } from './frame.mjs';
import { randomUUID } from 'node:crypto';

const manifest = {
  id: 'd7d3a0dd-2d5b-4b8f-bc4a-4b9b2f1cbb2f', name: 'Vokie Phone Wi-Fi', version: '0.1.1', apiVersion: '1',
  platforms: ['darwin', 'win32', 'linux'], transports: ['wifi'],
  capabilities: { ptt: true, handsfree: true, longRecording: true, sendEnter: true, undoLastOutput: true },
  permissions: ['network-lan'], icon: 'assets/icon.svg', ui: { entrypoint: 'ui/index.html' }, worker: { entrypoint: 'worker/index.mjs', args: [] }
};

const wsUrl = process.env.VOKIE_PLUGIN_WS_URL;
const token = process.env.VOKIE_PLUGIN_TOKEN;
if (!wsUrl || !token) throw new Error('VOKIE_PLUGIN_WS_URL and VOKIE_PLUGIN_TOKEN are required');

let socket;
let started = false;
let current = null;
let sequence = 0;

function send(message) { if (socket?.readyState === 1) socket.send(JSON.stringify(message)); }
function state(value, details = {}) { send({ type: 'state', state: value, transport: 'wifi', ...details }); }
function modeFor(value) { return value === 'handsfree' ? 'handsfree-ptt' : value === 'long' ? 'recording' : 'ptt'; }
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
    const { pairingInvite, ...core } = details;
    state(value, { ...core, ...(pairingInvite ? { extensions: { pairingInvite } } : {}) });
  },
  onCommand: () => {}
});

function handleHostMessage(message) {
  if (message.type === 'initialize') return send({ type: 'initialized' });
  if (message.type === 'start') { started = true; return server.start().then(() => send({ type: 'ready' })).catch((error) => { state('error', { message: error.message }); send({ type: 'ready' }); }); }
  if (message.type === 'stop') { started = false; stopPhoneSession(null, 'disconnect'); server.stop(); return send({ type: 'stopped' }); }
  if (message.type === 'shutdown') { started = false; stopPhoneSession(null, 'disconnect'); server.stop(); send({ type: 'destroyed' }); return socket.close(); }
  if (message.type === 'session_accepted' && current?.requestId === message.requestId) { current.accepted = true; state('recording', { audioSource: 'stream' }); return; }
  if (message.type === 'session_rejected' && current?.requestId === message.requestId) { current = null; sequence = 0; return; }
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
