package com.hearlens.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Changing directions defeat the old exact-angle bitmap cache. Exercises the actual async encoder.
 * Test APK only: public still image, synthetic angles, isolated preferences; no microphone or cloud. */
internal object GlowStreamChecks {
    fun run(app:Context,test:Context):String {
        val root=File(app.cacheDir,"glow-stream-check").apply {mkdirs()}
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("glow_stream_check_$name",mode)
        }
        val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
        val source=test.assets.open("glow/bright-room.jpg").use {BitmapFactory.decodeStream(it)}
        val results=JSONObject();val profiles=JSONArray()
        try {
            engine.dimensions(3200,1440,120)
            val generation=camera.beginCameraSession("Test image with continuously changing direction")
            engine.directionProvider={
                val now=SystemClock.elapsedRealtime()
                DirectionSnapshot(true,true,config=DirectionConfig(frontDegrees=0.0,clockwise=true),
                    reading=DirectionReading((now*.173)%360,now),now=now)
            }
            for(width in listOf(1920,2560)) {
                engine.configure(StereoSettings(outputWidth=width,wirelessFps=30,wirelessQuality=90,wirelessAdaptive=false,scale=70,spacing=-100))
                var after=-1L;var last=0L;var received=0
                val gaps=mutableListOf<Long>();val ages=mutableListOf<Long>();val costs=mutableListOf<Double>()
                val start=SystemClock.elapsedRealtime();val end=start+6500
                while(SystemClock.elapsedRealtime()<end) {
                    engine.networkFrame(after)
                    engine.render(source,generation,0,false)
                    engine.networkFrame(after)?.let {wire ->
                        val now=SystemClock.elapsedRealtime()
                        check(wire.jpeg.size>1000 && wire.jpeg[0]==(-1).toByte() && wire.jpeg[1]==0xd8.toByte())
                        if(last!=0L)gaps+=now-last
                        last=now;after=wire.id;received++;ages+=now-wire.time
                        costs+=engine.status().getDouble("wirelessEncodeMs")
                    }
                    Thread.sleep(16)
                }
                val report=JSONObject().put("width",width).put("quality",90).put("changingAngleEveryFrame",true)
                    .put("receivedFrames",received).put("seconds",(SystemClock.elapsedRealtime()-start)/1000.0)
                    .put("maxGapMs",gaps.maxOrNull()).put("maxAgeMs",ages.maxOrNull())
                    .put("averageEncodeMs",costs.average()).put("maxEncodeMs",costs.maxOrNull())
                    .put("encoderError",engine.status().opt("encoderError"))
                profiles.put(report);results.put("asyncEncoder",profiles)
                File(root,"results.json").writeText(results.toString(2))
                check(received>=65) {"Changing-angle encoder fell below 10 fps: $report"}
                check(gaps.maxOrNull()!!<800 && ages.maxOrNull()!!<800) {"Changing glow starved fresh video: $report"}
                check(engine.status().isNull("encoderError"))
            }

            // Four simultaneous changing glows plus labels and retained subtitles, at the maximum wire size.
            val out=Bitmap.createBitmap(2560,1152,Bitmap.Config.ARGB_8888);val canvas=Canvas(out)
            val bytes=ByteArrayOutputStream();val costs=mutableListOf<Double>()
            engine.captionTurns=listOf(CaptionTurn("held","1","已经识别的字幕会继续保留"))
            repeat(45) {i ->
                val now=SystemClock.elapsedRealtime();val angle=(i*17.31)%360
                val hud=SoundHudState(true,SoundGlowSettings(100,96,95),
                    listOf(60.0,150.0,240.0).mapIndexed {j,offset->SoundHudEvent(j.toLong(),349,false,(angle+offset)%360,now-300,now,"测试声音")},now,
                    LiveDirectionGlow(angle,1.0))
                val frame=LocalStereoEngine.DisplayFrame(source,i.toLong(),generation,now,0,false,engine.settings)
                val before=SystemClock.elapsedRealtimeNanos()
                engine.drawStereo(canvas,out.width,out.height,frame,hud)
                bytes.reset();check(out.compress(Bitmap.CompressFormat.JPEG,90,bytes))
                costs+=(SystemClock.elapsedRealtimeNanos()-before)/1e6
                if(i==20)File(root,"four-glows-subtitles.jpg").writeBytes(bytes.toByteArray())
                check(engine.captionVisibleLines>0)
            }
            results.put("multipleGlows",JSONObject().put("frames",costs.size).put("averageEncodeMs",costs.average()).put("maxEncodeMs",costs.maxOrNull()))
            File(root,"results.json").writeText(results.toString(2))
            check(costs.maxOrNull()!!<800 && costs.average()<100) {"Multiple sound glows exceed the wireless frame budget"}
            return "PASS: fresh 1920/2560 JPEG stream with changing angles, four simultaneous glows, labels and retained subtitles"
        } finally {engine.release();camera.stop()}
    }
}
