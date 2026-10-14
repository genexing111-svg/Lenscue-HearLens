// Live-device rendering checks. Reports contain statistics only, no pictures or access keys.
'use strict';
const http=require('node:http'),assert=require('node:assert/strict'),fs=require('node:fs');
const url=new URL(process.argv[2]),key=url.hash.slice(1),output=process.argv[3];
function api(path,body){return new Promise((resolve,reject)=>{const text=body===undefined?undefined:JSON.stringify(body);const q=http.request(new URL(path,url),{method:text===undefined?'GET':'POST',headers:{'X-HearLens-Key':key,...(text===undefined?{}:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(text)})},timeout:5000},r=>{const chunks=[];r.on('data',b=>chunks.push(b));r.on('error',reject);r.on('end',()=>resolve({code:r.statusCode,body:Buffer.concat(chunks)}));});q.on('error',reject);q.on('timeout',()=>q.destroy(Error('timeout')));q.end(text);});}
async function status(){const r=await api('/status');assert.equal(r.code,200);return JSON.parse(r.body);}
async function set(path,body){const r=await api(path,body);assert.equal(r.code,200,r.body.toString());}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function sample(s){const t=s.stereo;return {camera:s.selectedCameraId,source:[t.sourceWidth,t.sourceHeight],render:[t.renderWidth,t.renderHeight],cameraFps:s.cameraFps,localFps:t.localFps,phoneDrawFps:t.phoneDisplayFps,targetFps:t.renderTargetFps,wire:[t.width,t.height],wireFps:t.wirelessTargetFps,wireQuality:t.wirelessEffectiveQuality,hardwareCanvas:t.hardwareCanvas,renderError:t.renderError,vr:s.vrEnabled};}
(async()=>{const initial=await status(),results=[];assert(initial.vrEnabled,'enter binocular mode first');
 try {
  for(const [body,name] of [[{phoneRenderWidth:99999},'invalid phone size'],[{phoneFps:1000},'invalid phone fps'],[{wirelessAdaptive:'yes'},'invalid adaptive type']])assert.equal((await api('/layout',body)).code,400,name);
  for(const body of [{transportMs:-1},{transportMs:'bad'},{transportMs:1,extra:1}])assert.equal((await api('/network-feedback',body)).code,400);
  for(const width of initial.stereo.phoneResolutionOptions){await set('/layout',{phoneRenderWidth:width,phoneFps:0});await sleep(3500);const s=await status(),r=sample(s);const expected=width||s.stereo.phoneWidth;assert.equal(r.render[0],expected);assert.equal(r.render[1],Math.round(expected*s.stereo.phoneHeight/s.stereo.phoneWidth/2)*2);assert(r.phoneDrawFps>10);assert(r.hardwareCanvas && !r.renderError && r.vr);results.push({test:'real phone buffer',requested:width,...r});console.log(JSON.stringify(results.at(-1)));}
  await set('/layout',{phoneFps:15});await sleep(4000);let s=await status();assert(s.stereo.phoneDisplayFps>10&&s.stereo.phoneDisplayFps<17);results.push({test:'15 fps limit',...sample(s)});
  await set('/layout',{phoneFps:0,phoneRenderWidth:0,outputWidth:1280,wirelessFps:20,wirelessQuality:80,wirelessAdaptive:true});
  await set('/network-feedback',{transportMs:400});await sleep(1800);await set('/network-feedback',{transportMs:400});s=await status();assert(s.stereo.wirelessAdaptiveLevel>0);assert(s.stereo.width<1280);assert(s.stereo.wirelessTargetFps<20);assert.equal(s.stereo.renderWidth,s.stereo.phoneWidth);results.push({test:'wireless degradation preserves native phone buffer',...sample(s)});
  await set('/layout',{wirelessAdaptive:false});s=await status();assert.equal(s.stereo.width,1280);assert.equal(s.stereo.wirelessTargetFps,20);results.push({test:'manual wireless cap',...sample(s)});
  for(const width of [1280,1920]){await set('/camera',{maxWidth:width,fps:0});await sleep(5000);s=await status();assert(s.stereo.localFps>10);assert(s.stereo.ageMs<500);results.push({test:'capture size and native display',requestedCapture:width,...sample(s)});}
 } finally {await set('/layout',initial.stereo.settings);await set('/camera',{fps:initial.settings.fps,maxWidth:initial.settings.maxWidth});}
 const report={time:new Date().toISOString(),transport:'direct Wi-Fi; no ADB forward',checks:results};fs.writeFileSync(output,JSON.stringify(report,null,2));console.log('All v0.6 checks passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
