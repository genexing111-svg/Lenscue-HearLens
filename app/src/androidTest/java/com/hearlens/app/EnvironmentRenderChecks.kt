package com.hearlens.app

import android.content.Context
import android.graphics.*
import android.media.ImageReader
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test APK only. Uses supplied public speech and design backgrounds; never opens a microphone or cloud API. */
internal object EnvironmentRenderChecks {
    fun run(app:Context,test:Context):String {
        val root=File(app.cacheDir,"environment-render-check").apply {mkdirs()}
        val results=JSONObject()
        EnvironmentSoundModel(app).use {model ->
            val silence=model.classify(FloatArray(15600));check(silence.first().index==494)
            val wave=test.assets.open("environment/public-reference-speech.wav").use {it.readBytes()}
            val buffer=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
            var position=12;var data=-1
            while(position+8<wave.size) {
                val name=String(wave,position,4,Charsets.US_ASCII);val size=buffer.getInt(position+4)
                if(name=="fmt ") {check(buffer.getShort(position+8).toInt()==1);check(buffer.getShort(position+10).toInt()==1);check(buffer.getInt(position+12)==16000)}
                if(name=="data") {data=position+8;break};position+=8+size+(size%2)
            }
            check(data>=0)
            val samples=FloatArray(15600) {buffer.getShort(data+(8000+it)*2)/32768f}
            val speech=model.classify(samples);check(speech.first().index==0 && speech.first().score>.2)
            val times=mutableListOf<Double>()
            repeat(20) {val before=SystemClock.elapsedRealtimeNanos();model.classify(samples);times+=(SystemClock.elapsedRealtimeNanos()-before)/1e6}
            results.put("silenceTop",silence.first().json()).put("speechTop",speech.first().json()).put("inferenceMs",JSONArray(times))
                .put("averageInferenceMs",times.average()).put("modelSha256",EnvironmentSoundModel.MODEL_SHA256)
        }
        File(root,"model-results.json").writeText(results.toString(2))
        val renderer=SoundGlowRenderer(SoundLabels(app))
        fun save(bitmap:Bitmap,name:String) {File(root,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        val timings=mutableListOf<Double>()
        try {
            for(sceneName in listOf("dark","bright","white")) {
                val source=if(sceneName=="white")Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
                    else test.assets.open("glow/${if(sceneName=="dark")"dark-cafe.jpg" else "bright-room.jpg"}").use {stream ->
                        val original=BitmapFactory.decodeStream(stream)
                        Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).also {out->
                            val c=Canvas(out);val scale=maxOf(736f/original.width,430f/original.height)
                            c.translate((736-original.width*scale)/2,(430-original.height*scale)/2);c.scale(scale,scale)
                            c.drawBitmap(original,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG));original.recycle()
                        }
                    }
                val scene=SoundScene(source)
                for(bearing in listOf(90.0,135.0)) {
                    val state=SoundHudState(true,events=listOf(SoundHudEvent(1,349,false,bearing,0,0,"泼水声")),now=1200)
                    val box=StereoGeometry.Box(0f,0f,736f,430f)
                    val bmp=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888);val canvas=Canvas(bmp)
                    scene.draw(canvas,736f,430f);renderer.draw(canvas,box,state,scene)
                    val deadline=SystemClock.elapsedRealtime()+5000
                    while(!renderer.materialReady(state,736f,430f,source.generationId) && SystemClock.elapsedRealtime()<deadline) {renderer.draw(canvas,box,state,scene);Thread.sleep(20)}
                    check(renderer.materialReady(state,736f,430f,source.generationId)) {"Backdrop cache did not complete: ${renderer.materialError}"}
                    scene.draw(canvas,736f,430f)
                    val before=SystemClock.elapsedRealtimeNanos();renderer.draw(canvas,box,state,scene);timings+=(SystemClock.elapsedRealtimeNanos()-before)/1e6
                    save(bmp,"$sceneName-${bearing.toInt()}-software.png")
                    if(sceneName=="white")check(Color.blue(bmp.getPixel(730,420))>=Color.red(bmp.getPixel(730,420)))
                    val hardware=hardwareBitmap(736,430) {draw->scene.draw(draw,736f,430f);renderer.draw(draw,box,state,scene)}
                    save(hardware,"$sceneName-${bearing.toInt()}-hardware.png");hardware.recycle()
                    val expired=state.copy(now=2800)
                    scene.draw(canvas,736f,430f);renderer.draw(canvas,box,expired,scene)
                    if(sceneName=="white")check(bmp.getPixel(730,420)==Color.WHITE) {"Expired glow remains"}
                    bmp.recycle()
                }
                // Shared production compositor transforms the same HUD into each eye and the wireless view.
                if(sceneName=="dark") {
                    val stereo=Bitmap.createBitmap(1472,430,Bitmap.Config.ARGB_8888);val c=Canvas(stereo)
                    val state=SoundHudState(true,events=listOf(SoundHudEvent(2,349,false,135.0,0,0,"泼水声"),SoundHudEvent(3,353,false,270.0,0,0,"敲击声")),now=1200)
                    for(x in listOf(0f,736f)) {c.save();c.translate(x,0f);scene.draw(c,736f,430f);renderer.draw(c,StereoGeometry.Box(0f,0f,736f,430f),state,scene);c.restore()}
                    Thread.sleep(180)
                    for(x in listOf(0f,736f)) {c.save();c.translate(x,0f);scene.draw(c,736f,430f);renderer.draw(c,StereoGeometry.Box(0f,0f,736f,430f),state,scene);c.restore()}
                    save(stereo,"binocular-two-sounds.png");stereo.recycle()
                }
                // Published bitmaps may still be referenced by the background material worker.
            }
            results.put("softwareDrawMs",JSONArray(timings)).put("lastMaterialMs",renderer.materialCostMs).put("materialError",renderer.materialError ?: JSONObject.NULL)
            val controls=HeadsetMenuState(listOf("字幕语言" to "转译为简体中文","使用镜头" to "后置主摄","双眼观看" to "开启"),0,"已应用",controls=listOf(SettingControl(3,6),SettingControl(0,3),SettingControl(toggle=true)))
            for(landscape in listOf(false,true)) {
                val image=Bitmap.createBitmap(if(landscape)1600 else 900,if(landscape)900 else 1400,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(image);canvas.drawColor(Color.rgb(32,39,43))
                val panels=if(landscape)listOf(HeadsetMenuGeometry.Panel(70f,100f,650f,680f),HeadsetMenuGeometry.Panel(880f,100f,650f,680f)) else listOf(HeadsetMenuGeometry.Panel(60f,100f,780f,1200f))
                HeadsetMenuPainter.draw(canvas,panels,controls)
                save(image,"settings-${if(landscape)"landscape" else "portrait"}.png");image.recycle()
            }
        } finally {renderer.release()}
        results.put("productionUsbOnly",usbOnlyProductionChecks(app,root))
        File(root,"results.json").writeText(results.toString(2))
        return "PASS: native YAMNet, GPU/software glow, production USB-only stereo/mono/wireless without classifier, stale/disconnect clearing, shallow perception entry"
    }
    /** Regression for the exact previously missed branch: the production engine, no recognition session. */
    private fun usbOnlyProductionChecks(app:Context,root:File):JSONObject {
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("usb_glow_check_$name",mode)
        }
        val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
        val direction=UsbDirectionController(isolated)
        var micStarts=0
        val audio=SharedPhoneAudio {object:SpeechMicrophone {
            override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {micStarts++;error("Unexpected microphone start in render check")}
            override fun stop() {}
        }}
        val environment=EnvironmentSoundSession(isolated,audio,direction) {}
        val captions=LiveCaptionSession(isolated,engine,audio::microphone)
        val source=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        try {
            engine.configure(StereoSettings(scale=100,spacing=0))
            val generation=camera.beginCameraSession("USB-only regression test frame")
            fun board(stale:Boolean=false,connected:Boolean=true,visible:Boolean=true):DirectionSnapshot {
                val now=SystemClock.elapsedRealtime()
                return DirectionSnapshot(true,connected,config=DirectionConfig(frontDegrees=95.0,clockwise=false,showOverlay=visible),
                    reading=DirectionReading(176.0,now-if(stale)4000 else 100),now=now)
            }
            engine.directionProvider={board()}
            fun frame():LocalStereoEngine.DisplayFrame {
                engine.render(source,generation,0,false)
                return checkNotNull(engine.current())
            }
            fun assertLeftGlow(bmp:Bitmap) {
                val ew=bmp.width/2
                for(offset in listOf(0,ew)) {
                    val left=bmp.getPixel(offset+2,bmp.height/2);val right=bmp.getPixel(offset+ew-3,bmp.height/2)
                    check(Color.blue(left)-Color.red(left)>100) {"No left-edge glow with classifier off"}
                    check(Color.red(right)>245) {"Wrong edge lit"}
                    check(Color.red(bmp.getPixel(offset+ew/2,bmp.height/10))>240) {"Legacy top direction strip remains"}
                }
            }
            // No EnvironmentSoundSession attached at all: display must still work.
            val current=frame();val software=Bitmap.createBitmap(1472,430,Bitmap.Config.ARGB_8888)
            engine.drawStereo(Canvas(software),1472,430,current);assertLeftGlow(software)
            File(root,"usb-only-stereo-software.png").outputStream().use {software.compress(Bitmap.CompressFormat.PNG,100,it)}
            val hardware=hardwareBitmap(1472,430) {engine.drawStereo(it,1472,430,current)};assertLeftGlow(hardware)
            File(root,"usb-only-stereo-hardware.png").outputStream().use {hardware.compress(Bitmap.CompressFormat.PNG,100,it)}
            // Actual production session object present, but never started.
            engine.environment=environment
            val wire=Bitmap.createBitmap(960,281,Bitmap.Config.ARGB_8888);engine.drawStereo(Canvas(wire),960,281,frame());assertLeftGlow(wire)
            val mono=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888);mono.eraseColor(Color.WHITE)
            frame();engine.drawMonoCaptions(Canvas(mono),736,430)
            check(Color.blue(mono.getPixel(2,215))-Color.red(mono.getPixel(2,215))>100)
            File(root,"usb-only-mono.png").outputStream().use {mono.compress(Bitmap.CompressFormat.PNG,100,it)}
            check(!environment.enabled && micStarts==0)
            val hud=engine.status().getJSONObject("soundHud")
            check(hud.getBoolean("directionGlowVisible") && !hud.getBoolean("classificationActive") && hud.getInt("labelCount")==0)
            var requested=false;var toggles=0;var details=""
            val menu=HeadsetMenu(engine,camera,captions,{},{false},{},{false},{},{requested},{requested=!requested;toggles++})
            menu.onAuxiliary={details=it}
            check(menu.state().controls[2].toggle==false && menu.state().controls[2].hasDetails)
            menu.open();menu.selectRow(2);menu.command("right");check(requested && toggles==1)
            menu.command("right");check(toggles==1) // Repeated right is idempotent, not a second permission trigger.
            menu.command("left");check(!requested && toggles==2)
            menu.activate(2);check(requested && toggles==3)
            menu.details(2);check(details=="perception" && requested)
            val home=menu.state();check(home.rows[2].first=="环境音感知" && home.controls[2].toggle==true)
            check(home.rows.count {it.first=="环境音感知"}==1)
            menu.showAdvanced();check(menu.state().rows.none {it.first=="USB 声源方向与左右校准" || it.first=="本地环境音与方向光晕"});menu.close()
            val menuImage=Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888);val c=Canvas(menuImage);c.drawColor(Color.rgb(32,39,43))
            HeadsetMenuPainter.draw(c,listOf(HeadsetMenuGeometry.Panel(70f,100f,650f,680f),HeadsetMenuGeometry.Panel(880f,100f,650f,680f)),home)
            File(root,"perception-shallow-menu.png").outputStream().use {menuImage.compress(Bitmap.CompressFormat.PNG,100,it)}
            val quick=Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888);val q=Canvas(quick);q.drawColor(Color.rgb(32,39,43))
            QuickOverlayPainter.draw(q,listOf(HeadsetMenuGeometry.Panel(70f,100f,650f,680f),HeadsetMenuGeometry.Panel(880f,100f,650f,680f)),
                QuickOverlay(3,CaptionChoices.labels))
            File(root,"x-language-restored.png").outputStream().use {quick.compress(Bitmap.CompressFormat.PNG,100,it)}
            for(invalid in listOf(board(stale=true),board(connected=false),board(visible=false))) {
                engine.directionProvider={invalid};engine.drawStereo(Canvas(software),1472,430,frame())
                check(software.getPixel(2,215)==Color.WHITE) {"Disconnected/stale/disabled glow was not cleared"}
            }
            return JSONObject().put("classifierStarted",false).put("microphoneStarts",micStarts).put("bearingDeg",279)
                .put("stereoGpu",true).put("stereoSoftware",true).put("mono",true).put("wirelessSize",true).put("staleAndDisconnectClear",true)
                .put("homeRows",JSONArray(home.rows.map {it.first})).put("oldDeepEntriesRemoved",true)
                .put("perceptionToggle",true).put("toggleDirectionsIdempotent",true).put("detailsDoesNotToggle",true)
        } finally {captions.release();environment.release();audio.release();direction.release();engine.release();camera.stop()}
    }
    internal fun hardwareBitmap(w:Int,h:Int,draw:(Canvas)->Unit):Bitmap {
        val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        val renderer=HardwareRenderer();val node=RenderNode("environment-test");node.setPosition(0,0,w,h)
        try {
            val canvas=node.beginRecording(w,h);draw(canvas);node.endRecording()
            renderer.setSurface(reader.surface);renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image=reader.acquireLatestImage()
            repeat(20) {if(image==null) {Thread.sleep(20);image=reader.acquireLatestImage()}}
            val acquired=checkNotNull(image) {"GPU output unavailable"}
            acquired.use {
                val plane=it.planes[0];val wide=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,h,Bitmap.Config.ARGB_8888)
                wide.copyPixelsFromBuffer(plane.buffer)
                val result=Bitmap.createBitmap(wide,0,0,w,h);if(result!==wide)wide.recycle();return result
            }
        } finally {renderer.destroy();reader.close()}
    }
}
