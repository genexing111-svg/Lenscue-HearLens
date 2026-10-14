package com.hearlens.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Only official Beijing hosts can receive a cloud credential. No arbitrary URL field. */
internal class AliyunConnection(val workspace:String,val key:String) {
    init {
        require(workspace.isEmpty() || Regex("llm-[a-z0-9-]{4,80}").matches(workspace)) { "业务空间格式应为 llm-…（只填标识，不填网址）" }
        require(key.length in 10..8192 && key.all { it.code in 33..126 }) { "请填写有效的 API Key 或临时 API Key" }
    }
    fun url(engine:String):HttpUrl {
        require(engine in listOf("asr","live"))
        val host=if(workspace.isBlank())"dashscope.aliyuncs.com" else "$workspace.cn-beijing.maas.aliyuncs.com"
        return "https://$host/api-ws/v1/${if(engine=="asr")"inference" else "realtime"}".toHttpUrl().newBuilder().apply {
            if(engine=="live")addQueryParameter("model",AliyunProtocol.LIVE_MODEL)
        }.build()
    }
}

/** Cloud protocols are adapted on the phone; no HTTP bridge or computer process is involved. */
internal class AliyunProtocol(val engine:String,private val target:String) {
    companion object {
        const val ASR_MODEL="qwen-audio-3.1-asr-flash-streaming"
        const val LIVE_MODEL="qwen3.8-livetranslate-flash-realtime"
        const val LIVE_VOICE="Tina"
    }
    private val taskId=UUID.randomUUID().toString().replace("-","")
    val model=if(engine=="asr")ASR_MODEL else LIVE_MODEL
    private val transcript=LiveTranscript(target)
    private fun header(action:String)=JSONObject().put("action",action).put("task_id",taskId).put("streaming","duplex")
    fun initial():String=if(engine=="asr") JSONObject().put("header",header("run-task")).put("payload",JSONObject()
        .put("task_group","audio").put("task","asr").put("function","recognition").put("model",model)
        .put("parameters",JSONObject().put("format","pcm").put("sample_rate",16000)
            .put("semantic_punctuation_enabled",false).put("max_sentence_silence",200).put("heartbeat",true))
        .put("input",JSONObject())).toString()
    else JSONObject().put("event_id",UUID.randomUUID().toString()).put("type","session.update").put("session",JSONObject()
        .put("output_modalities",JSONArray().put("text"))
        .put("audio",JSONObject().put("input",JSONObject()
            .put("format",JSONObject().put("type","pcm").put("sample_rate",16000))
            .put("turn_detection",JSONObject().put("type","speaker_detection").put("threshold",0.5).put("silence_duration_ms",500)))
            .put("output",JSONObject().put("voice",LIVE_VOICE)))
        .put("translation",JSONObject().put("language",target))).toString()
    fun audio(bytes:ByteArray):String?=if(engine=="asr")null else JSONObject().put("type","input_audio_buffer.append")
        .put("audio",Base64.getEncoder().encodeToString(bytes)).toString()
    fun finish():String=if(engine=="asr")JSONObject().put("header",header("finish-task"))
        .put("payload",JSONObject().put("input",JSONObject())).toString() else JSONObject().put("type","session.finish").toString()
    private fun ready()=JSONObject().put("type","ready").put("engine",engine).put("model",model).put("frameMs",20)
    fun decode(j:JSONObject):List<JSONObject> {
        if(engine=="asr") {
            val h=j.getJSONObject("header")
            require(h.optString("task_id")==taskId) { "云端 task_id 与当前会话不一致" }
            return when(h.getString("event")) {
                "task-started"->listOf(ready())
                "task-finished"->listOf(JSONObject().put("type","finished"))
                "task-failed"->listOf(JSONObject().put("type","error").put("message","${h.optString("error_code")}：${h.optString("error_message")}"))
                "result-generated"->{
                    val sentence=j.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence")
                    if(sentence==null || sentence.optBoolean("heartbeat"))emptyList()
                    else listOf(JSONObject().put("type","result").put("sentence",sentence))
                }
                else->emptyList()
            }
        }
        return when(j.getString("type")) {
            "session.updated"->{
                val session=j.getJSONObject("session")
                val voice=session.optJSONObject("audio")?.optJSONObject("output")?.optString("voice").orEmpty().ifBlank {session.optString("voice")}
                require(voice.isBlank() || voice.equals(LIVE_VOICE,ignoreCase=true)) {"云端未采用请求的同传音色 $LIVE_VOICE，实际为 ${voice.take(40)}；未启动采集"}
                val output=session.optJSONArray("output_modalities")
                require(output==null || (output.length()==1 && output.optString(0)=="text")) {"云端未采用仅文本输出配置；未启动采集"}
                val language=session.optJSONObject("translation")?.optString("language")
                require(language.isNullOrEmpty() || language==target) { "云端翻译语言与请求不一致" }
                listOf(ready())
            }
            "session.finished"->listOf(JSONObject().put("type","finished"))
            "error","conversation.item.input_audio_transcription.failed"->listOf(JSONObject().put("type","error")
                .put("message",j.optJSONObject("error")?.toString() ?: "云端转写失败"))
            else->{
                if(j.optString("type")=="response.done" && j.optJSONObject("response")?.optString("status")=="failed")
                    listOf(JSONObject().put("type","error").put("message",j.getJSONObject("response").optJSONObject("status_details")?.toString() ?: "云端翻译失败"))
                else transcript.accept(j).map { JSONObject().put("type","segment").put("segment",it) }
            }
        }
    }
}

