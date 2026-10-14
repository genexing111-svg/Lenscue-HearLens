// Controlled browser/USB simulation. Run: node desktop/tests/viewer.test.cjs
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const html = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/usb-viewer.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const flush = async () => { for (let i = 0; i < 90; i++) await Promise.resolve(); };
let nextClient = 0;
async function environment(initialStatus = {}) {
  let now = 100000, timerId = 0, postActive = 0, encodeActive = 0, decodeActive = 0;
  const timers = new Map(), elements = new Map(), windowHandlers = {}, streams = [];
  const env = { clientId: 'page-' + ++nextClient, status: { state: 'waiting', sessionId: 'session-a', frameId: 0, width: 1920, height: 1080, lastFrameAgeMs: null, returnOwnerId: null, vrEnabled: false, returnAgeMs: null, returnFrameId: 0, settings: { fps: 0, quality: 75, maxWidth: 960 }, cameras: [{id:'0',label:'后置镜头',facing:'back'},{id:'1',label:'前置镜头',facing:'front'},{id:'0:3',label:'物理镜头 3（待验证）',facing:'back',focalLengthMm:2.4}], selectedCameraId:'0', cameraControls:{zoomMin:1,zoomMax:6,zoomRatio:1,exposureMin:-4,exposureMax:4,exposureStep:.5,exposureIndex:0,torchSupported:true,torchEnabled:false,focusSupported:true,controlsError:null}, capabilities: { fpsRanges: [[15,30],[30,60]], maxFps: 60, selectedFpsRange: [30,60] }, cameraFps: 60, uplinkFps: 20, returnFps: 18, displayFps: 17, displayWidth: 3200, displayHeight: 1440, ...initialStatus }, failStatus: false, streamCalls: 0, returnCalls: [], displayCalls: [], settingsCalls: [], focusCalls: [], encoders: [], heldPosts: [], holdEncode: false, holdReturn: false, maxPosts: 0, maxEncodes: 0, maxDecodes: 0, bitmapCloses: 0, saved: new Map() };
  const defaults = { fontSize:32,textY:78,returnWidth:1280,imageScale:100,eyeSpacing:0,leftSize:0,rightSize:0,leftY:0,rightY:0,brightness:100,contrast:100,saturation:100,rotation:0,textColor:'#ffffff',textAlign:'center',backgroundOpacity:76,textStroke:0,eyeMode:'both',calibration:'off',fps:0,maxWidth:960,quality:75,caption:'测试文字\n左右一致',preset:'comfortable',zoomRatio:1,exposureIndex:0 };
  function element(id) {
    if (!elements.has(id)) {
      const handlers = {}, calls = [], stack = [];
      const ctx = { calls, filter:'none', fillRect(...args) { calls.push(['fillRect', ...args]); }, drawImage(...args) { calls.push(['drawImage', ...args]); calls.push(['filter',this.filter]); }, fillText(...args) { calls.push(['fillText', ...args]); calls.push(['textStyle',this.fillStyle,this.textAlign]); }, measureText(text) { return { width: Array.from(text).length * 18 }; }, save(){ stack.push({filter:this.filter}); calls.push(['save']); },restore(){ Object.assign(this,stack.pop());calls.push(['restore']); },translate(...args){calls.push(['translate',...args]);},scale(...args){calls.push(['scale',...args]);},rotate(...args){calls.push(['rotate',...args]);},strokeText(...args){calls.push(['strokeText',...args]);},beginPath(){calls.push(['beginPath']);},moveTo(...args){calls.push(['moveTo',...args]);},lineTo(...args){calls.push(['lineTo',...args]);},stroke(){calls.push(['stroke']);} };
      elements.set(id, { textContent: '', value: String(defaults[id] ?? ''), width: 1280, height: 576, dataset: { live: 'false' }, style: {}, handlers, checked: id === 'phoneDisplay', options: [0,15,24,30,60,120].map(value => ({ value: String(value) })), context: ctx,
        addEventListener(type, callback) { handlers[type] = callback; },
        replaceChildren(...children){this.options=children;},click(){if(handlers.click)handlers.click();},
        getContext() { return ctx; }, async requestFullscreen() {},
        toBlob(callback) {
          encodeActive++; env.maxEncodes = Math.max(env.maxEncodes, encodeActive);
          const done = () => { encodeActive--; callback(new Blob([new Uint8Array(40)], { type: 'image/jpeg' })); };
          if (env.holdEncode) env.encoders.push(done); else Promise.resolve().then(done);
        }
      });
    }
    return elements.get(id);
  }
  function schedule(callback, delay, interval = false) { const id = ++timerId; timers.set(id, { callback, due: now + delay, delay, interval }); return id; }
  function reader() {
    const queue = [], waiters = [];
    const object = { cancelled: false, read() { return queue.length ? Promise.resolve(queue.shift()) : new Promise(resolve => waiters.push(resolve)); },
      push(value) { const item = { done: false, value }; if (waiters.length) waiters.shift()(item); else queue.push(item); },
      end() { const item = { done: true }; if (waiters.length) waiters.shift()(item); else queue.push(item); },
      async cancel() { object.cancelled = true; object.end(); } };
    return object;
  }
  const browserWindow = { addEventListener(type, callback) { windowHandlers[type] = callback; } };
  const context = vm.createContext({ console, Uint8Array, TextDecoder, Blob, AbortController, encodeURIComponent, crypto:{randomUUID:()=>env.clientId}, localStorage:{setItem:(key,val)=>env.saved.set(key,val),getItem:key=>env.saved.get(key)},
    Date: class extends Date { static now() { return now; } }, document: { getElementById: element, fullscreenElement: null,createElement:()=>({click(){}}) }, window: browserWindow, location: { hostname: '127.0.0.1', reload() {} },
    setTimeout: (callback, delay) => schedule(callback, delay), clearTimeout: id => timers.delete(id), setInterval: (callback, delay) => schedule(callback, delay, true), clearInterval: id => timers.delete(id),
    async createImageBitmap(blob) { decodeActive++; env.maxDecodes = Math.max(env.maxDecodes, decodeActive); await Promise.resolve(); decodeActive--; return { width: 1920, height: 1080, close() { env.bitmapCloses++; } }; },
    async fetch(url, options = {}) {
      if (url === '/status') { if (env.failStatus) throw new Error('offline'); return { ok: true, json: async () => ({ ...env.status }) }; }
      if (url === '/display') { const body=JSON.parse(options.body);env.displayCalls.push(body);if(env.status.returnOwnerId&&env.status.returnOwnerId!==body.clientId&&!body.takeover)return{ok:false,status:409};env.status.returnOwnerId=body.enabled?body.clientId:null;env.status.vrEnabled=body.enabled;return { ok: true, status: 204 }; }
      if (url === '/settings') { env.settingsCalls.push(JSON.parse(options.body));return { ok: true, status: 200, json: async () => env.status }; }
      if (url === '/focus') { env.focusCalls.push(JSON.parse(options.body));return {ok:true,status:204}; }
      if (url.startsWith('/return-frame?')) {
        const query = new URL(url,'http://localhost').searchParams;
        const call = { url, source:Number(query.get('source')),session:query.get('session'),client:query.get('client'),signal: options.signal, body: options.body }; env.returnCalls.push(call);
        postActive++; env.maxPosts = Math.max(env.maxPosts, postActive);
        if (!env.holdReturn) { postActive--; return { ok: true, status: 204 }; }
        return new Promise((resolve, reject) => {
          let completed = false;
          const done = () => { if (completed) return; completed = true; postActive--; resolve({ ok: true, status: 204 }); };
          env.heldPosts.push(done);
          options.signal.addEventListener('abort', () => { if (completed) return; completed = true; postActive--; reject(new Error('aborted')); });
        });
      }
      assert.match(url, /^\/stream\?t=\d+&client=/);
      assert.equal(new URL(url,'http://localhost').searchParams.get('client'),env.clientId,'source stream carries the same per-page client id as returns');env.streamCalls++;
      const stream = reader(); streams.push(stream); options.signal.addEventListener('abort', () => stream.end());
      return { ok: true, body: { getReader: () => stream } };
    }
  });
  vm.runInContext(script, context); await flush();
  Object.assign(env, { element, streams, timers, geometry: browserWindow.HearLensGeometry,
    async advance(ms) { const target = now + ms; while (true) { const next = [...timers.entries()].filter(([,t]) => t.due <= target).sort((a,b) => a[1].due - b[1].due)[0]; if (!next) break; const [id,t] = next; now = t.due; if (t.interval) t.due += t.delay; else timers.delete(id); t.callback(); await flush(); } now = target; await flush(); },
    async sendFrame(id, session = env.status.sessionId, fragment = false) { const part = Buffer.concat([Buffer.from('--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\nX-Session-Id: ' + session + '\r\nX-Frame-Id: ' + id + '\r\n\r\n'), Buffer.from([0xff,0xd8,0xff,0xd9]), Buffer.from('\r\n')]); if (fragment) { streams.at(-1).push(part.subarray(0, 24)); streams.at(-1).push(part.subarray(24, 101)); streams.at(-1).push(part.subarray(101)); } else streams.at(-1).push(part); await flush(); },
    async click(id) { element(id).handlers.click(); await flush(); }, async close() { windowHandlers.pagehide(); await flush(); }
  });
  return env;
}
(async () => {
  const env = await environment();
  assert.equal(env.streamCalls, 1, 'waiting subscribes before first encoded frame');
  assert.equal(env.displayCalls[0].enabled, true, 'initial request enables phone display');
  assert.equal(env.element('fps').options.find(x => x.value === '120').hidden, true, 'unsupported 120fps is not offered');
  assert.equal(env.element('sbsCanvas').height, 576, '3200x1440 display ratio gives 1280x576');
  for (const [sw,sh] of [[1920,1080],[1080,1920],[1440,1440],[2560,720]]) for (const scale of [.5,.75,1]) for (const spacing of [-1,0,1]) {
    const eyes = env.geometry.geometry(sw,sh,1280,576,scale,spacing);
    eyes.forEach((eye,index) => { assert.ok(eye.x >= index*640-1e-6 && eye.x+eye.w <= (index+1)*640+1e-6, 'each full image fits its eye horizontally'); assert.ok(eye.y >= -1e-6 && eye.y+eye.h <= 576+1e-6, 'each full image fits vertically'); assert.ok(Math.abs(eye.w/eye.h-sw/sh)<1e-8, 'image keeps source aspect ratio'); });
  }
  await env.sendFrame(1, 'session-a', true);
  assert.equal(env.element('sourceStage').dataset.live, 'true');
  assert.equal(env.element('sbsStage').dataset.live, 'true');
  assert.match(env.returnCalls.at(-1).url, /session=session-a&source=1&client=/);
  assert.equal(env.returnCalls.at(-1).client,env.clientId);
  const drawn = env.element('sbsCanvas').context.calls.filter(x=>x[0]==='drawImage');
  assert.equal(drawn.length,2);
  for (const draw of drawn) assert.deepEqual(draw.slice(2,6),[0,0,1920,1080],'both eyes use complete source rectangle');
  const texts = env.element('sbsCanvas').context.calls.filter(x=>x[0]==='fillText').map(x=>x[1]);
  assert.deepEqual(texts.slice(0,texts.length/2),texts.slice(texts.length/2),'both eyes draw identical manual text');
  env.holdReturn=true; await env.sendFrame(2); await env.sendFrame(3); await env.sendFrame(4); await env.sendFrame(5);
  assert.equal(env.maxPosts,1); assert.equal(env.returnCalls.at(-1).source,2);
  assert.equal(env.bitmapCloses,5,'decode/composite/encode continues while the previous network POST is pending');
  assert.equal(env.element('sbsCanvas').context.calls.filter(x=>x[0]==='drawImage').length,10,'all newer frames are composed without waiting for the sender');
  env.heldPosts.shift()(); await flush();
  assert.equal(env.returnCalls.at(-1).source,5,'outbound slot replaces intermediates and retains matching metadata');
  assert.equal(env.maxEncodes,1); assert.equal(env.maxDecodes,1);
  env.holdReturn=false; env.heldPosts.shift()(); await flush();
  env.holdEncode=true; await env.sendFrame(6);
  const callsBefore=env.returnCalls.length;
  env.status.sessionId='session-b'; await env.advance(1000);
  assert.equal(env.element('sourceStage').dataset.live,'false','session transition clears old image');
  env.encoders.shift()(); await flush();
  assert.equal(env.returnCalls.length,callsBefore,'late old-session toBlob cannot POST');
  env.holdEncode=false; await env.sendFrame(1,'session-b');
  assert.match(env.returnCalls.at(-1).url,/session=session-b&source=1&client=/);
  await env.sendFrame(99,'session-a');
  assert.match(env.returnCalls.at(-1).url,/session=session-b&source=1&client=/,'retired-session frames are ignored');
  env.holdReturn=true; await env.sendFrame(2,'session-b'); await env.sendFrame(3,'session-b');
  const previousPost=env.returnCalls.at(-1), beforeSessionReset=env.returnCalls.length;
  env.status.sessionId='session-c'; await env.advance(1000);
  assert.equal(previousPost.signal.aborted,true,'session change aborts the sender');
  assert.equal(env.returnCalls.length,beforeSessionReset,'session change discards encoded outbound slot before sender resumes');
  env.holdReturn=false; env.heldPosts.length=0; await env.sendFrame(1,'session-c');
  assert.match(env.returnCalls.at(-1).url,/session=session-c&source=1&client=/);
  assert.equal(env.returnCalls.some(call=>call.session==='session-b'&&call.source===3),false,'old-session outbound is never sent');
  env.holdReturn=true; await env.sendFrame(2,'session-c'); await env.sendFrame(3,'session-c');
  const beforeStop=env.returnCalls.length; await env.click('toggleReturn');
  assert.equal(env.returnCalls.at(-1).signal.aborted,true,'stop aborts active POST');
  assert.equal(env.returnCalls.length,beforeStop,'stop clears waiting outbound frame');
  env.holdReturn=false; env.heldPosts.length=0; await env.sendFrame(4,'session-c');
  assert.equal(env.returnCalls.length,beforeStop,'stopped return still draws without posting');
  await env.click('toggleReturn'); await env.sendFrame(5,'session-c');
  assert.match(env.returnCalls.at(-1).url,/session=session-c&source=5&client=/);
  assert.equal(env.maxPosts,1); assert.equal(env.maxEncodes,1); assert.equal(env.maxDecodes,1);
  env.status.state='paused'; await env.advance(1000);
  assert.equal(env.element('sourceStage').dataset.live,'false'); assert.equal(env.element('sbsStage').dataset.live,'false');
  env.status.state='waiting'; await env.advance(1000); await env.sendFrame(6,'session-c');
  env.streams.at(-1).end(); await flush();
  assert.equal(env.element('sbsStage').dataset.live,'false','normal EOF clears old SBS');
  await env.advance(1000); await env.sendFrame(7,'session-c');
  await env.advance(3500); assert.equal(env.element('sourceStage').dataset.live,'false','local stall clears even with live global status');
  env.failStatus=true; await env.advance(1000); assert.equal(env.element('sbsStage').dataset.live,'false','USB disconnect clears composite');
  await env.close(); assert.equal(env.timers.size,0,'pagehide clears timers');
  const custom=await environment();
  assert.equal(custom.element('returnAge').textContent,'—','no return frame/null age is not presented as 0ms');
  let geometryCases=0;
  for(const [sw,sh] of [[1920,1080],[1080,1920],[1440,1440],[2560,720]])for(const rotation of [0,90,180,270])for(const scale of [.5,1])for(const sizes of [[-10,10],[10,-10],[0,0]])for(const offsets of [[-10,10],[10,-10],[0,0]])for(const spacing of [-1,0,1]){
    const eyes=custom.geometry.geometry(sw,sh,1280,576,scale,spacing,{rotation,leftSize:sizes[0],rightSize:sizes[1],leftY:offsets[0],rightY:offsets[1]});
    eyes.forEach((eye,index)=>{assert.ok(eye.x>=index*640-1e-6&&eye.x+eye.w<=(index+1)*640+1e-6);assert.ok(eye.y>=-1e-6&&eye.y+eye.h<=576+1e-6);assert.ok(Math.abs(eye.w/eye.h-(rotation%180?sh/sw:sw/sh))<1e-8);});geometryCases++;
  }
  for(const [key,val] of Object.entries({brightness:120,contrast:130,saturation:80,rotation:90,leftSize:-10,rightSize:10,leftY:-10,rightY:10,imageScale:80,textColor:'#00ffaa',backgroundOpacity:35,textStroke:2,textAlign:'left',calibration:'cross'}))custom.element(key).value=String(val);
  custom.element('mirror').checked=true;await custom.sendFrame(1);
  const customCalls=custom.element('sbsCanvas').context.calls;
  assert.equal(customCalls.filter(call=>call[0]==='filter'&&call[1]==='brightness(120%) contrast(130%) saturate(80%)').length,2,'software adjustments affect both encoded eyes');
  assert.equal(customCalls.filter(call=>call[0]==='scale'&&call[1]===-1).length,2,'both eyes mirror the same direction');
  assert.equal(customCalls.filter(call=>call[0]==='rotate'&&call[1]===Math.PI/2).length,2,'both eyes rotate identically');
  assert.ok(customCalls.filter(call=>call[0]==='textStyle').every(call=>call[1]==='#00ffaa'&&call[2]==='left'),'text color and alignment reach the return canvas');
  assert.equal(customCalls.filter(call=>call[0]==='strokeText').length,4,'text outline reaches both eyes');
  assert.equal(customCalls.filter(call=>call[0]==='stroke').length,2,'calibration is drawn for both eyes');
  assert.ok(custom.element('sourceCanvas').context.calls.filter(call=>call[0]==='filter').every(call=>call[1]==='none'),'source preview remains unfiltered');
  const previousDraws=customCalls.filter(call=>call[0]==='drawImage').length;custom.element('eyeMode').value='left';await custom.sendFrame(2);
  assert.equal(customCalls.filter(call=>call[0]==='drawImage').length-previousDraws,1,'single-eye check leaves the other eye black');
  await custom.click('saveLayout');const saved=JSON.parse(custom.saved.get('hearlens.display.v1'));
  for(const secret of ['caption','cameraId','zoomRatio','torch','fps','quality','maxWidth','clientId'])assert.equal(Object.hasOwn(saved.settings,secret),false,'saved settings exclude '+secret);
  custom.element('caption').value='这段文字不应被保存或覆盖';custom.element('zoomRatio').value='2.5';custom.element('brightness').value='50';await custom.click('loadLayout');
  assert.equal(Number(custom.element('brightness').value),120);assert.equal(custom.element('caption').value,'这段文字不应被保存或覆盖');assert.equal(Number(custom.element('zoomRatio').value),2.5);
  const invalids=[{...saved,version:2},{...saved,settings:{...saved.settings,brightness:151}},{...saved,settings:{...saved.settings,rotation:45}},{...saved,settings:{...saved.settings,textColor:'red'}},{...saved,settings:{...saved.settings,mirror:'true'}},{...saved,settings:{...saved.settings,cameraId:'0'}},{...saved,settings:{...saved.settings,caption:'secret'}}];
  for(const invalid of invalids)assert.throws(()=>custom.geometry.validateLayout(invalid),'invalid configuration is rejected atomically');
  const badFile=JSON.stringify(invalids[1]);custom.element('layoutFile').files=[{size:badFile.length,text:async()=>badFile}];await custom.element('layoutFile').handlers.change();
  assert.equal(Number(custom.element('brightness').value),120,'failed import must not partially mutate controls');
  assert.match(custom.element('layoutMessage').textContent,/未导入/);
  custom.element('layoutFile').files=[{size:16385,text:async()=>JSON.stringify(saved)}];await custom.element('layoutFile').handlers.change();assert.match(custom.element('layoutMessage').textContent,/16 KB/);
  await custom.click('applyPreset');assert.equal(Number(custom.element('imageScale').value),85);assert.equal(custom.settingsCalls.length,0,'presets never silently change hardware');
  await custom.click('reset');assert.equal(Number(custom.element('imageScale').value),100);assert.equal(custom.element('caption').value,'这段文字不应被保存或覆盖');
  assert.equal(custom.element('cameraId').options.length,3,'only real reported catalog entries are offered');
  custom.element('cameraId').value='0:3';custom.element('cameraId').handlers.input();
  assert.equal(custom.element('zoomRatio').disabled,true,'unbound lens controls must not reuse previous lens capabilities');
  await custom.click('applyCamera');assert.deepEqual(custom.settingsCalls.at(-1),{cameraId:'0:3'},'switch lens sends only actual catalog camera id');
  custom.status.selectedCameraId='0:3';custom.status.cameraControls={zoomMin:null,zoomMax:null,exposureMin:null,exposureMax:null,torchSupported:false,focusSupported:false,controlsError:null};await custom.advance(1000);
  for(const key of ['zoomRatio','exposureIndex','torch','focus'])assert.equal(custom.element(key).disabled,true,'unknown/unsupported '+key+' is disabled');
  await custom.click('focus');assert.equal(custom.focusCalls.length,0);
  custom.status.selectedCameraId='0';custom.status.cameraControls={zoomMin:1,zoomMax:6,zoomRatio:1,exposureMin:-4,exposureMax:4,exposureStep:.5,exposureIndex:0,torchSupported:true,torchEnabled:false,focusSupported:true,controlsError:null};await custom.advance(1000);
  custom.element('zoomRatio').value='2.5';custom.element('zoomRatio').handlers.input();custom.element('exposureIndex').value='2';custom.element('exposureIndex').handlers.input();custom.element('torch').checked=true;custom.element('torch').handlers.input();await custom.click('applyCamera');
  assert.deepEqual(custom.settingsCalls.at(-1),{cameraId:'0',zoomRatio:2.5,exposureIndex:2,torch:true});
  await custom.click('focus');assert.deepEqual(custom.focusCalls.at(-1),{action:'center'});
  await custom.close();
  const ownership=await environment({returnOwnerId:'another-page',vrEnabled:true});
  assert.equal(ownership.displayCalls.length,0,'new page never auto steals an existing owner');await ownership.sendFrame(1);assert.equal(ownership.returnCalls.length,0);
  assert.equal(ownership.element('toggleReturn').textContent,'接管双眼回传');
  ownership.streams.at(-1).end();await flush();const streamsBeforeTakeover=ownership.streamCalls;
  await ownership.click('toggleReturn');
  assert.equal(ownership.streamCalls,streamsBeforeTakeover+1,'successful explicit takeover immediately retries a missing source stream');
  assert.equal(ownership.displayCalls.at(-1).takeover,true,'explicit takeover is distinguishable from automatic startup');
  assert.equal(ownership.displayCalls.at(-1).clientId,ownership.clientId);await ownership.sendFrame(2);assert.equal(ownership.returnCalls.length,1);
  ownership.holdReturn=true;await ownership.sendFrame(3);await ownership.sendFrame(4);const ownedPost=ownership.returnCalls.at(-1),ownedCount=ownership.returnCalls.length;
  ownership.status.returnOwnerId='new-owner';await ownership.advance(1000);
  assert.equal(ownedPost.signal.aborted,true,'losing ownership aborts active return');await ownership.sendFrame(5);assert.equal(ownership.returnCalls.length,ownedCount,'losing ownership clears outbound and stops new returns');
  const autoClaimCount=ownership.displayCalls.length;ownership.status.returnOwnerId=null;ownership.status.vrEnabled=false;await ownership.advance(3000);
  assert.equal(ownership.displayCalls.length,autoClaimCount,'old page does not auto reclaim after phone exits or another owner releases');
  await ownership.close();
  console.log('PASS: '+geometryCases+' rotated/independent-eye contain geometries; canvas image/text/calibration customization; safe local schema/import; real camera selection/capabilities; automatic non-stealing ownership and explicit takeover; separately single-flight latest pipelines; session/stop/freshness/cleanup regressions.');
})().catch(error=>{console.error(error);process.exitCode=1;});
