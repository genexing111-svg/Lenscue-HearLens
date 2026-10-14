package com.hearlens.app

/** Order is part of the user-facing five-stop control. */
internal enum class CaptionMode(val label:String,val protocol:String,val target:String,val translation:Boolean) {
    FAST("快速识别","asr","en",false),
    SPEAKERS("说话人识别","live","en",false),
    SIMPLIFIED("转译为简体中文","live","zh",true),
    TRADITIONAL("转译为繁体中文","live","zh",true),
    ENGLISH("转译为英文","live","en",true);
    fun sameSession(other:CaptionMode)=protocol==other.protocol && target==other.target
    companion object {
        fun restore(name:String?,translated:Boolean,target:String)=entries.firstOrNull { it.name==name }
            ?: if(!translated)SPEAKERS else if(target=="zh")SIMPLIFIED else ENGLISH
    }
}

/** Off is a UI choice, never an ASR/translation model or a shifted persisted enum. */
internal object CaptionChoices {
    val labels=listOf("关闭字幕")+CaptionMode.entries.map {it.label}
    fun modeAt(index:Int):CaptionMode? {require(index in labels.indices);return if(index==0)null else CaptionMode.entries[index-1]}
    fun index(mode:CaptionMode,enabled:Boolean)=if(enabled)mode.ordinal+1 else 0
}

/** Pure state for cancelable shortcuts, independent of Android input codes. */
internal class ShortcutSelection {
    enum class Kind { NONE,LANGUAGE }
    var kind=Kind.NONE;private set
    var selected=-1;private set
    fun language(current:Int) { kind=Kind.LANGUAGE;selected=current.coerceIn(CaptionChoices.labels.indices) }
    fun step(delta:Int) { if(kind==Kind.LANGUAGE)selected=(selected+delta).coerceIn(CaptionChoices.labels.indices) }
    fun cancel() { kind=Kind.NONE;selected=-1 }
    fun take():Int { val choice=selected;cancel();return choice }
    companion object {
        // Top: main; lower right: telephoto; lower left: ultrawide. Center makes no selection.
        fun sector(x:Float,y:Float):Int {
            if(!x.isFinite() || !y.isFinite() || x*x+y*y<.30f)return -1
            val degrees=Math.toDegrees(kotlin.math.atan2(y.toDouble(),x.toDouble()))
            return (kotlin.math.floor(((degrees+150+360)%360)/120).toInt()).coerceIn(0,2)
        }
    }
}
