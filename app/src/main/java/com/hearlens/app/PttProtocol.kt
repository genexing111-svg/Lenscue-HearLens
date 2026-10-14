package com.hearlens.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal class PttProtocol(private val target:String) {
    private val transcript=LiveTranscript(target)
    companion object {const val WORKSPACE="llm-h6q09ysut5xftgvu"}
    init {require(target in listOf("zh","en"))}
    fun initial()=JSONObject().put("type","session.update").put("session",JSONObject()
        .put("output_modalities",JSONArray().put("text").put("audio"))
        .put("audio",JSONObject().put("input",JSONObject().put("format",JSONObject().put("type","pcm").put("sample_rate",16000))
            .put("turn_detection",JSONObject().put("type","speaker_detection").put("threshold",.5).put("silence_duration_ms",500))))
        .put("voice","default").put("enable_voice_clone",true).put("voice_clone_options",JSONObject().put("frequency","once"))
        .put("translation",JSONObject().put("language",target))).toString()
    fun ready(j:JSONObject):Int {
        val s=j.getJSONObject("session");val outputs=s.getJSONArray("output_modalities")
        require((0 until outputs.length()).map {outputs.getString(it)}.containsAll(listOf("text","audio"))) {"云端未启用文本及音频输出"}
        require(s.optBoolean("enable_voice_clone")) {"云端未启用本轮音色复刻"}
        require(s.getJSONObject("translation").getString("language")==target) {"云端输出语言与请求不符"}
        val output=s.getJSONObject("audio").getJSONObject("output").getJSONObject("format")
        require(output.getString("type")=="pcm") {"云端音频格式不是 PCM"}
        return output.getInt("sample_rate").also {require(it in listOf(16000,24000,48000)) {"云端采样率不受支持"}}
    }
    fun audio(bytes:ByteArray)=JSONObject().put("type","input_audio_buffer.append").put("audio",Base64.getEncoder().encodeToString(bytes)).toString()
    fun finish()="{\"type\":\"session.finish\"}"
    fun segments(j:JSONObject)=transcript.accept(j)
}

internal enum class PttPhase {IDLE,CONNECTING,RECORDING,WAIT_RELEASE,FINISHING,PLAYING,DONE,ERROR}
internal data class PttState(val phase:PttPhase=PttPhase.IDLE,val text:String="",val output:String="",val message:String="",
    val level:Int=0,val levelAt:Long=0,val inputBytes:Long=0,val outputBytes:Long=0,val sampleRate:Int=0,val generation:Long=0) {
    val busy get()=phase !in listOf(PttPhase.IDLE,PttPhase.DONE,PttPhase.ERROR)
}
internal interface PttPlayback {
    fun start(sampleRate:Int,done:()->Unit,error:(String)->Unit,level:(Int)->Unit)
    fun offer(pcm:ByteArray):Boolean
    fun finish()
    fun cancel()
}
