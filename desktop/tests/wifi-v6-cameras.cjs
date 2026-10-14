'use strict';
const http=require('node:http'),fs=require('node:fs'),assert=require('node:assert/strict');
const url=new URL(process.argv[2]),key=url.hash.slice(1),file=process.argv[3],results=[];
function api(path,body){return new Promise((resolve,reject)=>{const b=body===undefined?null:JSON.stringify(body),q=http.request(new URL(path,url),{method:b?'POST':'GET',headers:{'X-HearLens-Key':key,...(b?{'Content-Type':'application/json','Content-Length':Buffer.byteLength(b)}:{})},timeout:5000},r=>{let data='';r.setEncoding('utf8');r.on('data',d=>data+=d);r.on('error',reject);r.on('end',()=>r.statusCode===200?resolve(JSON.parse(data)):reject(Error('HTTP '+r.statusCode)));});q.on('error',reject);q.on('timeout',()=>q.destroy(Error('timeout')));q.end(b);});}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{const original=await api('/status'),ids=original.cameras.map(c=>c.id);try {
 for(const width of [1280,1920]) {
  await api('/camera',{maxWidth:width,fps:0});await sleep(2000);
  for(let i=0;i<12;i++) {const id=ids[(i+1)%ids.length],before=await api('/status'),start=performance.now();await api('/camera',{cameraId:id});let s;
   while(performance.now()-start<15000){await sleep(250);s=await api('/status');if(s.selectedCameraId===id&&s.stereo.frameId>before.stereo.frameId&&s.stereo.ageMs>=0&&s.stereo.ageMs<300)break;}
   const pass=s.selectedCameraId===id&&s.stereo.frameId>before.stereo.frameId&&s.stereo.ageMs>=0&&s.stereo.ageMs<300&&s.vrEnabled;
   const row={captureWidth:width,iteration:i+1,id,pass,seconds:(performance.now()-start)/1000,source:[s.stereo.sourceWidth,s.stereo.sourceHeight],activePhysical:s.activePhysicalCameraId,fpsRange:s.capabilities.selectedFpsRange};results.push(row);console.log(JSON.stringify(row));assert(pass,'lens did not recover');await sleep(700);
  }
 }
 } finally {await api('/camera',{cameraId:original.selectedCameraId});await sleep(1600);await api('/camera',{fps:original.settings.fps,maxWidth:original.settings.maxWidth});fs.writeFileSync(file,JSON.stringify({time:new Date().toISOString(),transport:'direct Wi-Fi LAN',results},null,2));}
})().catch(e=>{console.error(e);process.exitCode=1;});
