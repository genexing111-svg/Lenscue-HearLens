package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class SharedPhoneAudioTest {
    private class Mic:SpeechMicrophone {
        var starts=0;var stops=0
        lateinit var packet:(ByteArray)->Boolean
        lateinit var ended:()->Unit
        lateinit var error:(String)->Unit
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {starts++;this.packet=packet;ended=stopped;this.error=error}
        override fun stop() {stops++}
    }
    @Test fun environmentAndSpeechShareOneCaptureAndCanStopIndependently() {
        val mic=Mic();val hub=SharedPhoneAudio {mic};val env=hub.microphone();val speech=hub.microphone()
        var e=0;var s=0
        env.start({e++;true},{_,_->},{},{fail(it)});speech.start({s++;true},{_,_->},{},{fail(it)})
        assertEquals(1,mic.starts);assertTrue(mic.packet(byteArrayOf(0,0)));assertEquals(1,e);assertEquals(1,s)
        speech.stop();assertEquals(0,mic.stops);mic.packet(byteArrayOf(0,0));assertEquals(2,e);assertEquals(1,s)
        env.stop();assertEquals(1,mic.stops);mic.ended();hub.release()
    }
    @Test fun startDuringRecorderShutdownWaitsForOldRecorderToEnd() {
        val created=mutableListOf<Mic>();val hub=SharedPhoneAudio {Mic().also {created+=it}}
        val first=hub.microphone();first.start({true},{_,_->},{},{fail(it)});first.stop()
        val second=hub.microphone();second.start({true},{_,_->},{},{fail(it)})
        assertEquals(1,created.size);created.first().ended();assertEquals(2,created.size)
        second.stop();created.last().ended();hub.release()
    }
    @Test fun rejectedSpeechPacketDoesNotStopEnvironmentCapture() {
        val mic=Mic();val hub=SharedPhoneAudio {mic};var envCount=0;var speechEnded=0
        hub.microphone().start({false},{_,_->},{speechEnded++},{})
        hub.microphone().start({envCount++;true},{_,_->},{},{})
        assertTrue(mic.packet(byteArrayOf(0,0)));assertEquals(1,speechEnded);assertEquals(1,envCount);assertEquals(0,mic.stops)
        hub.release();mic.ended()
    }
    @Test fun hardwareErrorNotifiesBothAndNoAutomaticMicRestart() {
        val mic=Mic();val hub=SharedPhoneAudio {mic};var errors=0
        repeat(2) {hub.microphone().start({true},{_,_->},{},{errors++})}
        mic.error("disconnected");mic.ended();assertEquals(2,errors);assertEquals(1,mic.starts);hub.release()
    }
    @Test fun exclusiveCaptureWaitsForPhysicalShutdownAndStopsWithItsTail() {
        val created=mutableListOf<Mic>();val hub=SharedPhoneAudio {Mic().also {created+=it}}
        val normal=hub.microphone();normal.start({true},{_,_->},{},{fail(it)});normal.stop()
        val ptt=hub.exclusiveMicrophone();var ended=0
        ptt.start({true},{_,_->},{ended++},{fail(it)});assertEquals(1,created.size)
        created[0].ended();assertEquals(2,created.size)
        ptt.stop();assertEquals(0,ended);created[1].ended();assertEquals(1,ended);hub.release()
    }
    @Test fun shortHoldWaitingForOldRecorderDoesNotStartLater() {
        val created=mutableListOf<Mic>();val hub=SharedPhoneAudio {Mic().also {created+=it}}
        val normal=hub.microphone();normal.start({true},{_,_->},{},{fail(it)});normal.stop()
        val ptt=hub.exclusiveMicrophone();var ended=0;ptt.start({true},{_,_->},{ended++},{fail(it)})
        ptt.stop();assertEquals(1,ended);created[0].ended();assertEquals(1,created.size);hub.release()
    }
}
