package com.hearlens.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean

/** A genuinely resizable GPU buffer, independent of both the camera and Wi-Fi buffers. */
@android.annotation.SuppressLint("ViewConstructor")
class LocalStereoView(context: Context, private val engine: LocalStereoEngine): SurfaceView(context), SurfaceHolder.Callback {
    private val thread=HandlerThread("HearLens-local-display").apply { start() }
    private val worker=Handler(thread.looper)
    @Volatile private var alive=false
    @Volatile private var dirty=true
    @Volatile private var revision=0
    private var lastRevision=-1
    private var lastFrame=-1L
    private var lastNull=false
    private val pacer=DisplayPacer()
    private var frameRateHint=0
    private var nextDrawNs=0L
    private val queued=AtomicBoolean(false)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawLoop=object:Runnable {
        override fun run() {
            if(!alive) { queued.set(false);return }
            val start=SystemClock.elapsedRealtimeNanos()
            if(start+500_000<nextDrawNs) { worker.postDelayed(this,((nextDrawNs-start)/1_000_000).coerceAtLeast(1));return }
            queued.set(false)
            val cap=engine.displayCap()
            if(lastRevision!=revision) { pacer.reset(cap);lastRevision=revision;dirty=true }
            val frame=engine.current()
            val changed=dirty || engine.hasPerceptionNotice() || (frame!=null && frame.id!=lastFrame) || (frame==null && !lastNull)
            if(changed && holder.surface.isValid) {
                var canvas:Canvas?=null
                try {
                    canvas=holder.lockHardwareCanvas()
                    if(canvas!=null) {
                        if(frame==null) {
                            canvas.drawColor(Color.BLACK)
                            paint.color=Color.WHITE;paint.textSize=canvas.width/55f;paint.textAlign=Paint.Align.CENTER
                            for(x in listOf(.25f,.75f)) canvas.drawText("等待摄像头画面",canvas.width*x,canvas.height*.5f,paint)
                            engine.drawMenu(canvas,canvas.width,canvas.height)
                        } else engine.drawStereo(canvas,canvas.width,canvas.height,frame)
                        val cw=canvas.width;val ch=canvas.height
                        holder.unlockCanvasAndPost(canvas);canvas=null
                        val cost=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                        pacer.sample(cost,cap)
                        engine.reportSurface(cw,ch,pacer.target,pacer.costMs,null)
                        if(frame!=null) engine.reportDisplayed(frame,true)
                        lastFrame=frame?.id ?: -1;lastNull=frame==null;dirty=false
                        if(Build.VERSION.SDK_INT>=30 && frameRateHint!=pacer.target) {
                            holder.surface.setFrameRate(pacer.target.toFloat(),Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                            frameRateHint=pacer.target
                        }
                    }
                } catch(e:Exception) { engine.reportSurface(0,0,pacer.target,pacer.costMs,e.javaClass.simpleName) }
                finally { if(canvas!=null) runCatching { holder.unlockCanvasAndPost(canvas) } }
            }
            nextDrawNs=start+1_000_000_000L/pacer.target.coerceAtLeast(1)
            // Wake on a new camera frame instead of polling out of phase with the sensor.
            // At most one callback is queued, even when the chosen display FPS is lower.
            if(engine.current()?.let { it.id!=lastFrame }==true || engine.hasPerceptionNotice())requestRender()
        }
    }
    init { holder.addCallback(this);setZOrderMediaOverlay(true);isClickable=true }
    fun configureSurface() {
        val size=engine.phoneRenderSize()
        holder.setFixedSize(size.first,size.second)
        revision++;dirty=true
    }
    override fun surfaceCreated(holder:SurfaceHolder) { alive=true;frameRateHint=0;nextDrawNs=0;queued.set(false);dirty=true;worker.removeCallbacks(drawLoop);requestRender() }
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) { revision++;dirty=true }
    override fun surfaceDestroyed(holder:SurfaceHolder) { alive=false;worker.removeCallbacks(drawLoop);queued.set(false) }
    fun requestRender() { dirty=true;if(alive && queued.compareAndSet(false,true))worker.post(drawLoop) }
    fun refreshFreshness() { if(engine.current()==null)requestRender() }
    override fun performClick():Boolean { super.performClick();return true }
    fun clear(message:String="") { engine.clear();dirty=true }
    fun release() { alive=false;worker.removeCallbacksAndMessages(null);thread.quitSafely();engine.release() }
}
