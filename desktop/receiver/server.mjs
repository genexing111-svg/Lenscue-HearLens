import http from 'node:http';
import crypto from 'node:crypto';
import {readFile,stat} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {PhoneBridge,limitedBody} from './bridge.mjs';
import {SecretStore} from './secret-store.mjs';
import {discoverPhone} from './adb-discovery.mjs';

export const ROOT=path.dirname(fileURLToPath(import.meta.url));
const apkCandidates=[path.resolve(ROOT,'downloads/听见视界-v0.14.1-环境音辉光连续图传修复.apk'),path.resolve(ROOT,'../../dist/0.14.1-Glow-Stream-Fix/听见视界-v0.14.1-环境音辉光连续图传修复.apk')];
async function body(req){let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>20000)throw Error('请求过大');chunks.push(chunk);}return JSON.parse(Buffer.concat(chunks).toString('utf8'));}
export function createReceiver(bridge,{port=18780,onQuit=()=>{}}={}){
  const csrf=crypto.randomBytes(24).toString('hex'),instance=crypto.randomUUID();
  const assets=new Map([['/','index.html'],['/app.js','app.js'],['/styles.css','styles.css'],['/catalog.mjs','catalog.mjs']]);
  const server=http.createServer(async(req,res)=>{
    const host=req.headers.host,origin=req.headers.origin;
    const boundPort=server.address()?.port||port;
    const local=new Set([`127.0.0.1:${boundPort}`,`localhost:${boundPort}`]);
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    const json=(code,data)=>{res.writeHead(code,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(data));};
    let originAllowed=!origin;try{if(origin)originAllowed=new URL(origin).protocol==='http:'&&local.has(new URL(origin).host);}catch{}
    if(!local.has(host)||!originAllowed||req.headers['sec-fetch-site']==='cross-site'){json(403,{error:'仅允许本机控制台访问'});return;}
    try{
      const url=new URL(req.url,'http://'+host),route=url.pathname;
      if(req.method==='GET'&&assets.has(route)){const file=assets.get(route);res.setHeader('Content-Type',file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'text/javascript; charset=utf-8');res.end(await readFile(path.join(ROOT,'public',file)));return;}
      if(req.method==='GET'&&route==='/api/health'){json(200,{service:'HearLensDesktop',version:'1.0.2',instance,pid:process.pid});return;}
      if(req.method==='GET'&&route==='/api/session'){json(200,{csrf,instance});return;}
      if(req.method==='GET'&&route==='/api/state'){json(200,{...bridge.snapshot(),instance});return;}
      if(req.method==='GET'&&route==='/api/frame'){
        const n=Number(url.searchParams.get('after')??'-1');if(!Number.isSafeInteger(n))throw Error('无效帧编号');
        const f=await bridge.nextFrame(n);if(!f){res.writeHead(204);res.end();return;}
        res.writeHead(200,{'Content-Type':'image/jpeg','Content-Length':f.jpeg.length,'X-Frame-Sequence':f.sequence,'X-Frame-Age-Ms':Date.now()-f.receivedAt+f.age,'X-Transport-Ms':f.transportMs});res.end(f.jpeg);return;
      }
      if(req.method==='GET'&&route==='/download/phone.apk'){
        for(const file of apkCandidates){try{const info=await stat(file);res.writeHead(200,{'Content-Type':'application/vnd.android.package-archive','Content-Length':info.size,'Content-Disposition':"attachment; filename=HearLens-v0.14.1-glow-stream-fix.apk"});const stream=createReadStream(file);res.on('close',()=>stream.destroy());stream.on('error',()=>res.destroy());stream.pipe(res);return;}catch{}}
        json(404,{error:'手机配套安装包尚未准备完成'});return;
      }
      if(req.method!=='POST'){json(404,{error:'不存在的接口'});return;}
      if(req.headers['x-hearlens-csrf']!==csrf){json(403,{error:'请刷新本机页面后重试'});return;}
      const value=await body(req);
      if(route==='/api/connect'){await bridge.connect(value.url);json(200,{ok:true});return;}
      if(route==='/api/disconnect'){bridge.disconnect();json(200,{ok:true});return;}
      if(route==='/api/reconnect'){if(bridge.connection){bridge.after=-1;bridge.frame=null;await bridge.poll();}else await bridge.restore();json(200,{ok:true});return;}
      if(route==='/api/discover'){const found=await discoverPhone();if(!found)throw Error('未发现唯一已授权的 USB 手机；也可粘贴无线链接');await bridge.connect(found.url);json(200,{ok:true});return;}
      if(route==='/api/command'){json(200,await bridge.command(value.path,value.body));return;}
      if(route==='/api/feedback'){bridge.feedback(Number(value.transportMs));json(200,{ok:true});return;}
      if(route==='/api/quit'){json(200,{ok:true});setTimeout(onQuit,100);return;}
      json(404,{error:'不存在的接口'});
    }catch(e){if(!res.headersSent)json(400,{error:e.message?.slice(0,240)||'操作失败'});else res.destroy();}
  });
  server.requestTimeout=8000;server.headersTimeout=6000;server.keepAliveTimeout=3000;
  return server;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const port=Number(process.env.HEARLENS_PORT||18780);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('端口无效');
  const bridge=new PhoneBridge({store:new SecretStore(path.join(ROOT,'.runtime'))});
  let timer;const server=createReceiver(bridge,{port,onQuit:()=>{clearInterval(timer);bridge.stop();server.closeAllConnections();server.close(()=>process.exit(0));}});
  server.on('error',e=>{console.error('接收端未能启动：'+e.code);process.exitCode=1;bridge.stop();clearInterval(timer);});
  server.listen(port,'127.0.0.1',async()=>{
    console.log('听见视界接收端已运行 http://127.0.0.1:'+port);await bridge.start();
    let checking=false;async function discover(){if(checking||bridge.state==='connected'||bridge.discoveryPaused)return;checking=true;try{const found=await discoverPhone();if(found&&(bridge.connection?.base!==new URL(found.url).origin||bridge.connection?.key!==new URL(found.url).hash.slice(1)))await bridge.connect(found.url);}catch{}finally{checking=false;}}
    discover();timer=setInterval(discover,12000);
  });
}
