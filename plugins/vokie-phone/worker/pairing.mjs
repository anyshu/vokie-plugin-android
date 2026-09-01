import { createHmac, diffieHellman, generateKeyPairSync, hkdfSync, timingSafeEqual, createPublicKey } from 'node:crypto';

const contexts = {
  code: Buffer.from('vokie-pair-code-v2\0'), ready: Buffer.from('vokie-pair-ready-v2\0'),
  auth: Buffer.from('vokie-auth-v2\0'), ok: Buffer.from('vokie-auth-ok-v2\0'), token: Buffer.from('vokie-device-token-v2\0')
};
const hmac = (key, prefix, context) => createHmac('sha256', key).update(prefix).update(context).digest();
export const createKeys = () => {
  const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { privateKey: keys.privateKey, publicKey: keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
};
export const publicKey = (value) => createPublicKey({ key: Buffer.from(value, 'base64'), format: 'der', type: 'spki' });
export const secret = (privateKey, peer) => diffieHellman({ privateKey, publicKey: peer });
export const context = (instanceId, deviceId, pairingId, clientPublicKey, serverPublicKey) => Buffer.from(['vokie-phone-v2', instanceId, deviceId, pairingId, clientPublicKey, serverPublicKey].join('\0'));
export const pairingCode = (key, ctx) => String(hmac(key, contexts.code, ctx).readUInt32BE(0) % 1000000).padStart(6, '0');
export const readyProof = (key, ctx) => hmac(key, contexts.ready, ctx).toString('base64');
export const deviceToken = (key, pairingId, ctx) => Buffer.from(hkdfSync('sha256', key, Buffer.from(pairingId), Buffer.concat([contexts.token, ctx]), 32));
export const authProof = (token, ctx) => hmac(token, contexts.auth, ctx).toString('base64');
export const authOkProof = (token, ctx) => hmac(token, contexts.ok, ctx).toString('base64');
export function matches(expected, actual) {
  if (typeof actual !== 'string') return false;
  try { const a = Buffer.from(expected, 'base64'); const b = Buffer.from(actual, 'base64'); return a.length === b.length && timingSafeEqual(a, b); } catch { return false; }
}
