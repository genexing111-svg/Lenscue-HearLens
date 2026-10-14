package com.hearlens.app

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One bounded session per hold. All mutable session state is owned by the serial executor. */
internal class PttClient(private val microphone:()->SpeechMicrophone,private val playback:()->PttPlayback,
    private val http:OkHttpClient=OkHttpClient.Builder().followRedirects(false).connectTimeout(12,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build(),
    private val endpointOverride:HttpUrl?=null,private val readyTimeoutMs:Long=15000,private val finishTimeoutMs:Long=30000,
    private val clock:()->Long={System.nanoTime()/1_000_000}) {
    private val worker=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"HearLens-PTT").apply {isDaemon=true}}
    @Volatile var state=PttState();private set
    @Volatile var changed:(PttState)->Unit={}
    @Volatile private var run:Run?=null
    @Volatile private var released=false
    private var sequence=0L
    private class Run(val id:Long,val connection:AliyunConnection,val protocol:PttProtocol) {
        var held=true;var micDone=false;var receivedInput=false;var ready=false;var stopSent=false;var finished=false
        var socket:WebSocket?=null;var mic:SpeechMicrophone?=null;var player:PttPlayback?=null
        var timer:ScheduledFuture<*>?=null;var limit:ScheduledFuture<*>?=null;var stopping=false;var pumping=false
        var rate=0;var inputTotal=0L;var outputTotal=0L
        val input=ArrayDeque<ByteArray>();val output=ArrayDeque<ByteArray>();val segments=linkedMapOf<String,JSONObject>()
    }
    private fun post(task:()->Unit) {if(!released)runCatching {worker.execute {if(!released)task()}}}
    private fun publish(value:PttState) {state=value;changed(value)}
    fun begin(connection:AliyunConnection,target:String="zh")=post {
        if(run!=null)return@post
        val r=Run(++sequence,connection,PttProtocol(target));run=r
        publish(PttState(PttPhase.CONNECTING,message="准备收音…",generation=r.id))
        r.timer=worker.schedule({fail(r,"连接或模型配置超时")},readyTimeoutMs,TimeUnit.MILLISECONDS)
        r.limit=worker.schedule({if(run===r && r.held) {
            r.stopping=true;r.mic?.stop();publish(state.copy(phase=PttPhase.WAIT_RELEASE,message="已达 30 秒，松开播放",level=0))
        }},30,TimeUnit.SECONDS)
        val request=Request.Builder().url(endpointOverride ?: connection.url("live")).header("Authorization","Bearer ${connection.key}").build()
        r.socket=http.newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response)=post {if(run===r && !webSocket.send(r.protocol.initial()))fail(r,"会话配置发送失败")}
            override fun onMessage(webSocket:WebSocket,text:String)=post {
                if(run!==r)return@post
                try {require(text.length<=2_000_000) {"云端单条消息过大"};accept(r,JSONObject(text))}catch(e:Exception) {fail(r,e.message ?: "云端数据错误")}
            }
            override fun onMessage(webSocket:WebSocket,bytes:ByteString)=post {fail(r,"云端返回了非 JSON 数据")}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?)=post {fail(r,"连接失败${response?.code?.let {"（HTTP $it）"} ?: ""}：${t.message ?: "网络错误"}")}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {webSocket.close(code,null)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String)=post {if(!r.finished)fail(r,"连接提前关闭（$code），请重试")}
        })
        val mic=microphone();r.mic=mic
        try {mic.start({bytes->
            if(run!==r || r.micDone)false else {val copy=bytes.copyOf();post {input(r,copy)};true}
        },{level,_->post {if(run===r && r.held && !r.stopping)publish(state.copy(level=level,levelAt=clock()))}},
            {post {micStopped(r)}},{message->post {fail(r,message)}})
        }catch(e:Exception) {fail(r,e.message ?: "无法启动麦克风")}
    }
    private fun input(r:Run,bytes:ByteArray) {
        if(run!==r || r.micDone)return
        if(bytes.isEmpty() || bytes.size%2!=0 || bytes.size>640) {fail(r,"麦克风 PCM 包格式错误");return}
        r.receivedInput=true;r.inputTotal+=bytes.size
        if(r.inputTotal>16000*2*31) {fail(r,"本轮录音超过时长上限");return}
        r.input+=bytes
        publish(state.copy(phase=if(r.held && !r.stopping)PttPhase.RECORDING else state.phase,
            message=if(r.held && !r.stopping)if(r.ready)"松开播放" else "正在收音 · 连接中" else state.message,inputBytes=r.inputTotal))
        pump(r)
    }
    private fun pump(r:Run) {
        if(run!==r || !r.ready || r.stopSent || r.pumping)return
        val socket=r.socket ?: return
        if(socket.queueSize()>64000) {fail(r,"网络发送积压，请检查网络后重试");return}
        repeat(8) {if(r.input.isNotEmpty() && socket.queueSize()<8000) {
            if(!socket.send(r.protocol.audio(r.input.removeFirst()))) {fail(r,"音频发送失败");return}
        }}
        if(r.input.isNotEmpty()) {
            r.pumping=true;worker.schedule({r.pumping=false;pump(r)},10,TimeUnit.MILLISECONDS)
        } else if(r.micDone && !r.held) {
            r.stopSent=true
            if(!socket.send(r.protocol.finish())) {fail(r,"结束请求发送失败");return}
            r.timer?.cancel(false);r.timer=worker.schedule({fail(r,"等待生成音频超时")},finishTimeoutMs,TimeUnit.MILLISECONDS)
        }
    }
    fun end()=post {
        val r=run ?: return@post
        if(!r.held)return@post
        r.held=false;r.limit?.cancel(false)
        if(!r.receivedInput) {cancelRun(r,"按住时间太短，请重试");return@post}
        publish(state.copy(phase=PttPhase.FINISHING,message="等待生成音频…",level=0))
        if(r.micDone) {drain(r);pump(r)} else {
            r.stopping=true;r.mic?.stop()
            worker.schedule({if(run===r && !r.micDone)fail(r,"麦克风未能及时停止，已取消播放")},3,TimeUnit.SECONDS)
        }
    }
    private fun micStopped(r:Run) {
        if(run!==r)return
        r.micDone=true
        if(!r.stopping) {fail(r,"麦克风被中断");return}
        if(!r.held) {drain(r);pump(r)}
    }
    private fun accept(r:Run,j:JSONObject) {
        when(j.getString("type")) {
            "session.updated"->{check(!r.ready);r.rate=r.protocol.ready(j);r.ready=true;r.timer?.cancel(false)
                publish(state.copy(sampleRate=r.rate));pump(r)}
            "response.audio.delta"->{
                check(r.ready) {"音频早于配置确认"}
                val bytes=Base64.getDecoder().decode(j.getString("delta"));require(bytes.size%2==0) {"云端 PCM 长度无效"}
                r.outputTotal+=bytes.size;require(r.outputTotal<=r.rate*2L*120) {"生成音频超过 120 秒上限"}
                if(bytes.isNotEmpty())r.output+=bytes;publish(state.copy(outputBytes=r.outputTotal));drain(r)
            }
            "session.finished"->{
                check(r.stopSent) {"云端提前结束本轮"};r.finished=true;r.timer?.cancel(false);r.socket?.close(1000,"finished")
                drain(r)
                if(r.player!=null)r.player?.finish() else if(state.text.isBlank())complete(r,"未识别到语音") else fail(r,"识别到文字但未返回可播放音频，请重试")
            }
            "error","conversation.item.input_audio_transcription.failed"->fail(r,j.optJSONObject("error")?.optString("message") ?: "云端处理失败")
            else->{
                if(j.optString("type")=="response.done" && j.optJSONObject("response")?.optString("status")=="failed") {fail(r,"云端未能生成重述音频");return}
                for(segment in r.protocol.segments(j))r.segments[segment.getString("id")]=segment
                val original=r.segments.values.joinToString(" ") {it.optString("original").trim()}.trim().takeLast(8192)
                val output=r.segments.values.joinToString(" ") {s->s.optJSONObject("translations")?.let {translations->translations.keys().asSequence().firstOrNull()?.let {translations.optString(it)}}.orEmpty().trim()}.trim().takeLast(8192)
                if(original!=state.text || output!=state.output)publish(state.copy(text=original,output=output))
            }
        }
    }
    private fun drain(r:Run) {
        if(run!==r || r.held || !r.micDone || r.output.isEmpty())return
        if(r.player==null) {
            val p=playback();r.player=p
            try {p.start(r.rate,{post {if(run===r && r.finished)complete(r,"播放完成")}},
                {message->post {fail(r,message)}},{level->post {if(run===r)publish(state.copy(level=level,levelAt=clock()))}})
            }catch(e:Exception) {fail(r,e.message ?: "播放器启动失败");return}
            publish(state.copy(phase=PttPhase.PLAYING,message="正在播放…",level=0))
        }
        while(r.output.isNotEmpty())if(r.player?.offer(r.output.removeFirst())!=true) {fail(r,"播放缓冲已满");return}
    }
    private fun clean(r:Run) {r.timer?.cancel(false);r.limit?.cancel(false);r.mic?.stop();r.input.clear();r.output.clear()}
    private fun complete(r:Run,message:String) {if(run!==r)return;run=null;clean(r);publish(state.copy(phase=PttPhase.DONE,message=message,level=0))}
    private fun fail(r:Run,message:String) {if(run!==r)return;run=null;clean(r);r.socket?.cancel();r.player?.cancel()
        publish(state.copy(phase=PttPhase.ERROR,message=message.replace(r.connection.key,"[已隐藏]").take(160),level=0))}
    private fun cancelRun(r:Run,message:String) {if(run!==r)return;run=null;clean(r);r.socket?.cancel();r.player?.cancel();publish(state.copy(phase=PttPhase.DONE,message=message,level=0))}
    fun cancel(message:String="本轮已取消")=post {run?.let {cancelRun(it,message)}}
    fun release() {post {run?.let {cancelRun(it,"已退出")};released=true;worker.shutdown();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}}
}
