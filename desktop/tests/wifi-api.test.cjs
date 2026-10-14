// Real-device protocol checks. No camera images or access keys are written to reports.
'use strict';
const http=require('node:http'),assert=require('node:assert/strict'),fs=require('node:fs');
const url=new URL(process.argv[2]),key=url.hash.slice(1),report=process.argv[3];
assert.equal(url.protocol,'http:');assert.equal(url.port,'18766');assert.match(key,/^[a-f0-9]{32}$/);
function request(path,body,headers={}){return new Promise((resolve,reject)=>{const text=body===undefined?undefined:JSON.stringify(body);const q=http.request(new URL(path,url),{method:text===undefined?'GET':'POST',headers:{'X-HearLens-Key':key,...(text===undefined?{}:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(text)}),...headers},timeout:5000},r=>{const chunks=[];r.on('data',c=>chunks.push(c));r.on('end',()=>resolve({code:r.statusCode,headers:r.headers,body:Buffer.concat(chunks)}));});q.on('error',reject);q.on('timeout',()=>q.destroy(Error('timeout')));q.end(text);});}
(async()=>{
 const checks=[];async function check(name,path,body,expected,headers){console.log(name);const r=await request(path,body,headers);assert.equal(r.code,expected,name);checks.push({name,status:r.code});return r;}
 await check('Status rejects missing key','/status',undefined,403,{'X-HearLens-Key':''});
 await check('Frame rejects wrong key','/frame.jpg',undefined,403,{'X-HearLens-Key':'0'.repeat(32)});
 await check('Reject cross-origin caption','/subtitle',{text:'not applied'},403,{Origin:'https://untrusted.example'});
 await check('Reject 121-character caption','/subtitle',{text:'字'.repeat(121)},400);
 await check('Reject non-string caption','/subtitle',{text:123},400);
 await check('Reject extra caption keys','/subtitle',{text:'not applied',extra:true},400);
 await check('Reject unknown layout key','/layout',{invalid:true},400);
 await check('Reject oversized output','/layout',{outputWidth:999999},400);
 await check('Reject zero wireless FPS','/layout',{wirelessFps:0},400);
 await check('Reject float instead of integer','/layout',{scale:99.5},400);
 await check('Reject nonexistent lens','/camera',{cameraId:'not-a-camera'},400);
 await check('Reject huge zoom','/camera',{zoomRatio:1e20},400);
 await check('Reject unknown camera field','/camera',{unknown:1},400);
 try { await check('Reject oversized request','/subtitle',{text:'x'.repeat(17000)},413); } catch(e) { if(e.code!=='ECONNRESET')throw e; checks.push({name:'Reject oversized request',status:'connection closed before accepting oversized body'}); }
 const first=JSON.parse((await check('Authenticated status','/status',undefined,200)).body);
 assert.equal(first.transport,'phone-local-wifi');assert(first.stereo.localFps>0);
 const frame=await check('Latest binocular JPEG','/frame.jpg?after=-1',undefined,200);
 assert.equal(frame.body[0],255);assert.equal(frame.body[1],216);assert.equal(frame.body.at(-2),255);assert.equal(frame.body.at(-1),217);
 const id=Number(frame.headers['x-frame-id']);assert(Number.isInteger(id)&&id>0);
 const next=await check('Subsequent fresh JPEG','/frame.jpg?after='+id,undefined,200);assert(Number(next.headers['x-frame-id'])>id);
 const result={time:new Date().toISOString(),transport:'direct Wi-Fi LAN HTTP; no ADB forwarding',checks,cameraIds:first.cameras.map(c=>c.id),localFps:first.stereo.localFps,output:[first.stereo.width,first.stereo.height]};
 if(report)fs.writeFileSync(report,JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
