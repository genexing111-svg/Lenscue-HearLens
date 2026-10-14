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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechClientTest {
    private class Mic:SpeechMicrophone {
        @Volatile var starts=0
        @Volatile var stops=0
        var packet:((ByteArray)->Boolean)?=null
        var ended:(()->Unit)?=null
        var failed:((String)->Unit)?=null
        var tail=byteArrayOf()
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
            this.packet=packet;ended=stopped;failed=error;starts++
        }
        override fun stop() { stops++;if(tail.isNotEmpty())packet?.invoke(tail);ended?.invoke() }
    }
    private class Fixture(val finishOnStop:Boolean=true,val readyMs:Long=2000,val finishMs:Long=2000):AutoCloseable {
        val server=MockWebServer()
        val mic=Mic()
        val received=CopyOnWriteArrayList<String>()
        val connected=CountDownLatch(1)
        lateinit var socket:WebSocket
        val client=SpeechClient({mic},OkHttpClient.Builder().followRedirects(false).build(),readyMs,finishMs)
        init { server.start() }
        val endpoint get()=SpeechEndpoint.parse(server.url("/").toString(),"","")
        fun prepare(configured:Boolean=true) {
            server.enqueue(MockResponse().setBody("""{"configured":$configured,"frameMs":20,"engines":[{"id":"asr"},{"id":"live"}]}"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                override fun onOpen(webSocket:WebSocket,response:Response) { socket=webSocket;connected.countDown() }
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) { received+="audio:${bytes.size}" }
                override fun onMessage(webSocket:WebSocket,text:String) {
                    received+=JSONObject(text).getString("type")
                    if(finishOnStop && text.contains("stop")) {
                        socket.send("""{"type":"result","sentence":{"sentence_id":1,"text":"最终测试文字","sentence_end":true}}""")
                        socket.send("""{"type":"finished"}""")
                    }
                }
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,null) }
            }))
        }
        fun ready(engine:String="asr") {
            assertTrue(connected.await(3,TimeUnit.SECONDS))
            socket.send("""{"type":"ready","engine":"$engine","model":"local-contract-fixture","frameMs":20}""")
            await { mic.starts==1 }
        }
        override fun close() { client.release();server.close() }
    }
    @Test fun configCheckNeverCreatesMicrophone()=Fixture().use { f ->
        f.prepare();f.client.check(f.endpoint)
        await { f.client.snapshot().phase==SpeechPhase.IDLE }
        assertEquals(0,f.mic.starts);assertEquals(1,f.server.requestCount)
    }
    @Test fun readyGatesAudioAndStopFlushesTailBeforeFinalFields()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en")
        assertTrue(f.connected.await(3,TimeUnit.SECONDS));assertEquals(0,f.mic.starts)
        assertEquals(SpeechPhase.CONNECTING,f.client.snapshot().phase)
        f.ready();assertTrue(f.mic.packet!!(ByteArray(640)));f.mic.tail=byteArrayOf(1,0)
        f.client.stop();await { f.client.snapshot().phase==SpeechPhase.FINISHED }
        assertEquals(listOf("audio:640","audio:2","stop"),f.received.toList())
        assertEquals(642,f.client.snapshot().bytes);assertFalse(f.client.snapshot().microphone)
        assertTrue(f.client.snapshot().lastData.contains("最终测试文字"));assertTrue(f.mic.stops>=1)
    }
    @Test fun asrSnapshotReplacesEarlierSentence()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en");f.ready()
        for(text in listOf("我们","我们明天","我们明天见"))f.socket.send("""{"type":"result","sentence":{"sentence_id":1,"text":"$text","sentence_end":false}}""")
        await { f.client.snapshot().lastData.contains("我们明天见") }
        assertEquals(1,f.client.snapshot().sentences.size)
        assertTrue(f.client.snapshot().sentences.single().endsWith("我们明天见"))
    }
    @Test fun livePreservesZeroSpeakerSparseTranslationsAndIndependentFinalFlags()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"live","zh");f.ready("live")
        f.socket.send("""{"type":"segment","segment":{"id":"s1","speakerId":"0","startMs":null,"endMs":null,"original":"你好","originalFinal":true,"translations":{"zh":"你好"},"translationFinal":{"zh":false}}}""")
        await { f.client.snapshot().sentences.size==1 }
        val segment=JSONObject(f.client.snapshot().lastData).getJSONObject("segment")
        assertEquals("0",segment.getString("speakerId"));assertTrue(segment.getBoolean("originalFinal"))
        assertFalse(segment.getJSONObject("translationFinal").getBoolean("zh"))
        assertFalse(segment.getJSONObject("translations").has("en"))
        assertEquals("zh",f.server.takeRequest(1,TimeUnit.SECONDS)?.let { f.server.takeRequest(1,TimeUnit.SECONDS)?.requestUrl?.queryParameter("target") })
    }
    @Test fun unconfiguredBackendNeverStartsMicrophone()=Fixture().use { f ->
        f.prepare(false);f.client.start(f.endpoint,"asr","en")
        await { f.client.snapshot().phase==SpeechPhase.ERROR }
        assertEquals(0,f.mic.starts);assertEquals(1,f.server.requestCount)
    }
    @Test fun missingReadyTimesOutWithoutAudio()=Fixture(readyMs=150).use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en")
        await { f.client.snapshot().phase==SpeechPhase.ERROR };assertEquals(0,f.mic.starts)
    }
    @Test fun serverErrorReleasesMicrophone()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en");f.ready()
        f.socket.send("""{"type":"error","message":"fixture failure"}""")
        await { f.client.snapshot().phase==SpeechPhase.ERROR }
        assertFalse(f.client.snapshot().microphone);assertTrue(f.mic.stops>0)
    }
    @Test fun missingFinishedTimesOutWithMicrophoneStopped()=Fixture(finishOnStop=false,finishMs=150).use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en");f.ready();f.client.stop()
        await { f.client.snapshot().phase==SpeechPhase.ERROR };assertFalse(f.client.snapshot().microphone)
    }
    @Test fun canceledSessionCannotSendLateAudioOrPolluteNewSession()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en");f.ready()
        val oldPacket=f.mic.packet!!;val oldError=f.mic.failed!!
        f.client.cancel();assertFalse(oldPacket(ByteArray(640)))
        f.prepare();f.client.check(f.endpoint);await { f.client.snapshot().phase==SpeechPhase.IDLE }
        oldError("late failure");assertEquals(SpeechPhase.IDLE,f.client.snapshot().phase)
        assertEquals(0,f.client.snapshot().bytes)
    }
    @Test fun duplicateStartDoesNotCreateAnotherSession()=Fixture().use { f ->
        f.prepare();f.client.start(f.endpoint,"asr","en");f.ready()
        assertThrows(IllegalStateException::class.java) { f.client.start(f.endpoint,"asr","en") }
        assertEquals(1,f.mic.starts);assertEquals(2,f.server.requestCount)
    }
    @Test fun malformedJsonAndUnexpectedCloseAreErrors() {
        Fixture().use { f ->
            f.prepare();f.client.start(f.endpoint,"asr","en");f.ready();f.socket.send("not json")
            await { f.client.snapshot().phase==SpeechPhase.ERROR };assertTrue(f.mic.stops>0)
        }
        Fixture().use { f ->
            f.prepare();f.client.start(f.endpoint,"asr","en");f.ready();f.socket.close(1000,"fixture")
            await { f.client.snapshot().phase==SpeechPhase.ERROR };assertFalse(f.client.snapshot().microphone)
        }
    }
    companion object {
        private fun await(condition:()->Boolean) {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
            while(!condition() && System.nanoTime()<until)Thread.sleep(10)
            assertTrue("condition timed out",condition())
        }
    }
}
