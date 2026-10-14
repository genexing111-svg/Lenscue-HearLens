package com.hearlens.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal data class SoundCategory(val index:Int,val name:String,val score:Double) {
    fun json()=JSONObject().put("index",index).put("name",name).put("score",score)
    companion object {
        fun parse(array:JSONArray):List<SoundCategory> = (0 until array.length()).mapNotNull {i->
            val j=array.optJSONObject(i) ?: return@mapNotNull null
            val index=j.opt("index") as? Number ?: return@mapNotNull null
            val score=j.opt("score") as? Number ?: return@mapNotNull null
            if(!index.toDouble().isFinite() || index.toDouble()!=index.toInt().toDouble())null
            else SoundCategory(index.toInt(),j.optString("name",""),score.toDouble())
        }
    }
}
internal data class SoundDecision(val kind:String,val candidates:List<SoundCategory>,val event:SoundCategory?,val noiseFloor:Double,val calibrationWindows:Int) {
    fun json()=JSONObject().put("kind",kind).put("candidates",JSONArray(candidates.map {it.json()})).put("event",event?.json() ?: JSONObject.NULL)
        .put("noiseFloor",noiseFloor).put("calibrationWindows",calibrationWindows)
}

/** Direct port of the supplied 2026-10-03 filter; raw model scores are never reweighted. */
internal class EnvironmentEventFilter {
    private var floor=.0025
    private val noise=ArrayDeque<Double>()
    private var calibration=0
    private var streaks=mapOf<Int,Int>()
    private var lastEnd:Double?=null
    private var candidate:Int?=null
    private var count=0
    private fun minimum(index:Int)=if(index in KNOCK).12 else .25
    private fun immediate(index:Int)=if(index in KNOCK).45 else .70
    private fun learn(rms:Double) {
        noise.addLast(rms.coerceIn(.00075,.025));if(noise.size>40)noise.removeFirst()
        val target=noise.sorted()[floor((noise.size-1)*.2).toInt()]
        floor=if(calibration<4)target else floor*.9+target*.1;calibration++
    }
    fun evaluate(categories:List<SoundCategory>,inputRms:Double,endMs:Double):SoundDecision {
        val rms=if(inputRms.isFinite() && inputRms>0)inputRms else 0.0
        val list=categories.filter {it.index in 0..520 && it.score.isFinite() && it.score in 0.0..1.0}.sortedByDescending {it.score}
        if(lastEnd?.let {endMs-it>800}==true) {streaks=emptyMap();candidate=null;count=0};lastEnd=endMs
        val ordinary=list.any {it.index !in CAUTIOUS && it.index !in BACKGROUND && it.score>=minimum(it.index)}
        val loud=list.any {it.index in CAUTIOUS && it.score>=.7} && rms>=.03
        if(!ordinary && !loud)learn(rms)
        val cautious=list.filter {it.index in CAUTIOUS && it.score>=.7 && rms>max(.0015,floor*2.5)}
        val previous=streaks;streaks=cautious.associate {it.index to ((previous[it.index] ?: 0)+1)}
        val visible=list.filter {it.index !in CAUTIOUS || (streaks[it.index] ?: 0)>=3}
        val lead=visible.firstOrNull {it.score>=minimum(it.index)}
        val foreground=visible.firstOrNull {it.index !in BACKGROUND && it.score>=minimum(it.index)}
        val knock=visible.firstOrNull {it.index in KNOCK && it.score>=minimum(it.index)}
        val top=if(knock!=null && lead?.index in BACKGROUND)foreground else lead
        val candidates=if(knock!=null && knock !in visible.take(3))visible.take(2)+knock else visible
        fun result(kind:String,items:List<SoundCategory> = emptyList(),event:SoundCategory?=null)=SoundDecision(kind,items.take(3),event,floor,calibration)
        if(top!=null) {
            if(top.index==candidate)count++ else {candidate=top.index;count=1}
            if(top.index==494)return result("quiet")
            return result(if(top.index in CAUTIOUS || top.score>=immediate(top.index) || count>=2)"event" else "possible",candidates,top)
        }
        candidate=null;count=0
        if(cautious.isNotEmpty())return result("confirming")
        if(rms<=max(.0015,floor*1.8))return result("quiet")
        return result("uncertain",candidates)
    }
    companion object {
        private val CAUTIOUS=setOf(121,122,123,124,125,126,129,130)
        private val KNOCK=setOf(353,354)
        private val BACKGROUND=setOf(67,68,81,103,494,500,501,502,503,504,505,506,507,508,509,510,511,512,513,514,515,520)
    }
}

internal data class AudioWindow(val samples:FloatArray,val rms:Double,val peak:Double,val endMs:Double)
/** Stateful area-weighted resampler and 975/480 ms sliding window, byte-comparable to handoff fixtures. */
internal class EnvironmentAudioFramer(sourceSampleRate:Int,private val onWindow:(AudioWindow)->Unit) {
    private val ratio=sourceSampleRate/16000.0
    private var weight=0.0;private var sum=0.0
    private val ring=FloatArray(15600);private var write=0;private var total=0L;private var next=15600L
    init {require(sourceSampleRate>0)}
    fun feed(input:FloatArray) {
        for(value in input) {
            var remain=1.0
            while(remain>1e-9) {
                val n=min(remain,ratio-weight);sum+=value*n;weight+=n;remain-=n
                if(weight>=ratio-1e-9) {push((sum/ratio).toFloat());sum=0.0;weight=0.0}
            }
        }
    }
    private fun push(value:Float) {
        ring[write]=value;write=(write+1)%ring.size;total++
        if(total>=next) {
            val samples=FloatArray(ring.size) {ring[(write+it)%ring.size]}
            var energy=0.0;var peak=0.0
            for(v in samples) {energy+=v.toDouble()*v;peak=max(peak,abs(v.toDouble()))}
            onWindow(AudioWindow(samples,sqrt(energy/ring.size),peak,total/16.0));next+=7680
        }
    }
}
