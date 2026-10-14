package com.hearlens.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import java.io.File

/** On-device visual contract checks using production renderer. This class is in the test APK only. */
class CaptionRenderInstrumentation:Instrumentation() {
    private var environmentCheck=false
    private var liveDiagnostic=false
    private var liveProduction=false
    private var rearLayout=false
    private var environmentNotice=false
    private var pttRender=false;private var pttLive=false
    private var glowStream=false
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);environmentCheck=arguments?.getString("environment")=="1";liveDiagnostic=arguments?.getString("liveDiagnostic")=="1";liveProduction=arguments?.getString("liveProduction")=="1";rearLayout=arguments?.getString("rearLayout")=="1";environmentNotice=arguments?.getString("environmentNotice")=="1";pttRender=arguments?.getString("pttRender")=="1";pttLive=arguments?.getString("pttLive")=="1";glowStream=arguments?.getString("glowStream")=="1";start() }
    override fun onStart() {
        val output=Bundle()
        if(glowStream) {
            try {output.putString("result",GlowStreamChecks.run(targetContext,context));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(pttRender || pttLive) {
            try {output.putString("result",if(pttLive)PttChecks.live(targetContext,context) else PttChecks.render(targetContext));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(environmentNotice) {
            try {output.putString("result",EnvironmentNoticeChecks.run(this));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(rearLayout) {
            try {output.putString("result",RearSoundLayoutChecks.run(targetContext,context));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(liveProduction) {
            try {output.putString("result",AliyunLiveDiagnostic.runProduction(targetContext,context));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.javaClass.simpleName+": "+e.message);finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(liveDiagnostic) {
            try {output.putString("result",AliyunLiveDiagnostic.run(targetContext,context));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.javaClass.simpleName+": "+e.message);finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if(environmentCheck) {
            try {output.putString("result",EnvironmentRenderChecks.run(targetContext,context));finish(Activity.RESULT_OK,output)}
            catch(e:Throwable) {output.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,output)}
            return
        }
        try {
            val root=File(targetContext.cacheDir,"caption-render-check").apply { mkdirs() }
            val checks=mutableListOf<String>()
            val examples=listOf(CaptionTurn("a","1","我们先把字幕放低一点，让文字靠近视野下方，同时保留周围的画面。"),
                CaptionTurn("b","2","可以，换人之后立即另起一行。"),CaptionTurn("c","3","最多保留四行字幕。"))
            fun save(bitmap:Bitmap,name:String) { File(root,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            for(scene in listOf("bright","dark","white")) {
                val source=context.assets.open("caption-$scene.${if(scene=="white")"png" else "jpg"}").use { BitmapFactory.decodeStream(it) }
                val outputBitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);val c=Canvas(outputBitmap)
                val imageBox=StereoGeometry.Box(0f,0f,1280f,720f)
                val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                c.drawBitmap(source,null,RectF(0f,0f,1280f,720f),paint)
                val count=CaptionPainter().draw(c,imageBox,examples,CaptionStyle())
                check(count==4) { "Expected exactly four display rows, got $count" };checks.add("$scene: four-line caption rendered")
                save(outputBitmap,"$scene-four-lines.png")
                if(scene=="bright") {
                    val isolated=object:android.content.ContextWrapper(targetContext) {
                        override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("render_check_$name",mode)
                    }
                    val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
                    try {
                        engine.configure(StereoSettings(scale=90,leftSize=-2,rightSize=2,leftY=1,rightY=-1))
                        engine.captionTurns=examples
                        val frame=LocalStereoEngine.DisplayFrame(source,1,1,0,0,false,engine.settings)
                        val stereo=Bitmap.createBitmap(3200,1440,Bitmap.Config.ARGB_8888)
                        engine.drawStereo(Canvas(stereo),3200,1440,frame)
                        check(engine.captionVisibleLines==4);save(stereo,"stereo-four-lines.png")
                        val wire=Bitmap.createBitmap(960,432,Bitmap.Config.ARGB_8888)
                        engine.drawStereo(Canvas(wire),960,432,frame);check(engine.captionVisibleLines==4);save(wire,"wireless-four-lines.png")
                    } finally { engine.release();camera.stop() }
                }
                // One and two line snapshots exercise B growth without any empty reserved panel.
                if(scene=="white") {
                    val steps=listOf("", "你", "你好，我们正在测试手机上的实时字幕，换行时底衬会增长。")
                    for((i,text) in steps.withIndex()) {
                        val bmp=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);val canvas=Canvas(bmp);canvas.drawColor(Color.WHITE)
                        val count=CaptionPainter().draw(canvas,imageBox,listOf(CaptionTurn("step","1",text)),CaptionStyle())
                        if(i==0) { check(count==0);check(bmp.getPixel(640,620)==Color.WHITE) }
                        if(i==1)check(count==1)
                        if(i==2)check(count in 2..4)
                        save(bmp,"growth-$i.png")
                    }
                }
                source.recycle()
            }
            File(root,"results.txt").writeText(checks.joinToString("\n")+"\nStereo and wireless: four rows\nBlank/one/multiple-line growth: passed\n")
            output.putString("result","PASS: native rendering, stereo, wireless, four-line cap, blank and growth states")
            finish(Activity.RESULT_OK,output)
        } catch(e:Throwable) { output.putString("error",e.toString());finish(Activity.RESULT_CANCELED,output) }
    }
}

