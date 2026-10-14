// Observe an already-running real phone -> browser -> phone pipeline. No image is saved.
// Node.js >= 20; run --help for usage. This script never starts ADB, a camera or a stream.
import { mkdir, open, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import assert from 'node:assert/strict';

const FPS_FIELDS = ['cameraFps', 'uplinkFps', 'returnFps', 'displayFps'];
const round = value => Math.round(value * 100) / 100;

export function statistics(values) {
  if (!values.length) return { count: 0, mean: null, min: null, max: null, p50: null };
  const sorted = [...values].sort((a, b) => a - b);
  const half = Math.floor(sorted.length / 2);
  return {
    count: values.length,
    mean: round(values.reduce((sum, value) => sum + value, 0) / values.length),
    min: round(sorted[0]), max: round(sorted.at(-1)),
    p50: round(sorted.length % 2 ? sorted[half] : (sorted[half - 1] + sorted[half]) / 2),
  };
}

function ready(status) {
  return status?.state === 'streaming' && status.isRunning === true && status.clients > 0 &&
    status.vrEnabled === true && typeof status.sessionId === 'string' && status.sessionId.length > 0 &&
    Number.isSafeInteger(status.frameId) && status.frameId > 0 &&
    Number.isSafeInteger(status.returnFrameId) && status.returnFrameId > 0 &&
    FPS_FIELDS.every(field => Number.isFinite(status[field]) && status[field] > 0);
}

export class EnduranceMonitor {
  constructor(status, now, stallMs = 3000) {
    assert(ready(status), 'Initial status must represent an active bidirectional pipeline');
    this.start = now;
    this.stallMs = stallMs;
    this.sessionId = status.sessionId;
    this.selectedCameraId = status.selectedCameraId ?? null;
    this.returnOwnerId = status.returnOwnerId ?? null;
    this.initial = { frameId: status.frameId, returnFrameId: status.returnFrameId };
    this.lastIds = { ...this.initial };
    this.lastAdvance = { frameId: now, returnFrameId: now };
    this.longestMs = { frameId: 0, returnFrameId: 0, httpFailure: 0, unhealthy: 0 };
    this.badSince = new Map();
    this.failures = [];
    this.failureCodes = new Set();
    this.fps = Object.fromEntries(FPS_FIELDS.map(field => [field, []]));
    this.httpFailures = 0;
    this.goodSamples = 0;
    this.totalSamples = 0;
    this.longestSampleGapMs = 0;
    this.lastSampleAt = now;
  }

  fail(code, now, detail) {
    if (this.failureCodes.has(code)) return;
    this.failureCodes.add(code);
    this.failures.push({ code, elapsedMs: round(now - this.start), detail });
  }

  checkDuration(key, bad, now, detail) {
    if (bad && !this.badSince.has(key)) this.badSince.set(key, now);
    const since = this.badSince.get(key);
    if (since !== undefined) {
      const elapsed = now - since;
      this.longestMs[key] = Math.max(this.longestMs[key] ?? 0, elapsed);
      if (elapsed > this.stallMs) this.fail(key, now, detail);
    }
    if (!bad) this.badSince.delete(key);
  }

  sample(now, status, error = null, requestStartedAt = now) {
    const previousFailureCount = this.failures.length;
    this.totalSamples++;
    this.longestSampleGapMs = Math.max(this.longestSampleGapMs, now - this.lastSampleAt);
    this.lastSampleAt = now;
    if (!status) {
      this.httpFailures++;
      // Include the failed request's wait time in an outage, not just poll boundaries.
      if (!this.badSince.has('httpFailure')) this.badSince.set('httpFailure', requestStartedAt);
    }
    this.checkDuration('httpFailure', !status, now, error ?? 'Status HTTP unavailable');
    this.checkDuration('unhealthy', !status || !ready(status), now,
      status ? `state=${status.state}, clients=${status.clients}, vrEnabled=${status.vrEnabled}` : error);

    if (status) {
      this.goodSamples++;
      if (status.sessionId !== this.sessionId) this.fail('sessionChanged', now,
        { expected: this.sessionId, actual: status.sessionId });
      if (this.selectedCameraId !== null && status.selectedCameraId !== this.selectedCameraId)
        this.fail('cameraChanged', now, { expected: this.selectedCameraId, actual: status.selectedCameraId });
      if (this.returnOwnerId !== null && status.returnOwnerId !== this.returnOwnerId)
        this.fail('returnOwnerChanged', now, { expected: this.returnOwnerId, actual: status.returnOwnerId });
      for (const field of FPS_FIELDS) {
        const value = status[field];
        if (Number.isFinite(value)) this.fps[field].push(value);
        this.checkDuration(`${field}Stopped`, !Number.isFinite(value) || value <= 0, now, `${field}=${value}`);
      }
      for (const field of ['frameId', 'returnFrameId']) {
        const value = status[field];
        // Examine the gap BEFORE resetting it: recovery must not hide a prior stall.
        const gap = now - this.lastAdvance[field];
        this.longestMs[field] = Math.max(this.longestMs[field], gap);
        if (gap > this.stallMs) this.fail(`${field}Stalled`, now, { gapMs: round(gap), lastId: this.lastIds[field] });
        if (!Number.isSafeInteger(value) || value < 0) this.fail(`${field}Invalid`, now, value);
        else if (value < this.lastIds[field]) this.fail(`${field}Regressed`, now, { previous: this.lastIds[field], actual: value });
        else if (value > this.lastIds[field]) {
          this.lastAdvance[field] = now;
          this.lastIds[field] = value;
        }
      }
    } else {
      for (const field of ['frameId', 'returnFrameId']) {
        const gap = now - this.lastAdvance[field];
        this.longestMs[field] = Math.max(this.longestMs[field], gap);
        if (gap > this.stallMs) this.fail(`${field}Stalled`, now,
          { gapMs: round(gap), detail: 'No fresh status; progress cannot be verified' });
      }
    }
    return this.failures.slice(previousFailureCount);
  }

  summary(now) {
    return {
      passed: this.failures.length === 0,
      measuredSeconds: round((now - this.start) / 1000), sessionId: this.sessionId,
      selectedCameraId: this.selectedCameraId,
      returnOwnerId: this.returnOwnerId,
      totalSamples: this.totalSamples, successfulHttpSamples: this.goodSamples, httpFailures: this.httpFailures,
      initialIds: this.initial, finalIds: this.lastIds,
      longestObservedGapMs: Object.fromEntries(Object.entries(this.longestMs).map(([key, value]) => [key, round(value)])),
      longestSampleGapMs: round(this.longestSampleGapMs),
      fps: Object.fromEntries(FPS_FIELDS.map(field => [field, statistics(this.fps[field])])),
      failures: this.failures,
    };
  }
}

function parseOptions(argv) {
  const options = { url: 'http://127.0.0.1:18765', seconds: 600, intervalMs: 1000, stallMs: 3000, startupSeconds: 30, switches: 0,
    out: resolve('dist', `vr-endurance-${new Date().toISOString().replace(/[:.]/g, '-')}`) };
  const names = { '--url': 'url', '--seconds': 'seconds', '--interval-ms': 'intervalMs', '--stall-ms': 'stallMs',
    '--startup-seconds': 'startupSeconds', '--out': 'out', '--switches': 'switches', '--camera-ids': 'cameraIds' };
  for (let i = 0; i < argv.length; i++) {
    const key = names[argv[i]];
    if (!key || argv[i + 1] === undefined) throw new Error(`Unknown or incomplete argument: ${argv[i]}`);
    const value = argv[++i];
    options[key] = ['url', 'out', 'cameraIds'].includes(key) ? value : Number(value);
  }
  for (const key of ['seconds', 'intervalMs', 'stallMs', 'startupSeconds'])
    if (!Number.isFinite(options[key]) || options[key] <= 0) throw new Error(`${key} must be positive`);
  if (options.intervalMs > options.stallMs / 2) throw new Error('Sampling interval must be at most half the stall threshold');
  if (!Number.isSafeInteger(options.switches) || options.switches < 0 || options.switches > 1000)
    throw new Error('--switches must be an integer between 0 and 1000');
  const url = new URL(options.url);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname))
    throw new Error('Use the local USB-forwarded HTTP address (localhost / 127.0.0.1 / [::1])');
  options.url = url.origin;
  options.out = resolve(options.out);
  return options;
}

