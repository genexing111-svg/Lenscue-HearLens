package com.hearlens.app

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class SpeechEndpoint(val http:HttpUrl,val socket:HttpUrl,val token:String) {
    companion object {
        fun parse(http:String,ws:String,token:String):SpeechEndpoint {
            fun base(value:String):HttpUrl {
                val url=value.trim().replaceFirst(Regex("^wss://"),"https://").replaceFirst(Regex("^ws://"),"http://").toHttpUrl()
                require(url.username.isEmpty() && url.password.isEmpty() && url.query==null && url.fragment==null) { "地址不能包含密码、查询参数或 #" }
                val parts=url.host.split('.').mapNotNull { it.toIntOrNull() }
                val privateIp=parts.size==4 && parts.all { it in 0..255 } && (parts[0]==10 || parts[0]==127 || parts[0]==192 && parts[1]==168 || parts[0]==172 && parts[1] in 16..31)
                require(url.isHttps || privateIp || url.host in listOf("localhost","::1") || url.host.endsWith(".local")) { "公网地址请使用 HTTPS / WSS；HTTP 仅供局域网或本机调试" }
                return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/')+"/").build()
            }
            require(http.isNotBlank()) { "请填写手机可访问的后端 HTTP 地址" }
            require(token.length<=8192 && token.all { it.code in 32..126 } && !token.startsWith("sk-")) { "请填写后端访问令牌，不要填写云端 API Key" }
            return SpeechEndpoint(base(http),base(ws.ifBlank { http }),token)
        }
    }
    fun request(url:HttpUrl):Request= Request.Builder().url(url).apply {
        if(token.isNotBlank())header("Authorization","Bearer $token")
    }.build()
}

internal enum class SpeechPhase { IDLE,CHECKING,CONNECTING,STREAMING,STOPPING,FINISHED,CANCELLED,ERROR }
internal data class SpeechSnapshot(
    val phase:SpeechPhase=SpeechPhase.IDLE,val message:String="待测试：尚未连接或录音",val config:String="",
    val model:String="",val lastEvent:String="",val lastData:String="",val sentences:List<String> = emptyList(),
    val packets:Long=0,val bytes:Long=0,val messages:Int=0,val level:Int=0,val input:String="未采集",val microphone:Boolean=false
) {
    val busy get()=phase in listOf(SpeechPhase.CHECKING,SpeechPhase.CONNECTING,SpeechPhase.STREAMING,SpeechPhase.STOPPING)
}

