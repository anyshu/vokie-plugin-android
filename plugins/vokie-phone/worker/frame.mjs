export const MAX_FRAME_BYTES = 64 * 1024;

export class FrameDecoder {
  #pending = Buffer.alloc(0);
  push(chunk) {
    this.#pending = Buffer.concat([this.#pending, chunk]);
    if (this.#pending.length > MAX_FRAME_BYTES * 2) throw new Error('frame buffer limit exceeded');
    const frames = [];
    while (this.#pending.length >= 4) {
      const length = this.#pending.readUInt32BE(0);
      if (length <= 0 || length > MAX_FRAME_BYTES) throw new Error('invalid frame length');
      if (this.#pending.length < length + 4) break;
      frames.push(this.#pending.subarray(4, length + 4));
      this.#pending = this.#pending.subarray(length + 4);
    }
    return frames;
  }
}

export function encodeFrame(payload) {
  if (!payload.length || payload.length > MAX_FRAME_BYTES) throw new Error('invalid frame length');
  const header = Buffer.alloc(4);
  header.writeUInt32BE(payload.length);
  return Buffer.concat([header, payload]);
}

export function parseJson(frame) {
  try { return JSON.parse(frame.toString('utf8')); } catch { return null; }
}

export function encodePluginAudio(requestId, sequence, pcm) {
  const header = Buffer.from(JSON.stringify({
    type: 'audio', requestId, sequence, sampleRate: 16000, channels: 1, format: 'pcm_s16le'
  }));
  const length = Buffer.alloc(4);
  length.writeUInt32BE(header.length);
  return Buffer.concat([length, header, pcm]);
}

export function parsePhoneAudio(frame) {
  if (frame.length < 16 || frame.readUInt16LE(0) !== 0x5041 || frame[2] !== 1) return null;
  const payloadLength = frame.readUInt16LE(14);
  if (payloadLength !== frame.length - 16 || payloadLength % 2) return null;
  return {
    sessionId: frame.readUInt32LE(4), sequence: frame.readUInt32LE(8),
    fragmentIndex: frame[12], fragmentCount: frame[13], payload: frame.subarray(16)
  };
}
