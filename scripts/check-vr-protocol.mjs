import assert from 'node:assert/strict';
import http from 'node:http';
const base = process.argv[2] || 'http://127.0.0.1:18765';
const readStatus = async () => {
  const response = await fetch(base + '/status', {signal: AbortSignal.timeout(4000)});
  assert(response.ok, 'Status endpoint must be available');
  return response.json();
};
const state = await readStatus();
assert(typeof state.returnOwnerId === 'string' && state.returnOwnerId.length > 0,
  'Start the v0.4 viewer and claim return ownership before running these negative protocol checks.');
assert(Number.isSafeInteger(state.returnFrameId) && state.returnFrameId > 0,
  'Wait for at least one accepted return frame: source=0 would not test an actual duplicate frame.');
assert(state.vrEnabled === true, 'Keep phone return display enabled while running checks.');
const checks = [];
async function reject(path, body, type, expected, label = path) {
  // A real viewer may briefly occupy the one return receiver. A 429 is retried,
  // never treated as proof that an old session/source/owner was rejected.
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = await fetch(base + path, { method: 'POST', headers: {'Content-Type': type}, body, signal: AbortSignal.timeout(4000) });
    const message = await response.text();
    if (response.status === 429 && path.startsWith('/return-frame') && attempt < 19) { await new Promise(resolve => setTimeout(resolve, 70)); continue; }
    assert(expected.includes(response.status), `${label}: ${response.status} ${message}`);
    checks.push({label, path, response: response.status, passed: true});
    return;
  }
}
const postJson = (path, value, expected = [400], label) => reject(path, JSON.stringify(value), 'application/json', expected, label);
const ownedReturn = (session, source, client = state.returnOwnerId) => '/return-frame?' + new URLSearchParams({session, source: String(source), client});
await reject('/settings', JSON.stringify({quality: 96}), 'application/json', [400]);
await reject('/settings', JSON.stringify({fps: -1}), 'application/json', [400]);
await reject('/settings', JSON.stringify({maxWidth: 12345}), 'application/json', [400]);
await postJson('/settings', {cameraId: '__hearlens_missing_camera__'}, [400], 'unknown camera id');
await postJson('/settings', {cameraId: 0}, [400], 'camera id must be a string');
await postJson('/settings', {zoomRatio: -1}, [400], 'invalid hardware zoom');
await postJson('/settings', {exposureIndex: 0.5}, [400], 'exposure index must be an integer');
await postJson('/settings', {torch: 'true'}, [400], 'torch must be boolean');
await postJson('/focus', {action: 'unknown'}, [400], 'unknown focus action');
await postJson('/focus', {action: 'center', x: 0.5}, [400], 'focus rejects extra fields');
await postJson('/display', {enabled: 'true', clientId: state.returnOwnerId}, [400], 'display enabled must be boolean');
await postJson('/display', {enabled: true, clientId: 'invalid owner id'}, [400], 'invalid owner id');
await postJson('/display', {enabled: true, clientId: state.returnOwnerId, takeover: 'true'}, [400], 'takeover must be boolean');
const foreignOwner = state.returnOwnerId === 'negative-protocol-foreign' ? 'negative-protocol-other' : 'negative-protocol-foreign';
await postJson('/display', {enabled: true, clientId: foreignOwner, takeover: false}, [409], 'another page cannot automatically claim');
await postJson('/display', {enabled: false, clientId: foreignOwner}, [409], 'another page cannot stop the current owner');
await postJson('/display', {enabled: true}, [409], 'legacy page cannot start over current owner');
await postJson('/display', {enabled: false}, [409], 'legacy page cannot stop current owner');
await postJson('/display', {enabled: true, takeover: true}, [409], 'takeover requires an identified page');
await reject(ownedReturn('old-session', state.returnFrameId), Buffer.from('invalid'), 'image/jpeg', [409], 'old session with the correct current owner');
await reject(ownedReturn(state.sessionId, state.returnFrameId), Buffer.from('invalid'), 'image/jpeg', [409], 'duplicate accepted source with the correct current owner');
await reject(ownedReturn(state.sessionId, state.frameId, foreignOwner), Buffer.from('invalid'), 'image/jpeg', [409], 'return frame from a different owner');
await new Promise((resolve, fail) => {
  const req = http.request(base + ownedReturn(state.sessionId, state.frameId), {method:'POST', headers:{'Content-Type':'image/jpeg','Content-Length':5*1024*1024}}, response => {
    try { assert.equal(response.statusCode,413); checks.push({path:'/return-frame (oversize header)',response:413,passed:true}); response.resume(); resolve(); }
    catch(error) { fail(error); }
  });
  req.on('error',fail); req.setTimeout(4000,()=>req.destroy(new Error('timeout'))); req.end();
});
const after = await readStatus();
assert.deepEqual(after.settings, state.settings, 'Rejected settings must not mutate active settings');
assert.equal(after.vrEnabled, state.vrEnabled, 'Invalid display request must not change display state');
assert.equal(after.selectedCameraId, state.selectedCameraId, 'Rejected camera requests must not switch lenses');
assert.equal(after.returnOwnerId, state.returnOwnerId, 'Rejected foreign display requests must preserve the owner');
assert.equal(after.sessionId, state.sessionId, 'No negative check may restart the camera session');
console.log(JSON.stringify({checkedAt:new Date().toISOString(), version:'0.4', checks, sessionAndDuplicateChecksUseCurrentOwner:true, settings: after.settings, selectedCameraId:after.selectedCameraId, state:after.state},null,2));
