package com.hearlens.app

import kotlin.math.*

internal data class SoundGlowSettings(val intensity:Int=85,val depth:Int=80,val spread:Int=60,val transparency:Int=80,
    val language:String="zh-Hans",val associateDirection:Boolean=true,val reducedMotion:Boolean=false) {
    init {require(intensity in 0..100 && depth in 20..160 && spread in 20..160 && transparency in 0..100);require(language in listOf("zh-Hans","zh-Hant","en"))}
}
internal data class SoundHudEvent(val id:Long,val index:Int,val possible:Boolean,val bearing:Double?,val born:Long,val sourceEnd:Long,
    val textOverride:String?=null)
internal data class LiveDirectionGlow(val bearing:Double,val opacity:Double)
internal data class SoundHudState(val enabled:Boolean=false,val settings:SoundGlowSettings=SoundGlowSettings(),val events:List<SoundHudEvent> = emptyList(),val now:Long=0,
    val liveDirection:LiveDirectionGlow?=null)

/** Display is independent of microphone/model state. A board reading never invents a sound name. */
internal object SoundHudRouting {
    fun compose(classifier:SoundHudState,direction:DirectionSnapshot?):SoundHudState {
        val showDirection=direction?.config?.showOverlay!=false
        val events=if(classifier.enabled)classifier.events.map {if(showDirection)it else it.copy(bearing=null)} else emptyList()
        val live=if(showDirection && direction?.fresh==true) {
            val end=direction.config.staleMs
            val hold=minOf(1200L,end/2)
            val age=direction.ageMs!!
            val opacity=if(age>=end)0.0 else if(classifier.settings.reducedMotion || age<=hold)1.0 else {
                val t=(age-hold).toDouble()/(end-hold);1-t*t*(3-2*t)
            }
            if(opacity>0)LiveDirectionGlow(direction.relative!!,opacity) else null
        } else null
        return classifier.copy(enabled=classifier.enabled || live!=null,events=events,liveDirection=live)
    }
}

internal object SoundGlowKernel {
    fun sample(x:Double,y:Double,w:Double,h:Double,bearing:Double,settings:SoundGlowSettings=SoundGlowSettings(),all:Boolean=false):DoubleArray {
        val d=max(0.0,minOf(x,y,w-x,h-y))
        val angle=atan2((x-w/2)/(w/2),-(y-h/2)/(h/2))
        val delta=abs(atan2(sin(angle-Math.toRadians(bearing)),cos(angle-Math.toRadians(bearing))))
        val sigma=Math.toRadians(settings.spread.toDouble())/2.355
        val sector=if(all)1.0 else exp(-.5*(delta/sigma).pow(2))
        val core=.65*exp(-d/2.2);val ribbon=.35*exp(-d/18);val bloom=.35*exp(-.5*(d/settings.depth).pow(2))
        val near=exp(-d/13)
        return doubleArrayOf(floor(48*near+.5),floor(114+86*near+.5),255.0,min(1.0,(core+ribbon+bloom)*sector*settings.intensity/100))
    }
    fun opacity(elapsed:Long,reduced:Boolean=false):Double {
        if(elapsed<0)return 0.0
        if(reduced)return if(elapsed<2600)1.0 else 0.0
        if(elapsed<180) {val t=elapsed/180.0;return t*t*(3-2*t)}
        if(elapsed<1200)return 1.0
        if(elapsed<2800) {val t=(elapsed-1200)/1600.0;return 1-t*t*(3-2*t)}
        return 0.0
    }
    /** Label center clamped by its measured outer dimensions. Null bearing is explicitly nondirectional. */
    fun labelBox(w:Float,h:Float,tw:Float,th:Float,bearing:Double?):FloatArray {
        val cx:Float;val cy:Float
        if(bearing==null) {cx=w/2;cy=h/2} else {
            val x=sin(Math.toRadians(bearing));val y=-cos(Math.toRadians(bearing));val r=.5/max(abs(x),abs(y))
            cx=(w*(.5+x*r)).toFloat();cy=(h*(.5+y*r)).toFloat()
        }
        val left=minOf(22f,(w-tw).coerceAtLeast(0f)/2);val top=minOf(24f,(h-th).coerceAtLeast(0f)/2)
        return floatArrayOf((cx-tw/2).coerceIn(left,(w-tw-left).coerceAtLeast(left)),(cy-th/2).coerceIn(top,(h-th-top).coerceAtLeast(top)),tw,th)
    }
    fun avoidRectangle(label:FloatArray,obstacle:FloatArray,w:Float,h:Float):FloatArray {
        fun intersects(a:FloatArray)=a[0]<obstacle[0]+obstacle[2] && a[0]+a[2]>obstacle[0] && a[1]<obstacle[1]+obstacle[3] && a[1]+a[3]>obstacle[1]
        if(!intersects(label))return label
        val x=label[0];val y=label[1];val tw=label[2];val th=label[3]
        val candidates=listOf(floatArrayOf(x,obstacle[1]-th-8,tw,th),floatArrayOf(x,obstacle[1]+obstacle[3]+8,tw,th),
            floatArrayOf(obstacle[0]-tw-8,y,tw,th),floatArrayOf(obstacle[0]+obstacle[2]+8,y,tw,th))
        return candidates.filter {it[0]>=4 && it[1]>=4 && it[0]+tw<=w-4 && it[1]+th<=h-4 && !intersects(it)}
            .minByOrNull {abs(it[0]-x)+abs(it[1]-y)} ?: label
    }
}

/** There is no proven source identity: this only returns a temporally aligned, stable direction estimate. */
internal object SoundDirectionAssociation {
    fun estimate(readings:List<DirectionReading>,windowStart:Long,windowEnd:Long):Double? {
        val relevant=readings.filter {it.receivedAt in windowStart..windowEnd}
        if(relevant.isEmpty())return null
        val x=relevant.sumOf {cos(Math.toRadians(it.degrees))};val y=relevant.sumOf {sin(Math.toRadians(it.degrees))}
        if(hypot(x,y)/relevant.size<.9)return null
        val mean=DirectionMath.normalize(Math.toDegrees(atan2(y,x)))
        if(relevant.any {abs(DirectionMath.normalize(it.degrees-mean+180)-180)>25})return null
        return mean
    }
}

/** Same class/nearby direction within a lifetime coalesces; distinct events keep separate 2.8 s lifetimes. */
internal class SoundEventTimeline {
    private val events=mutableListOf<SoundHudEvent>();private var sequence=0L
    @Synchronized fun clear() {events.clear()}
    @Synchronized fun accept(index:Int,possible:Boolean,bearing:Double?,now:Long,sourceEnd:Long,reduced:Boolean) {
        events.removeAll {now-it.born>=if(reduced)2600 else 2800}
        val same=events.indexOfFirst {it.index==index && if(it.bearing==null || bearing==null)it.bearing==bearing else abs(DirectionMath.normalize(it.bearing-bearing+180)-180)<=30}
        if(same>=0) {if(!possible)events[same]=events[same].copy(possible=false);return}
        events+=SoundHudEvent(++sequence,index,possible,bearing,now,sourceEnd)
        if(events.size>3)events.removeAt(0)
    }
    @Synchronized fun snapshot(now:Long,reduced:Boolean):List<SoundHudEvent> {
        events.removeAll {now-it.born>=if(reduced)2600 else 2800};return events.toList()
    }
}
