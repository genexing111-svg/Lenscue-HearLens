// Real USB end-to-end test. Counts JPEG frames in memory; never writes camera images.
import http from 'node:http';
import assert from 'node:assert/strict';

const base = process.argv[2] || 'http://127.0.0.1:18765';
const seconds = Number(process.argv[3] || 10);
assert(seconds > 0 && seconds <= 120, 'Duration must be between 0 and 120 seconds');
const initial = await fetch(`${base}/status`, {signal: AbortSignal.timeout(5000)});
assert.equal(initial.status, 200);
const before = await initial.json();
let pending = Buffer.alloc(0);
let frameLength = null;
let frames = 0;
let bytes = 0;
let malformed = 0;
let firstFrameAt = null;
let lastFrameAt = null;
const started = Date.now();
await new Promise((resolve, reject) => {
  const req = http.get(`${base}/stream`, (res) => {
    if (res.statusCode !== 200 || !/multipart\/x-mixed-replace/.test(res.headers['content-type'] || '')) {
      res.resume(); reject(new Error(`Unexpected stream: ${res.statusCode} ${res.headers['content-type']}`)); return;
    }
    res.on('data', (chunk) => {
      bytes += chunk.length;
      pending = Buffer.concat([pending, chunk]);
      while (true) {
        if (frameLength === null) {
          const headerEnd = pending.indexOf('\r\n\r\n');
          if (headerEnd < 0) break;
          const header = pending.subarray(0, headerEnd).toString('ascii');
          const length = /Content-Length:\s*(\d+)/i.exec(header);
          if (!length) { reject(new Error('MJPEG part missing Content-Length')); req.destroy(); return; }
          frameLength = Number(length[1]);
          if (frameLength < 4 || frameLength > 8_000_000) { reject(new Error('Invalid frame length')); req.destroy(); return; }
          pending = pending.subarray(headerEnd + 4);
        }
        if (pending.length < frameLength) break;
        const frame = pending.subarray(0, frameLength);
        if (frame[0] !== 0xff || frame[1] !== 0xd8 || frame.at(-2) !== 0xff || frame.at(-1) !== 0xd9) malformed++;
        frames++;
        firstFrameAt ??= Date.now();
        lastFrameAt = Date.now();
        pending = pending.subarray(frameLength);
        frameLength = null;
      }
    });
    res.on('end', resolve);
    res.on('error', reject);
  });
  const timer = setTimeout(() => { resolve(); req.destroy(); }, seconds * 1000);
  req.on('error', reject);
  req.on('close', () => clearTimeout(timer));
});
const after = await (await fetch(`${base}/status`, {signal: AbortSignal.timeout(5000)})).json();
const elapsedSeconds = (Date.now() - started) / 1000;
console.log(JSON.stringify({
  checkedAt: new Date().toISOString(), elapsedSeconds, frames, malformed, bytes,
  observedFramesPerSecond: Number((frames / elapsedSeconds).toFixed(2)),
  firstFrameWaitMs: firstFrameAt === null ? null : firstFrameAt - started,
  lastFrameAgeAtEndMs: lastFrameAt === null ? null : Date.now() - lastFrameAt,
  before, after,
}, null, 2));
assert(frames >= 3, 'Fewer than 3 complete frames received');
assert.equal(malformed, 0, 'Invalid JPEG framing');
assert(after.frameId > before.frameId, 'Frame counter did not advance');