/** Frontend bridge protocol from the handoff, independently testable without an Android microphone. */
internal class SpeechClient(private val microphoneFactory:()->SpeechMicrophone,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val readyTimeoutMs:Long=20000,private val finishTimeoutMs:Long?=null) {
    private class Run(val generation:Long,val endpoint:SpeechEndpoint,val engine:String,val target:String,
        val cloud:AliyunProtocol?=null,val probe:Boolean=false) {
        var socket:WebSocket?=null
        var mic:SpeechMicrophone?=null
        var ready=false;var stopping=false;var stopSent=false
        var timer:ScheduledFuture<*>?=null
        val sentences=linkedMapOf<String,String>()
    }
    private val lock=Any()
    private val timers=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"HearLens-speech-timeouts").apply { isDaemon=true } }
    private var generation=0L
    private var active:Run?=null
    private var configCall:Call?=null
    @Volatile private var state=SpeechSnapshot()
    @Volatile var onResult:((JSONObject)->Unit)?=null
    fun snapshot()=state
    private fun safe(text:String,token:String=""):String {
        val noToken=if(token.isEmpty())text else text.replace(token,"[已隐藏令牌]")
        return noToken.replace(Regex("(?<![A-Za-z0-9_])(?:sk|st)-[A-Za-z0-9_.-]+"),"[已隐藏密钥]").take(16000)
    }
    fun check(endpoint:SpeechEndpoint)=begin(endpoint,null,"en")
    fun start(endpoint:SpeechEndpoint,engine:String,target:String) {
        require(engine in listOf("asr","live") && target in listOf("en","zh"))
        begin(endpoint,engine,target)
    }
    fun direct(connection:AliyunConnection,engine:String,target:String,probe:Boolean=false):Unit=synchronized(lock) {
        require(engine in listOf("asr","live") && target in listOf("en","zh"))
        check(!state.busy) { "请先停止当前会话" }
        val url=connection.url(engine)
        state=SpeechSnapshot(config=JSONObject().put("connection","手机直连阿里云 · 北京")
            .put("endpoint",url.toString().replaceFirst("https://","wss://"))
            .put("model",if(engine=="live")AliyunProtocol.LIVE_MODEL else AliyunProtocol.ASR_MODEL)
            .put("voice",if(engine=="live")AliyunProtocol.LIVE_VOICE else JSONObject.NULL)
            .put("output",if(engine=="live")"text only" else "transcription")
            .put("sampleRate",16000).put("channels",1).put("format","pcm_s16le").put("frameMs",20).toString(2))
        connect(Run(++generation,SpeechEndpoint(url,url,connection.key),engine,target,AliyunProtocol(engine,target),probe))
    }
    private fun begin(endpoint:SpeechEndpoint,engine:String?,target:String)=synchronized(lock) {
        check(!state.busy) { "请先停止当前会话" }
        val id=++generation
        state=SpeechSnapshot(SpeechPhase.CHECKING,"正在读取后端配置（不采集音频）")
        val call=client.newCall(endpoint.request(endpoint.http.resolve("api/config")!!))
        configCall=call;call.timeout().timeout(10,TimeUnit.SECONDS)
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException)=synchronized(lock) {
                if(id!=generation)return@synchronized
                configCall=null;state=state.copy(phase=SpeechPhase.ERROR,message="无法连接后端：${safe(e.message ?: "网络错误",endpoint.token)}")
            }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val json=response.use {
                        check(it.isSuccessful) { "配置接口返回 HTTP ${it.code}" }
                        val body=it.body ?: error("配置接口没有内容")
                        check(body.contentLength()<=65536) { "配置响应过大" }
                        val source=body.source();source.request(65537)
                        check(source.buffer.size<=65536) { "配置响应过大" }
                        JSONObject(source.readUtf8())
                    }
                    synchronized(lock) {
                        if(id!=generation)return
                        configCall=null
                        check(json.opt("configured") is Boolean) { "配置接口缺少 configured 字段" }
                        state=state.copy(config=safe(json.toString(2),endpoint.token))
                        if(!json.getBoolean("configured")) {
                            state=state.copy(phase=SpeechPhase.ERROR,message="后端尚未配置云端密钥；请由后端配置，不要填入 App");return
                        }
                        if(engine==null) { state=state.copy(phase=SpeechPhase.IDLE,message="配置接口可访问；云端密钥有效性尚未验证");return }
                        val engines=json.optJSONArray("engines") ?: error("配置接口缺少 engines 字段")
                        check((0 until engines.length()).any { engines.optJSONObject(it)?.optString("id")==engine }) { "后端未提供所选识别模式" }
                        check(json.optInt("frameMs")==20) { "后端音频分包规格不是 20 ms" }
                        connect(Run(id,endpoint,engine,target))
                    }
                } catch(e:Exception) { synchronized(lock) {
                    if(id==generation) {
                        configCall=null
                        val run=active
                        if(run!=null)fail(run,e.message ?: "连接初始化失败")
                        else state=state.copy(phase=SpeechPhase.ERROR,message=safe(e.message ?: "配置响应无效",endpoint.token))
                    }
                } }
            }
        })
    }
    private fun connect(run:Run) {
        active=run;state=state.copy(phase=SpeechPhase.CONNECTING,message="等待模型 ready；麦克风尚未启动")
        val url=if(run.cloud!=null)run.endpoint.socket else run.endpoint.socket.resolve("stream")!!.newBuilder().addQueryParameter("engine",run.engine).apply {
            if(run.engine=="live")addQueryParameter("target",run.target)
        }.build()
        run.timer=timers.schedule({ synchronized(lock) { fail(run,"等待模型 ready 超时") } },readyTimeoutMs,TimeUnit.MILLISECONDS)
        run.socket=client.newWebSocket(run.endpoint.request(url),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response):Unit=synchronized(lock) {
                if(active!==run) { webSocket.cancel();return@synchronized }
                run.cloud?.let { if(!webSocket.send(it.initial()))fail(run,"模型初始化请求发送失败") }
            }
            override fun onMessage(webSocket:WebSocket,text:String)=synchronized(lock) {
                if(active!==run)return@synchronized
                try {
                    check(text.length<=65536) { "后端返回字段过大" }
                    val raw=JSONObject(text)
                    state=state.copy(lastEvent=safe(raw.toString(2),run.endpoint.token),messages=state.messages+1)
                    val messages=run.cloud?.decode(raw) ?: listOf(raw)
                    for(j in messages) {
                    if(active!==run)break
                    val type=j.getString("type")
                    when(type) {
                        "ready" -> {
                            check(!run.ready && j.getString("engine")==run.engine && j.getInt("frameMs")==20) { "ready 字段与请求不匹配" }
                            run.timer?.cancel(false);run.ready=true
                            if(run.probe) {
                                state=state.copy(phase=SpeechPhase.STOPPING,message="鉴权和模型初始化成功，正在结束检查（未录音）",model=j.getString("model"))
                                run.stopping=true;captureStopped(run)
                                continue
                            }
                            state=state.copy(phase=SpeechPhase.STREAMING,message="正在采集并上传手机麦克风",model=j.getString("model"),microphone=true)
                            val mic=microphoneFactory();run.mic=mic
                            mic.start({ bytes->sendAudio(run,bytes) },{ level,input-> synchronized(lock) {
                                if(active===run)state=state.copy(level=level,input=input)
                            } },{ captureStopped(run) },{ message->synchronized(lock) { fail(run,message) } })
                        }
                        "result","segment" -> {
                            check(run.ready) { "后端在 ready 前返回识别数据" }
                            val v=j.getJSONObject(if(type=="result")"sentence" else "segment")
                            val key=if(type=="result")"asr:${v.opt("sentence_id") ?: v.opt("begin_time") ?: "current"}" else "live:${v.getString("id")}" 
                            // Replace each complete snapshot, including final flags and unknown/provider fields.
                            val summary=if(type=="result")"句子 ${v.opt("sentence_id") ?: "未知"} · final=${v.opt("sentence_end")}\n${v.optString("text")}" else
                                "句段 ${v.getString("id")} · speakerId=${v.opt("speakerId")} · originalFinal=${v.opt("originalFinal")}\n${v.optString("original")}\ntranslations=${v.optJSONObject("translations")}\ntranslationFinal=${v.optJSONObject("translationFinal")}" 
                            run.sentences[key]=safe(summary,run.endpoint.token).take(2000)
                            while(run.sentences.size>12)run.sentences.remove(run.sentences.keys.first())
                            state=state.copy(lastData=safe(j.toString(2),run.endpoint.token),sentences=run.sentences.values.toList())
                            onResult?.invoke(j) // Data callback consumes provider fields, not the truncated debug display.
                        }
                        "configured" -> Unit
                        "restart_required" -> fail(run,"后端要求重连；请停止后选择模式 / 目标语言重新开始")
                        "error" -> fail(run,j.optString("message","后端返回错误"))
                        "finished" -> {
                            active=null;run.timer?.cancel(false);run.mic?.stop();run.socket?.close(1000,"finished")
                            state=state.copy(phase=SpeechPhase.FINISHED,message=if(run.probe)"手机直连检查通过：鉴权、模型初始化、正常结束均成功；未采集音频" else "收到 finished，最终字段已保留",microphone=false,level=0)
                        }
                        else -> Unit // Keep future protocol fields visible without treating them as subtitles.
                    }
                    }
                } catch(e:Exception) { fail(run,e.message ?: "后端返回无效 JSON") }
            }
            override fun onMessage(webSocket:WebSocket,bytes:ByteString)=synchronized(lock) { fail(run,"后端应返回 JSON 文本，收到意外二进制消息") }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?)=synchronized(lock) {
                fail(run,"连接失败${response?.let { "（HTTP ${it.code}）" } ?: ""}：${t.message ?: "网络错误"}")
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,null) }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String)=synchronized(lock) {
                fail(run,"连接提前关闭（$code），未收到 finished；尾句可能不完整")
            }
        })
    }
    private fun sendAudio(run:Run,bytes:ByteArray):Boolean=synchronized(lock) {
        if(active!==run || !run.ready || run.stopSent)return false
        val socket=run.socket ?: return false
        if(bytes.isEmpty() || bytes.size%2!=0 || bytes.size>640) { fail(run,"PCM 包格式错误");return false }
        if(socket.queueSize()>32000) { fail(run,"音频发送积压超过 1 秒，已停止；请检查网络");return false }
        val cloudAudio=run.cloud?.audio(bytes)
        if(!(if(cloudAudio==null)socket.send(bytes.toByteString()) else socket.send(cloudAudio))) { fail(run,"音频发送失败");return false }
        state=state.copy(packets=state.packets+1,bytes=state.bytes+bytes.size)
        true
    }
    fun stop():Unit=synchronized(lock) {
        val run=active
        if(run==null || !run.ready) { cancel("已取消连接；未继续采集");return }
        if(run.stopping)return
        run.stopping=true;state=state.copy(phase=SpeechPhase.STOPPING,message="停止采集，等待最终字段")
        // Bound even an unresponsive microphone driver; normal order is tail -> stop -> finished.
        run.timer=timers.schedule({ synchronized(lock) { fail(run,"麦克风停止超时") } },3000,TimeUnit.MILLISECONDS)
        if(run.mic==null)captureStopped(run) else run.mic?.stop()
    }
    private fun captureStopped(run:Run):Unit=synchronized(lock) {
        if(active!==run)return
        state=state.copy(microphone=false,level=0)
        if(!run.stopping) { fail(run,"麦克风采集中断");return }
        run.stopSent=true;run.timer?.cancel(false)
        if(run.socket?.send(run.cloud?.finish() ?: "{\"type\":\"stop\"}")!=true) { fail(run,"停止请求发送失败");return }
        run.timer=timers.schedule({ synchronized(lock) { fail(run,"等待最终字段超时；麦克风已停止") } },
            finishTimeoutMs ?: if(run.engine=="asr")10000L else 18000L,TimeUnit.MILLISECONDS)
    }
    private fun fail(run:Run,message:String) {
        if(active!==run)return
        active=null;run.timer?.cancel(false);run.mic?.stop();run.socket?.cancel()
        state=state.copy(phase=SpeechPhase.ERROR,message=safe(message,run.endpoint.token),microphone=false,level=0)
    }
    fun cancel(message:String="已取消；麦克风已停止")=synchronized(lock) {
        ++generation;configCall?.cancel();configCall=null
        val run=active;active=null;run?.timer?.cancel(false);run?.mic?.stop();run?.socket?.cancel()
        state=state.copy(phase=SpeechPhase.CANCELLED,message=message,microphone=false,level=0)
    }
    fun release() { cancel();timers.shutdownNow();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
}
