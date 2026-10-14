import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {readFile} from 'node:fs/promises';
import {PhoneBridge,parseConnection} from '../bridge.mjs';
import {createReceiver} from '../server.mjs';

const key='1'.repeat(32),pause=ms=>new Promise(r=>setTimeout(r,ms));
async function phoneFixture(){
  let frame=0,activeFrames=0,maxFrames=0;const commands=[];
  const jpeg=await readFile(new URL('../../../app/src/androidTest/assets/glow/dark-cafe.jpg',import.meta.url)).catch(()=>Buffer.from([255,216,255,217]));
  const state={controlVersion:1,appVersion:'0.14.0',sessionId:'one',state:'streaming',settings:{fps:30},cameras:[],stereo:{settings:{scale:90},frameId:1,width:960,height:432,phoneDisplayFps:30}};
  const server=http.createServer(async(req,res)=>{
    if(req.headers['x-hearlens-key']!==key){res.writeHead(403);res.end();return;}
    if(req.url.startsWith('/frame.jpg')){activeFrames++;maxFrames=Math.max(maxFrames,activeFrames);await pause(35);res.writeHead(200,{'Content-Type':'image/jpeg','X-Frame-Id':++frame,'X-Frame-Age-Ms':'0'});res.end(jpeg);activeFrames--;return;}
    if(req.url==='/status'){state.stereo.frameId=frame;res.setHeader('Content-Type','application/json');res.end(JSON.stringify(state));return;}
    if(req.url==='/control'&&req.method==='GET'){res.end(JSON.stringify({version:1,captions:{choice:2}}));return;}
    let body='';for await(const chunk of req)body+=chunk;commands.push({path:req.url,body:JSON.parse(body)});res.end('{"ok":true}');
  });server.listen(0,'127.0.0.1');await once(server,'listening');
  return {server,state,commands,url:`http://127.0.0.1:${server.address().port}/#${key}`,maxFrames:()=>maxFrames,close(){server.closeAllConnections();server.close();}};
}
test('LAN link validation rejects credentials, foreign hosts, wrong ports and URL paths',()=>{
  assert.equal(parseConnection('http://192.168.1.8:18766/#'+key).base,'http://192.168.1.8:18766');
  for(const link of ['http://example.com:18766/#'+key,'http://127.0.0.1:18766/#'+key,'http://169.254.169.254:18766/#'+key,'http://10.0.0.1:80/#'+key,'http://10.0.0.1:18766/private#'+key,'http://user:pass@10.0.0.1:18766/#'+key,'http://10.0.0.1:18766/#short'])assert.throws(()=>parseConnection(link));
});
test('one phone frame reader serves multiple viewers, commands forward and snapshots never expose key',async()=>{
  const phone=await phoneFixture(),bridge=new PhoneBridge({allowLoopback:true});
  try{await bridge.connect(phone.url);await bridge.start();await bridge.poll();const results=await Promise.all([bridge.nextFrame(-1),bridge.nextFrame(-1),bridge.nextFrame(-1)]);assert(results.every(Boolean));assert.equal(phone.maxFrames(),1);assert.equal(results[0].sequence,results[1].sequence);
    await bridge.command('/control',{section:'captions',values:{choice:0}});assert.deepEqual(phone.commands.at(-1),{path:'/control',body:{section:'captions',values:{choice:0}}});assert(!JSON.stringify(bridge.snapshot()).includes(key));
    bridge.frame.receivedAt-=2000;assert.equal(await bridge.nextFrame(bridge.sequence+1000),null);bridge.disconnect();assert.equal(bridge.snapshot().connection.state,'unconfigured');assert.equal(bridge.discoveryPaused,true);
  }finally{bridge.stop();phone.close();}
});
test('restarting phone frame ids recover and disconnect rejects queued setting mutations',async()=>{
  const phone=await phoneFixture(),bridge=new PhoneBridge({allowLoopback:true});
  try{await bridge.connect(phone.url);await bridge.poll();bridge.after=999;phone.state.sessionId='two';await bridge.poll();assert.equal(bridge.after,-1);bridge.disconnect();await assert.rejects(()=>bridge.command('/layout',{scale:85}));}
  finally{bridge.stop();phone.close();}
});
test('legacy phones remain viewable but extended controls cannot falsely succeed',async()=>{
  const phone=await phoneFixture(),bridge=new PhoneBridge({allowLoopback:true});phone.state.controlVersion=0;
  try{await bridge.connect(phone.url);await bridge.poll();assert.equal(bridge.state,'connected');assert.equal(bridge.control,null);await assert.rejects(()=>bridge.command('/control',{section:'captions',values:{choice:0}}));await bridge.command('/layout',{scale:80});assert.equal(phone.commands.at(-1).path,'/layout');}
  finally{bridge.stop();phone.close();}
});
test('local server protects writes with origin/host and CSRF, refuses malformed origins, serves UI',async()=>{
  const bridge=new PhoneBridge(),server=createReceiver(bridge,{port:0});server.listen(0,'127.0.0.1');await once(server,'listening');const base='http://127.0.0.1:'+server.address().port;
  try{assert.equal((await fetch(base)).status,200);const session=await (await fetch(base+'/api/session')).json();
    assert.equal((await fetch(base+'/api/disconnect',{method:'POST',body:'{}'})).status,403);
    assert.equal((await fetch(base+'/api/state',{headers:{Origin:'not a url'}})).status,403);
    assert.equal((await fetch(base+'/api/disconnect',{method:'POST',headers:{'X-HearLens-CSRF':session.csrf,Origin:'https://foreign.example'},body:'{}'})).status,403);
    assert.equal((await fetch(base+'/api/disconnect',{method:'POST',headers:{'X-HearLens-CSRF':session.csrf},body:'{}'})).status,200);
    assert.equal((await fetch(base+'/private-files')).status,404);assert.equal((await fetch(base+'/api/health')).status,200);
  }finally{bridge.stop();server.closeAllConnections();server.close();}
});
