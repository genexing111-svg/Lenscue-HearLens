package com.hearlens.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in test APK diagnostic. The saved key stays on device; only supplied public PCM is uploaded. */
internal object AliyunLiveDiagnostic {
    fun run(app:Context,test:Context):String {
        val credentials=SpeechCredentials(app);val key=credentials.read()
        check(key.isNotBlank()) {"No readable saved API key; no network request sent"}
        credentials.save(key);check(credentials.read()==key) {"Saved credential verification failed"}
        val workspace=app.getSharedPreferences("speech_v7",Context.MODE_PRIVATE).getString("workspace","") ?: ""
        val connection=AliyunConnection(workspace,key)
        val bytes=test.assets.open("environment/public-reference-speech.wav").use {it.readBytes()}
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset=12;var pcm=byteArrayOf()
        while(offset+8<bytes.size) {val name=String(bytes,offset,4,Charsets.US_ASCII);val n=buffer.getInt(offset+4)
            if(name=="data") {pcm=bytes.copyOfRange(offset+8,offset+8+n);break};offset+=8+n+n%2}
        check(pcm.isNotEmpty())
        val root=File(app.cacheDir,"aliyun-live-check").apply {mkdirs()}
        val results=JSONObject().put("credentialResaved",true).put("keyPresent",true).put("endpointKind",if(workspace.isBlank())"Beijing general" else "Beijing workspace")
            .put("audioSource","supplied public WAV, no microphone").put("cases",JSONArray())
        val variants=listOf("baseline","nested-tina","flat-tina")
        for(variant in variants) {
            val result=probe(connection,pcm,variant)
            results.getJSONArray("cases").put(result)
            File(root,"results.json").writeText(results.toString(2).replace(key,"[redacted]"))
            if(variant!="baseline" && result.optBoolean("hasText") && result.optString("error").isBlank())break
            if(result.optInt("httpStatus")==401 || result.optInt("httpStatus")==403)break
        }
        return "Completed safe live diagnostic; saved key refreshed. Results in cache/aliyun-live-check/results.json (no key)."
    }
    private fun probe(connection:AliyunConnection,pcm:ByteArray,variant:String):JSONObject {
        val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
        val ready=CountDownLatch(1);val done=CountDownLatch(1);val failed=AtomicBoolean(false);val cancelling=AtomicBoolean(false)
        val lock=Any();val result=JSONObject().put("variant",variant).put("model",AliyunProtocol.LIVE_MODEL).put("hasText",false).put("error","")
        var originalChars=0;var translationChars=0
        fun voice(session:JSONObject?):String=session?.optJSONObject("audio")?.optJSONObject("output")?.optString("voice").orEmpty().ifBlank {session?.optString("voice").orEmpty()}
        val protocol=AliyunProtocol("live","en")
        val request=JSONObject(protocol.initial())
        val session=request.getJSONObject("session")
        session.getJSONObject("audio").remove("output") // Keep the historical baseline even after production is fixed.
        if(variant=="nested-tina")session.getJSONObject("audio").put("output",JSONObject().put("voice","Tina"))
        if(variant=="flat-tina")session.put("voice","Tina")
        result.put("requestSession",session)
        val socket=client.newWebSocket(Request.Builder().url(connection.url("live")).header("Authorization","Bearer ${connection.key}").build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {synchronized(lock) {result.put("httpStatus",response.code)};webSocket.send(request.toString())}
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(cancelling.get())return
                try {
                    val j=JSONObject(text);val type=j.optString("type")
                    synchronized(lock) {
                        when(type) {
                            "session.created"->{result.put("createdVoice",voice(j.optJSONObject("session")));result.put("createdModel",j.optJSONObject("session")?.optString("model"))}
                            "session.updated"->{result.put("updatedVoice",voice(j.optJSONObject("session")));ready.countDown()}
                            "error","conversation.item.input_audio_transcription.failed"->{result.put("error",(j.optJSONObject("error")?.toString() ?: type).replace(connection.key,"[redacted]").take(1200));failed.set(true);ready.countDown();done.countDown()}
                            "conversation.item.input_audio_transcription.delta"->{originalChars+=j.optString("delta").length}
                            "conversation.item.input_audio_transcription.text"->{originalChars=maxOf(originalChars,j.optString("text").length)}
                            "conversation.item.input_audio_transcription.completed"->{originalChars=maxOf(originalChars,j.optString("transcript").length)}
                            "response.text.delta","response.audio_transcript.delta"->{translationChars+=j.optString("delta").length}
                            "response.text.done","response.audio_transcript.done"->{translationChars=maxOf(translationChars,j.optString("text",j.optString("transcript")).length)}
                            "response.done"->if(j.optJSONObject("response")?.optString("status")=="failed") {
                                result.put("error",j.optJSONObject("response")?.optJSONObject("status_details")?.toString()?.replace(connection.key,"[redacted]")?.take(1200) ?: "response failed");failed.set(true);done.countDown()
                            }
                            "session.finished"->{result.put("finished",true);done.countDown()}
                        }
                    }
                } catch(e:Exception) {synchronized(lock) {result.put("error",e.javaClass.simpleName)};failed.set(true);ready.countDown();done.countDown()}
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                if(cancelling.get())return
                synchronized(lock) {result.put("httpStatus",response?.code ?: result.optInt("httpStatus",0));result.put("error",t.javaClass.simpleName+": "+(t.message ?: "network failure").replace(connection.key,"[redacted]").take(300))}
                failed.set(true);ready.countDown();done.countDown()
            }
        })
        try {
            if(!ready.await(15,TimeUnit.SECONDS)) {synchronized(lock) {result.put("error","session.updated timeout")};failed.set(true)}
            if(!failed.get()) {
                val audio=pcm+ByteArray(32000)
                var offset=0
                while(offset<audio.size && !failed.get()) {val end=minOf(audio.size,offset+640);socket.send(protocol.audio(audio.copyOfRange(offset,end))!!);offset=end;Thread.sleep(20)}
                if(!failed.get()) {socket.send(protocol.finish());if(!done.await(15,TimeUnit.SECONDS))synchronized(lock) {result.put("finishTimeout",true)}}
            }
            synchronized(lock) {result.put("originalChars",originalChars).put("translationChars",translationChars).put("hasText",originalChars>0 || translationChars>0)}
        } finally {cancelling.set(true);socket.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
        return synchronized(lock) {JSONObject(result.toString())}
    }
    fun runProduction(app:Context,test:Context):String {
        val key=SpeechCredentials(app).read();check(key.isNotBlank()) {"No saved credential"}
        val workspace=app.getSharedPreferences("speech_v7",Context.MODE_PRIVATE).getString("workspace","") ?: ""
        val wave=test.assets.open("environment/public-reference-speech.wav").use {it.readBytes()}
        val b=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);var pos=12;var pcm=byteArrayOf()
        while(pos+8<wave.size) {val n=b.getInt(pos+4);if(String(wave,pos,4,Charsets.US_ASCII)=="data") {pcm=wave.copyOfRange(pos+8,pos+8+n);break};pos+=8+n+n%2}
        check(pcm.isNotEmpty())
        val sent=CountDownLatch(1);val stopFlag=AtomicBoolean(false)
        val fake=object:SpeechMicrophone {
            var ended:(()->Unit)?=null
            override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
                ended=stopped
                Thread({try {
                    val audio=pcm+ByteArray(32000);var offset=0
                    while(offset<audio.size && !stopFlag.get()) {val end=minOf(audio.size,offset+640);if(!packet(audio.copyOfRange(offset,end)))break;offset=end;Thread.sleep(20)}
                } finally {sent.countDown()}},"HearLens-public-audio-test").start()
            }
            override fun stop() {if(stopFlag.compareAndSet(false,true))ended?.invoke()}
        }
        val transcript=CaptionTranscript();val transcriptLock=Any()
        val client=SpeechClient({fake});client.onResult={synchronized(transcriptLock) {transcript.accept(it,"en")}}
        try {
            client.direct(AliyunConnection(workspace,key),"live","en")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
            while(sent.count>0 && client.snapshot().phase!=SpeechPhase.ERROR && System.nanoTime()<until)Thread.sleep(50)
            if(client.snapshot().phase==SpeechPhase.STREAMING)client.stop()
            val finish=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            while(client.snapshot().busy && System.nanoTime()<finish)Thread.sleep(50)
            val snapshot=client.snapshot()
            val original=synchronized(transcriptLock) {transcript.turns(false).count {it.text.isNotBlank()}}
            val translated=synchronized(transcriptLock) {transcript.turns(true).count {it.text.isNotBlank()}}
            val result=JSONObject().put("phase",snapshot.phase.name).put("model",snapshot.model).put("voice",AliyunProtocol.LIVE_VOICE)
                .put("originalCaptionTurns",original).put("translatedCaptionTurns",translated).put("packets",snapshot.packets)
                .put("message",snapshot.message.replace(key,"[redacted]")).put("source","public fixture through production SpeechClient and CaptionTranscript; no microphone")
            val root=File(app.cacheDir,"aliyun-live-check").apply {mkdirs()};File(root,"production-results.json").writeText(result.toString(2))
            check(snapshot.phase==SpeechPhase.FINISHED && original>0 && translated>0) {"Production text path failed: ${snapshot.phase}, original=$original, translated=$translated"}
            return "PASS: production same-model text-only live translation, original and translated caption turns, normal finish, saved credential."
        } finally {client.release()}
    }
}
