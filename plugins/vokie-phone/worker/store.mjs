import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export class PairingStore {
  constructor(dir = process.env.VOKIE_PHONE_DATA_DIR || path.resolve('data')) {
    this.file = path.join(dir, 'phone-pairings.json');
    this.dir = dir;
    this.data = this.load();
  }
  get instanceId() { return this.data.instanceId; }
  getToken(deviceId) { const value = this.data.devices[deviceId]?.token; if (!value) return null; try { const token = Buffer.from(value, 'base64'); return token.length === 32 ? token : null; } catch { return null; } }
  trust(deviceId, deviceName, platform, token) { const old = this.data.devices[deviceId]; this.data.devices[deviceId] = { deviceId, deviceName, platform, token: token.toString('base64'), pairedAt: old?.pairedAt || new Date().toISOString(), lastConnectedAt: new Date().toISOString() }; this.save(); }
  touch(deviceId) { if (this.data.devices[deviceId]) { this.data.devices[deviceId].lastConnectedAt = new Date().toISOString(); this.save(); } }
  revoke(deviceId) { if (delete this.data.devices[deviceId]) this.save(); }
  load() { try { const value = JSON.parse(fs.readFileSync(this.file, 'utf8')); if (value.version === 1 && value.instanceId && value.devices) return value; } catch {} const value = { version: 1, instanceId: crypto.randomUUID(), devices: {} }; this.data = value; this.save(); return value; }
  save() { fs.mkdirSync(this.dir, { recursive: true }); const tmp = `${this.file}.tmp`; fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 }); fs.renameSync(tmp, this.file); }
}
