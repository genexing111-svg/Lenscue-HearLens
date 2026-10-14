package com.hearlens.app

import org.json.JSONObject
import kotlin.math.sqrt

internal data class CaptionTurn(val id:String,val speaker:String?,val text:String)
internal data class CaptionRow(val turn:String,val speaker:String?,val text:String,val width:Float)
internal data class CaptionStyle(val widthPercent:Int=52,val bottomPercent:Int=8,val sizePercent:Int=100,
    val transparency:Int=11) {
    init {
        require(widthPercent in 35..80 && bottomPercent in 3..30 && sizePercent in 70..160)
        require(transparency in 0..100)
    }
    fun toJson()=JSONObject().put("widthPercent",widthPercent).put("bottomPercent",bottomPercent)
        .put("sizePercent",sizePercent).put("transparency",transparency).put("backgroundMaterial",MATERIAL)
        .put("feather",0).put("blur",0) // Explicit zeros for older diagnostic consumers.
    companion object {
        const val MATERIAL="settings-mask-v1"
        fun fromJson(j:JSONObject)=CaptionStyle(j.optInt("widthPercent",52),j.optInt("bottomPercent",8),j.optInt("sizePercent",100),
            // The old transparency controlled a tint over a copied scene, not this alpha-composited panel.
            // Migrate only the material; preserve placement and font size. Future custom opacity survives.
            if(j.optString("backgroundMaterial")==MATERIAL)j.optInt("transparency",11) else 11)
    }
}

/** Stable item order, replaceable hypotheses, and explicit speaker boundaries. No synthetic speakers. */
internal class CaptionTranscript {
    private data class Entry(val id:String,val speaker:String?,val original:String,val translated:String,val session:String)
    private val entries=linkedMapOf<String,Entry>()
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun accept(event:JSONObject,target:String,session:String="") {
        fun scoped(id:String)=if(session.isEmpty())id else "$session:$id"
        if(event.optString("type")=="result") {
            val sentence=event.optJSONObject("sentence") ?: return
            val text=sentence.optString("text").take(8192)
            if(text.isBlank())return
            val id=scoped("asr:${sentence.opt("sentence_id")?.takeUnless { it==JSONObject.NULL } ?: sentence.opt("begin_time")?.takeUnless { it==JSONObject.NULL } ?: "current"}")
            entries[id]=Entry(id,null,text,"",session)
            while(entries.size>64)entries.remove(entries.keys.first())
            return
        }
        if(event.optString("type")!="segment")return
        val s=event.getJSONObject("segment");val id=scoped(s.getString("id"))
        val speaker=if(s.isNull("speakerId"))null else s.optString("speakerId").takeIf { it.isNotBlank() }
        fun bounded(text:String):String {
            val clean=text.replace("\r","").filter { it=='\n' || it=='\t' || !it.isISOControl() }
            if(clean.length<=8192)return clean
            val start=(clean.length-8192).let { if(Character.isLowSurrogate(clean[it]))it+1 else it }
            return clean.substring(start)
        }
        entries[id]=Entry(id,speaker,bounded(s.optString("original")),bounded(s.optJSONObject("translations")?.optString(target).orEmpty()),session)
        while(entries.size>64)entries.remove(entries.keys.first())
    }
    @Synchronized fun turns(translation:Boolean):List<CaptionTurn> {
        val result=mutableListOf<CaptionTurn>()
        var previousSession:String?=null
        for(e in entries.values) {
            val text=(if(translation)e.translated else e.original).trim()
            // Keep even empty speaker-boundary items: late text cannot merge through another speaker.
            val previous=result.lastOrNull()
            if(previous!=null && previous.speaker==e.speaker && previousSession==e.session) {
                val separator=if(previous.text.isNotEmpty() && text.isNotEmpty() &&
                    previous.text.last().isLetterOrDigit() && previous.text.last().code<128 && text.first().code<128)" " else ""
                result[result.lastIndex]=previous.copy(text=previous.text+separator+text)
            } else result.add(CaptionTurn(e.id,e.speaker,text))
            previousSession=e.session
        }
        return result.filter { it.text.isNotEmpty() }
    }
}

internal object CaptionLayout {
    const val MAX_LINES=4
    /** Break only at supplied grapheme boundaries; Android supplies ICU extended clusters. */
    fun rows(turns:List<CaptionTurn>,maxWidth:Float,measure:(String)->Float,clusters:(String)->List<String>):List<CaptionRow> {
        require(maxWidth>0)
        val rows=ArrayDeque<CaptionRow>()
        fun emit(turn:CaptionTurn,text:String) {
            if(text.isNotEmpty()) {
                rows.addLast(CaptionRow(turn.id,turn.speaker,text,measure(text)))
                while(rows.size>MAX_LINES)rows.removeFirst()
            }
        }
        for(turn in turns) {
            var line=""
            for(c in clusters(turn.text)) {
                if(c=="\n") { emit(turn,line);line="";continue }
                if(line.isNotEmpty() && measure(line+c)>maxWidth) {
                    // Preserve whole Latin words when there is a breakable space; CJK wraps by cluster.
                    val space=line.lastIndexOf(' ')
                    if(space>0 && !c.isBlank() && c.first().code<128) {
                        emit(turn,line.substring(0,space));line=line.substring(space+1)
                        if(line.isNotEmpty() && measure(line+c)>maxWidth) { emit(turn,line);line="" }
                    } else { emit(turn,line);line="" }
                }
                if(line.isNotEmpty() || !c.isBlank())line+=c
            }
            emit(turn,line.trimEnd())
        }
        return rows.toList()
    }
    fun coverage(x:Float,y:Float,width:Float,height:Float,feather:Float):Float {
        val dx=maxOf(-x,0f,x-width);val dy=maxOf(-y,0f,y-height)
        if(dx==0f && dy==0f)return 1f
        if(feather<=0)return 0f
        val t=(sqrt(dx*dx+dy*dy)/feather).coerceIn(0f,1f)
        return 1-t*t*(3-2*t)
    }
}
