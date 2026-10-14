import {tabs,forms,pathValue,typedValue} from './catalog.mjs';
const $=id=>document.getElementById(id),ctx=$('video').getContext('2d',{alpha:false});
let state=null,csrf='',activeTab='connection',after=-1,instance='',lastPaint=0,viewMode=0,stopped=false,toastTimer,lastFeedback=0;
const drafts=new Set(),draftValues=new Map(),pending=new Map(),formNodes=new Map();let lease=null,held=false,startPromise=null,heartbeat;
const iconPaths={link:'M10 13a5 5 0 007 0l3-3a5 5 0 00-7-7l-2 2M14 11a5 5 0 00-7 0l-3 3a5 5 0 007 7l2-2',camera:'M4 7h3l2-3h6l2 3h3v13H4zM16 13a4 4 0 11-8 0 4 4 0 018 0',eyes:'M2 7h8v10H2zM14 7h8v10h-8zM10 11h4',text:'M4 5h16M12 5v15M7 20h10',sound:'M3 10v4M7 7v10M11 3v18M15 7v10M19 5v14M23 10v4',compass:'M21 12a9 9 0 11-18 0 9 9 0 0118 0M16 8l-3 6-5 2 3-6z',mic:'M9 3h6v11H9zM5 10v3a7 7 0 0014 0v-3M12 20v3M8 23h8',remote:'M5 6h14l3 13h-5l-3-4h-4l-3 4H2zM7 9v5M4.5 11.5h5M16 10h.1M19 13h.1',chart:'M4 3v18h17M8 16l4-5 4 2 5-7'};
function node(tag,cls,text){const el=document.createElement(tag);if(cls)el.className=cls;if(text!==undefined)el.textContent=text;return el;}
function button(label,fn,{secondary=true,extended=false,online=true,cls=''}={}){const b=node('button','button '+(secondary?'ghost ':'')+cls,label);b.type='button';b.dataset.online=String(online);if(extended)b.dataset.extended='true';b.addEventListener('click',()=>action(fn));return b;}
function notice(message,error=false){$('toast').textContent=message;$('toast').className=error?'error':'';$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,error?6500:3300);}
async function api(route,body){const r=await fetch(route,{method:body===undefined?'GET':'POST',cache:'no-store',headers:body===undefined?{}:{'Content-Type':'application/json','X-HearLens-CSRF':csrf},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(11000)});const data=await r.json();if(!r.ok)throw Error(data.error||'操作失败');return data;}
async function command(path,body){return api('/api/command',{path,body});}
const control=(section,values)=>command('/control',{section,values});
async function action(fn){try{await fn();notice('操作已提交');await refresh();}catch(e){notice(e.message,true);}}
function section(title,note){const s=node('section','section');if(title)s.append(node('h3','',title));if(note)s.append(node('p','note',note));$('controls').append(s);return s;}
function row(parent,...buttons){const r=node('div','button-row');r.append(...buttons);parent.append(r);}
function selectField(parent,label,options,id){const box=node('label','field'),select=node('select');if(id)select.id=id;box.append(node('span','field-label',label),select);for(const [v,text] of options)select.add(new Option(text,v));parent.append(box);return select;}
function genericForm(spec){
  const form=node('form','section');form.dataset.form=spec.id;if(spec.extended)form.dataset.extended='true';form.append(node('h3','',spec.title));if(spec.note)form.append(node('p','note',spec.note));const inputs=new Map();
  for(const field of spec.fields){
    const label=node('label','field'+(field.type==='boolean'?' toggle':''));label.append(node('span','field-label',field.label));
    const input=node(field.type==='select'?'select':'input');input.id=spec.id+'-'+field.key;input.name=field.key;label.htmlFor=input.id;
    if(field.type==='boolean'){input.type='checkbox';input.className='switch';}
    else if(field.type==='select'){input.add(new Option('连接后同步',''));for(const [value,text] of field.options||[])input.add(new Option(text,value));}
    else {input.type=field.type;if(field.min!==undefined){input.min=field.min;input.max=field.max;input.step=field.step;}input.placeholder=field.type==='password'?'留空保持原有 Key':'连接后同步';input.autocomplete='off';}
    if(field.type==='password')input.dataset.secret='true';if(field.key==='workspace')input.placeholder='默认北京入口或 llm-…';
    if(!field.optional&&field.type!=='boolean'&&field.key!=='workspace')input.required=true;
    input.addEventListener('input',()=>{drafts.add(spec.id);if(field.type!=='password')draftValues.set(spec.id,{...draftValues.get(spec.id),[field.key]:field.type==='boolean'?input.checked:input.value});pending.delete(spec.id);status.textContent='未应用';status.classList.add('pending');});label.append(input);form.append(label);inputs.set(field.key,input);
  }
  const actions=node('div','button-row'),submit=node('button','button','应用到手机'),status=node('span','form-status','等待同步');submit.type='submit';actions.append(submit,status);form.append(actions);
  form.addEventListener('submit',async e=>{
    e.preventDefault();if(!form.reportValidity())return;const values={};
    for(const field of spec.fields){const input=inputs.get(field.key);if(field.optional&&!input.value)continue;values[field.key]=typedValue(field,field.type==='boolean'?input.checked:input.value);}
    submit.disabled=true;try{
      await command(spec.path,spec.section?{section:spec.section,values}:values);
      const expected={...values};delete expected.key;for(const input of inputs.values())if(input.dataset.secret)input.value='';draftValues.set(spec.id,{...expected});
      pending.set(spec.id,{expected,at:Date.now()});drafts.add(spec.id);status.textContent='等待手机确认';notice('设置已发送');await refresh();
    }catch(e){notice(e.message,true);status.textContent='未应用';}finally{updateAvailability();}
  });
  $('controls').append(form);formNodes.set(spec.id,{spec,form,inputs,status});
}
function connectPanel(){
  const s=section('接入手机','电脑和手机连接同一 Wi-Fi 或手机热点。手机开启无线共享后，复制完整观看链接。');
  const form=node('form'),label=node('label','field'),input=node('input');input.id='connectionUrl';input.type='password';input.autocomplete='off';input.placeholder='http://手机IP:18766/#访问密钥';input.required=true;label.append(node('span','field-label','手机完整观看链接'),input);form.append(label);const submit=node('button','button full-width','连接手机');submit.type='submit';form.append(submit);
  form.addEventListener('submit',async e=>{e.preventDefault();submit.disabled=true;try{await api('/api/connect',{url:input.value.trim()});input.value='';after=-1;notice('连接已保存，正在接收画面');await refresh();}catch(e){notice(e.message,true);}finally{submit.disabled=false;}});s.append(form);
  row(s,button('通过 USB 读取连接',()=>api('/api/discover',{}),{online:false}),button('断开电脑',()=>api('/api/disconnect',{}),{online:false}));
  s.append(node('p','note','观看链接只保存在本机加密配置中。USB 用于自动读取连接信息，视频仍通过局域网传输。'));
  const address=node('div','connection-address');address.id='connectionAddress';s.append(address);
  const guide=section('手机配套版本','v0.14.0 提供全部远程设置。旧版手机可继续接收视频、切换镜头和调整双眼画面。');
  const a=node('a','button ghost full-width','下载配套手机 APK');a.href='/download/phone.apk';a.style.display='block';a.style.textAlign='center';a.style.textDecoration='none';guide.append(a);
  guide.append(node('p','note','完整接入后可以在电脑控制字幕、环境音、声源校准和按住说话。系统权限仍需在手机允许。'));
}
function extras(tab){
  if(tab==='camera'){const s=section('对焦与查看','采集尺寸影响镜头细节；无线尺寸只影响电脑端画面。');row(s,button('中心对焦 / 测光',()=>command('/focus',{action:'center'})),button('手机进入双眼',()=>command('/view',{stereo:true})));}
  if(tab==='captions'){
    const s=section('发送手动文字','手机没有显示实时语音字幕时，手动文字会显示在双眼画面。最多 120 个字符。'),area=node('textarea');area.id='manualCaption';area.maxLength=120;area.placeholder='输入要在两眼显示的文字…';const label=node('label','field');label.append(node('span','field-label','文字内容'),area);s.append(label);
    row(s,button('发送到手机',()=>command('/subtitle',{text:area.value}),{secondary:false}),button('清空手动文字',()=>{area.value='';return command('/subtitle',{text:''});}));
    row(s,button('清空实时字幕',()=>control('captions',{action:'clear'}),{extended:true}),button('恢复字幕外观',()=>control('captions',{action:'resetStyle'}),{extended:true}));
  }
  if(tab==='environment'){const s=section('识别状态'),readout=node('div','readout');readout.id='environmentReadout';s.append(readout);s.append(node('p','note','声音名称来自手机本地模型，方向来自 USB 小板。语音重构期间暂时隐藏环境音，保留已有字幕。'));}
  if(tab==='direction'){
    const s=section('小板连接与校准'),readout=node('div','readout');readout.id='directionReadout';s.append(readout);const devices=selectField(s,'已连接 USB 设备',[],'usbDevices');devices.dataset.online='true';devices.dataset.extended='true';
    row(s,button('连接设备',()=>control('direction',{action:'connect',deviceName:devices.value}),{extended:true}),button('重试 USB 授权',()=>control('direction',{action:'retryPermission'}),{extended:true}),button('断开小板',()=>control('direction',{action:'disconnect'}),{extended:true}));
    row(s,button('当前设为正前方',()=>control('direction',{action:'calibrateFront'}),{extended:true}),button('左右互换',()=>control('direction',{action:'swapSides'}),{extended:true}));
    const c=section('左右方向采样','将声音放在指定一侧，持续发声 4 秒；只有稳定的新鲜角度才会保存。');row(c,button('从左侧校准',()=>control('direction',{action:'startSide',side:'LEFT'}),{extended:true}),button('从右侧校准',()=>control('direction',{action:'startSide',side:'RIGHT'}),{extended:true}),button('取消采样',()=>control('direction',{action:'cancelSide'}),{extended:true}));const detail=node('pre','diagnostics');detail.id='directionRaw';c.append(detail);
  }
  if(tab==='ptt'){
    const s=section('电脑按住说话','按住按钮让手机收音，松开后由手机播放。手机已显示的字幕会保留。');
    const b=node('button','button ptt-button','按住此处 · 让手机说话');b.id='holdToTalk';b.dataset.extended='true';b.dataset.online='true';
    b.addEventListener('pointerdown',e=>{if(e.button!==0)return;e.preventDefault();b.setPointerCapture(e.pointerId);startHold();});b.addEventListener('pointerup',()=>stopHold('end'));b.addEventListener('pointercancel',()=>stopHold('cancel'));b.addEventListener('lostpointercapture',()=>{if(held)stopHold('cancel');});
    b.addEventListener('keydown',e=>{if([' ','Enter'].includes(e.key)){e.preventDefault();if(!e.repeat)startHold();}});b.addEventListener('keyup',e=>{if([' ','Enter'].includes(e.key)){e.preventDefault();stopHold('end');}});s.append(b);
    const readout=node('p','note');readout.id='pttReadout';s.append(readout);row(s,button('清除手机 API Key',()=>control('speech',{action:'clearKey'}),{extended:true,cls:'danger'}));
  }
  if(tab==='remote'){
    const s=section('观看预设','预设只在点击后应用，不会在连接时覆盖你的参数。');for(const [name,label] of [['smooth','高清流畅'],['sharp','最高采集清晰度'],['comfortable','大字舒适'],['default','默认画面']])row(s,button(label,()=>control('preset',{name}),{extended:true}));
    const r=section('遥控手机设置');row(r,button('打开菜单',()=>control('remote',{action:'openMenu'}),{extended:true}),button('关闭菜单',()=>control('remote',{action:'closeMenu'}),{extended:true}),button('字幕语言',()=>control('remote',{action:'language'}),{extended:true}));
    const pad=node('div','dpad');for(const [a,label] of [['back','返回'],['up','↑'],['confirm','确认'],['left','←'],['down','↓'],['right','→']])pad.append(button(label,()=>control('remote',{action:a}),{extended:true}));r.append(pad);
    const l=section('实体遥控器学习','开始后请按手机画面的提示操作实体遥控器。');row(l,button('开始四键校准',()=>command('/remote-calibration',{start:true})),button('取消校准',()=>command('/remote-calibration',{start:false})));
    const mapping=selectField(l,'要学习的功能',[['confirm','A / 确认'],['back','B / 返回'],['language','X / 字幕语言'],['perception','Y / 环境音'],['shoulder','肩键 / 按住说话'],['up','上一项'],['down','下一项'],['left','向左'],['right','向右']],'mapping');
    row(l,button('学习单个按键',()=>control('remote',{action:'learn',mapping:mapping.value}),{extended:true}),button('恢复默认映射',()=>control('remote',{action:'reset'}),{extended:true,cls:'danger'}));
    const info=node('pre','diagnostics');info.id='remoteReadout';l.append(info);
  }
  if(tab==='diagnostics'){const s=section('当前运行快照','只显示手机和接收端当前状态，不自动保存画面或识别正文。'),pre=node('pre','diagnostics');pre.id='rawState';s.append(pre);}
}
function renderPanel(){formNodes.clear();$('controls').replaceChildren();$('panelTitle').textContent=tabs.find(([id])=>id===activeTab)[1];if(activeTab==='connection')connectPanel();if(['captions','direction','ptt'].includes(activeTab))extras(activeTab);for(const spec of forms[activeTab])genericForm(spec);if(!['connection','captions','direction','ptt'].includes(activeTab))extras(activeTab);updateView();}
for(const [id,title,icon] of tabs){const b=node('button','nav-item'+(id===activeTab?' active':''));b.type='button';b.title=title;b.dataset.tab=id;const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');svg.setAttribute('stroke-linecap','round');svg.setAttribute('stroke-linejoin','round');const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',iconPaths[icon]);svg.append(p);b.append(svg,node('span','',title));b.onclick=()=>{if(held)stopHold('cancel');activeTab=id;document.querySelectorAll('.nav-item').forEach(n=>n.classList.toggle('active',n===b));renderPanel();};$('navigation').append(b);}
function optionsFor(field){if(field.dynamic==='cameras')return (state?.phone?.cameras||[]).map(c=>[c.id,c.label]);if(field.dynamic==='resolutions')return (state?.phone?.stereo?.phoneResolutionOptions||[0,1280,1920]).map(v=>[v,v===0?'屏幕最高':String(v)]);return field.options;}
function syncForms(){for(const {spec,form,inputs,status} of formNodes.values()){
    const source=pathValue(state,spec.read);const waiting=pending.get(spec.id);
    if(waiting&&source){const matched=Object.entries(waiting.expected).every(([k,v])=>typeof v==='number'?Math.abs(Number(source[k])-v)<.05:source[k]===v);if(matched){pending.delete(spec.id);drafts.delete(spec.id);draftValues.delete(spec.id);status.textContent='已同步';status.classList.remove('pending');}else if(Date.now()-waiting.at>8000)status.textContent='尚未确认，请检查手机';}
    for(const field of spec.fields){const input=inputs.get(field.key);if(field.dynamic){const options=optionsFor(field),signature=JSON.stringify(options);if(input.dataset.options!==signature){input.replaceChildren(new Option('请选择',''));for(const [v,t] of options)input.add(new Option(t,v));input.dataset.options=signature;}}
      const value=draftValues.get(spec.id)?.[field.key]??source?.[field.key];
      if(field.type!=='password'&&value!==undefined){if(field.type==='boolean')input.checked=value===true;else if(input.value!==String(value))input.value=value;}
      const limits=state?.phone?.cameraControls;if(limits&&field.key==='zoomRatio'){input.min=limits.zoomMin;input.max=limits.zoomMax;}if(limits&&field.key==='exposureIndex'){input.min=limits.exposureMin;input.max=limits.exposureMax;}
      if(field.key==='fps'&&state?.phone?.capabilities?.maxFps)for(const option of input.options)option.disabled=Number(option.value)>state.phone.capabilities.maxFps;
    }
    if(!source&&!drafts.has(spec.id))status.textContent=spec.extended?'需完整控制接口':'等待同步';else if(!drafts.has(spec.id)&&!pending.has(spec.id))status.textContent='与手机同步';
  }}
