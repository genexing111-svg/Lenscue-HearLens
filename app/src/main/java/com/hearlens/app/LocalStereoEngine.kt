package com.hearlens.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Native-resolution phone rendering and independent, bounded wireless encoding. */
class LocalStereoEngine(context: Context, private val camera: UsbVideoServer) {
    @Volatile internal var directionProvider:(()->DirectionSnapshot)?=null
    @Volatile internal var environment:EnvironmentSoundSession?=null
    private val soundRenderer=SoundGlowRenderer(SoundLabels(context))
    private fun soundHudState(direction:DirectionSnapshot?)=if(pttState?.busy==true)SoundHudState() else
        SoundHudRouting.compose(environment?.state() ?: SoundHudState(now=SystemClock.elapsedRealtime()),direction)
    data class DisplayFrame(val bitmap: Bitmap, val id: Long, val generation: Long, val time: Long,
        val rotation: Int, val mirror: Boolean, val layout: StereoSettings)
    data class WireFrame(val jpeg: ByteArray, val id: Long, val time: Long, val generation: Long)
    private val preferences = context.getSharedPreferences("local_stereo_v5", Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile var settings = runCatching { decode(JSONObject(preferences.getString("layout", "{}")!!)) }.getOrDefault(StereoSettings())
        private set
    @Volatile private var latest: DisplayFrame? = null
    @Volatile private var wire: WireFrame? = null
    @Volatile private var demandAt = -10000L
    @Volatile private var screenRatio = 2400f / 1080f
    @Volatile private var phoneWidth = 2400
    @Volatile private var phoneHeight = 1080
    @Volatile private var screenHz = 60
    @Volatile private var renderWidth = 0
    @Volatile private var renderHeight = 0
    @Volatile private var renderTarget = 60
    @Volatile private var renderCost = 0.0
    @Volatile private var renderError: String? = null
    @Volatile var menu: HeadsetMenuState? = null
    @Volatile var calibrationMenu:HeadsetMenuState?=null
    @Volatile internal var quickOverlay:QuickOverlay?=null
    @Volatile internal var perceptionNotice:EnvironmentNotice?=null
    @Volatile internal var pttState:PttState?=null
    private val pttPainter=ThreadLocal.withInitial {PttPainter()}
    internal fun showPerceptionNotice(enabled:Boolean,message:String="") {
        perceptionNotice=EnvironmentNotice.transition(perceptionNotice,enabled,SystemClock.uptimeMillis(),message)
        onFrame?.invoke()
    }
    internal fun clearPerceptionNotice() {perceptionNotice=null;onFrame?.invoke()}
    internal fun hasPerceptionNotice()=perceptionNotice?.frame(SystemClock.uptimeMillis())?.active==true
    @Volatile var remoteLearning:String?=null
    @Volatile var remoteStatus = "尚未收到遥控器输入"
    @Volatile var remoteEvents: List<String> = emptyList()
    @Volatile internal var captionTurns:List<CaptionTurn> = emptyList()
    @Volatile internal var captionStyle=CaptionStyle()
    @Volatile internal var captionDiagnostic=JSONObject()
    @Volatile internal var captionVisibleLines=0
    private val captionPainter=ThreadLocal.withInitial { CaptionPainter() }
    @Volatile var onConfiguration: (() -> Unit)? = null
    private var wirelessLevel = 0
    private var healthyNetwork = 0
    private var feedbackAt = 0L
    @Volatile private var encodeCostMs = 0.0
    @Volatile private var hardwareCanvas = false
    @Volatile private var closed = false
    @Volatile private var encoderError: String? = null
    private val encoding = AtomicBoolean(false)
    private val encoder = Executors.newSingleThreadExecutor { r -> Thread(r,"HearLens-WiFi-encoder").apply { isDaemon=true } }
    private var encoderBitmap: Bitmap? = null
    private val encoderBytes = ByteArrayOutputStream(128 * 1024)
    private val wirelessRate = FrameRateMeter()
    @Volatile var onFrame: (() -> Unit)? = null
    private var sequence = 0L
    private var nextWire = 0L
    private val renderedRate = FrameRateMeter()
    private val displayedRate = FrameRateMeter()
    private var displayedId = -1L

    fun configure(value: StereoSettings) {
        val previous = settings
        settings = value
        // Manual text is session-only; layout is saved automatically on this phone.
        if (previous.copy(caption = "") != value.copy(caption = "")) preferences.edit { putString("layout", encode(value.copy(caption = "")).toString()) }
        if(previous.outputWidth!=value.outputWidth || previous.wirelessFps!=value.wirelessFps || previous.wirelessQuality!=value.wirelessQuality || previous.wirelessAdaptive!=value.wirelessAdaptive) synchronized(lock) { wirelessLevel=0;healthyNetwork=0 }
        onConfiguration?.invoke()
    }
    fun dimensions(width: Int, height: Int, hz: Int = 60) { if (width > 0 && height > 0) { phoneWidth=maxOf(width,height);phoneHeight=minOf(width,height);screenRatio=phoneWidth.toFloat()/phoneHeight;screenHz=hz } }
    fun phoneRenderSize() = PhoneResolution.size(settings.phoneRenderWidth,phoneWidth,phoneHeight)
    fun phoneWidthOptions() = listOf(1280,1600,1920,2400,2560,3200,3840).filter { it<phoneWidth } + 0
    fun resolutionLabel(request:Int):String { val (w,h)=PhoneResolution.size(request,phoneWidth,phoneHeight);return "$w × $h" + if(request==0)"（屏幕最高）" else "" }
    fun displayCap():Int {
        val s=camera.snapshot()
        return minOf(screenHz,s.capabilities.maxFps.takeIf { it>0 } ?: 60,
            s.settings.fps.takeIf { it>0 } ?: 120,settings.phoneFps.takeIf { it>0 } ?: 120).coerceIn(1,120)
    }
    fun reportSurface(w:Int,h:Int,fps:Int,cost:Double,error:String?) {
        if(w>0 && h>0) { renderWidth=w;renderHeight=h }
        renderTarget=fps;renderCost=cost;renderError=error
    }
    private fun wirelessProfile():Triple<Int,Int,Int> = synchronized(lock) {
        val s=settings; val level=if(s.wirelessAdaptive)wirelessLevel else 0
        val width=listOf(640,960,1280,1600,1920,2560).filter { it<=s.outputWidth }.let { it[(it.lastIndex-level).coerceAtLeast(0)] }
        Triple(width,(s.wirelessFps/(1.0+level*.35)).roundToInt().coerceAtLeast(minOf(5,s.wirelessFps)),(s.wirelessQuality-level*7).coerceAtLeast(35))
    }
    fun networkFeedback(transportMs:Double) = synchronized(lock) {
        val now=SystemClock.elapsedRealtime()
        if(!settings.wirelessAdaptive || now-feedbackAt<1500)return@synchronized
        feedbackAt=now
        val budget=1000.0/wirelessProfile().second
        if(maxOf(transportMs,encodeCostMs)>budget*.95) { wirelessLevel=(wirelessLevel+1).coerceAtMost(4);healthyNetwork=0 }
        else if(maxOf(transportMs,encodeCostMs)<budget*.5) { if(++healthyNetwork>=5) { wirelessLevel=(wirelessLevel-1).coerceAtLeast(0);healthyNetwork=0 } }
        else healthyNetwork=0
    }
    fun clear() = synchronized(lock) { latest = null; wire = null; renderedRate.reset(); displayedRate.reset(); wirelessRate.reset(); onFrame?.invoke() }
    fun current(): DisplayFrame? = latest?.takeIf { camera.isLocalGenerationCurrent(it.generation) && SystemClock.elapsedRealtime() - it.time < 1500 }
    fun networkFrame(after: Long): WireFrame? {
        demandAt = SystemClock.elapsedRealtime()
        return wire?.takeIf { it.id > after && demandAt - it.time < 1500 && camera.isLocalGenerationCurrent(it.generation) }
    }
    fun reportDisplayed(frame: DisplayFrame, accelerated: Boolean) = synchronized(lock) {
        hardwareCanvas = accelerated
        if (displayedId != frame.id) { displayedId = frame.id; displayedRate.tick(SystemClock.elapsedRealtime()) }
    }
    fun status(): JSONObject = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        val soundHud=soundHudState(directionProvider?.invoke())
        JSONObject().put("localFps", renderedRate.fps(now)).put("phoneDisplayFps", displayedRate.fps(now))
            .put("frameId", latest?.id ?: 0).put("ageMs", latest?.let { now - it.time } ?: -1)
            .put("width", wirelessProfile().first).put("height", wirelessHeight(wirelessProfile().first))
            .put("phoneWidth",phoneWidth).put("phoneHeight",phoneHeight).put("hardwareCanvas",hardwareCanvas)
            .put("renderWidth",renderWidth).put("renderHeight",renderHeight).put("renderTargetFps",renderTarget).put("renderCostMs",renderCost)
            .put("renderError",renderError ?: JSONObject.NULL).put("screenMaxHz",screenHz)
            .put("wirelessTargetFps",wirelessProfile().second).put("wirelessEffectiveQuality",wirelessProfile().third).put("wirelessAdaptiveLevel",wirelessLevel)
            .put("wirelessEncodeMs",encodeCostMs).put("remoteStatus",remoteStatus).put("remoteEvents",org.json.JSONArray(remoteEvents))
            .put("remoteLearning",remoteLearning ?: JSONObject.NULL)
            .put("menuOpen",menu!=null).put("menuSelected",menu?.selected ?: -1).put("phoneResolutionOptions",org.json.JSONArray(phoneWidthOptions()))
            .put("menuTitle",menu?.title ?: JSONObject.NULL).put("menuRowCount",menu?.rows?.size ?: 0)
            .put("menuSelectedTitle",menu?.let { it.rows.getOrNull(it.selected)?.first } ?: JSONObject.NULL)
            .put("quickControl",quickOverlay?.let {JSONObject().put("kind","language").put("selected",it.selected).put("message",it.message).put("labels",org.json.JSONArray(it.labels))} ?: JSONObject.NULL)
            .put("voiceRepair",pttState?.let {JSONObject().put("phase",it.phase.name).put("message",it.message).put("inputBytes",it.inputBytes).put("outputBytes",it.outputBytes).put("sampleRate",it.sampleRate)} ?: JSONObject.NULL)
            .put("wirelessEncodeFps",wirelessRate.fps(now)).put("encoderError",encoderError ?: JSONObject.NULL)
            .put("sourceWidth",latest?.bitmap?.width ?: 0).put("sourceHeight",latest?.bitmap?.height ?: 0)
            .put("speechCaption",captionDiagnostic).put("captionVisibleLines",captionVisibleLines)
            .put("direction",directionProvider?.invoke()?.json() ?: JSONObject.NULL)
            .put("environmentSound",environment?.diagnostic() ?: JSONObject.NULL)
            .put("soundHud",JSONObject().put("renderer","edge-glow").put("directionGlowVisible",soundHud.liveDirection!=null && soundHud.settings.intensity>0)
                .put("directionBearing",soundHud.liveDirection?.bearing ?: JSONObject.NULL).put("classificationActive",environment?.enabled==true).put("labelCount",soundHud.events.size))
            .put("settings", encode(settings))
    }

