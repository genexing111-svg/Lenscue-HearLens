package com.hearlens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import org.json.JSONObject
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Foreground-only opt-in integration page; deliberately independent of the future subtitle UI. */
class SpeechActivity:AppCompatActivity() {
    private lateinit var speech:SpeechClient
    private lateinit var root:LinearLayout
    private lateinit var http:EditText
    private lateinit var ws:EditText
    private lateinit var token:EditText
    private lateinit var connection:Spinner
    private lateinit var workspace:EditText
    private lateinit var cloudKey:EditText
    private lateinit var saveDefault:Button
    private lateinit var cloudGroup:LinearLayout
    private lateinit var bridgeGroup:LinearLayout
    private lateinit var credentials:SpeechCredentials
    private lateinit var forget:Button
    private lateinit var engine:Spinner
    private lateinit var target:Spinner
    private lateinit var check:Button
    private lateinit var start:Button
    private lateinit var stop:Button
    private lateinit var cancel:Button
    private lateinit var permission:Button
    private lateinit var status:TextView
    private lateinit var counts:TextView
    private lateinit var config:TextView
    private lateinit var textFields:TextView
    private lateinit var data:TextView
    private lateinit var events:TextView
    private lateinit var meter:ProgressBar
    private val ui=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("speech_v7",MODE_PRIVATE) }
    private var foreground=false
    private var notice=""
    private var pendingProbe:String?=null
    private val permissionRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Permission dialogs and late callbacks never start a recording automatically.
        notice=if(granted)"麦克风已授权。准备好后再点“开始语音测试”。" else "麦克风未授权。可重试授权，或在系统权限设置中开启。"
        if(::status.isInitialized)render()
    }
    private val ticker=object:Runnable {
        override fun run() { if(foreground) { render();ui.postDelayed(this,300) } }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        speech=SpeechClient({PhoneMicrophone(applicationContext)})
        credentials=SpeechCredentials(this)
        notice=credentials.consumeDebugProvision().orEmpty()
        if(BuildConfig.DEBUG)pendingProbe=intent.getStringExtra("probe_engine")?.takeIf { it in listOf("asr","live") }
        WindowCompat.setDecorFitsSystemWindows(window,false)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(16,21,17)) }
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val sys=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(sys.left+dp(20),sys.top+dp(12),sys.right+dp(20),sys.bottom+dp(24));insets
        }
        setContentView(scroll)
        label("语音接口测试 · 手机直连",24f,true)
        label("直连模式：手机麦克风 → 阿里云北京 → 手机文字字段，不需要电脑后端。点击开始且模型准备好后才采集，不保存录音。离开页面或锁屏停止，返回后需手动开始。",15f)
        label("先检查接口，不会打开麦克风。字幕样式将在后续阶段接入。",15f)
        label("连接方式",16f,true)
        connection=spinner(listOf("阿里云直连 · 北京","原有后端桥接 · 兼容模式"),prefs.getInt("connection",0).coerceIn(0,1))
        val outer=root
        cloudGroup=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };outer.addView(cloudGroup);root=cloudGroup
        workspace=field("北京业务空间标识（留空使用北京通用入口）","llm-…",prefs.getString("workspace","") ?: "")
        cloudKey=field("API Key / 临时 API Key","仅发送到阿里云官方北京地址",credentials.read()).apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        label("开始或检查时，用 Android Keystore 加密保存到本机。安装包和源码不含密钥。临时 Key 过期后需重新填写。",13f)
        label(if(cloudKey.text.isNotBlank())"已自动填入这台手机保存的默认 Key。" else "尚未设置默认 Key，填写后可保存。",13f)
        saveDefault=button("保存为本机默认 Key") {
            try {
                val saved=AliyunConnection(workspace.text.toString().trim(),cloudKey.text.toString().trim())
                credentials.save(saved.key)
                check(credentials.read()==saved.key)
                prefs.edit(commit=true) {putString("workspace",saved.workspace);putInt("connection",0)}
                notice="默认 Key 已保存并核验；下次打开会自动填入。";render()
            } catch(_:Exception) {notice="默认 Key 保存失败，请检查填写内容后重试。";render()}
        }
        forget=button("清除本机保存的 Key") { credentials.clear();cloudKey.setText("");notice="本机凭据已清除。";render() }
        bridgeGroup=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };outer.addView(bridgeGroup);root=bridgeGroup
        http=field("后端 HTTP 地址","例如：https://服务地址 或 http://电脑局域网IP:端口",prefs.getString("http","") ?: "")
        ws=field("WebSocket 地址（可留空，随 HTTP 地址推导）","例如：wss://服务地址",prefs.getString("ws","") ?: "")
        token=field("后端访问令牌（可选，仅当前页面有效）","不是云端 API Key","").apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled=false
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        label("127.0.0.1 是手机自身；交接包的电脑本机服务不能直接用电脑 IP 替换。HTTP / WS 仅供可信局域网调试，公网请用 HTTPS / WSS。",13f)
        root=outer
        label("识别模式",16f,true)
        engine=spinner(listOf("快速转写 · asr","同声传译 · live"),prefs.getInt("engine",0).coerceIn(0,1))
        label("同传目标（仅 live 使用）",16f,true)
        target=spinner(listOf("英文 · en","中文 · zh"),prefs.getInt("target",0).coerceIn(0,1))
        check=button("检查接口（不录音）") { runSpeech(probe=true) }
        permission=button("申请麦克风权限") { requestMicrophone() }
        start=button("开始语音测试") {
            if(!hasMicrophone())requestMicrophone()
            else runSpeech(probe=false)
        }
        stop=button("停止采集并等待最终字段") { speech.stop();render() }
        cancel=button("立即取消连接 / 采集") { speech.cancel();render() }
        button("系统权限设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:$packageName".toUri())) }
        button("返回相机") { finish() }
        status=label("待测试",17f,true)
        meter=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        root.addView(meter,LinearLayout.LayoutParams(-1,dp(20)))
        counts=label("尚未采集音频",14f)
        label("收到的原文 / 翻译字段（调试）",18f,true)
        textFields=selectable("暂无返回文字")
        label("最近识别数据 JSON",18f,true);data=selectable("等待 result / segment")
        label("最近协议消息 JSON",18f,true);events=selectable("等待 ready / finished / error 等事件")
        label("连接配置（不含密钥）",18f,true);config=selectable("尚未连接")
        label("测试诊断：本测试版会在应用私有缓存暂存当前返回字段和计数，供 USB 调试验收；退出本页即删除，不包含密钥或录音。",13f)
        connection.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { render() }
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        render()
    }
    override fun onResume() {
        super.onResume();foreground=true;ui.post(ticker)
        pendingProbe?.let { value ->
            pendingProbe=null;connection.setSelection(0);engine.setSelection(if(value=="asr")0 else 1)
            runSpeech(probe=true)
        }
    }
    override fun onPause() {
        foreground=false;ui.removeCallbacks(ticker)
        if(speech.snapshot().busy)speech.cancel("页面已离开，采集与连接已取消；尾句可能不完整")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() { ui.removeCallbacks(ticker);speech.release();File(cacheDir,"speech-diagnostic.json").delete();super.onDestroy() }
    private fun hasMicrophone()=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun requestMicrophone() {
        if(hasMicrophone()) { notice="麦克风已授权；只有点击开始并收到模型 ready 后才采集。";render();return }
        AlertDialog.Builder(this).setTitle("允许语音接口测试使用麦克风")
            .setMessage("只在你点击开始、且模型准备好后实时采集。直连模式直接发送到阿里云；桥接模式发送到填写的后端。音频不保存，授权本身不会开始采集。")
            .setPositiveButton("继续授权") { _,_->permissionRequest.launch(Manifest.permission.RECORD_AUDIO) }
            .setNegativeButton("暂不授权",null).show()
    }
    private fun withEndpoint(action:(SpeechEndpoint)->Unit) {
        try {
            val endpoint=SpeechEndpoint.parse(http.text.toString(),ws.text.toString(),token.text.toString().trim())
            prefs.edit { putString("http",http.text.toString().trim());putString("ws",ws.text.toString().trim());putInt("engine",engine.selectedItemPosition);putInt("target",target.selectedItemPosition) }
            notice="";action(endpoint)
        } catch(e:Exception) { notice=e.message ?: "地址或参数无效" }
        render()
    }
    private fun runSpeech(probe:Boolean) {
        val selectedEngine=if(engine.selectedItemPosition==0)"asr" else "live"
        val language=if(target.selectedItemPosition==0)"en" else "zh"
        prefs.edit { putInt("connection",connection.selectedItemPosition);putInt("engine",engine.selectedItemPosition);putInt("target",target.selectedItemPosition) }
        if(connection.selectedItemPosition==1) {
            withEndpoint { if(probe)speech.check(it) else speech.start(it,selectedEngine,language) };return
        }
        try {
            val endpoint=AliyunConnection(workspace.text.toString().trim(),cloudKey.text.toString().trim())
            credentials.save(endpoint.key)
            prefs.edit { putString("workspace",endpoint.workspace) }
            notice="";speech.direct(endpoint,selectedEngine,language,probe)
        } catch(_:Exception) { notice="直连参数无效或无法加密保存，请检查业务空间和 Key 后重试。" }
        render()
    }
    private fun render() {
        val s=speech.snapshot()
        val phase=when(s.phase) {
            SpeechPhase.IDLE->"待机";SpeechPhase.CHECKING->"检查配置";SpeechPhase.CONNECTING->"等待模型"
            SpeechPhase.STREAMING->"采集中";SpeechPhase.STOPPING->"等待收尾";SpeechPhase.FINISHED->"已完成"
            SpeechPhase.CANCELLED->"已取消";SpeechPhase.ERROR->"错误"
        }
        status.text=getString(R.string.speech_status,phase,s.message,if(notice.isBlank())"" else "\n$notice")
        status.setTextColor(if(s.phase==SpeechPhase.ERROR)Color.rgb(255,170,160) else Color.WHITE)
        listOf(http,ws,token,engine,check,start,permission,connection,workspace,cloudKey,forget,saveDefault).forEach { it.isEnabled=!s.busy }
        cloudGroup.visibility=if(connection.selectedItemPosition==0)View.VISIBLE else View.GONE
        bridgeGroup.visibility=if(connection.selectedItemPosition==1)View.VISIBLE else View.GONE
        target.isEnabled=!s.busy && engine.selectedItemPosition==1
        stop.isEnabled=s.phase in listOf(SpeechPhase.STREAMING,SpeechPhase.CONNECTING,SpeechPhase.CHECKING)
        cancel.isEnabled=s.busy
        meter.progress=s.level
        counts.text=getString(R.string.speech_stats,if(s.microphone)"正在采集" else "未采集",if(hasMicrophone())"已允许" else "未允许",
            s.input,s.packets,s.bytes,s.bytes/32000.0,s.messages,s.model.ifBlank { "等待 ready" })
        fun update(view:TextView,value:String) { if(view.text.toString()!=value)view.text=value }
        update(textFields,s.sentences.joinToString("\n\n").ifBlank { "暂无返回文字" })
        update(data,s.lastData.ifBlank { "等待 result / segment" })
        update(events,s.lastEvent.ifBlank { "等待协议消息" })
        update(config,s.config.ifBlank { "尚未连接" })
        if(BuildConfig.DEBUG)runCatching {
            val json=JSONObject().put("phase",s.phase.name).put("message",s.message).put("config",s.config)
                .put("model",s.model).put("packets",s.packets).put("bytes",s.bytes).put("messages",s.messages)
                .put("microphone",s.microphone).put("permission",hasMicrophone()).put("input",s.input)
                .put("lastEvent",s.lastEvent).put("lastData",s.lastData).put("sentences",org.json.JSONArray(s.sentences))
            val file=File(cacheDir,"speech-diagnostic.json");val value=json.toString(2)
            if(!file.exists() || file.readText()!=value)file.writeText(value)
        }
        if(s.busy)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun label(value:String,size:Float=16f,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(Color.WHITE);if(bold)typeface=Typeface.DEFAULT_BOLD
        setLineSpacing(dp(3).toFloat(),1f)
        root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
    }
    private fun selectable(value:String)=label(value,14f).apply { setTextIsSelectable(true);typeface=Typeface.MONOSPACE }
    private fun field(title:String,hintText:String,value:String):EditText {
        label(title,15f,true)
        return EditText(this).apply {
            setText(value);hint=hintText;textSize=15f;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine(true)
            root.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
    }
    private fun spinner(items:List<String>,selection:Int)=Spinner(this).apply {
        adapter=ArrayAdapter(this@SpeechActivity,android.R.layout.simple_spinner_dropdown_item,items)
        setSelection(selection);root.addView(this,LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=value;textSize=16f;isAllCaps=false;setOnClickListener { action() }
        root.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
