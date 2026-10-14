package com.hearlens.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

internal enum class DirectionFormat(val label:String) {
    AUTO("自动：angle 文本 / JSON"), LABEL("angle: 123.0 deg 文本"), JSON("JSON 角度字段"),
    NUMBER("每行仅角度数字"), RAW("只读原始数据（不解析）")
}

internal data class DirectionConfig(
    val baudRate:Int=115200, val format:DirectionFormat=DirectionFormat.AUTO,
    val frontDegrees:Double=0.0, val clockwise:Boolean=true, val staleMs:Long=3000,
    val showOverlay:Boolean=true
) {
    init {
        require(baudRate in BAUD_RATES)
        require(frontDegrees.isFinite() && frontDegrees>=0 && frontDegrees<360)
        require(staleMs in 500..10000)
    }
    companion object { val BAUD_RATES=listOf(9600,19200,38400,57600,115200,230400,460800,921600) }
}

internal sealed interface DirectionEvent {
    data class Angle(val degrees:Double):DirectionEvent
    data object NoDirection:DirectionEvent
}

/** Only documented, complete records count as angles. Boot log numbers are never auto-parsed. */
internal object DirectionProtocol {
    private const val NUMBER="[+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)"
    private val number=Regex("^($NUMBER)\\s*(?:deg|°)?$",RegexOption.IGNORE_CASE)
    private val label=Regex("^(?:angle|angle_deg|azimuth|doa|direction|方向角)\\s*[:=]\\s*($NUMBER)\\s*(?:deg|°)?$",RegexOption.IGNORE_CASE)
    private val keys=setOf("angle","angle_deg","azimuth","doa","direction")
    fun parse(line:String,format:DirectionFormat):DirectionEvent? {
        val text=line.trim()
        if(text.isEmpty() || text.length>512 || format==DirectionFormat.RAW)return null
        if(format==DirectionFormat.JSON || format==DirectionFormat.AUTO && text.startsWith("{")) {
            return runCatching {
                val json=JSONObject(text)
                if(json.has("valid") && json.get("valid") !is Boolean)return null
                if(json.has("valid") && !json.getBoolean("valid"))return DirectionEvent.NoDirection
                val found=keys.filter(json::has)
                if(found.size!=1)return null
                val value=json.get(found.single())
                if(value !is Number)return null
                angle(value.toDouble())
            }.getOrNull()
        }
        val match=when(format) {
            DirectionFormat.AUTO,DirectionFormat.LABEL->label.matchEntire(text)
            DirectionFormat.NUMBER->number.matchEntire(text)
        } ?: return null
        return match.groupValues[1].toDoubleOrNull()?.let(::angle)
    }
    private fun angle(value:Double):DirectionEvent.Angle? =
        if(value.isFinite() && value in 0.0..360.0)DirectionEvent.Angle(DirectionMath.normalize(value)) else null
}

/** CR, LF or CRLF framing, including split UTF-8. Oversized/binary lines are discarded as a whole. */
internal class DirectionLineDecoder(private val maxBytes:Int=512) {
    private val buffer=ByteArrayOutputStream()
    private var discard=false
    var discardedLines=0L;private set
    fun feed(bytes:ByteArray,onLine:(String)->Unit) {
        for(b in bytes) {
            if(b==10.toByte() || b==13.toByte()) {
                if(!discard && buffer.size()>0)onLine(buffer.toString(Charsets.UTF_8.name()))
                buffer.reset();discard=false
            } else if(!discard) {
                if(buffer.size()>=maxBytes || (b.toInt() and 255)<32 && b!=9.toByte()) {
                    buffer.reset();discard=true;discardedLines++
                } else buffer.write(b.toInt())
            }
        }
    }
}

internal object DirectionMath {
    fun normalize(degrees:Double):Double { require(degrees.isFinite());return ((degrees%360)+360)%360 }
    fun relative(raw:Double,front:Double,clockwise:Boolean)=normalize(if(clockwise)raw-front else front-raw)
    fun label(relative:Double)=listOf("正前方","右前方","右方","右后方","正后方","左后方","左方","左前方")[((normalize(relative)+22.5)/45).toInt()%8]
    fun degrees(value:Double)=String.format(Locale.US,"%.1f°",value)
}

internal data class DirectionReading(val degrees:Double,val receivedAt:Long)

internal data class DirectionSnapshot(
    val enabled:Boolean=false, val connected:Boolean=false, val message:String="USB 方向小板未连接",
    val device:String="", val config:DirectionConfig=DirectionConfig(), val reading:DirectionReading?=null,
    val now:Long=0, val receivedBytes:Long=0, val accepted:Long=0, val rejected:Long=0,
    val rawText:String="", val rawHex:String="",
    val permissionRequests:Long=0,val portOpens:Long=0,val portCloses:Long=0,val permissionPending:Boolean=false
) {
    val ageMs get()=reading?.let {(now-it.receivedAt).coerceAtLeast(0)}
    val fresh get()=enabled && connected && reading!=null && ageMs!!<=config.staleMs
    val relative get()=if(fresh)DirectionMath.relative(reading!!.degrees,config.frontDegrees,config.clockwise) else null
    fun json():JSONObject = JSONObject().put("enabled",enabled).put("connected",connected).put("fresh",fresh)
        .put("message",message).put("device",device).put("rawDegrees",if(fresh)reading!!.degrees else JSONObject.NULL)
        .put("relativeDegrees",relative ?: JSONObject.NULL).put("sector",relative?.let(DirectionMath::label) ?: JSONObject.NULL)
        .put("ageMs",ageMs ?: JSONObject.NULL).put("frontDegrees",config.frontDegrees).put("clockwise",config.clockwise)
        .put("baudRate",config.baudRate).put("format",config.format.name).put("staleMs",config.staleMs)
        .put("showOverlay",config.showOverlay).put("receivedBytes",receivedBytes).put("accepted",accepted).put("rejected",rejected)
        .put("permissionRequests",permissionRequests).put("portOpens",portOpens).put("portCloses",portCloses)
        .put("permissionPending",permissionPending).put("standby",connected && !enabled)
}
