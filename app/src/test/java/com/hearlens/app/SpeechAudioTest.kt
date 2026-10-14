package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class SpeechAudioTest {
    @Test fun oneMinuteIsThreeThousandPacketsAnd1920000Bytes() {
        var count=0;var bytes=0
        val packets=PcmPackets { assertEquals(640,it.size);count++;bytes+=it.size;true }
        repeat(6000) { assertTrue(packets.append(ShortArray(160),160)) }
        assertTrue(packets.finish());assertEquals(3000,count);assertEquals(1920000,bytes)
    }
    @Test fun littleEndianAndNonemptyEvenTailAreExact() {
        val actual=mutableListOf<ByteArray>()
        val packets=PcmPackets { actual+=it;true }
        packets.append(shortArrayOf(0x1234,-2,32767,-32768),4);packets.finish();packets.finish()
        assertEquals(1,actual.size)
        assertArrayEquals(byteArrayOf(0x34,0x12,0xfe.toByte(),0xff.toByte(),0xff.toByte(),0x7f,0,0x80.toByte()),actual.single())
    }
    @Test fun backpressureStopsFurtherPackets() {
        var count=0;val packets=PcmPackets { count++;false }
        assertFalse(packets.append(ShortArray(1000),1000));assertEquals(1,count)
    }
    @Test fun endpointDerivesWsPreservesPrefixAndRejectsUnsafeConfig() {
        val e=SpeechEndpoint.parse("https://example.com/bridge","wss://example.com/bridge","")
        assertEquals("/bridge/api/config",e.http.resolve("api/config")!!.encodedPath)
        assertEquals("https",e.socket.scheme)
        for(url in listOf("http://example.com","https://u:p@example.com","https://example.com/#secret","https://example.com/?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { SpeechEndpoint.parse(url,"","") }
        }
        assertThrows(IllegalArgumentException::class.java) { SpeechEndpoint.parse("http://127.0.0.1:8765","","sk-example") }
        assertEquals("192.168.1.4",SpeechEndpoint.parse("http://192.168.1.4:8765","","").socket.host)
    }
}
