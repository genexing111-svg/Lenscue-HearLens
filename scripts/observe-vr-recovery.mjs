// Read-only sampling while the user physically locks, rotates and reconnects.
import { appendFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
const output = process.argv[2];
if (!output) throw new Error('Provide an output .ndjson path');
const end = Date.now() + Number(process.argv[3] || 240) * 1000;
let lastState = '';
while (Date.now() < end) {
  let status, error;
  try {
    const response = await fetch('http://127.0.0.1:18765/status', { signal: AbortSignal.timeout(1500) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const full = await response.json();
    status = Object.fromEntries(['state','isRunning','sessionId','selectedCameraId','frameId','returnFrameId','vrEnabled','returnOwnerId','displayFps','width','height'].map(key => [key, full[key]]));
  } catch (reason) { error = String(reason); }
  const event = { at: new Date().toISOString(), status, error };
  await appendFile(output, JSON.stringify(event) + '\n');
  const state = JSON.stringify([status?.state, status?.sessionId, status?.vrEnabled, status?.returnOwnerId, error]);
  if (state !== lastState) { console.log(JSON.stringify(event)); lastState = state; }
  await delay(500);
}