    /** CameraX's native converter hands us a lossless RGB frame. Local display never waits on Wi-Fi. */
    fun render(source: Bitmap, generation: Long, rotation: Int, mirror: Boolean) {
        if (closed || !camera.isLocalGenerationCurrent(generation)) { source.recycle(); return }
        source.prepareToDraw()
        val now = SystemClock.elapsedRealtime()
        val frame = synchronized(lock) {
            sequence++
            DisplayFrame(source, sequence, generation, now, rotation, mirror, settings).also {
                latest = it; renderedRate.tick(now)
            }
        }
        onFrame?.invoke()
        if (now - demandAt < 3000 && now + 2 >= nextWire && encoding.compareAndSet(false, true)) {
            val interval = 1000 / wirelessProfile().second
            nextWire = if (nextWire == 0L || now - nextWire > interval) now + interval else nextWire + interval
            try {
                encoder.execute {
                    try {
                        if (!closed && camera.isLocalGenerationCurrent(frame.generation)) {
                            val encodeStart=SystemClock.elapsedRealtimeNanos()
                            val profile=wirelessProfile()
                            val w = profile.first; val h = wirelessHeight(w)
                            var out = encoderBitmap
                            if (out == null || out.width != w || out.height != h) {
                                out?.recycle(); out = createBitmap(w, h); encoderBitmap = out
                            }
                            drawStereo(Canvas(out), w, h, frame)
                            encoderBytes.reset()
                            out.compress(Bitmap.CompressFormat.JPEG, profile.third, encoderBytes)
                            encodeCostMs=(SystemClock.elapsedRealtimeNanos()-encodeStart)/1_000_000.0
                            if (!closed && camera.isLocalGenerationCurrent(frame.generation)) {
                                wire = WireFrame(encoderBytes.toByteArray(),frame.id,frame.time,frame.generation)
                                synchronized(lock) { wirelessRate.tick(SystemClock.elapsedRealtime()) }
                                encoderError = null
                            }
                        }
                    } catch (error: Exception) { encoderError = "无线编码暂时失败：${error.message}" }
                    finally { encoding.set(false) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { encoding.set(false) }
        }
        // Source bitmaps are immutable by convention once published. Android RenderThread may
        // still use older frames; GC releases them safely. There is no growing frame queue.
    }

    private fun wirelessHeight(width: Int) = ((width / screenRatio / 2).roundToInt() * 2).coerceAtLeast(2)

    /** The phone calls this on its hardware Canvas at native screen resolution.
     * A separate worker calls the same geometry on a smaller bitmap for the network. */
    fun drawStereo(canvas: Canvas, width: Int, height: Int, frame: DisplayFrame) {
        drawStereo(canvas,width,height,frame,soundHudState(directionProvider?.invoke()))
    }
    /** One HUD snapshot is shared by both eyes and the wireless compositor. */
    internal fun drawStereo(canvas:Canvas,width:Int,height:Int,frame:DisplayFrame,soundState:SoundHudState) {
        val source = frame.bitmap; val value = settings
        canvas.drawColor(Color.BLACK)
        val rotation = (frame.rotation + value.rotation) % 360
        val sw = if (rotation % 180 == 0) source.width else source.height
        val sh = if (rotation % 180 == 0) source.height else source.width
        val ew = width / 2f; val eh = height.toFloat()
        val turns=captionTurns;val style=captionStyle
        val soundScene=SoundScene(source,rotation,value.mirror xor frame.mirror,value.brightness,value.contrast,value.saturation)
        if(turns.isEmpty())captionVisibleLines=0
        val boxes=listOf(false,true).map { right -> StereoGeometry.fit(sw,sh,ew,eh,value.scale,if(right)value.rightSize else value.leftSize,value.spacing,if(right)value.rightY else value.leftY,right) }
        val commonW=boxes.minOf { it.width };val commonH=boxes.minOf { it.height }
        val captionBoxes=boxes.map {StereoGeometry.Box(it.x+(it.width-commonW)/2,it.y+(it.height-commonH)/2,commonW,commonH)}
        val rearLimits=boxes.map {soundRenderer.captionBottomLimit(it,soundState)}
        // Both eyes move by the same distance even when their calibrated image sizes differ.
        val captionLift=captionBoxes.indices.maxOf {i->rearLimits[i]?.let {limit->
            (captionBoxes[i].y+commonH*(1-style.bottomPercent/100f)-limit).coerceAtLeast(0f)
        } ?: 0f}
        val manualBottomLimit=rearLimits.filterNotNull().minOrNull()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (value.saturation != 100 || value.contrast != 100 || value.brightness != 100) {
            val cm = ColorMatrix().apply { setSaturation(value.saturation / 100f) }
            val c = value.contrast / 100f; val offset = 128 * (1 - c) + (value.brightness - 100) * 2.55f
            cm.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset, 0f,c,0f,0f,offset, 0f,0f,c,0f,offset, 0f,0f,0f,1f,0f)))
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }
        for (right in listOf(false, true)) {
            if (value.eyeMode == "left" && right || value.eyeMode == "right" && !right) continue
            canvas.save(); canvas.translate(if (right) ew else 0f, 0f); canvas.clipRect(0f,0f,ew,eh)
            val box = StereoGeometry.fit(sw, sh, ew, eh, value.scale, if (right) value.rightSize else value.leftSize,
                value.spacing, if (right) value.rightY else value.leftY, right)
            canvas.save()
            canvas.translate(box.x + box.width / 2, box.y + box.height / 2)
            canvas.scale((if (value.mirror xor frame.mirror) -1 else 1) * box.width / sw, box.height / sh)
            canvas.rotate(rotation.toFloat())
            canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, paint)
            canvas.restore()
            soundRenderer.draw(canvas,box,soundState,soundScene,
                if(turns.isNotEmpty())captionReservation(captionBoxes[if(right)1 else 0],style,captionLift) else null)
            if(turns.isEmpty())drawCaption(canvas, ew, eh, width, value,manualBottomLimit)
            else {
                captionVisibleLines=captionPainter.get()!!.draw(canvas,captionBoxes[if(right)1 else 0],turns,style,captionLift)
            }
            if (value.calibration != "off") {
                val guide = Paint().apply { color = Color.GREEN; strokeWidth = maxOf(1f, width / 960f) }
                canvas.drawLine(ew/2,0f,ew/2,eh,guide); canvas.drawLine(0f,eh/2,ew,eh/2,guide)
                if (value.calibration == "grid") for (i in 1..3) {
                    canvas.drawLine(ew*i/4,0f,ew*i/4,eh,guide); canvas.drawLine(0f,eh*i/4,ew,eh*i/4,guide)
                }
            }
            canvas.restore()
        }
        drawMenu(canvas,width,height,frame)
    }

    /** PreviewView uses FIT_CENTER; this overlay samples the identical uncropped camera image. */
    internal fun drawMonoCaptions(canvas:Canvas,width:Int,height:Int,soundState:SoundHudState=soundHudState(directionProvider?.invoke())) {
        val frame=current()
        if(frame==null) {
            perceptionNotice?.let {EnvironmentNoticePainter.draw(canvas,listOf(StereoGeometry.Box(0f,0f,width.toFloat(),height.toFloat())),it,SystemClock.uptimeMillis())}
            pttState?.let {pttPainter.get()!!.draw(canvas,listOf(StereoGeometry.Box(0f,0f,width.toFloat(),height.toFloat())),it,SystemClock.uptimeMillis())}
            return
        }
        val turns=captionTurns
        val rotated=frame.rotation%180!=0
        val sw=if(rotated)frame.bitmap.height else frame.bitmap.width;val sh=if(rotated)frame.bitmap.width else frame.bitmap.height
        val box=StereoGeometry.fit(sw,sh,width.toFloat(),height.toFloat(),100,0,0,0,false)
        val style=captionStyle
        val lift=soundRenderer.captionBottomLimit(box,soundState)?.let {(box.y+box.height*(1-style.bottomPercent/100f)-it).coerceAtLeast(0f)} ?: 0f
        soundRenderer.draw(canvas,box,soundState,SoundScene(frame.bitmap,frame.rotation,frame.mirror),if(turns.isNotEmpty())captionReservation(box,style,lift) else null)
        if(turns.isNotEmpty())captionVisibleLines=captionPainter.get()!!.draw(canvas,box,turns,style,lift)
        perceptionNotice?.let {EnvironmentNoticePainter.draw(canvas,listOf(box),it,SystemClock.uptimeMillis())}
        pttState?.let {pttPainter.get()!!.draw(canvas,listOf(box),it,SystemClock.uptimeMillis())}
    }

    internal fun menuPanels(width:Int,height:Int,frame:DisplayFrame?=latest):List<HeadsetMenuGeometry.Panel> {
        val value=settings
        val rotated=((frame?.rotation ?: 0)+value.rotation)%180!=0
        val sw=frame?.bitmap?.let { if(rotated)it.height else it.width } ?: (width/2).coerceAtLeast(1)
        val sh=frame?.bitmap?.let { if(rotated)it.width else it.height } ?: height.coerceAtLeast(1)
        return HeadsetMenuGeometry.panels(width,height,sw,sh,value)
    }
    private fun captionReservation(box:StereoGeometry.Box,style:CaptionStyle,bottomLift:Float=0f):RectF {
        val font=minOf(box.width*.026f,box.height*.047f)*style.sizePercent/100f
        val height=4*font*1.45f+64*font/22f
        val width=box.width*style.widthPercent/100f;val bottom=box.y+box.height*(1-style.bottomPercent/100f)-bottomLift
        return RectF(box.x+(box.width-width)/2,(bottom-height).coerceAtLeast(box.y),box.x+(box.width+width)/2,bottom)
    }
    fun drawMenu(canvas:Canvas,width:Int,height:Int,frame:DisplayFrame?=latest) {
        (calibrationMenu ?: menu)?.let { HeadsetMenuPainter.draw(canvas,menuPanels(width,height,frame),it) }
        if(calibrationMenu==null)quickOverlay?.let {overlay->
            QuickOverlayPainter.draw(canvas,menuPanels(width,height,frame),overlay)
        }
        if(perceptionNotice!=null || pttState!=null) {
            val s=settings;val rotated=((frame?.rotation ?: 0)+s.rotation)%180!=0
            val sw=frame?.bitmap?.let {if(rotated)it.height else it.width} ?: width/2
            val sh=frame?.bitmap?.let {if(rotated)it.width else it.height} ?: height
            val boxes=listOf(false,true).filterNot {right->s.eyeMode=="left" && right || s.eyeMode=="right" && !right}.map {right->
                val box=StereoGeometry.fit(sw,sh,width/2f,height.toFloat(),s.scale,if(right)s.rightSize else s.leftSize,s.spacing,if(right)s.rightY else s.leftY,right)
                box.copy(x=box.x+if(right)width/2f else 0f)
            }
            perceptionNotice?.let {EnvironmentNoticePainter.draw(canvas,boxes,it,SystemClock.uptimeMillis())}
            pttState?.let {pttPainter.get()!!.draw(canvas,boxes,it,SystemClock.uptimeMillis())}
        }
    }

    fun release() { closed = true; onFrame = null; clear(); encoder.shutdownNow();soundRenderer.release() }
    private fun drawCaption(canvas: Canvas, ew: Float, eh: Float, width: Int, s: StereoSettings,bottomLimit:Float?=null) {
        if (s.caption.isBlank()) return
        val unit = width / 1280f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s.fontSize * unit; typeface = Typeface.DEFAULT_BOLD }
        val pad = 12 * unit; val max = ew - 4 * pad
        val lines = ArrayList<String>(); var line = ""
        for (char in s.caption) {
            if (char == '\n' || p.measureText(line + char) > max) { lines.add(line); line = ""; if (lines.size == 4) break }
            if (char != '\n') line += char
        }
        if (lines.size < 4 && line.isNotEmpty()) lines.add(line)
        if (lines.size == 4) { var last = lines[3]; while (last.isNotEmpty() && p.measureText(last + "…") > max) last = last.dropLast(1); lines[3] = last + "…" }
        val lh = p.fontSpacing * 1.12f; val height = lines.size * lh + 2 * pad
        val top = (eh * s.textY / 100f - height / 2).coerceIn(0f, ((bottomLimit ?: eh) - height).coerceAtLeast(0f))
        // Manual text uses the same warm material while preserving its existing opacity control.
        SettingsPainter.panel(canvas,RectF(pad,top,ew-pad,top+height),p,radius=pad,opacity=s.background/89f)
        p.textAlign = when(s.align) { "left" -> Paint.Align.LEFT; "right" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
        val x = when(s.align) { "left" -> 2*pad; "right" -> ew-2*pad; else -> ew/2 }
        lines.forEachIndexed { i, text ->
            val y = top + pad - p.fontMetrics.top + i * lh
            if (s.stroke > 0) { p.style = Paint.Style.STROKE; p.strokeWidth = s.stroke * unit; p.color = Color.BLACK; canvas.drawText(text,x,y,p) }
            p.style = Paint.Style.FILL; p.color = s.textColor.toColorInt(); canvas.drawText(text,x,y,p)
        }
    }

    companion object {
        fun encode(s: StereoSettings): JSONObject = JSONObject().apply {
            put("outputWidth",s.outputWidth); put("wirelessFps",s.wirelessFps); put("wirelessQuality",s.wirelessQuality)
            put("scale",s.scale); put("spacing",s.spacing); put("leftSize",s.leftSize); put("rightSize",s.rightSize); put("leftY",s.leftY); put("rightY",s.rightY)
            put("brightness",s.brightness); put("contrast",s.contrast); put("saturation",s.saturation); put("rotation",s.rotation); put("mirror",s.mirror)
            put("caption",s.caption); put("fontSize",s.fontSize); put("textY",s.textY); put("textColor",s.textColor); put("align",s.align); put("background",s.background)
            put("stroke",s.stroke); put("eyeMode",s.eyeMode); put("calibration",s.calibration)
            put("phoneRenderWidth",s.phoneRenderWidth);put("phoneFps",s.phoneFps);put("wirelessAdaptive",s.wirelessAdaptive)
        }
        fun decode(j: JSONObject, base: StereoSettings = StereoSettings()): StereoSettings {
            val defaults = encode(base)
            require(j.keys().asSequence().all { defaults.has(it) }) { "未知画面设置" }
            fun n(k: String): Int { val v = j.opt(k) ?: defaults.get(k); require(v is Int) { "$k 需要整数" }; return v }
            fun t(k: String): String { val v = j.opt(k) ?: defaults.get(k); require(v is String); return v }
            val mirror = j.opt("mirror") ?: base.mirror; require(mirror is Boolean)
            val adaptive=j.opt("wirelessAdaptive") ?: base.wirelessAdaptive; require(adaptive is Boolean)
            return StereoSettings(n("outputWidth"),n("wirelessFps"),n("wirelessQuality"),n("scale"),n("spacing"),n("leftSize"),n("rightSize"),n("leftY"),n("rightY"),
                n("brightness"),n("contrast"),n("saturation"),n("rotation"),mirror,t("caption"),n("fontSize"),n("textY"),t("textColor"),t("align"),n("background"),n("stroke"),t("eyeMode"),t("calibration"),n("phoneRenderWidth"),n("phoneFps"),adaptive)
        }
    }
}