/** Join source items and translated output by explicit item links, never by arrival order. */
internal class LiveTranscript(private val target:String) {
    private val segments=linkedMapOf<String,JSONObject>()
    private class Output(var text:String="",var final:Boolean=false)
    private val outputs=linkedMapOf<String,Output>()
    private val links=linkedMapOf<String,String>()
    private val seen=linkedSetOf<String>()
    private fun segment(id:String):JSONObject=segments.getOrPut(id) { JSONObject().put("id",id).put("speakerId",JSONObject.NULL)
        .put("startMs",JSONObject.NULL).put("endMs",JSONObject.NULL).put("original","").put("originalFinal",false)
        .put("translations",JSONObject()).put("translationFinal",JSONObject()) }
    fun accept(j:JSONObject):List<JSONObject> {
        val eventId=j.optString("event_id")
        if(eventId.isNotBlank() && !seen.add(eventId))return emptyList()
        val changed=linkedSetOf<String>()
        fun sync(outputId:String) {
            val inputId=links[outputId] ?: return
            val s=segments[inputId] ?: return
            val output=outputs[outputId] ?: return
            s.getJSONObject("translations").put(target,output.text)
            s.getJSONObject("translationFinal").put(target,output.final);changed.add(inputId)
        }
        val id=j.optString("item_id")
        val type=j.getString("type")
        when(type) {
            "input_audio_buffer.speech_started","input_audio_buffer.speech_stopped"->if(id.isNotBlank()) {
                val s=segment(id)
                if(j.has("speaker_id") && !j.isNull("speaker_id"))s.put("speakerId",j.get("speaker_id").toString())
                if(j.has("audio_start_ms"))s.put("startMs",j.get("audio_start_ms"))
                if(j.has("audio_end_ms"))s.put("endMs",j.get("audio_end_ms"))
                changed.add(id)
            }
            "conversation.item.created"->{
                val item=j.optJSONObject("item")
                val itemId=item?.optString("id").orEmpty()
                val content=item?.optJSONArray("content") ?: JSONArray()
                val input=(0 until content.length()).any { content.optJSONObject(it)?.optString("type")=="input_audio" }
                if(itemId.isNotBlank()) {
                    if(input) { segment(itemId);changed.add(itemId);links.filterValues { it==itemId }.keys.toList().forEach(::sync) }
                    else if(j.optString("previous_item_id").isNotBlank()) {
                        links[itemId]=j.getString("previous_item_id");sync(itemId)
                    }
                }
            }
            "conversation.item.input_audio_transcription.delta","conversation.item.input_audio_transcription.text",
            "conversation.item.input_audio_transcription.completed"->if(id.isNotBlank()) {
                val s=segment(id)
                if(j.has("speaker_id") && !j.isNull("speaker_id"))s.put("speakerId",j.get("speaker_id").toString())
                if(type.endsWith(".completed")) { s.put("original",j.optString("transcript",s.getString("original")));s.put("originalFinal",true) }
                else if(!s.getBoolean("originalFinal"))s.put("original",if(type.endsWith(".delta"))s.getString("original")+j.optString("delta") else j.optString("text"))
                changed.add(id);links.filterValues { it==id }.keys.toList().forEach(::sync)
            }
            "response.text.delta","response.audio_transcript.delta","response.text.done","response.audio_transcript.done"->if(id.isNotBlank()) {
                val output=outputs.getOrPut(id) { Output() }
                if(type.endsWith(".done")) { output.text=j.optString("text",j.optString("transcript",output.text));output.final=true }
                else if(!output.final)output.text+=j.optString("delta")
                sync(id)
            }
            "response.done"->{
                val response=j.getJSONObject("response");val items=response.optJSONArray("output") ?: JSONArray()
                for(i in 0 until items.length()) {
                    val item=items.optJSONObject(i) ?: continue;val outputId=item.optString("id")
                    if(outputId.isBlank())continue
                    val output=outputs.getOrPut(outputId) { Output() }
                    val content=item.optJSONArray("content") ?: JSONArray()
                    val text=(0 until content.length()).joinToString("") { n-> content.optJSONObject(n)?.let { it.optString("text",it.optString("transcript")) }.orEmpty() }
                    if(text.isNotEmpty())output.text=text
                    output.final=response.optString("status")=="completed";sync(outputId)
                }
            }
        }
        while(seen.size>4096)seen.remove(seen.first())
        while(segments.size>128)segments.remove(segments.keys.first())
        while(outputs.size>256)outputs.remove(outputs.keys.first())
        while(links.size>256)links.remove(links.keys.first())
        return changed.mapNotNull { segments[it]?.let { s->JSONObject(s.toString()) } }
    }
}