function updateAvailability(){const online=state?.connection.state==='connected',extended=online&&!!state?.control;for(const {spec,form} of formNodes.values()){for(const el of form.querySelectorAll('input,select,button'))el.disabled=!online||spec.extended&&!extended;const torch=form.querySelector('[name=torch]');if(torch&&state?.phone?.cameraControls?.torchSupported===false)torch.disabled=true;}
  for(const el of $('controls').querySelectorAll('[data-online]'))el.disabled=el.dataset.online==='true'&&!online||el.dataset.extended==='true'&&!extended;
}
function updateView(){if(!state)return;const {connection:c,phone:p,control:ctrl,metrics:m}=state,online=c.state==='connected';
  $('connectionBadge').textContent=online?'手机已连接':c.state==='reconnecting'?'正在重连':c.state==='connecting'?'连接中':'等待手机';$('connectionBadge').className='badge '+(online?'online':c.state==='reconnecting'?'warning':'');$('panelStatus').textContent=online?(ctrl?'实时同步':p.controlVersion>=1?'设置同步中':'兼容模式'):'未连接';$('upgrade').hidden=!online||p.controlVersion>=1;
  $('deviceLine').textContent=online?`${c.phoneVersion?'v'+c.phoneVersion:'兼容手机端'} · ${p.cameras?.find(x=>x.id===p.selectedCameraId)?.label||'手机画面'}`:'等待连接 · 手机本地合成';
  $('metricPhone').textContent=online&&Number.isFinite(p.stereo.phoneDisplayFps)?p.stereo.phoneDisplayFps.toFixed(1):'—';$('metricReceive').textContent=online&&state.frame.fresh?m.receiveFps.toFixed(1):'—';$('metricAge').textContent=online&&state.frame.fresh?Math.round(m.frameAgeMs):'—';$('metricSize').textContent=online?`${p.stereo.width}×${p.stereo.height}`:'—';
  $('streamHint').textContent=online?(state.frame.fresh?'同步接收 · 无画面裁切':'已连接，等待新鲜画面'):c.message;$('syncTime').textContent=c.lastStatusAt?'同步于 '+new Date(c.lastStatusAt).toLocaleTimeString('zh-CN'):'等待状态';
  $('captionState').textContent='字幕 · '+(ctrl?.captions.status||p?.stereo?.speechCaption?.modeLabel||'待连接');$('soundState').textContent='环境音 · '+(ctrl?ctrl.environment.enabled?'开启':'关闭':'待连接');$('pttState').textContent='语音重构 · '+(ctrl?.ptt.message||'待连接');
  $('events').replaceChildren(...(state.events.length?state.events.slice(-4).reverse().map(e=>{const p=node('p'),t=node('time','',new Date(e.at).toLocaleTimeString('zh-CN'));p.append(t,document.createTextNode(e.message));return p;}):[node('p','','接收服务已就绪。添加连接后自动恢复视频。')]));
  if($('connectionAddress'))$('connectionAddress').textContent=c.address?'当前地址：'+c.address:'当前未设置手机地址';if($('environmentReadout'))$('environmentReadout').textContent=ctrl?.environment.status||'连接配套手机后显示环境音识别状态';
  if($('directionReadout')){const d=ctrl?.direction;$('directionReadout').replaceChildren(node('strong','',d?.fresh?Number(d.relativeDegrees).toFixed(1)+'°':'—'),document.createTextNode(d?`${d.sector||'暂无有效方位'} · ${d.message}`:'等待小板连接'));const sel=$('usbDevices'),options=d?.devices||[],sig=JSON.stringify(options);if(sel.dataset.options!==sig){sel.replaceChildren(new Option('选择小板',''));for(const dev of options){const option=new Option(dev.label,dev.name);option.disabled=!dev.supported;sel.add(option);}sel.dataset.options=sig;}$('directionRaw').textContent=d?`${d.sideCalibration}\n\n${d.rawText||'尚无串口文本'}\n${d.rawHex||''}`:'等待数据';}
  if($('pttReadout'))$('pttReadout').textContent=ctrl?`${ctrl.ptt.message||'就绪'} · ${ctrl.speech.configured?'手机已配置 Key':'手机尚未配置 Key'}`:'需要配套手机端的控制接口';
  if($('remoteReadout'))$('remoteReadout').textContent=ctrl?`当前学习：${ctrl.remote.learning||'无'}\n\n${ctrl.remote.events.join('\n')}`:'等待遥控器状态';
  if($('rawState'))$('rawState').textContent=JSON.stringify({connection:c,metrics:m,application:ctrl?.application,render:p?.stereo&&{source:[p.stereo.sourceWidth,p.stereo.sourceHeight],display:[p.stereo.renderWidth,p.stereo.renderHeight],wireless:[p.stereo.width,p.stereo.height],renderCostMs:p.stereo.renderCostMs,encoderError:p.stereo.encoderError},soundHud:p?.stereo.soundHud,voiceRepair:p?.stereo.voiceRepair,permissions:ctrl?.permissions},null,2);
  syncForms();updateAvailability();if(!online)clearVideo(c.message);
}
async function refresh(){state=await api('/api/state');if(instance&&instance!==state.instance){const session=await api('/api/session');csrf=session.csrf;after=-1;clearVideo('服务已重新启动');}instance=state.instance;updateView();}
function clearVideo(message='等待手机双眼画面'){$('liveTag').hidden=true;$('emptyState').hidden=false;$('emptyTitle').textContent=state?.connection.state==='connected'?'等待新鲜画面':'等待手机双眼画面';$('emptyMessage').textContent=message;ctx.fillStyle='#0b1220';ctx.fillRect(0,0,$('video').width,$('video').height);}
async function videoLoop(){while(!stopped){try{
    if(state?.connection.state!=='connected'){await new Promise(r=>setTimeout(r,350));continue;}
    const response=await fetch('/api/frame?after='+after,{cache:'no-store',signal:AbortSignal.timeout(3500)});if(response.status===204){if(performance.now()-lastPaint>1600)clearVideo('手机已连接，尚未收到新画面');continue;}if(!response.ok)throw Error();
    const begin=performance.now(),image=await createImageBitmap(await response.blob());const canvas=$('video');
    if(viewMode===0){canvas.width=image.width;canvas.height=image.height;ctx.drawImage(image,0,0);}else{canvas.width=Math.floor(image.width/2);canvas.height=image.height;ctx.drawImage(image,viewMode===2?canvas.width:0,0,canvas.width,image.height,0,0,canvas.width,image.height);}image.close();
    after=Number(response.headers.get('x-frame-sequence'));lastPaint=performance.now();$('emptyState').hidden=true;$('liveTag').hidden=false;
    if(performance.now()-lastFeedback>3000){lastFeedback=performance.now();api('/api/feedback',{transportMs:Number(response.headers.get('x-transport-ms')||0)+performance.now()-begin}).catch(()=>{});}
  }catch{if(performance.now()-lastPaint>1600)clearVideo('视频连接暂时中断，正在恢复');await new Promise(r=>setTimeout(r,450));}}}
