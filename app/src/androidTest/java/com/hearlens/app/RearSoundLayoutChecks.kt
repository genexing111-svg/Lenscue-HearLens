package com.hearlens.app

import android.content.Context
import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Controlled layout regression: no microphone, camera capture, board or cloud request. */
internal object RearSoundLayoutChecks {
    fun run(app:Context,test:Context):String {
        val root=File(app.cacheDir,"rear-layout-check").apply {mkdirs()}
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("rear_layout_check_$name",mode)
        }
        val camera=UsbVideoServer(isolated)
        val engine=LocalStereoEngine(isolated,camera)
        val renderer=SoundGlowRenderer(SoundLabels(app))
        val white=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val background=test.assets.open("glow/dark-cafe.jpg").use {BitmapFactory.decodeStream(it)}
        val results=JSONObject();val cases=JSONArray()
        fun save(bitmap:Bitmap,name:String) {File(root,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        fun state(vararg angles:Double)=SoundHudState(true,events=angles.mapIndexed {i,a->SoundHudEvent(i.toLong(),349,false,a,0,0,listOf("泼水声","敲击声","脚步声")[i])},now=1000)
        fun render(width:Int,height:Int,hud:SoundHudState,source:Bitmap=white):Bitmap {
            val frame=LocalStereoEngine.DisplayFrame(source,1,1,0,0,false,engine.settings)
            return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {engine.drawStereo(Canvas(it),width,height,frame,hud)}
        }
        // Detect the actual caption panel in the rendered pixels, away from label and glow pixels.
        fun panelRange(bitmap:Bitmap,x:Int):IntRange {
            val ys=(0 until bitmap.height).filter {y->val c=bitmap.getPixel(x,y);Color.red(c) in 5..190 && abs(Color.red(c)-Color.blue(c))<30}
            check(ys.isNotEmpty()) {"Caption panel missing"};return ys.first()..ys.last()
        }
        try {
            engine.configure(StereoSettings(scale=100,spacing=0))
            engine.captionTurns=listOf(CaptionTurn("a","1","这是一行实时字幕"))
            val none=SoundHudState()
            val baseline=render(1472,430,none)
            val rear=state(180.0)
            val bottom=render(1472,430,rear)
            save(baseline,"before-rear-caption.png");save(bottom,"rear-caption-raised.png")
            val oldRange=panelRange(baseline,180);val moved=panelRange(bottom,180)
            check(moved.last<oldRange.last-20) {"Rear cue did not move captions up"}
            check(moved==panelRange(bottom,736+180)) {"Left and right captions diverged"}
            val label=renderer.labelBounds(StereoGeometry.Box(0f,0f,736f,430f),rear).values.single()
            check(moved.last<label.top-8) {"Caption overlaps bottom label"}
            val expired=render(1472,430,rear.copy(now=2800))
            check(expired.sameAs(baseline)) {"Expired sound did not restore original caption position"}
            val side=render(1472,430,state(90.0))
            check(panelRange(side,180)==oldRange) {"Side sound moved caption unnecessarily"}
            val unknown=rear.copy(events=rear.events.map {it.copy(bearing=null)})
            check(renderer.captionBottomLimit(StereoGeometry.Box(0f,0f,736f,430f),unknown)==null)
            check(renderer.captionBottomLimit(StereoGeometry.Box(0f,0f,736f,430f),rear.copy(enabled=false))==null)
            for(angles in listOf(listOf(135.0),listOf(180.0),listOf(225.0),listOf(179.0,180.0,181.0))) {
                val hud=state(*angles.toDoubleArray())
                val rects=renderer.labelBounds(StereoGeometry.Box(0f,0f,736f,430f),hud,RectF(180f,280f,556f,410f)).values.toList()
                rects.forEach {check(abs(it.bottom-406f)<.1 && it.left>=21.9f && it.right<=714.1f) {"Rear label left the edge: $it"}}
                for(i in rects.indices)for(j in 0 until i)check(!RectF.intersects(rects[i],rects[j])) {"Rear labels overlap"}
                cases.put(JSONObject().put("bearings",JSONArray(angles)).put("edgeAndSeparation",true))
            }
            // Four speakers/rows, maximum font size, smallest bottom offset and asymmetric eye calibration.
            engine.captionTurns=(1..4).map {CaptionTurn("$it","$it","第${it}行字幕保留完整")}
            engine.captionStyle=CaptionStyle(sizePercent=160,bottomPercent=3)
            engine.configure(engine.settings.copy(scale=90,leftSize=-8,rightSize=4,leftY=2,rightY=-1))
            val four=render(2844,1280,state(180.0));check(engine.captionVisibleLines==4);save(four,"rear-four-lines-calibrated.png")
            val wireless=render(960,432,state(180.0));check(engine.captionVisibleLines==4);save(wireless,"rear-wireless.png")
            engine.configure(StereoSettings(scale=100,caption="电脑发来的字幕也会避让后方提示",textY=90,fontSize=32))
            engine.captionTurns=emptyList()
            val manual=render(1472,430,state(180.0));save(manual,"rear-manual-text.png")
            check(panelRange(manual,25).last<368) {"Manual subtitle overlaps rear row"}
            // Portrait mono overlay, using a real production frame and the same HUD layout.
            engine.captionTurns=listOf(CaptionTurn("a","1","竖屏预览中的字幕同样避让"));engine.captionStyle=CaptionStyle()
            val generation=camera.beginCameraSession("Rear caption layout test")
            engine.render(white,generation,0,false)
            val mono=Bitmap.createBitmap(900,1400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            engine.drawMonoCaptions(Canvas(mono),900,1400,rear);save(mono,"rear-mono-portrait.png")
            val monoBox=StereoGeometry.fit(736,430,900f,1400f,100,0,0,0,false)
            check(panelRange(mono,225).last<renderer.captionBottomLimit(monoBox,rear)!!+2)
            // A side-by-side visual over the supplied reference scene for human inspection.
            engine.configure(StereoSettings(scale=95));engine.captionStyle=CaptionStyle()
            engine.captionTurns=listOf(CaptionTurn("a","1","后方传来泼水声，字幕自动上移。"),CaptionTurn("b","2","声音种类保持在视野底部边缘。"))
            val preview=render(2400,1080,state(180.0),background);save(preview,"rear-scene-stereo.png")
            results.put("edgeCases",cases).put("captionBefore",oldRange.toString()).put("captionAfter",moved.toString())
                .put("sameLiftBothEyes",true).put("restoresAfterExpiry",true).put("sideUnchanged",true)
                .put("fourLinesAndCalibration",true).put("wireless",true).put("manualSubtitle",true).put("monoPortrait",true)
                .put("unknownDoesNotReserveRear",true).put("microphoneOrCloudUsed",false)
            File(root,"results.json").writeText(results.toString(2))
            return "PASS: rear edge labels, three-label separation, caption lift/restoration, stereo, wireless, manual text, four-line captions"
        } finally {engine.release();camera.stop();renderer.release()}
    }
}
