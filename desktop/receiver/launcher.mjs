import {spawn,execFile} from 'node:child_process';
import {mkdirSync,openSync,closeSync,existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url)),base='http://127.0.0.1:18780';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function health(){const r=await fetch(base+'/api/health',{signal:AbortSignal.timeout(1000)});const j=await r.json();if(j.service!=='HearLensDesktop')throw Error('端口 18780 已被其他程序使用');return j;}
const exec=(file,args)=>new Promise((resolve,reject)=>execFile(file,args,{windowsHide:true,timeout:90000,maxBuffer:300000},(error,out)=>error?reject(Error(out||error.message)):resolve(out)));
async function start(){
  let ready=false;try{await health();ready=true;}catch(e){if(e.message.includes('其他程序'))throw e;}
  if(!ready){mkdirSync(path.join(root,'.runtime'),{recursive:true});const out=openSync(path.join(root,'.runtime/service.log'),'a'),err=openSync(path.join(root,'.runtime/service-error.log'),'a');
    const child=spawn(process.execPath,[path.join(root,'server.mjs')],{cwd:root,windowsHide:true,detached:true,stdio:['ignore',out,err]});child.unref();closeSync(out);closeSync(err);
    for(let i=0;i<32;i++){try{await health();ready=true;break;}catch{}await delay(200);}
    if(!ready)throw Error('接收端未能启动，请查看 .runtime/service-error.log');
  }
  console.log('听见视界电脑接收端已运行：'+base);
  if(!process.argv.includes('--no-browser')){const opener=spawn('rundll32.exe',['url.dll,FileProtocolHandler',base],{windowsHide:true,detached:true,stdio:'ignore'});opener.unref();}
}
async function stop(){await health();const session=await (await fetch(base+'/api/session')).json();const r=await fetch(base+'/api/quit',{method:'POST',headers:{'Content-Type':'application/json','X-HearLens-CSRF':session.csrf},body:'{}'});if(!r.ok)throw Error('停止请求未通过');console.log('电脑接收服务已停止。');}
async function install(){const {adbPath}=await import('./adb-discovery.mjs');if(!adbPath)throw Error('未找到 ADB，可从控制台下载 APK 后在手机安装');const devices=(await exec(adbPath,['devices'])).split(/\r?\n/).map(x=>x.match(/^([^\s]+)\s+device$/)?.[1]).filter(Boolean);if(devices.length!==1)throw Error('请连接一台已授权 USB 调试的手机并解锁');
  const apk=[path.resolve(root,'downloads/听见视界-v0.14.1-环境音辉光连续图传修复.apk'),path.resolve(root,'../../dist/0.14.1-Glow-Stream-Fix/听见视界-v0.14.1-环境音辉光连续图传修复.apk')].find(existsSync);if(!apk)throw Error('配套安装包未找到');
  console.log(await exec(adbPath,['-s',devices[0],'install','-r',apk]));console.log(await exec(adbPath,['-s',devices[0],'shell','am','start','-n','com.hearlens.app/.MainActivity']));
}
try{const action=process.argv[2]||'start';if(action==='start')await start();else if(action==='stop')await stop();else if(action==='install')await install();else throw Error('未知操作');}catch(e){console.error(e.message);process.exitCode=1;}
