package com.hearlens.app

import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class PttClientTest {
    private class Mic:SpeechMicrophone {
        @Volatile var starts=0;@Volatile var stops=0
        lateinit var packet:(ByteArray)->Boolean;lateinit var ended:()->Unit
        var autoEnd=true;var tail=byteArrayOf(7,0)
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {this.packet=packet;ended=stopped;starts++}
        override fun stop() {if(stops++==0 && autoEnd) {packet(tail);ended()}}
    }
    private class Player:PttPlayback {
        @Volatile var starts=0;@Volatile var cancelled=0;@Volatile var finished=false
        val chunks=CopyOnWriteArrayList<ByteArray>();lateinit var done:()->Unit
        override fun start(sampleRate:Int,done:()->Unit,error:(String)->Unit,level:(Int)->Unit) {assertEquals(24000,sampleRate);this.done=done;starts++}
        override fun offer(pcm:ByteArray):Boolean {check(starts==1);chunks+=pcm;return true}
        override fun finish() {finished=true}
        override fun cancel() {cancelled++}
    }
    private class Fixture(readyTimeout:Long=2000):AutoCloseable {
        val server=MockWebServer();val mic=Mic();val player=Player();val received=CopyOnWriteArrayList<JSONObject>()
        lateinit var socket:WebSocket
        val client:PttClient
        init {
            server.start();server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                override fun onOpen(webSocket:WebSocket,response:Response) {socket=webSocket}
                override fun onMessage(webSocket:WebSocket,text:String) {received+=JSONObject(text)}
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {webSocket.close(code,null)}
            }))
            client=PttClient({mic},{player},endpointOverride=server.url("/realtime"),readyTimeoutMs=readyTimeout,finishTimeoutMs=2000)
        }
        fun begin() {client.begin(AliyunConnection("","test-key-not-a-real-secret"));await {mic.starts==1 && received.isNotEmpty()}}
        fun ready() {socket.send("""{"type":"session.updated","session":{"output_modalities":["text","audio"],"enable_voice_clone":true,"translation":{"language":"zh"},"audio":{"output":{"format":{"type":"pcm","sample_rate":24000}}}}}""");await {client.state.sampleRate==24000}}
        fun audio(value:Byte)=socket.send(JSONObject().put("type","response.audio.delta").put("delta",Base64.getEncoder().encodeToString(byteArrayOf(value,0))).toString())
        override fun close() {client.release();Thread.sleep(30);server.close()}
    }
    @Test fun connectsInParallelBuffersOpeningAndSendsTailBeforeFinish()=Fixture().use {f->
        f.begin();f.mic.packet(ByteArray(640){2});await {f.client.state.inputBytes==640L}
        assertEquals(listOf("session.update"),f.received.map {it.getString("type")})
        f.client.end();await {f.mic.stops>0};f.ready()
        await {f.received.any {it.optString("type")=="session.finish"}}
        assertEquals(listOf("session.update","input_audio_buffer.append","input_audio_buffer.append","session.finish"),f.received.map {it.getString("type")})
        assertEquals(2,Base64.getDecoder().decode(f.received[2].getString("audio")).size)
    }
    @Test fun generatedAudioWaitsForBothReleaseAndPhysicalMicStop()=Fixture().use {f->
        f.begin();f.ready();f.mic.packet(ByteArray(640));await {f.client.state.inputBytes>0}
        f.audio(1);await {f.client.state.outputBytes==2L};assertEquals(0,f.player.starts)
        f.mic.autoEnd=false;f.client.end();await {f.mic.stops>0};assertEquals(0,f.player.starts)
        f.mic.packet(byteArrayOf(3,0));f.mic.ended();await {f.player.starts==1}
        f.audio(2);await {f.player.chunks.size==2};assertEquals(listOf(1.toByte(),2.toByte()),f.player.chunks.map {it[0]})
        await {f.received.any {it.optString("type")=="session.finish"}};f.socket.send("""{"type":"session.finished"}""")
        await {f.player.finished};assertTrue(f.client.state.busy);f.player.done();await {f.client.state.phase==PttPhase.DONE}
    }
    @Test fun releaseBeforeMicrophoneReadyCancelsAndDiscardsLatePackets()=Fixture().use {f->
        f.begin();f.client.end();await {f.client.state.phase==PttPhase.DONE}
        assertFalse(f.mic.packet(ByteArray(640)));assertEquals(0,f.player.starts)
        assertFalse(f.received.any {it.optString("type")=="input_audio_buffer.append"})
    }
    @Test fun cancellationStopsOutputAndLateCloudAudioCannotRestartIt()=Fixture().use {f->
        f.begin();f.ready();f.mic.packet(ByteArray(640));await {f.client.state.inputBytes>0};f.audio(1)
        await {f.client.state.outputBytes>0};f.client.end();await {f.player.starts==1}
        f.client.cancel();await {f.client.state.phase==PttPhase.DONE};assertEquals(1,f.player.cancelled)
        f.audio(2);Thread.sleep(30);assertEquals(1,f.player.chunks.size)
    }
    @Test fun readyTimeoutStopsCaptureWithoutPlayback()=Fixture(150).use {f->
        f.begin();await {f.client.state.phase==PttPhase.ERROR};assertTrue(f.mic.stops>0);assertEquals(0,f.player.starts)
    }
    @Test fun voiceCloneConfigIsSeparateFromTextOnlyCaptionVoice() {
        val s=JSONObject(PttProtocol("zh").initial()).getJSONObject("session")
        assertEquals("default",s.getString("voice"));assertTrue(s.getBoolean("enable_voice_clone"))
        assertEquals("once",s.getJSONObject("voice_clone_options").getString("frequency"))
        assertEquals(2,s.getJSONArray("output_modalities").length())
        assertEquals("Tina",JSONObject(AliyunProtocol("live","en").initial()).getJSONObject("session").getJSONObject("audio").getJSONObject("output").getString("voice"))
    }
    @Test fun shoulderAliasesMustAllReleaseAndRepeatsDoNotRestart() {
        val gate=ShoulderHoldGate();assertTrue(gate.press("button"));assertFalse(gate.press("button"));assertFalse(gate.press("trigger"))
        assertFalse(gate.release("button"));assertTrue(gate.release("trigger"));assertFalse(gate.release("trigger"))
        assertTrue(gate.press("button"));assertTrue(gate.reset());assertFalse(gate.reset())
    }
    companion object {private fun await(test:()->Boolean) {val until=System.nanoTime()+5_000_000_000L;while(!test() && System.nanoTime()<until)Thread.sleep(10);assertTrue("Timed out",test())}}
}
