package com.hearlens.app

import android.graphics.*
import android.os.Build
import android.os.SystemClock
import androidx.core.graphics.withSave
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal data class SoundScene(val image:Bitmap,val rotation:Int=0,val mirror:Boolean=false,val brightness:Int=100,val contrast:Int=100,val saturation:Int=100) {
    fun draw(canvas:Canvas,w:Float,h:Float) {
        val rotated=rotation%180!=0;val sw=if(rotated)image.height else image.width;val sh=if(rotated)image.width else image.height
        val cm=ColorMatrix().apply {setSaturation(saturation/100f)};val c=contrast/100f;val offset=128*(1-c)+(brightness-100)*2.55f
        cm.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset,0f,c,0f,0f,offset,0f,0f,c,0f,offset,0f,0f,0f,1f,0f)))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {colorFilter=ColorMatrixColorFilter(cm)}
        canvas.withSave {translate(w/2,h/2);scale((if(mirror)-1 else 1)*w/sw,h/sh);rotate(rotation.toFloat());drawBitmap(image,-image.width/2f,-image.height/2f,paint)}
    }
}

/** Reference-space glow + locally blurred label backdrop, shared by mono, stereo and wireless. */
internal class SoundGlowRenderer(private val labels:SoundLabels) {
    private data class Label(val event:SoundHudEvent,val text:String,val rect:RectF)
    private data class Material(val rect:RectF,val bitmap:Bitmap)
    private data class MaterialSet(val sceneId:Int,val createdAt:Long,val tiles:Map<Long,Material>)
    private val materialWorker=Executors.newSingleThreadExecutor {r->Thread(r,"HearLens-sound-material").apply {isDaemon=true}}
    private val busy=AtomicBoolean(false)
    private val lock=Any()
    private val materials=linkedMapOf<String,MaterialSet>()
    private val raster=ThreadLocal.withInitial {linkedMapOf<String,Bitmap>()}
    private val rasterGeometry=ThreadLocal.withInitial {linkedMapOf<String,SoundGlowRaster>()}
    private val shader=ThreadLocal<RuntimeShader?>()
    @Volatile private var released=false
    @Volatile private var requestedAt=0L
    @Volatile var materialError:String?=null;private set
    @Volatile var materialCostMs=0.0;private set
    private fun name(event:SoundHudEvent,language:String)=event.textOverride ?: (if(event.possible)if(language=="en")"Possibly " else "可能是" else "")+labels.name(event.index,language)
    private fun isRear(event:SoundHudEvent)=event.bearing?.let {DirectionMath.normalize(it) in 135.0..225.0}==true
    private fun labels(state:SoundHudState,w:Float,h:Float,avoid:RectF?=null):List<Label> {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=15f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
        val visible=state.events.filter {SoundGlowKernel.opacity(state.now-it.born,state.settings.reducedMotion)>0}
        val rearCount=visible.count(::isRear)
        val items=visible.map {event ->
            // Rear labels share the bottom edge horizontally; long names cannot force a second row.
            val maxWidth=if(isRear(event))min(w-44f,(w-44f-8f*(rearCount-1))/rearCount) else w-44f
            val text=android.text.TextUtils.ellipsize(name(event,state.settings.language),android.text.TextPaint(paint),(maxWidth-26f).coerceAtLeast(20f),android.text.TextUtils.TruncateAt.END).toString()
            val tw=min(w-4,paint.measureText(text)+26);val th=37.5f.coerceAtMost(h)
            var b=SoundGlowKernel.labelBox(w,h,tw,th,event.bearing)
            if(avoid!=null && !isRear(event))b=SoundGlowKernel.avoidRectangle(b,floatArrayOf(avoid.left,avoid.top,avoid.width(),avoid.height()),w,h)
            Label(event,text,RectF(b[0],b[1],b[0]+b[2],b[1]+b[3]))
        }
        val rear=items.filter {isRear(it.event)}.sortedWith(compareBy<Label> {it.rect.left}.thenBy {it.event.id})
        for(i in 1 until rear.size) {
            val rect=rear[i].rect;val left=rear[i-1].rect.right+8
            if(rect.left<left)rect.offset(left-rect.left,0f)
        }
        rear.lastOrNull()?.rect?.let {if(it.right>w-22)it.offset(w-22-it.right,0f)}
        for(i in rear.size-2 downTo 0) {
            val rect=rear[i].rect;val right=rear[i+1].rect.left-8
            if(rect.right>right)rect.offset(right-rect.right,0f)
        }
        val used=rear.map {RectF(it.rect)}.toMutableList()
        for(label in items.filterNot {isRear(it.event)}) {
            val rect=label.rect;val th=rect.height()
            // Other directions retain the existing collision handling, outside the rear row.
            repeat(3) {
                if(used.any {RectF.intersects(it,rect)}) {
                    val dy=if(rect.centerY()>h/2)-th-8 else th+8
                    rect.offset(0f,dy);if(rect.top<4)rect.offset(0f,4-rect.top);if(rect.bottom>h-4)rect.offset(0f,h-4-rect.bottom)
                }
            }
            used+=RectF(rect)
        }
        return items
    }
    /** Absolute lower bound for captions, leaving a 12-reference-pixel gap above visible rear labels. */
    fun captionBottomLimit(box:StereoGeometry.Box,state:SoundHudState):Float? {
        if(!state.enabled)return null
        val unit=min(box.width/736f,box.height/430f).coerceAtLeast(.05f)
        return labels(state,box.width/unit,box.height/unit).filter {isRear(it.event)}.minOfOrNull {box.y+(it.rect.top-12)*unit}
    }
    internal fun labelBounds(box:StereoGeometry.Box,state:SoundHudState,avoid:RectF?=null):Map<Long,RectF> {
        val unit=min(box.width/736f,box.height/430f).coerceAtLeast(.05f)
        val logicalAvoid=avoid?.let {RectF((it.left-box.x)/unit,(it.top-box.y)/unit,(it.right-box.x)/unit,(it.bottom-box.y)/unit)}
        return labels(state,box.width/unit,box.height/unit,logicalAvoid).associate {it.event.id to RectF(box.x+it.rect.left*unit,box.y+it.rect.top*unit,box.x+it.rect.right*unit,box.y+it.rect.bottom*unit)}
    }
    private fun materialKey(state:SoundHudState,w:Float,h:Float,items:List<Label>)="$w:$h:${state.settings}:${items.map {"${it.event.id}:${it.event.bearing}:${it.text}:${it.rect}"}}"
    fun materialReady(state:SoundHudState,w:Float,h:Float,sceneId:Int?=null):Boolean=synchronized(lock) {
        materials[materialKey(state,w,h,labels(state,w,h))]?.let {sceneId==null || it.sceneId==sceneId}==true
    }
    fun draw(canvas:Canvas,box:StereoGeometry.Box,state:SoundHudState,scene:SoundScene,avoid:RectF?=null) {
        if(!state.enabled || (state.events.isEmpty() && state.liveDirection==null) || released)return
        val unit=min(box.width/736f,box.height/430f).coerceAtLeast(.05f)
        val w=box.width/unit;val h=box.height/unit
        val logicalAvoid=avoid?.let {RectF((it.left-box.x)/unit,(it.top-box.y)/unit,(it.right-box.x)/unit,(it.bottom-box.y)/unit)}
        val items=labels(state,w,h,logicalAvoid);val key=materialKey(state,w,h,items)
        if(items.isNotEmpty())requestMaterials(key,items,state,scene,w,h)
        val cached=synchronized(lock) {materials[key]?.takeIf {SystemClock.elapsedRealtime()-it.createdAt<500}?.tiles}
        canvas.withSave {
            translate(box.x,box.y);scale(unit,unit);clipRect(0f,0f,w,h)
            val live=state.liveDirection
            if(live!=null)drawGlow(this,w,h,live.bearing,state.settings,live.opacity.toFloat())
            for(event in state.events) {
                val bearing=event.bearing ?: continue
                // The current board cue replaces nearby inferred glow, avoiding doubled brightness.
                if(live!=null && abs(DirectionMath.normalize(bearing-live.bearing+180)-180)<=30)continue
                val opacity=SoundGlowKernel.opacity(state.now-event.born,state.settings.reducedMotion).toFloat()
                if(opacity>0)drawGlow(this,w,h,bearing,state.settings,opacity)
            }
            for(label in items) {
                val opacity=SoundGlowKernel.opacity(state.now-label.event.born,state.settings.reducedMotion).toFloat()
                if(opacity>0)drawLabel(this,label,cached?.get(label.event.id),state.settings,opacity)
            }
        }
    }
    private fun drawGlow(canvas:Canvas,w:Float,h:Float,bearing:Double,s:SoundGlowSettings,opacity:Float) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=(opacity*255).roundToInt()}
        if(Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated) {
            val program=shader.get() ?: RuntimeShader(SHADER).also {shader.set(it)}
            program.setFloatUniform("size",w,h);program.setFloatUniform("bearing",Math.toRadians(bearing).toFloat())
            program.setFloatUniform("intensity",s.intensity/100f);program.setFloatUniform("depth",s.depth.toFloat());program.setFloatUniform("sigma",Math.toRadians(s.spread.toDouble()).toFloat()/2.355f)
            paint.shader=program;canvas.drawRect(0f,0f,w,h,paint)
        } else {
            val key="$w:$h:$bearing:${s.intensity}:${s.depth}:${s.spread}"
            val cache=raster.get()!!
            val texture=cache[key] ?: run {
                val geometries=rasterGeometry.get()!!;val geometryKey="$w:$h"
                val geometry=geometries[geometryKey] ?: SoundGlowRaster(w,h).also {
                    if(geometries.size>=2)geometries.remove(geometries.keys.first());geometries[geometryKey]=it
                }
                Bitmap.createBitmap(geometry.render(bearing,s),geometry.width,geometry.height,Bitmap.Config.ARGB_8888)
                    .also {if(cache.size>=6)cache.remove(cache.keys.first());cache[key]=it}
            }
            canvas.drawBitmap(texture,null,RectF(0f,0f,w,h),paint)
        }
    }
    private fun drawLabel(canvas:Canvas,label:Label,material:Material?,settings:SoundGlowSettings,opacity:Float) {
        val rect=label.rect;val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val layer=canvas.saveLayerAlpha(RectF(rect.left-20,rect.top-20,rect.right+20,rect.bottom+23),(opacity*255).roundToInt())
        paint.color=Color.argb(1,0,0,0);paint.setShadowLayer(6f,0f,3f,Color.argb(31,0,0,0));canvas.drawRoundRect(rect,12f,12f,paint);paint.clearShadowLayer()
        canvas.withSave {
            clipPath(Path().apply {addRoundRect(rect,12f,12f,Path.Direction.CW)})
            if(material!=null) {paint.color=Color.WHITE;drawBitmap(material.bitmap,null,material.rect,paint)}
            paint.color=Color.argb(((100-settings.transparency)*2.55).roundToInt(),72,74,68);drawRect(rect,paint)
            paint.shader=LinearGradient(0f,rect.top,0f,rect.bottom,Color.argb(10,255,255,255),Color.argb(3,255,255,255),Shader.TileMode.CLAMP)
            paint.alpha=255;drawRect(rect,paint);paint.shader=null
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=Color.argb(20,255,255,255)
            drawRoundRect(RectF(rect.left+.5f,rect.top+1.5f,rect.right-.5f,rect.bottom+1.5f),11.5f,11.5f,paint)
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=Color.argb(56,255,255,255);canvas.drawRoundRect(RectF(rect.left+.5f,rect.top+.5f,rect.right-.5f,rect.bottom-.5f),11.5f,11.5f,paint)
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.textSize=15f;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        canvas.drawText(label.text,rect.left+13,rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
        canvas.restoreToCount(layer)
    }
    private fun requestMaterials(key:String,items:List<Label>,state:SoundHudState,scene:SoundScene,w:Float,h:Float) {
        val now=SystemClock.elapsedRealtime()
        if(released || now-requestedAt<120 || !busy.compareAndSet(false,true))return
        requestedAt=now
        materialWorker.execute {
            try {
                val start=SystemClock.elapsedRealtimeNanos()
                val result=items.associate {it.event.id to makeMaterial(it.rect,state,scene,w,h)}
                synchronized(lock) {if(!released) {if(materials.size>=4)materials.remove(materials.keys.first());materials[key]=MaterialSet(scene.image.generationId,SystemClock.elapsedRealtime(),result)}}
                materialCostMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6;materialError=null
            } catch(e:Exception) {materialError=e.message ?: "声音标签背景模糊失败"}
            finally {busy.set(false)}
        }
    }
    /** Local 3x downsampling keeps the 18 reference-pixel Gaussian bounded and off camera threads. */
    private fun makeMaterial(rect:RectF,state:SoundHudState,scene:SoundScene,w:Float,h:Float):Material {
        val padded=RectF(rect.left-54,rect.top-54,rect.right+54,rect.bottom+54)
        val width=ceil(padded.width()/3).toInt();val height=ceil(padded.height()/3).toInt()
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.BLACK);canvas.scale(1/3f,1/3f);canvas.translate(-padded.left,-padded.top);scene.draw(canvas,w,h)
        val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height)
        for(y in 0 until height)for(x in 0 until width) {
            val px=(padded.left+(x+.5)*3).coerceIn(0.0,w.toDouble());val py=(padded.top+(y+.5)*3).coerceIn(0.0,h.toDouble())
            var color=pixels[y*width+x]
            state.liveDirection?.let {live ->
                val glow=SoundGlowKernel.sample(px,py,w.toDouble(),h.toDouble(),live.bearing,state.settings)
                val alpha=glow[3]*live.opacity
                color=Color.rgb((glow[0]*alpha+Color.red(color)*(1-alpha)).roundToInt(),(glow[1]*alpha+Color.green(color)*(1-alpha)).roundToInt(),(255*alpha+Color.blue(color)*(1-alpha)).roundToInt())
            }
            for(event in state.events) {
                val bearing=event.bearing ?: continue
                if(state.liveDirection?.let {abs(DirectionMath.normalize(bearing-it.bearing+180)-180)<=30}==true)continue
                val glow=SoundGlowKernel.sample(px,py,w.toDouble(),h.toDouble(),bearing,state.settings)
                val alpha=glow[3]*SoundGlowKernel.opacity(state.now-event.born,state.settings.reducedMotion)
                color=Color.rgb((glow[0]*alpha+Color.red(color)*(1-alpha)).roundToInt(),(glow[1]*alpha+Color.green(color)*(1-alpha)).roundToInt(),(255*alpha+Color.blue(color)*(1-alpha)).roundToInt())
            }
            pixels[y*width+x]=color
        }
        val weights=DoubleArray(37) {exp(-.5*((it-18)/6.0).pow(2))};val sum=weights.sum();for(i in weights.indices)weights[i]/=sum
        val tmp=IntArray(pixels.size)
        fun pass(source:IntArray,target:IntArray,horizontal:Boolean) {
            for(y in 0 until height)for(x in 0 until width) {
                var r=0.0;var g=0.0;var b=0.0
                for(k in -18..18) {
                    val color=source[if(horizontal)y*width+(x+k).coerceIn(0,width-1) else (y+k).coerceIn(0,height-1)*width+x]
                    val q=weights[k+18];r+=Color.red(color)*q;g+=Color.green(color)*q;b+=Color.blue(color)*q
                }
                target[y*width+x]=Color.rgb(r.roundToInt(),g.roundToInt(),b.roundToInt())
            }
        }
        pass(pixels,tmp,true);pass(tmp,pixels,false);bitmap.setPixels(pixels,0,width,0,0,width,height)
        return Material(padded,bitmap)
    }
    fun release() {released=true;synchronized(lock) {materials.clear()};materialWorker.shutdown()}
    companion object {
        private const val SHADER="""
            uniform float2 size; uniform float bearing; uniform float intensity; uniform float depth; uniform float sigma;
            half4 main(float2 p) {
                float d=max(0.0,min(min(p.x,p.y),min(size.x-p.x,size.y-p.y)));
                float2 n=(p-size*0.5)/(size*0.5); float a=atan(n.x,-n.y)-bearing;
                float delta=atan(sin(a),cos(a)); float sector=exp(-0.5*delta*delta/(sigma*sigma));
                float alpha=min(1.0,(0.65*exp(-d/2.2)+0.35*exp(-d/18.0)+0.35*exp(-0.5*d*d/(depth*depth)))*sector*intensity);
                float near=exp(-d/13.0);float3 rgb=float3(floor(48.0*near+0.5),floor(114.0+86.0*near+0.5),255.0)/255.0;
                return half4(rgb*alpha,alpha);
            }
        """
    }
}
