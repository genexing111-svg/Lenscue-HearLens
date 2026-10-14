package com.hearlens.app

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AliyunSpeechTest {
    @Test fun onlyOfficialWorkspaceHostsAndExactModels() {
        val connection=AliyunConnection("llm-test1234","fixture-key")
        assertEquals("llm-test1234.cn-beijing.maas.aliyuncs.com",connection.url("asr").host)
        assertEquals("/api-ws/v1/inference",connection.url("asr").encodedPath)
        assertEquals(AliyunProtocol.LIVE_MODEL,connection.url("live").queryParameter("model"))
        assertEquals("dashscope.aliyuncs.com",AliyunConnection("","fixture-key").url("asr").host)
        for(value in listOf("https://evil.invalid","llm-x@evil.invalid","llm-a/../../"))
            assertThrows(IllegalArgumentException::class.java) { AliyunConnection(value,"fixture-key") }
    }
    @Test fun asrKeepsAllProviderFieldsAndChecksTaskId() {
        val protocol=AliyunProtocol("asr","en")
        val task=JSONObject(protocol.initial()).getJSONObject("header").getString("task_id")
        val event=JSONObject("""{"header":{"event":"result-generated","task_id":"$task"},"payload":{"output":{"sentence":{"text":"今天晴天","sentence_id":0,"sentence_end":true,"words":[{"text":"今天"}]}}}}""")
        val sentence=protocol.decode(event).single().getJSONObject("sentence")
        assertEquals("今天晴天",sentence.getString("text"));assertTrue(sentence.has("words"))
        assertEquals(task,JSONObject(protocol.finish()).getJSONObject("header").getString("task_id"))
        event.getJSONObject("header").put("task_id","other")
        assertThrows(IllegalArgumentException::class.java) { protocol.decode(event) }
    }
    @Test fun liveEncodesPcmAndUses38SessionContract() {
        val protocol=AliyunProtocol("live","zh")
        val session=JSONObject(protocol.initial()).getJSONObject("session")
        assertEquals("text",session.getJSONArray("output_modalities").getString(0))
        assertEquals(16000,session.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getInt("sample_rate"))
        assertEquals("Tina",session.getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertFalse(session.has("voice"))
        val pcm=byteArrayOf(1,0,-1,127)
        assertArrayEquals(pcm,Base64.getDecoder().decode(JSONObject(protocol.audio(pcm)!!).getString("audio")))
        assertEquals("session.finish",JSONObject(protocol.finish()).getString("type"))
        assertThrows(IllegalArgumentException::class.java) { protocol.decode(JSONObject("""{"type":"session.updated","session":{"translation":{"language":"en"}}}""")) }
    }
    @Test fun liveRejectsUnsupportedServerDefaultBeforeOpeningMicrophone() {
        val protocol=AliyunProtocol("live","en")
        assertThrows(IllegalArgumentException::class.java) {protocol.decode(JSONObject("""{"type":"session.updated","session":{"audio":{"output":{"voice":"Chelsie"}},"translation":{"language":"en"}}}"""))}
        val ready=protocol.decode(JSONObject("""{"type":"session.updated","session":{"audio":{"output":{"voice":"Tina"}},"output_modalities":["text"],"translation":{"language":"en"}}}"""))
        assertEquals("ready",ready.single().getString("type"))
        assertThrows(IllegalArgumentException::class.java) {protocol.decode(JSONObject("""{"type":"session.updated","session":{"audio":{"output":{"voice":"Tina"}},"output_modalities":["text","audio"]}}"""))}
    }
    @Test fun liveJoinsOutOfOrderTranslationWithSourceAndKeepsSpeakerZero() {
        val t=LiveTranscript("en")
        fun accept(s:String)=t.accept(JSONObject(s))
        assertTrue(accept("""{"type":"response.text.delta","item_id":"out","delta":"Hello","event_id":"e1"}""").isEmpty())
        assertTrue(accept("""{"type":"conversation.item.created","item":{"id":"out"},"previous_item_id":"in"}""").isEmpty())
        val s=accept("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"in","speaker_id":0,"transcript":"你好"}""").single()
        assertEquals("0",s.getString("speakerId"));assertTrue(s.getBoolean("originalFinal"))
        assertEquals("Hello",s.getJSONObject("translations").getString("en"))
        assertFalse(s.getJSONObject("translationFinal").getBoolean("en"))
        assertTrue(accept("""{"type":"response.text.delta","item_id":"out","delta":"Hello","event_id":"e1"}""").isEmpty())
        val finished=accept("""{"type":"response.text.done","item_id":"out","text":"Hello!"}""").single()
        assertEquals("Hello!",finished.getJSONObject("translations").getString("en"));assertTrue(finished.getJSONObject("translationFinal").getBoolean("en"))
        accept("""{"type":"response.text.delta","item_id":"out","delta":" late"}""")
        assertEquals("Hello",s.getJSONObject("translations").getString("en")) // earlier snapshots are immutable copies
    }
    private class Mic:SpeechMicrophone {
        var starts=0;var ends:(()->Unit)?=null;var packet:((ByteArray)->Boolean)?=null
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
            starts++;this.packet=packet;ends=stopped
        }
        override fun stop() { ends?.invoke() }
    }
    private class Fixture(val engine:String):AutoCloseable {
        val server=MockWebServer();val mic=Mic();val requests=CopyOnWriteArrayList<String>()
        var task="";lateinit var socket:WebSocket
        val client:SpeechClient
        init {
            server.start()
            // Tests alone rewrite the official host to an in-process fake. No cloud or hardware used.
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            client=SpeechClient({mic},http,2000,2000)
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                override fun onOpen(webSocket:WebSocket,response:Response) { socket=webSocket }
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) { requests.add("pcm:${bytes.size}") }
                override fun onMessage(webSocket:WebSocket,text:String) {
                    val j=JSONObject(text);val action=if(engine=="asr")j.getJSONObject("header").getString("action") else j.getString("type")
                    requests.add(action)
                    if(action=="run-task")task=j.getJSONObject("header").getString("task_id")
                    if(action in listOf("finish-task","session.finish"))sendFinish()
                }
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,null) }
            }))
        }
        fun start(probe:Boolean) { client.direct(AliyunConnection("llm-fixture1","fixture-key"),engine,"en",probe) }
        fun ready() {
            await { requests.isNotEmpty() }
            socket.send(if(engine=="asr")"""{"header":{"event":"task-started","task_id":"$task"}}""" else """{"type":"session.updated","session":{"translation":{"language":"en"}}}""")
        }
        fun sendFinish() { socket.send(if(engine=="asr")"""{"header":{"event":"task-finished","task_id":"$task"}}""" else """{"type":"session.finished"}""") }
        override fun close() { client.release();server.close() }
    }
    @Test fun bothCloudChecksReachFinishedWithoutEverCreatingMicrophone() {
        for(engine in listOf("asr","live"))Fixture(engine).use { f ->
            f.start(true);f.ready();await { f.client.snapshot().phase==SpeechPhase.FINISHED }
            assertEquals(0,f.mic.starts);assertEquals(0L,f.client.snapshot().bytes)
            assertEquals(1,f.server.requestCount);assertFalse(f.client.snapshot().config.contains("fixture-key"))
            assertTrue(f.client.snapshot().lastEvent.contains(if(engine=="asr")"task-finished" else "session.finished"))
        }
    }
    @Test fun bothModesGateCaptureUntilReadyAndSendAudioInCorrectWireFormat() {
        for(engine in listOf("asr","live"))Fixture(engine).use { f ->
            f.start(false);await { f.requests.isNotEmpty() };assertEquals(0,f.mic.starts)
            f.ready();await { f.mic.starts==1 };assertTrue(f.mic.packet!!(ByteArray(640)))
            await { f.requests.size==2 };assertEquals(if(engine=="asr")"pcm:640" else "input_audio_buffer.append",f.requests[1])
            f.client.stop();await { f.client.snapshot().phase==SpeechPhase.FINISHED }
            assertFalse(f.client.snapshot().microphone);assertEquals(640L,f.client.snapshot().bytes)
        }
    }
    @Test fun cloudFailureDoesNotOpenMicAndRedactsCredential()=Fixture("live").use { f ->
        f.start(false);await { f.requests.isNotEmpty() }
        f.socket.send("""{"type":"error","error":{"code":"Denied","message":"fixture-key"}}""")
        await { f.client.snapshot().phase==SpeechPhase.ERROR }
        assertEquals(0,f.mic.starts);assertFalse(f.client.snapshot().message.contains("fixture-key"))
        assertFalse(f.client.snapshot().lastEvent.contains("fixture-key"))
    }
    companion object {
        private fun await(condition:()->Boolean) {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
            while(!condition() && System.nanoTime()<until)Thread.sleep(10)
            assertTrue("condition timed out",condition())
        }
    }
}