async function startHold(){if(held||startPromise)return;held=true;lease=crypto.randomUUID();const own=lease;$('holdToTalk')?.classList.add('held');if($('holdToTalk'))$('holdToTalk').textContent='正在让手机收音 · 松开播放';
  startPromise=control('ptt',{action:'start',lease:own});try{await startPromise;if(held&&lease===own)heartbeat=setInterval(()=>control('ptt',{action:'heartbeat',lease:own}).catch(()=>stopHold('cancel')),700);}catch(e){held=false;notice(e.message,true);}finally{startPromise=null;if(!held)resetHoldButton();}}
function resetHoldButton(){const b=$('holdToTalk');if(b){b.classList.remove('held');b.textContent='按住此处 · 让手机说话';}}
async function stopHold(action){if(!lease)return;const own=lease;held=false;clearInterval(heartbeat);resetHoldButton();try{if(startPromise)await startPromise;await control('ptt',{action,lease:own});}catch(e){if(action==='end')notice(e.message,true);}finally{if(lease===own)lease=null;}}
window.addEventListener('blur',()=>{if(held)stopHold('cancel');});document.addEventListener('visibilitychange',()=>{if(document.hidden&&held)stopHold('cancel');});window.addEventListener('pagehide',()=>{stopped=true;if(lease)fetch('/api/command',{method:'POST',keepalive:true,headers:{'Content-Type':'application/json','X-HearLens-CSRF':csrf},body:JSON.stringify({path:'/control',body:{section:'ptt',values:{action:'cancel',lease}}})}).catch(()=>{});});
$('reconnect').onclick=()=>action(()=>api('/api/reconnect',{}));$('fullscreen').onclick=()=>document.fullscreenElement?document.exitFullscreen():$('screen').requestFullscreen();$('viewMode').onclick=()=>{viewMode=(viewMode+1)%3;$('viewMode').textContent=['双眼','仅左眼','仅右眼'][viewMode];$('screen').dataset.view=viewMode===0?'stereo':'single';$('video').setAttribute('aria-label',['手机双眼实时视频','手机左眼完整画面','手机右眼完整画面'][viewMode]);$('leftEyeLabel').textContent=viewMode===0?'LEFT EYE / 左眼':viewMode===1?'LEFT EYE / 左眼':'RIGHT EYE / 右眼';$('rightEyeLabel').hidden=viewMode!==0;after=-1;};
renderPanel();clearVideo();
async function statusLoop(){while(!stopped){try{await refresh();}catch{clearVideo('本机服务连接中断，请重新运行桌面启动器');}await new Promise(r=>setTimeout(r,1100));}}
try{const session=await api('/api/session');csrf=session.csrf;instance=session.instance;await refresh();statusLoop();videoLoop();}catch(e){notice('本机服务尚未就绪：'+e.message,true);}
