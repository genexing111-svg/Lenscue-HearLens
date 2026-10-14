package com.hearlens.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File

/** Test APK only: fixture rendering and direct application-method key routing; no injected system input. */
internal object EnvironmentNoticeChecks {
    fun run(instrumentation:Instrumentation):String {
        val app=instrumentation.targetContext
        val root=File(app.cacheDir,"environment-notice-check").apply {mkdirs()}
        val isolated=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("notice_check_$name",mode)
        }
        val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
        val boxes=listOf(StereoGeometry.Box(0f,0f,736f,430f),StereoGeometry.Box(736f,0f,736f,430f))
        fun save(bmp:Bitmap,name:String) {File(root,name).outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}
        fun render(notice:EnvironmentNotice,now:Long):Bitmap=Bitmap.createBitmap(1472,430,Bitmap.Config.ARGB_8888).apply {
            val c=Canvas(this);c.drawColor(Color.rgb(38,44,45));EnvironmentNoticePainter.draw(c,boxes,notice,now)
        }
        val result=JSONObject()
        try {
            val on=EnvironmentNotice.transition(null,true,0)
            val off=EnvironmentNotice.transition(on,false,800)
            for((name,notice,time) in listOf(Triple("entering",on,120L),Triple("turning-on",on,320L),Triple("on",on,600L),
                Triple("turning-off",off,1120L),Triple("off",off,1400L),Triple("hidden",off,3100L))) {
                val bmp=render(notice,time);save(bmp,"notice-$name.png")
                val left=Bitmap.createBitmap(bmp,0,0,736,430);val right=Bitmap.createBitmap(bmp,736,0,736,430)
                check(left.sameAs(right)) {"Notification differs between eyes"}
                if(name=="on")check(bmp.getPixel(520,37)==SettingsPalette.active) {"ON track is not settings blue"}
                if(name=="hidden")check(bmp.getPixel(520,37)==Color.rgb(38,44,45)) {"Notification did not dismiss"}
            }
            val source=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(38,44,45))}
            engine.configure(StereoSettings(scale=100))
            engine.perceptionNotice=on.copy(born=SystemClock.uptimeMillis()-600)
            val frame=LocalStereoEngine.DisplayFrame(source,1,1,0,0,false,engine.settings)
            val hardware=EnvironmentRenderChecks.hardwareBitmap(1472,430) {engine.drawStereo(it,1472,430,frame)}
            check(hardware.getPixel(520,37)==SettingsPalette.active);save(hardware,"notice-production-gpu.png")
            val wire=Bitmap.createBitmap(960,432,Bitmap.Config.ARGB_8888)
            engine.drawStereo(Canvas(wire),960,432,frame);save(wire,"notice-wireless.png")
            val generation=camera.beginCameraSession("Notice render fixture");engine.render(source,generation,0,false)
            val mono=Bitmap.createBitmap(900,1400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(38,44,45))}
            engine.drawMonoCaptions(Canvas(mono),900,1400);save(mono,"notice-mono.png")
            // Paused/no-camera rendering must also include the card, and can be cleared independently.
            engine.clear();check(engine.current()==null)
            engine.drawMenu(Canvas(wire),960,432);save(wire,"notice-no-camera.png")
            engine.clearPerceptionNotice();check(!engine.hasPerceptionNotice())
            val controller=InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice).firstOrNull {it.name.contains("SC-B03",true)}
            if(controller!=null) {
                var error:Throwable?=null
                instrumentation.runOnMainSync {
                    try {
                        val activity=object:Activity() {init {attachBaseContext(app)}}
                        var vr=false;var wanted=false;var toggles=0;var languages=0;val actions=mutableListOf<String>()
                        var holds=0;var releases=0
                        val remote=RemoteInput(activity,engine,{vr},{actions+=it},{},shoulder={holds++},language={languages++},
                            perception={wanted=!wanted;toggles++},shortcutActive={true},shoulderReleased={releases++})
                        try {
                            fun key(code:Int,action:Int,repeat:Int=0)=remote.key(KeyEvent(10,20,action,code,repeat,0,controller.id,0,0,InputDevice.SOURCE_GAMEPAD))
                            check(key(108,KeyEvent.ACTION_DOWN));check(wanted && toggles==1)
                            key(108,KeyEvent.ACTION_DOWN,1);key(108,KeyEvent.ACTION_DOWN,0);key(108,KeyEvent.ACTION_UP)
                            check(toggles==1) {"Holding Y toggled more than once"}
                            key(108,KeyEvent.ACTION_DOWN);key(108,KeyEvent.ACTION_UP);check(!wanted && toggles==2)
                            check(!key(24,KeyEvent.ACTION_DOWN)) // Existing X keeps its original VR-only scope.
                            vr=true
                            key(100,KeyEvent.ACTION_DOWN);key(100,KeyEvent.ACTION_UP);check(wanted && toggles==3) // Standard Android Y.
                            key(24,KeyEvent.ACTION_DOWN);key(24,KeyEvent.ACTION_UP);check(languages==1)
                            key(25,KeyEvent.ACTION_DOWN);key(25,KeyEvent.ACTION_UP);check(actions==listOf("confirm"))
                            key(96,KeyEvent.ACTION_DOWN);key(96,KeyEvent.ACTION_DOWN,2);key(101,KeyEvent.ACTION_DOWN)
                            check(holds==1 && releases==0);key(96,KeyEvent.ACTION_UP);check(releases==0)
                            key(101,KeyEvent.ACTION_UP);check(releases==1)
                            check(engine.perceptionNotice==null) // Test callbacks never started the actual microphone.
                            result.put("keyRouting","PASS: saved SC-B03 Y=108, standard Y=100, shoulder 96/101 hold/release, repeats, X/A unchanged")
                        } finally {remote.release()}
                    } catch(e:Throwable) {error=e}
                }
                error?.let {throw it}
            } else result.put("keyRouting","SKIPPED: SC-B03 not connected; pure mapping tests still run")
            result.put("notificationName","环境音感知").put("sameBothEyes",true).put("settingsBlue",true)
                .put("gpuCompositor",true).put("wireless",true).put("mono",true).put("withoutCamera",true)
                .put("microphoneOrCloudStarted",false).put("physicalKeyPressInjected",false)
            File(root,"results.json").writeText(result.toString(2))
            return "PASS: notification on/off animation frames, native GPU, stereo/mono/wireless/no-camera, ${result.getString("keyRouting")}"
        } finally {engine.release();camera.stop()}
    }
}
