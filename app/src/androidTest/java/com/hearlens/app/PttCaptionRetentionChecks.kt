package com.hearlens.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/** Seed known recognition results in an isolated session; no microphone, credentials or network request. */
internal object PttCaptionRetentionChecks {
    fun run(app:Context,root:File):JSONObject {
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("retention_check_$name",mode)
        }
        check(SpeechCredentials(isolated).read().isEmpty()) {"Retention fixture must not have credentials"}
        val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
        var micStarts=0
        val captions=LiveCaptionSession(isolated,engine,{micStarts++;error("Unexpected microphone start")})
        // Fixture seeding is deliberately confined to the test APK, with no production debug action.
        val enabled=LiveCaptionSession::class.java.getDeclaredField("enabled").apply {isAccessible=true}
        val transport=LiveCaptionSession::class.java.getDeclaredField("client").apply {isAccessible=true}.get(captions) as SpeechClient
        fun original(text:String)=JSONObject().put("type","result").put("sentence",JSONObject().put("sentence_id",0).put("text",text))
        val bg=Color.rgb(32,38,39)
        val source=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(bg)}
        engine.configure(StereoSettings(scale=100))
        val frame=LocalStereoEngine.DisplayFrame(source,1,1,0,0,false,engine.settings)
        fun render(w:Int=1472,h:Int=430)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {engine.drawStereo(Canvas(it),w,h,frame)}
        fun save(bmp:Bitmap,name:String) {File(root,name).outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}
        try {
            enabled.setBoolean(captions,true)
            transport.onResult?.invoke(original("这段字幕在按住肩键之前已经识别。\n语音重构期间保留在这里。"))
            val before=engine.captionTurns.toList();check(before.isNotEmpty())
            val baseline=render();save(baseline,"retained-before.png")
            captions.suspendForPtt()
            check(engine.captionTurns==before && captions.snapshot.phase==SpeechPhase.CANCELLED && !captions.snapshot.microphone)
            transport.onResult?.invoke(original("暂停后迟到的事件不能改掉已经显示的内容"))
            check(engine.captionTurns==before)
            engine.directionProvider={val now=SystemClock.elapsedRealtime();DirectionSnapshot(true,true,reading=DirectionReading(90.0,now),now=now)}
            engine.pttState=PttState(PttPhase.RECORDING,text="现在轮到我说话，原来的字幕仍在下方。",level=45,levelAt=SystemClock.uptimeMillis())
            val during=render();save(during,"top-capsule-retained-subtitles.png")
            // The lower half is pixel-identical: no erased subtitles, new masks or environment glow.
            check(Bitmap.createBitmap(baseline,0,215,1472,215).sameAs(Bitmap.createBitmap(during,0,215,1472,215)))
            check(during.getPixel(735,215)==bg) {"Environment glow should be hidden while PTT is active"}
            engine.pttState=engine.pttState!!.copy(phase=PttPhase.PLAYING,output="播放阶段也保留原来的字幕。")
            check(engine.captionTurns==before);save(render(960,432),"top-capsule-retained-wireless.png")
            val gen=camera.beginCameraSession("Retained-caption fixture");engine.render(source,gen,0,false)
            val mono=Bitmap.createBitmap(900,1400,Bitmap.Config.ARGB_8888).apply {eraseColor(bg)}
            engine.drawMonoCaptions(Canvas(mono),900,1400);save(mono,"top-capsule-retained-mono.png")
            captions.resumeAfterPtt() // Missing isolated key exercises failed resume, never touches the live API.
            check(engine.captionTurns==before && !captions.diagnostic().getBoolean("suspendedForPtt"))
            captions.suspendForPtt();captions.clear();check(engine.captionTurns.isEmpty())
            enabled.setBoolean(captions,true);captions.resumeAfterPtt();transport.onResult?.invoke(original("清除测试"))
            captions.stop();check(engine.captionTurns.isEmpty())
            check(micStarts==0)
            return JSONObject().put("preservedWhileHeld",true).put("preservedDuringPlayback",true)
                .put("lateResultIgnored",true).put("failedReconnectPreserves",true).put("explicitClearStillWorks",true)
                .put("environmentHidden",true).put("lowerHalfUnchanged",true).put("microphoneStarts",micStarts).put("networkUsed",false)
        }finally {captions.release();engine.release();camera.stop()}
    }
}
