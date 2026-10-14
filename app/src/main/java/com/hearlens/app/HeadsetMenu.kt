package com.hearlens.app

import org.json.JSONObject

data class SettingControl(val position:Int=0,val count:Int=0,val toggle:Boolean?=null,val pending:Boolean=false,val labels:List<String> = emptyList(),val hasDetails:Boolean=false)
data class HeadsetMenuState(val rows:List<Pair<String,String>>,val selected:Int,val message:String,
    val title:String="观看设置",val backLabel:String="关闭",val controls:List<SettingControl> = emptyList())

/** Phone and headset share one catalog, values and validation. */
internal class HeadsetMenu(private val engine:LocalStereoEngine,private val camera:UsbVideoServer,
    private val captions:LiveCaptionSession,private val applyCaptionChoice:(Int)->Unit,
    private val wifiEnabled:()->Boolean,private val toggleWifi:()->Unit,
    private val stereoEnabled:()->Boolean,private val toggleStereo:()->Unit,
    private val perceptionEnabled:()->Boolean={engine.environment?.enabled==true},private val togglePerception:()->Unit={},
    private val pttTarget:()->String={"zh"},private val setPttTarget:(String)->Unit={} ) {
    private data class Row(val title:String,val value:()->String,val control:()->SettingControl={SettingControl()},
        val choose:((Int)->Unit)?=null,val apply:(()->Unit)?=null,val action:(()->Unit)?=null,val details:(()->Unit)?=null)
    private val home=mutableListOf<Row>()
    private val advanced=mutableListOf<Row>()
    private val rows get()=if(inMore)advanced else home
    var inMore=false;private set
    private var index=0
    private var message=""
    private var lensDraft:String?=null
    private var modeDraft:Int?=null
    private var widthDraft:Int?=null
    private var fpsDraft:Int?=null
    var onAuxiliary:(String)->Unit={}
    var onNavigate:(()->Unit)?=null
    val opened get()=engine.menu!=null
    init {
        home+=Row("字幕语言",{CaptionChoices.labels[modeDraft ?: captions.choiceIndex]},
            {SettingControl(modeDraft ?: captions.choiceIndex,CaptionChoices.labels.size,pending=modeDraft!=null,labels=CaptionChoices.labels)},
            {modeDraft=it},{applyCaptionChoice(modeDraft ?: captions.choiceIndex);modeDraft=null})
        home+=Row("使用镜头",{camera.snapshot().let {s->s.cameras.firstOrNull {it.id==(lensDraft ?: s.selectedCameraId)}?.label?.substringBefore("·")?.trim() ?: "等待相机"}},
            {camera.snapshot().let {s->SettingControl(s.cameras.indexOfFirst {it.id==(lensDraft ?: s.selectedCameraId)}.coerceAtLeast(0),s.cameras.size,pending=lensDraft!=null,labels=s.cameras.map {it.label.substringBefore("·").trim()})}},
            {lensDraft=camera.snapshot().cameras.getOrNull(it)?.id},{lensDraft?.let {require(camera.requestCamera(it)) {"镜头暂不可用"}};lensDraft=null})
        home+=Row("环境音感知",{if(perceptionEnabled())"开启" else "关闭"},{SettingControl(toggle=perceptionEnabled(),hasDetails=true)},
            action=togglePerception,details={onAuxiliary("perception")})
        home+=Row("语音修复输出",{if(pttTarget()=="zh")"中文重述" else "英文翻译"},
            {SettingControl(if(pttTarget()=="zh")0 else 1,2,labels=listOf("中文重述","英文翻译"))},
            {setPttTarget(if(it==0)"zh" else "en")})
        home+=Row("双眼观看",{if(stereoEnabled())"开启" else "关闭"},{SettingControl(toggle=stereoEnabled())},action={close();toggleStereo()})
        home+=Row("深层设置",{"画面、字幕、无线与遥控器"},action={showAdvanced()})
        action("环境音感知设置","连接、校准与光晕外观") {onAuxiliary("perception")}
        advanced+=Row("手机显示分辨率",{engine.resolutionLabel(engine.settings.phoneRenderWidth)},
            {val v=engine.phoneWidthOptions();SettingControl(v.indexOf(engine.settings.phoneRenderWidth).coerceAtLeast(0),v.size,labels=v.map {engine.resolutionLabel(it)})},
            {engine.configure(engine.settings.copy(phoneRenderWidth=engine.phoneWidthOptions()[it]))})
        enum("phoneFps","手机帧率上限",listOf(0,15,24,30,45,60,90,120),mapOf("0" to "自动 · 优先流畅"))
        advanced+=Row("相机采集分辨率",{(widthDraft ?: camera.currentSettings().maxWidth).toString()},
            {SettingControl(listOf(640,960,1280,1920).indexOf(widthDraft ?: camera.currentSettings().maxWidth),4,pending=widthDraft!=null,labels=listOf("640","960","1280","1920"))},
            {widthDraft=listOf(640,960,1280,1920)[it]},{widthDraft?.let {camera.applyPhoneSettings(camera.currentSettings().copy(maxWidth=it))};widthDraft=null})
        fun fpsChoices()=listOf(0,15,24,30,60,120).filter {it==0 || it<=camera.snapshot().capabilities.maxFps}
        advanced+=Row("相机目标帧率",{(fpsDraft ?: camera.currentSettings().fps).let {if(it==0)"自动最高" else "$it fps"}},
            {val v=fpsChoices();SettingControl(v.indexOf(fpsDraft ?: camera.currentSettings().fps).coerceAtLeast(0),v.size,pending=fpsDraft!=null,labels=v.map {if(it==0)"自动最高" else "$it fps"})},
            {fpsDraft=fpsChoices()[it]},{fpsDraft?.let {camera.applyPhoneSettings(camera.currentSettings().copy(fps=it))};fpsDraft=null})
        advanced+=Row("镜头变焦",{"%.1f×".format(camera.currentSettings().zoomRatio)},
            {val s=camera.snapshot();SettingControl(((s.settings.zoomRatio-s.cameraControls.zoomMin)*10).toInt(),((s.cameraControls.zoomMax-s.cameraControls.zoomMin)*10).toInt()+1)},
            {val s=camera.snapshot();camera.applyPhoneSettings(s.settings.copy(zoomRatio=(s.cameraControls.zoomMin+it*.1f).coerceAtMost(s.cameraControls.zoomMax)))})
        advanced+=Row("曝光补偿",{camera.currentSettings().exposureIndex.toString()},
            {val s=camera.snapshot();SettingControl(s.settings.exposureIndex-s.cameraControls.exposureMin,s.cameraControls.exposureMax-s.cameraControls.exposureMin+1)},
            {val s=camera.snapshot();camera.applyPhoneSettings(s.settings.copy(exposureIndex=s.cameraControls.exposureMin+it))})
        advanced+=Row("补光灯",{if(camera.currentSettings().torch)"开启" else "关闭"},{SettingControl(toggle=camera.currentSettings().torch)},action={
            val s=camera.snapshot();require(s.cameraControls.torchSupported) {"当前镜头不支持补光灯"};camera.applyPhoneSettings(s.settings.copy(torch=!s.settings.torch))})
        action("中心对焦与测光","对准画面中心") {camera.focusFromPhone()}
        number("scale","双眼整体大小",50,100,5);number("spacing","双眼间距",-100,100,5)
        number("leftSize","左眼大小微调",-10,10,1);number("rightSize","右眼大小微调",-10,10,1)
        number("leftY","左眼上下位置",-10,10,1);number("rightY","右眼上下位置",-10,10,1)
        enum("eyeMode","显示眼别",listOf("both","left","right"),mapOf("both" to "双眼","left" to "仅左眼","right" to "仅右眼"))
        enum("calibration","校准图",listOf("off","cross","grid"),mapOf("off" to "关闭","cross" to "十字","grid" to "网格"))
        number("brightness","画面亮度",50,150,5);number("contrast","画面对比度",50,150,5);number("saturation","画面饱和度",0,200,10)
        enum("rotation","画面附加旋转",listOf(0,90,180,270));enum("mirror","水平镜像",listOf(false,true))
        for((key,label,range) in listOf(Triple("widthPercent","语音字幕宽度",35..80),Triple("bottomPercent","语音字幕距底部",3..30),
            Triple("sizePercent","语音字幕字号比例",70..160),Triple("transparency","语音字幕蒙板透明度",0..100))) {
            advanced+=Row(label,{captions.styleJson().getInt(key).toString()},
                {SettingControl(captions.styleJson().getInt(key)-range.first,range.last-range.first+1,labels=range.map {it.toString()})},
                {captions.configure(captions.styleJson().put(key,it+range.first))})
        }
        action("清空语音字幕","保留当前识别模式",captions::clear)
        action("语音字幕恢复外观","默认宽度、位置与暖灰蒙板") {captions.configure(JSONObject())}
        action("手动文字输入","不影响语音识别设置") {onAuxiliary("text")}
        number("fontSize","手动文字字号",18,64,2);number("textY","手动文字上下位置",10,90,5)
        enum("textColor","手动文字颜色",listOf("#FFFFFF","#FFFF00","#00FFFF","#00FF00"),mapOf("#FFFFFF" to "白色","#FFFF00" to "黄色","#00FFFF" to "青色","#00FF00" to "绿色"))
        enum("align","手动文字对齐",listOf("left","center","right"),mapOf("left" to "左对齐","center" to "居中","right" to "右对齐"))
        number("background","手动文字底色",0,100,5);number("stroke","手动文字描边",0,6,1)
        action("清空手动文字","语音字幕保留") {engine.configure(engine.settings.copy(caption=""))}
        enum("outputWidth","无线双眼总宽度",listOf(640,960,1280,1600,1920,2560))
        number("wirelessFps","无线帧率上限",1,30,1);number("wirelessQuality","无线画质",35,95,5)
        enum("wirelessAdaptive","无线自动降负载",listOf(false,true))
        advanced+=Row("无线共享",{if(wifiEnabled())"开启" else "关闭"},{SettingControl(toggle=wifiEnabled())},action=toggleWifi)
        action("无线连接地址","连接同一 Wi-Fi 或手机热点") {onAuxiliary("connection")}
        action("观看预设","高清、舒适与恢复默认") {onAuxiliary("presets")}
        action("遥控器与快捷键","肩键按住说话 · Y 环境音感知 · X 字幕语言") {onAuxiliary("remote")}
        action("语音连接状态","查看错误与会话状态") {onAuxiliary("speechStatus")}
        action("语音接口配置与测试","配置手机直连阿里云") {onAuxiliary("speechSetup")}
    }
    private fun action(title:String,hint:String,call:()->Unit) {advanced+=Row(title,{hint},action=call)}
    private fun number(key:String,title:String,min:Int,max:Int,step:Int) {
        advanced+=Row(title,{LocalStereoEngine.encode(engine.settings).getInt(key).toString()},
            {SettingControl((LocalStereoEngine.encode(engine.settings).getInt(key)-min)/step,(max-min)/step+1,labels=(min..max step step).map {it.toString()})},
            {set(key,(min+it*step).coerceIn(min,max))})
    }
    private fun enum(key:String,title:String,values:List<Any>,labels:Map<String,String> = emptyMap()) {
        val boolean=values.all {it is Boolean}
        fun current()=LocalStereoEngine.encode(engine.settings).get(key).toString()
        advanced+=Row(title,{labels[current()] ?: if(boolean)if(current()=="true")"开启" else "关闭" else current()},
            {SettingControl(values.indexOfFirst {it.toString()==current()}.coerceAtLeast(0),if(boolean)0 else values.size,if(boolean)current()=="true" else null,labels=values.map {labels[it.toString()] ?: it.toString()})},
            if(boolean)null else {i->set(key,values[i])},action=if(boolean)({set(key,current()!="true")}) else null)
    }
    private fun set(key:String,value:Any) {engine.configure(LocalStereoEngine.decode(JSONObject().put(key,value),engine.settings))}
    private fun clearDrafts() {lensDraft=null;modeDraft=null;widthDraft=null;fpsDraft=null}
    fun state()=HeadsetMenuState(rows.map {it.title to it.value()},index,message,if(inMore)"深层设置" else "设置",if(inMore)"返回" else "关闭",rows.map {it.control()})
    fun open() {clearDrafts();inMore=false;index=0;message="";update();onNavigate?.invoke()}
    fun close() {engine.menu=null;clearDrafts();inMore=false;index=0;engine.onFrame?.invoke();onNavigate?.invoke()}
    fun showAdvanced() {clearDrafts();inMore=true;index=0;message="";update();onNavigate?.invoke()}
    fun back() {if(inMore) {clearDrafts();inMore=false;index=home.lastIndex;message="";update();onNavigate?.invoke()} else close()}
    fun refresh() {if(opened)update()}
    private fun update() {engine.menu=state();engine.onFrame?.invoke()}
    fun selectRow(row:Int) {index=row.coerceIn(rows.indices);update()}
    fun choose(row:Int,position:Int,commit:Boolean) {
        index=row.coerceIn(rows.indices)
        runCatching {
            val r=rows[index];r.choose?.invoke(position.coerceIn(0,(r.control().count-1).coerceAtLeast(0)))
            if(commit)r.apply?.invoke()
            message=if(r.apply!=null && !commit)"待确认 · A 应用 · B 取消" else "已应用 · ${r.title}"
        }.onFailure {message=it.message ?: "无法应用"}
        if(opened)update()
    }
    fun activate(row:Int) {index=row.coerceIn(rows.indices);command("confirm")}
    fun details(row:Int) {index=row.coerceIn(rows.indices);rows[index].details?.invoke()}
    fun command(action:String) {
        if(!opened) {if(action=="confirm")open();return}
        if(action=="back") {back();return}
        when(action) {
            "up"->{clearDrafts();index=Math.floorMod(index-1,rows.size)}
            "down"->{clearDrafts();index=(index+1)%rows.size}
            "left","right","minus","confirm"->{
                val r=rows[index]
                if(action=="confirm")runCatching {if(r.apply!=null)r.apply.invoke() else if(r.action!=null)r.action.invoke() else r.choose?.invoke((r.control().position+1).coerceAtMost(r.control().count-1))}
                    .onSuccess {message="已应用 · ${r.title}"}.onFailure {message=it.message ?: "无法应用"}
                else if(r.choose!=null)choose(index,r.control().position+if(action=="right")1 else -1,false)
                else if(r.control().toggle!=null && r.control().toggle!=(action=="right"))runCatching {r.action?.invoke()}.onFailure {message=it.message ?: "无法应用"}
            }
        }
        if(opened)update()
    }
    fun touch(x:Float,y:Float,width:Int,height:Int) {
        if(!opened) {open();return}
        val panel=engine.menuPanels(width,height).firstOrNull {it.contains(x,y)} ?: run {back();return}
        val xx=(x-panel.x)/panel.width;val yy=(y-panel.y)/panel.height
        if(yy<.15f && xx<.2f) {back();return}
        val slot=(yy-HeadsetMenuGeometry.ROW_TOP)/HeadsetMenuGeometry.ROW_PITCH
        if(slot<0 || slot>=HeadsetMenuGeometry.VISIBLE_ROWS)return
        val row=(HeadsetMenuGeometry.firstRow(index,rows.size)+slot.toInt()).coerceIn(rows.indices)
        index=row
        val control=rows[row].control()
        if(control.hasDetails && x<HeadsetMenuGeometry.toggle(panel,HeadsetMenuGeometry.ROW_TOP+slot.toInt()*HeadsetMenuGeometry.ROW_PITCH).left) {
            details(row);return
        }
        val track=HeadsetMenuGeometry.slider(panel,HeadsetMenuGeometry.ROW_TOP+slot.toInt()*HeadsetMenuGeometry.ROW_PITCH,width>height)
        if(control.count>1 && track.contains(x,y))
            choose(row,HeadsetMenuGeometry.position(x,track,control.count),true)
        else activate(row)
    }
}

