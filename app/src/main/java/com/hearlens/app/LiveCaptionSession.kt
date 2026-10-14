package com.hearlens.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONObject

/** Activity-owned capture: entering VR never transfers audio ownership to a background service. */
internal class LiveCaptionSession(private val context:Context,private val engine:LocalStereoEngine,microphoneFactory:()->SpeechMicrophone={PhoneMicrophone(context)}) {
    private val prefs=context.getSharedPreferences("caption_v9",Context.MODE_PRIVATE)
    private val transcript=CaptionTranscript()
    private val client=SpeechClient(microphoneFactory)
    @Volatile var mode=CaptionMode.restore(prefs.getString("mode",null),prefs.getBoolean("translated",false),prefs.getString("target","en") ?: "en");private set
    val translated get()=mode.translation
    val target get()=mode.target
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var pending:CaptionMode?=null
    private var switchingError:String?=null
    @Volatile var enabled=false;private set
    @Volatile private var suspendedForPtt=false
    @Volatile private var captionEpoch=0L
    private val scriptLock=Any()
    private val scripts=mutableMapOf<String,android.icu.text.Transliterator>()
    private val scriptConversionReady=Build.VERSION.SDK_INT>=29 && runCatching {
        android.icu.text.Transliterator.getInstance("Simplified-Traditional").transliterate("汉语") == "漢語" &&
            android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate("漢語") == "汉语"
    }.getOrDefault(false)
    val snapshot get()=client.snapshot()
    val active get()=snapshot.busy || pending!=null
    val selectedMode get()=pending ?: mode
    val choiceIndex get()=CaptionChoices.index(selectedMode,enabled)
    private val switchWhenFinished=object:Runnable {
        override fun run() {
            val requested=pending ?: return
            if(snapshot.busy) { handler.postDelayed(this,100);return }
            pending=null
            val healthy=snapshot.phase==SpeechPhase.FINISHED
            persist(requested);clear()
            if(healthy)runCatching { start() }.onFailure { enabled=false;publish();switchingError=it.message }
            else {enabled=false;publish();switchingError="旧会话未正常结束，请重新选择字幕语言：${snapshot.message}"}
        }
    }
    init {
        engine.captionStyle=runCatching { CaptionStyle.fromJson(JSONObject(prefs.getString("style","{}")!!)) }.getOrDefault(CaptionStyle())
        prefs.edit {putString("style",styleJson().toString())}
        client.onResult={ value ->
            if(enabled && !suspendedForPtt) {transcript.accept(value,target,captionEpoch.toString());publish()}
        }
    }
    private fun publish() {
        // PTT freezes the already published rows; stopping network capture is not a clear command.
        if(suspendedForPtt)return
        if(!enabled || pending!=null) {engine.captionTurns=emptyList();engine.onFrame?.invoke();return}
        val m=mode
        val turns=transcript.turns(m.translation)
        engine.captionTurns=if(m==CaptionMode.TRADITIONAL || m==CaptionMode.SIMPLIFIED)turns.map { it.copy(text=convertScript(it.text,m)) } else turns
        engine.onFrame?.invoke()
    }
    private fun convertScript(text:String,m:CaptionMode):String {
        if(Build.VERSION.SDK_INT<29)return text
        return synchronized(scriptLock) {
            val id=if(m==CaptionMode.TRADITIONAL)"Simplified-Traditional" else "Traditional-Simplified"
            scripts.getOrPut(id) { android.icu.text.Transliterator.getInstance(id) }.transliterate(text)
        }
    }
    private fun persist(next:CaptionMode) { mode=next;prefs.edit { putString("mode",next.name) };publish() }
    fun select(next:CaptionMode) {
        require(next!=CaptionMode.TRADITIONAL || scriptConversionReady) { "此系统未提供可用的繁体转换" }
        switchingError=null
        if(pending!=null) { pending=next;return }
        if(next==mode)return
        if(!active || mode.sameSession(next)) { persist(next);return }
        pending=next;publish();client.stop();handler.postDelayed(switchWhenFinished,100)
    }
    fun start(preserveCaptions:Boolean=false) {
        if(suspendedForPtt) {enabled=true;return}
        check(!active) { "语音字幕会话尚未停止" }
        val speechPrefs=context.getSharedPreferences("speech_v7",Context.MODE_PRIVATE)
        val key=SpeechCredentials(context).read()
        require(key.isNotBlank()) { "请先在“语音接口测试”配置阿里云 Key" }
        val connection=AliyunConnection(speechPrefs.getString("workspace","") ?: "",key)
        switchingError=null;enabled=true
        if(!preserveCaptions)clear()
        ++captionEpoch
        try {client.direct(connection,mode.protocol,target)} catch(e:Exception) {enabled=false;if(!preserveCaptions)publish();throw e}
    }
    fun enable(next:CaptionMode) {
        require(next!=CaptionMode.TRADITIONAL || scriptConversionReady) {"此系统未提供可用的繁体转换"}
        // Off can be selected while final fields are in flight. Queue a new session, never overlap mics.
        if(!enabled && active) {
            enabled=true;pending=next;switchingError=null;clear();client.stop()
            handler.removeCallbacks(switchWhenFinished);handler.postDelayed(switchWhenFinished,100);return
        }
        select(next)
        if(!active)start() else enabled=true
    }
    fun stop() { enabled=false;pending=null;handler.removeCallbacks(switchWhenFinished);engine.captionTurns=emptyList();engine.onFrame?.invoke();client.stop() }
    fun suspendForPtt() {
        suspendedForPtt=true;pending=null;handler.removeCallbacks(switchWhenFinished)
        client.cancel("语音重构期间暂停上传，已有字幕保留");engine.onFrame?.invoke()
    }
    fun resumeAfterPtt() {
        suspendedForPtt=false
        if(enabled)runCatching {start(preserveCaptions=true)}.onFailure {
            enabled=false;switchingError="恢复连接失败，已有字幕保留：${it.message}";engine.onFrame?.invoke()
        }
    }
    fun background() { enabled=false;suspendedForPtt=false;pending=null;handler.removeCallbacks(switchWhenFinished);publish();if(active)client.cancel("离开画面，语音字幕已停止；请重新选择字幕语言") }
    fun release() { pending=null;handler.removeCallbacks(switchWhenFinished);client.release() }
    fun clear() { transcript.clear();engine.captionTurns=emptyList();engine.onFrame?.invoke() }
    fun switchText() { select(if(translated)CaptionMode.SPEAKERS else CaptionMode.ENGLISH) }
    fun switchTarget() { select(if(target=="en")CaptionMode.SIMPLIFIED else CaptionMode.ENGLISH) }
    fun styleJson()=engine.captionStyle.toJson()
    fun configure(j:JSONObject) { engine.captionStyle=CaptionStyle.fromJson(j);prefs.edit { putString("style",styleJson().toString()) };engine.onFrame?.invoke() }
    fun status():String=if(suspendedForPtt && enabled)"暂停上传 · 保留已有字幕" else if(pending!=null)"正在切换 · ${pending!!.label}" else if(switchingError!=null)"切换未完成 · 请重新开启" else when(snapshot.phase) {
        SpeechPhase.STREAMING->"开启 · ${mode.label}"
        SpeechPhase.CONNECTING,SpeechPhase.CHECKING->"正在连接"
        SpeechPhase.STOPPING->"等待最后一句"
        SpeechPhase.ERROR->"错误 · 请查看手机提示"
        else->"关闭 · 请在字幕语言中选择识别方式"
    }
    fun diagnostic():JSONObject {
        val s=snapshot
        return JSONObject().put("phase",s.phase.name).put("message",s.message).put("microphone",s.microphone)
            .put("packets",s.packets).put("bytes",s.bytes).put("messages",s.messages).put("lastData",s.lastData)
            .put("mode",mode.name).put("modeLabel",mode.label).put("target",target).put("enabled",enabled).put("choiceIndex",choiceIndex)
            .put("suspendedForPtt",suspendedForPtt).put("captionEpoch",captionEpoch)
            .put("pendingMode",pending?.name ?: JSONObject.NULL).put("switchError",switchingError ?: JSONObject.NULL)
            .put("scriptConversionReady",scriptConversionReady)
            .put("maxLines",CaptionLayout.MAX_LINES).put("style",styleJson())
    }
}
