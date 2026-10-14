import {performance} from 'node:perf_hooks';

export function parseConnection(text,{allowLoopback=false}={}){
  if(typeof text!=='string'||text.length>512)throw Error('请粘贴手机显示的完整观看链接');
  let u;try{u=new URL(text.trim());}catch{throw Error('连接地址格式不正确');}
  const p=u.hostname.split('.').map(Number),ipv4=p.length===4&&p.every(n=>Number.isInteger(n)&&n>=0&&n<=255);
  const privateAddress=ipv4&&(p[0]===10||p[0]===192&&p[1]===168||p[0]===172&&p[1]>=16&&p[1]<=31);
  const test=allowLoopback&&u.hostname==='127.0.0.1';
  if(u.protocol!=='http:'||(!privateAddress&&!test)||(!test&&u.port!=='18766')||u.username||u.password||u.pathname!=='/'||u.search||!/^#[0-9a-f]{32}$/i.test(u.hash))throw Error('需要局域网手机链接：http://手机IP:18766/#访问密钥');
  return {base:u.origin,key:u.hash.slice(1)};
}
export async function limitedBody(response,limit){const reader=response.body?.getReader();if(!reader)return Buffer.alloc(0);let size=0;const chunks=[];try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>limit)throw Error('手机返回的数据过大');chunks.push(Buffer.from(value));}}finally{await reader.cancel().catch(()=>{});}return Buffer.concat(chunks);}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const allowed=new Set(['/layout','/camera','/subtitle','/focus','/view','/remote-calibration','/network-feedback','/control']);
export class PhoneBridge {
  constructor({store=null,allowLoopback=false,fetcher=fetch}={}){
    this.store=store;this.options={allowLoopback};this.fetcher=fetcher;this.connection=null;this.phone=null;this.control=null;this.state='unconfigured';this.message='添加手机观看链接，开始接收双眼画面';
    this.frame=null;this.sequence=0;this.after=-1;this.generation=0;this.running=false;this.demandUntil=0;this.lastStatus=0;this.events=[];this.received=0;this.receipts=[];this.failures=0;this.lastFeedback=0;this.serial=Promise.resolve();this.listeners=new Set();this.commandPending=0;
  }
  event(message){this.events.push({at:new Date().toISOString(),message});this.events=this.events.slice(-40);}
  async restore(){if(this.store){try{const saved=await this.store.load();if(saved)await this.connect(saved.url,{save:false});}catch(e){this.message=e.message;this.event(e.message);}}}
  async connect(url,{save=true}={}){const next=parseConnection(url,this.options);if(save&&this.store)await this.store.save(next);this.discoveryPaused=false;this.generation++;this.connection=next;this.phone=null;this.control=null;this.frame=null;this.after=-1;this.state='connecting';this.message='正在连接手机';this.failures=0;this.lastStatus=0;this.event('连接 '+next.base);this.wake();return this.snapshot();}
  disconnect(){this.discoveryPaused=true;this.generation++;this.connection=null;this.phone=null;this.control=null;this.frame=null;this.state='unconfigured';this.message='已断开，手机本地观看不受影响';this.wake();}
  demand(){this.demandUntil=Date.now()+2500;}
  wake(){for(const f of this.listeners)f();this.listeners.clear();}
  async request(path,body,connection=this.connection){
    if(!connection)throw Error('尚未连接手机');
    const response=await this.fetcher(connection.base+path,{method:body===undefined?'GET':'POST',redirect:'error',signal:AbortSignal.timeout(2800),headers:{'X-HearLens-Key':connection.key,...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body)});
    if(!response.ok&&response.status!==204){const message=(await limitedBody(response,20000)).toString('utf8');if(response.status===403)throw Error('手机访问密钥不匹配，请重新复制完整观看链接');if(response.status===404&&path==='/control')throw Error('请安装 v0.14.0 配套手机端以启用全部设置');throw Error(message.slice(0,200)||'手机请求失败（'+response.status+'）');}
    return response;
  }
  async poll(){
    if(!this.connection)return;const generation=this.generation;
    try{
      const response=await this.request('/status'),phone=JSON.parse((await limitedBody(response,1_000_000)).toString('utf8'));
      if(generation!==this.generation)return;
      if(!phone.stereo||!phone.stereo.settings)throw Error('此设备没有提供听见视界双眼状态');
      if(this.phone?.sessionId!==phone.sessionId||Number(phone.stereo.frameId)<this.after)this.after=-1;
      this.phone=phone;this.lastStatus=Date.now();this.failures=0;
      if(this.state!=='connected')this.event('手机已连接 · '+(phone.appVersion||'兼容接口'));
      this.state='connected';this.message=phone.state==='streaming'?'正在接收手机双眼画面':'手机已连接，等待相机画面';
      if(phone.controlVersion>=1){try{const r=await this.request('/control');const c=JSON.parse((await limitedBody(r,256000)).toString('utf8'));if(generation===this.generation)this.control=c;}catch(e){if(generation===this.generation)this.message='画面已连接；设置同步：'+e.message;}}
      else this.control=null;
    }catch(e){if(generation!==this.generation)return;this.failures++;this.state='reconnecting';this.message=e.message?.includes('密钥')?e.message:'手机暂不可达，正在自动重连';this.frame=null;if(this.failures===1)this.event(this.message);}
  }
  async start(){if(this.running)return;this.running=true;await this.restore();this.statusLoop();this.frameLoop();}
  async statusLoop(){while(this.running){if(this.connection)await this.poll();await sleep(this.state==='connected'?1400:Math.min(5000,700+this.failures*500));}}
  async frameLoop(){while(this.running){
    if(!this.connection||this.state!=='connected'||Date.now()>this.demandUntil){await sleep(100);continue;}
    const generation=this.generation,begin=performance.now();
    try{const response=await this.request('/frame.jpg?after='+this.after);if(response.status===204){await sleep(20);continue;}
      const jpeg=await limitedBody(response,8*1024*1024);if(generation!==this.generation)continue;
      const id=Number(response.headers.get('x-frame-id'));if(!Number.isSafeInteger(id)||id<0||jpeg[0]!==255||jpeg[1]!==216)throw Error('视频帧数据无效');
      const age=Number(response.headers.get('x-frame-age-ms')||0);if(!Number.isFinite(age)||age>1500){this.frame=null;continue;}
      this.after=id;this.received++;this.receipts.push(Date.now());this.receipts=this.receipts.filter(t=>Date.now()-t<3000);
      this.frame={jpeg,sequence:++this.sequence,receivedAt:Date.now(),age,phoneId:id,transportMs:Math.max(0,performance.now()-begin-Number(response.headers.get('x-wait-ms')||0))};this.wake();
    }catch{if(generation===this.generation)this.frame=null;await sleep(400);}
  }}
  async nextFrame(after){this.demand();if(!this.frame||this.frame.sequence<=after)await new Promise(resolve=>{const timer=setTimeout(()=>{this.listeners.delete(wake);resolve();},900);const wake=()=>{clearTimeout(timer);resolve();};this.listeners.add(wake);});const f=this.frame;return f&&f.sequence>after&&Date.now()-f.receivedAt+f.age<1600?f:null;}
  async command(path,body){
    if(!allowed.has(path)||!body||Array.isArray(body)||typeof body!=='object')throw Error('不支持的手机操作');
    if(path==='/control'&&!(this.phone?.controlVersion>=1))throw Error('该设置需要配套 v0.14.0 手机端');
    if(this.commandPending>=12)throw Error('操作较多，请等当前设置完成');
    const generation=this.generation;this.commandPending++;
    const task=async()=>{if(generation!==this.generation||!this.connection)throw Error('连接已更改，请重新操作');const r=await this.request(path,body);const data=JSON.parse((await limitedBody(r,256000)).toString('utf8'));if(generation===this.generation&&data.control)this.control=data.control;return data;};
    const result=this.serial.then(task,task);this.serial=result.catch(()=>{});
    try{return await result;}finally{this.commandPending--;}
  }
  feedback(ms){if(Date.now()-this.lastFeedback<3000||!this.connection||!Number.isFinite(ms))return;this.lastFeedback=Date.now();this.request('/network-feedback',{transportMs:Math.max(0,Math.min(10000,ms))}).catch(()=>{});}
  snapshot(){const f=this.frame,age=f?Date.now()-f.receivedAt+f.age:null;return {
    connection:{state:this.state,address:this.connection?.base||'',message:this.message,lastStatusAt:this.lastStatus,fullControl:!!this.control,phoneVersion:this.phone?.appVersion||null},
    phone:this.phone,control:this.control,metrics:{received:this.received,receiveFps:this.receipts.filter(t=>Date.now()-t<3000).length/3,frameAgeMs:age,transportMs:f?.transportMs??null},
    events:this.events,frame:{sequence:f?.sequence||0,fresh:age!==null&&age<1600}
  };}
  stop(){this.running=false;this.generation++;this.connection=null;this.frame=null;this.wake();}
}
