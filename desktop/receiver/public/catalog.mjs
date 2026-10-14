const num=(key,label,min,max,step=1)=>({key,label,type:'number',min,max,step});
const pick=(key,label,options)=>({key,label,type:'select',options:options.map(v=>Array.isArray(v)?v:[v,String(v)])});
const toggle=(key,label)=>({key,label,type:'boolean'});
const text=(key,label,type='text')=>({key,label,type});
const layout=(id,title,fields)=>({id,title,path:'/layout',read:'phone.stereo.settings',fields});
const control=(id,title,section,fields)=>({id,title,path:'/control',section,read:'control.'+section,fields,extended:true});
export const tabs=[['connection','连接手机','link'],['camera','镜头与采集','camera'],['stereo','双眼与显示','eyes'],['captions','字幕与文字','text'],['environment','环境音感知','sound'],['direction','声源与校准','compass'],['ptt','语音重构','mic'],['remote','遥控与预设','remote'],['diagnostics','运行诊断','chart']];
export const forms={
 connection:[layout('wireless','无线传输',[pick('outputWidth','双眼总宽度',[640,960,1280,1600,1920,2560]),num('wirelessFps','传输帧率上限',1,30),num('wirelessQuality','JPEG 画质',35,95),toggle('wirelessAdaptive','网络变慢时自动降低负载')]),control('application','手机运行状态','application',[toggle('stereo','手机双眼观看'),toggle('paused','暂停手机取景'),toggle('wireless','手机无线共享')])],
 camera:[{id:'lens',title:'使用镜头',path:'/camera',read:'phone.settings',fields:[{key:'cameraId',label:'当前镜头',type:'select',dynamic:'cameras'}]},
  {id:'capture',title:'采集参数',path:'/camera',read:'phone.settings',fields:[pick('maxWidth','采集尺寸档',[640,960,1280,1920]),pick('fps','采集帧率',[ [0,'自动最高'],15,24,30,60,120]),num('zoomRatio','镜头变焦',1,10,.1),num('exposureIndex','曝光补偿',-10,10),toggle('torch','补光灯')]}],
 stereo:[layout('display','手机显示',[{key:'phoneRenderWidth',label:'手机双眼总宽度',type:'select',dynamic:'resolutions'},pick('phoneFps','手机显示帧率上限',[[0,'自动'],15,24,30,45,60,90,120])]),
  layout('eyes','双眼布局',[num('scale','整体缩放 %',50,100),num('spacing','双眼间距',-100,100),num('leftSize','左眼尺寸微调',-10,10),num('rightSize','右眼尺寸微调',-10,10),num('leftY','左眼上下位置',-10,10),num('rightY','右眼上下位置',-10,10),pick('eyeMode','显示眼别',[['both','双眼'],['left','仅左眼'],['right','仅右眼']]),pick('calibration','校准参考线',[['off','关闭'],['cross','十字'],['grid','网格']])]),
  layout('color','画面颜色',[num('brightness','亮度 %',50,150),num('contrast','对比度 %',50,150),num('saturation','饱和度 %',0,200),pick('rotation','附加旋转',[0,90,180,270]),toggle('mirror','水平镜像')])],
 captions:[control('caption-mode','实时语音字幕','captions',[pick('choice','识别与翻译模式',[[0,'关闭字幕'],[1,'快速识别'],[2,'说话人识别'],[3,'转译为简体中文'],[4,'转译为繁体中文'],[5,'转译为英文']])]),
  control('caption-style','实时字幕外观','captions',[num('widthPercent','字幕宽度 %',35,80),num('bottomPercent','距底部 %',3,30),num('sizePercent','字号比例 %',70,160),num('transparency','蒙板透明度 %',0,100)]),
  layout('manual-style','手动文字外观',[num('fontSize','字号',18,64),num('textY','上下位置 %',10,90),text('textColor','文字颜色','color'),pick('align','对齐',[['left','左对齐'],['center','居中'],['right','右对齐']]),num('background','底色不透明度 %',0,100),num('stroke','描边宽度',0,6)])],
 environment:[control('environment','环境音与光晕','environment',[toggle('enabled','开启环境音感知'),num('intensity','光晕亮度 %',0,100),num('depth','向内扩散',20,160),num('spread','方向范围 FWHM °',20,160),num('transparency','名称蒙板透明度 %',0,100),pick('language','声音名称语言',[['zh-Hans','简体中文'],['zh-Hant','繁體中文'],['en','English']]),toggle('associateDirection','按同期小板信号估计方位'),toggle('reducedMotion','减少光晕动态效果')])],
 direction:[control('direction','小板参数与方向','direction',[pick('baudRate','串口波特率',[9600,19200,38400,57600,115200,230400,460800,921600]),pick('format','角度数据格式',[['AUTO','自动：文本 / JSON'],['LABEL','angle 文本'],['JSON','JSON 角度'],['NUMBER','单行数字'],['RAW','只读原始数据']]),num('frontDegrees','正前方对应角度 °',0,359.999,.1),num('staleMs','角度有效时间 ms',500,10000,100),toggle('clockwise','原始角度顺时针增加'),toggle('showOverlay','显示方向光晕')])],
 ptt:[control('ptt','语音重构','ptt',[pick('target','重构输出',[['zh','中文重述'],['en','英文翻译']])]),
  {...control('speech','手机直连模型配置','speech',[text('workspace','北京业务空间（可留空）'),{...text('key','更新 API Key','password'),optional:true}]),note:'原有 Key 继续留在手机。这里不会回读密钥，留空表示保持现有 Key。'}],
 remote:[],diagnostics:[]
};
export function pathValue(object,path){return path.split('.').reduce((value,k)=>value?.[k],object);}
export function typedValue(field,value){if(field.type==='boolean')return Boolean(value);if(field.type==='number'||field.type==='select'&&field.options?.some(([v])=>typeof v==='number')||field.dynamic==='resolutions')return Number(value);return value;}
