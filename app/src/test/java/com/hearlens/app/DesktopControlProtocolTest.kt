package com.hearlens.app
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopControlProtocolTest {
    private fun command(section:String,values:String)=JSONObject().put("section",section).put("values",JSONObject(values))
    @Test fun everyRuntimeSettingsGroupAcceptsAValidRequest() {
        val examples=mapOf("captions" to "{\"choice\":2}","environment" to "{\"enabled\":false,\"intensity\":85}","direction" to "{\"baudRate\":115200,\"format\":\"AUTO\",\"frontDegrees\":95.0}",
            "speech" to "{\"workspace\":\"\"}","ptt" to "{\"target\":\"zh\"}","application" to "{\"stereo\":true}","remote" to "{\"action\":\"learn\",\"mapping\":\"shoulder\"}","preset" to "{\"name\":\"comfortable\"}")
        for((section,values) in examples)assertEquals(section,DesktopControlProtocol.validate(command(section,values)).first)
    }
    @Test fun typesRangesUnknownFieldsAndPartialActionMixesAreRejected() {
        for((s,v) in listOf("captions" to "{\"choice\":9}","environment" to "{\"enabled\":\"true\"}","direction" to "{\"frontDegrees\":360}","direction" to "{\"baudRate\":12345}",
            "captions" to "{\"action\":\"clear\",\"sizePercent\":90}","speech" to "{\"workspace\":\"https://foreign.example\"}","environment" to "{\"arbitrary\":1}","direction" to "{\"side\":\"LEFT\"}"))
            assertThrows(IllegalArgumentException::class.java) {DesktopControlProtocol.validate(command(s,v))}
    }
    @Test fun remoteRecordingAlwaysRequiresExplicitLeaseAndAction() {
        assertThrows(IllegalArgumentException::class.java) {DesktopControlProtocol.validate(command("ptt","{\"action\":\"start\"}"))}
        for(action in listOf("start","heartbeat","end","cancel"))DesktopControlProtocol.validate(command("ptt","{\"action\":\"$action\",\"lease\":\"test-lease-12345\"}"))
    }
}
