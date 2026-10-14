package com.hearlens.app

/** Immutable animation shared across the phone render thread and wireless encoder. */
internal data class EnvironmentNotice(val enabled:Boolean,val born:Long,val from:Float,
    val entryFrom:Float=0f,val message:String="") {
    data class Frame(val reveal:Float,val switchPosition:Float,val active:Boolean)
    fun frame(now:Long):Frame {
        val elapsed=(now-born).coerceAtLeast(0)
        fun smooth(t:Float):Float {val x=t.coerceIn(0f,1f);return x*x*(3-2*x)}
        val entry=entryFrom+(1-entryFrom)*smooth(elapsed/220f)
        val reveal=entry*(1-smooth((elapsed-1900)/260f))
        val progress=smooth((elapsed-160)/320f)
        val target=if(enabled)1f else 0f
        return Frame(reveal,from+(target-from)*progress,elapsed<2160)
    }
    companion object {
        fun transition(previous:EnvironmentNotice?,enabled:Boolean,now:Long,message:String=""):EnvironmentNotice {
            val frame=previous?.frame(now)?.takeIf {it.active}
            return EnvironmentNotice(enabled,now,frame?.switchPosition ?: if(enabled)0f else 1f,frame?.reveal ?: 0f,message)
        }
    }
}