async function getStatus(base, timeoutMs = 2000) {
  const response = await fetch(`${base}/status`, { cache: 'no-store', signal: AbortSignal.timeout(Math.max(1, Math.ceil(timeoutMs))) });
  if (!response.ok) throw new Error(`GET /status returned HTTP ${response.status}`);
  return response.json();
}

async function postCamera(base, cameraId) {
  const response = await fetch(`${base}/settings`, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cameraId }),
    signal: AbortSignal.timeout(3000) });
  if (!response.ok) throw new Error(`POST /settings cameraId=${cameraId}: HTTP ${response.status} ${await response.text()}`);
  // Consume the body to release the connection. No other settings or VR owner are changed.
  await response.text();
}

export function switchReady(status, previous, cameraId, oldSession) {
  return ready(status) && (cameraId === null || status.selectedCameraId === cameraId) &&
    (oldSession === null || status.sessionId !== oldSession) &&
    previous?.sessionId === status.sessionId && status.frameId > previous.frameId &&
    status.returnFrameId > previous.returnFrameId;
}

export async function runSwitches(options) {
  await mkdir(dirname(options.out), { recursive: true });
  const log = await open(`${options.out}.ndjson`, 'wx');
  const write = data => log.write(`${JSON.stringify({ recordedAt: new Date().toISOString(), ...data })}\n`);
  let interrupted = false, originalCameraId = null, changed = false;
  const stop = () => { interrupted = true; };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  const result = { mode: 'camera-switch', startedAt: new Date().toISOString(), requestedSwitches: options.switches,
    transitions: [], restore: null, passed: false };
  const waitForFrames = async (cameraId, oldSession, phase, isRestore = false, deadline = performance.now() + 30000) => {
    let previous = null, last = null, lastError = null;
    while (performance.now() < deadline && (!interrupted || isRestore)) {
      try {
        last = await getStatus(options.url, Math.min(2000, deadline - performance.now()));
        await write({ type: 'switch-sample', phase, expectedCameraId: cameraId, status: last });
        if (switchReady(last, previous, cameraId, oldSession)) return last;
        previous = ready(last) ? last : null;
      } catch (error) {
        lastError = String(error);
        await write({ type: 'switch-http-error', phase, error: lastError });
        previous = null;
      }
      // Readiness is frame-based; this short delay only bounds the HTTP polling rate.
      await delay(Math.max(0, Math.min(250, deadline - performance.now())));
    }
    throw new Error(interrupted && !isRestore ? 'Interrupted' :
      `No advancing bidirectional frames for camera ${cameraId} within 30s; ` +
      `selected=${last?.selectedCameraId}, state=${last?.state}, controlsError=${last?.cameraControls?.controlsError ?? ''}, HTTP=${lastError ?? 'ok'}`);
  };
  try {
    await write({ type: 'start', options, description: 'Explicit camera switching via POST /settings; existing real browser stream only.' });
    let current = await waitForFrames(null, null, 'initial');
    originalCameraId = current.selectedCameraId;
    if (typeof originalCameraId !== 'string' || !originalCameraId) throw new Error('No selectedCameraId in status; v0.4 is required');
    const catalog = Array.isArray(current.cameras) ? current.cameras : [];
    const knownIds = catalog.map(camera => camera.id);
    const ids = options.cameraIds ? options.cameraIds.split(',').map(id => id.trim()).filter(Boolean) : knownIds;
    const cameraIds = [...new Set(ids)];
    if (cameraIds.length < 2 || cameraIds.some(id => typeof id !== 'string' || !knownIds.includes(id)))
      throw new Error('Need at least two distinct camera IDs from the real /status.cameras catalog');
    result.originalCameraId = originalCameraId;
    result.cameraIds = cameraIds;
    result.catalog = catalog;
    for (let index = 0; index < options.switches && !interrupted; index++) {
      const selectedIndex = cameraIds.indexOf(current.selectedCameraId);
      const target = cameraIds[(selectedIndex + 1) % cameraIds.length];
      const transition = { number: index + 1, fromCameraId: current.selectedCameraId,
        targetCameraId: target, oldSessionId: current.sessionId, startedAt: new Date().toISOString(), success: false };
      result.transitions.push(transition);
      const start = performance.now();
      try {
        changed = true; // Also restore after an uncertain POST response.
        await postCamera(options.url, target);
        current = await waitForFrames(target, transition.oldSessionId, `switch-${index + 1}`, false, start + 30000);
        Object.assign(transition, { success: true, elapsedMs: round(performance.now() - start),
          selectedCameraId: current.selectedCameraId, sessionId: current.sessionId,
          frameId: current.frameId, returnFrameId: current.returnFrameId,
          activePhysicalCameraId: current.activePhysicalCameraId ?? null,
          fps: Object.fromEntries(FPS_FIELDS.map(field => [field, current[field]])) });
        await write({ type: 'switch-result', ...transition });
        console.log(`Switch ${index + 1}/${options.switches}: camera=${current.selectedCameraId} session=${current.sessionId} ${transition.elapsedMs}ms`);
      } catch (error) {
        Object.assign(transition, { elapsedMs: round(performance.now() - start), error: String(error) });
        await write({ type: 'switch-result', ...transition });
        throw error;
      }
    }
    result.passed = !interrupted && result.transitions.length === options.switches;
  } catch (error) {
    result.error = String(error);
  } finally {
    if (changed && originalCameraId !== null) {
      try {
        await postCamera(options.url, originalCameraId);
        const restored = await waitForFrames(originalCameraId, null, 'restore', true);
        result.restore = { success: true, selectedCameraId: restored.selectedCameraId,
          sessionId: restored.sessionId, frameId: restored.frameId, returnFrameId: restored.returnFrameId };
      } catch (error) {
        result.restore = { success: false, cameraId: originalCameraId, error: String(error) };
        result.passed = false;
      }
      await write({ type: 'restore-result', ...result.restore });
    }
    process.off('SIGINT', stop);
    process.off('SIGTERM', stop);
    Object.assign(result, { interrupted, finishedAt: new Date().toISOString(),
      successfulSwitches: result.transitions.filter(item => item.success).length,
      artifacts: { ndjson: `${options.out}.ndjson`, summary: `${options.out}.json` },
      notes: ['Each success requires the requested actual selectedCameraId, a new session and advancing source/return frames with positive FPS.',
        'Intentional session changes are expected here; run the fixed-camera 600-second test separately.',
        'Camera metadata/stream success cannot establish physical field of view or lens identity by itself; compare a stationary scene separately.'] });
    await write({ type: 'summary', ...result });
    await log.close();
    await writeFile(`${options.out}.json`, `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' });
  }
  console.log(JSON.stringify(result, null, 2));
  return interrupted ? 130 : result.passed ? 0 : 1;
}

export async function run(options) {
  await mkdir(dirname(options.out), { recursive: true });
  const log = await open(`${options.out}.ndjson`, 'wx');
  const write = data => log.write(`${JSON.stringify({ recordedAt: new Date().toISOString(), ...data })}\n`);
  let interrupted = false;
  const stop = () => { interrupted = true; };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  let monitor = null;
  let result;
  const startedAt = new Date().toISOString();
  try {
    await write({ type: 'start', options, description: 'Read-only status sampling of an existing real bidirectional stream; no frames saved.' });
    const warmupDeadline = performance.now() + options.startupSeconds * 1000;
    let previous = null;
    while (!interrupted && performance.now() < warmupDeadline) {
      let status = null, error = null;
      try { status = await getStatus(options.url); } catch (reason) { error = String(reason); }
      const now = performance.now();
      await write({ type: 'warmup', status, error });
      if (ready(status) && previous?.sessionId === status.sessionId &&
          status.frameId > previous.frameId && status.returnFrameId > previous.returnFrameId) {
        monitor = new EnduranceMonitor(status, now, options.stallMs);
        monitor.sample(now, status);
        await write({ type: 'measurement-start', sessionId: status.sessionId, selectedCameraId: status.selectedCameraId ?? null });
        break;
      }
      previous = ready(status) ? status : null;
      await delay(Math.min(options.intervalMs, 1000));
    }
    if (!monitor) throw new Error(interrupted ? 'Interrupted before measurement' :
      'Startup timed out: open the real browser viewer and enable phone VR return display first');
    const endAt = monitor.start + options.seconds * 1000;
    let nextAt = monitor.start + options.intervalMs;
    let nextProgressAt = monitor.start;
    while (!interrupted) {
      await delay(Math.max(0, Math.min(nextAt, endAt) - performance.now()));
      if (interrupted) break;
      const requestStartedAt = performance.now();
      let status = null, error = null;
      try { status = await getStatus(options.url); } catch (reason) { error = String(reason); }
      const now = performance.now();
      const failures = monitor.sample(now, status, error, requestStartedAt);
      await write({ type: 'sample', elapsedMs: round(now - monitor.start), requestMs: round(now - requestStartedAt), status, error });
      for (const failure of failures) await write({ type: 'failure', ...failure });
      if (now >= nextProgressAt || failures.length) {
        console.log(`${round((now - monitor.start) / 1000)}s: source=${status?.frameId ?? '?'} return=${status?.returnFrameId ?? '?'} ` +
          `fps=${FPS_FIELDS.map(field => `${field}:${status?.[field] ?? '?'}`).join(' ')} failures=${monitor.failures.length}`);
        nextProgressAt = now + 15000;
      }
      if (now >= endAt) break;
      // Never overlap requests or issue a burst of catch-up samples after a timeout.
      nextAt = monitor.start + (Math.floor((now - monitor.start) / options.intervalMs) + 1) * options.intervalMs;
    }
    result = monitor.summary(performance.now());
    if (interrupted) result = { ...result, passed: false, interrupted: true };
  } catch (error) {
    result = { ...(monitor?.summary(performance.now()) ?? {}), passed: false, error: String(error), interrupted };
  } finally {
    process.off('SIGINT', stop);
    process.off('SIGTERM', stop);
    result = { ...result, startedAt, finishedAt: new Date().toISOString(), requestedSeconds: options.seconds,
      tenMinuteRunCompleted: !interrupted && (result?.measuredSeconds ?? 0) >= 600,
      samplingIntervalMs: options.intervalMs, failureThresholdMs: options.stallMs,
      notes: [
        'Frame IDs may skip; only forward progress is required. Camera/session changes fail this fixed-camera test.',
        'Gap values are conservative sampling bounds, not exact per-frame latency. HTTP outages prevent verifying progress.',
        'FPS values are server-reported snapshots. Means/p50 use successful status samples, not missing HTTP samples.',
        'Status counters do not prove optical clarity, field of view, temperature, or actual physical screen refresh. Check those separately.',
      ], artifacts: { ndjson: `${options.out}.ndjson`, summary: `${options.out}.json` } };
    await write({ type: 'summary', ...result });
    await log.close();
    await writeFile(`${options.out}.json`, `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' });
  }
  console.log(JSON.stringify(result, null, 2));
  return result.interrupted ? 130 : result.passed ? 0 : 1;
}

function selfTest() {
  const good = { state: 'streaming', isRunning: true, clients: 1, vrEnabled: true, sessionId: 'session-a',
    selectedCameraId: '0', frameId: 1, returnFrameId: 1, cameraFps: 60, uplinkFps: 42, returnFps: 38, displayFps: 38 };
  const advancing = new EnduranceMonitor(good, 0);
  advancing.sample(1000, { ...good, frameId: 40, returnFrameId: 38 });
  advancing.sample(2000, { ...good, frameId: 80, returnFrameId: 78 });
  assert.equal(advancing.summary(2000).passed, true, 'Latest-frame skips are normal');
  const frozen = new EnduranceMonitor(good, 0);
  for (const now of [1000, 2000, 3000, 4001]) frozen.sample(now, good);
  assert(frozen.failureCodes.has('frameIdStalled'));
  assert(frozen.failureCodes.has('returnFrameIdStalled'));
  const recovered = new EnduranceMonitor(good, 0);
  recovered.sample(3500, { ...good, frameId: 90, returnFrameId: 88 });
  assert(recovered.failureCodes.has('frameIdStalled'), 'A recovery must not erase an unverified long gap');
  const changed = new EnduranceMonitor(good, 0);
  changed.sample(1000, { ...good, sessionId: 'session-b', selectedCameraId: '1' });
  assert(changed.failureCodes.has('sessionChanged'));
  assert(changed.failureCodes.has('cameraChanged'));
  const unavailable = new EnduranceMonitor(good, 0);
  unavailable.sample(1000, null, 'timeout', 500);
  unavailable.sample(4000, null, 'timeout', 3000);
  assert(unavailable.failureCodes.has('httpFailure'));
  const noServe = new EnduranceMonitor(good, 0);
  noServe.sample(1000, { ...good, uplinkFps: 0, frameId: 40, returnFrameId: 39 });
  noServe.sample(4200, { ...good, uplinkFps: 0, frameId: 160, returnFrameId: 158 });
  assert(noServe.failureCodes.has('uplinkFpsStopped'));
  const switched = { ...good, selectedCameraId: '0/3', sessionId: 'new', frameId: 20, returnFrameId: 19 };
  assert(switchReady(switched, { ...switched, frameId: 10, returnFrameId: 9 }, '0/3', 'old'));
  assert(!switchReady(switched, { ...switched, frameId: 10, returnFrameId: 9 }, '0/4', 'old'), 'Fallback is not a successful requested lens');
  assert(!switchReady(switched, { ...switched, frameId: 10, returnFrameId: 19 }, '0/3', 'old'), 'Source-only progress is insufficient');
  assert(!switchReady(switched, { ...switched, frameId: 10, returnFrameId: 9 }, '0/3', 'new'), 'A switch must have a new session');
  assert.deepEqual(statistics([4, 2, 6, 8]), { count: 4, mean: 5, min: 2, max: 8, p50: 5 });
  console.log('PASS: progression, bounded stalls, recovery, sessions, HTTP outage, served-FPS and statistics');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.includes('--help')) {
    console.log(`Usage: node scripts/check-vr-endurance.mjs [options]
  --url http://127.0.0.1:18765   Existing USB-forwarded viewer address
  --seconds 600                Measurement duration (warmup excluded)
  --interval-ms 1000           Status sampling interval
  --stall-ms 3000              Fail if observed progress/health gap exceeds this
  --startup-seconds 30         Wait for real source AND return frames to advance
  --out dist/vr-endurance-NAME Output prefix (.ndjson progress + .json summary)
  --switches 20               Separate lens-switch mode; POST /settings cameraId
  --camera-ids 0,1            Optional exact catalog IDs; defaults to all cameras
  --self-test                  Deterministic checks; no phone or network access
Keep the real browser viewer and phone VR display running. Do not switch lenses,
change settings, pause, lock the phone or hide the app during this fixed-session test.
Default mode is read-only: no stream is opened and no camera frame is saved.
Lens-switch mode changes only cameraId, waits up to 30 seconds for actual return
frames per switch, then restores the original camera even after a failed switch.
Exit codes: 0 passed; 1 failed/incomplete; 2 usage error; 130 interrupted.`);
  } else if (process.argv.includes('--self-test')) selfTest();
  else {
    try {
      const options = parseOptions(process.argv.slice(2));
      process.exitCode = await (options.switches > 0 ? runSwitches(options) : run(options));
    }
    catch (error) { console.error(String(error)); process.exitCode = 2; }
  }
}
