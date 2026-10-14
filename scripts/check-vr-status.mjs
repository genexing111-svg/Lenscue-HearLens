// Sample the real phone/PC pipeline without saving camera frames.
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
const base = process.argv[2] || 'http://127.0.0.1:18765';
const seconds = Number(process.argv[3] || 20);
assert(seconds >= 3 && seconds <= 60);
const samples = [];
for (let i = 0; i < seconds; i++) {
  const response = await fetch(base + '/status', { signal: AbortSignal.timeout(4000) });
  assert.equal(response.status, 200);
  samples.push({ sampledAt: Date.now(), ...(await response.json()) });
  if (i + 1 < seconds) await delay(1000);
}
const fields = ['cameraFps', 'uplinkFps', 'returnFps', 'displayFps'];
const averages = Object.fromEntries(fields.map(key => [key,
  Number((samples.reduce((sum, row) => sum + Number(row[key] || 0), 0) / samples.length).toFixed(2))]));
console.log(JSON.stringify({ checkedAt: new Date().toISOString(), averages, samples }, null, 2));
assert(samples.at(-1).frameId > samples[0].frameId, 'Camera frame count must advance');
assert(samples.at(-1).returnFrameId > samples[0].returnFrameId, 'Returned frame count must advance');
assert(samples.some(row => Number(row.displayFps) > 0), 'Phone must submit real return frames for display');
assert(samples.every(row => row.vrEnabled), 'VR display must remain enabled during the sample');
