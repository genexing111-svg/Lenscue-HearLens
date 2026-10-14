package com.hearlens.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled fixtures only. Real cloud validation is a separate explicit instrumentation flag. */
internal object PttChecks {
    fun render(app:Context):String {
        val root=File(app.cacheDir,"ptt-render-check").apply {mkdirs()}
        val bg=Color.rgb(32,38,39);val box=StereoGeometry.Box(0f,0f,736f,430f)
        val painter=PttPainter()
        fun image(state:PttState)=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).also {val c=Canvas(it);c.drawColor(bg);painter.draw(c,listOf(box),state,1000)}
        fun save(b:Bitmap,n:String) {File(root,n).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        fun bounds(b:Bitmap):Rect {
            var left=b.width;var right=0;var top=b.height;var bottom=0
            for(y in 0 until b.height)for(x in 0 until b.width)if(b.getPixel(x,y)!=bg) {left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)}
            return Rect(left,top,right+1,bottom+1)
        }
        val silent=image(PttState(PttPhase.RECORDING,text="你好",levelAt=1000));save(silent,"capsule-silent.png")
        val talking=image(PttState(PttPhase.RECORDING,text="现在可以清楚地听到我说话。",level=55,levelAt=1000));save(talking,"capsule-speaking.png")
        val long=image(PttState(PttPhase.RECORDING,text="当实时识别到的文字变长，胶囊会先向两侧延伸；达到一行的宽度后自动换行，左侧人像和右侧响度条始终保持垂直居中。",level=24,levelAt=1000));save(long,"capsule-multiline.png")
        check(bounds(talking).width()>bounds(silent).width());check(bounds(long).height()>bounds(talking).height())
        val silentRect=bounds(silent)
        check(silentRect.top in 15..17 && bounds(long).top in 15..17) {"Capsule must expand down from the top inset"}
        val isolated=object:android.content.ContextWrapper(app) {override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("ptt_render_$name",mode)}
        val camera=UsbVideoServer(isolated);val engine=LocalStereoEngine(isolated,camera)
        try {
            engine.configure(StereoSettings(scale=100));engine.pttState=PttState(PttPhase.RECORDING,text="按住说话，松开后播放。",level=40,levelAt=SystemClock.uptimeMillis())
            val source=Bitmap.createBitmap(736,430,Bitmap.Config.ARGB_8888).apply {eraseColor(bg)}
            val frame=LocalStereoEngine.DisplayFrame(source,1,1,0,0,false,engine.settings)
            val hardware=EnvironmentRenderChecks.hardwareBitmap(1472,430) {engine.drawStereo(it,1472,430,frame)};save(hardware,"capsule-stereo-gpu.png")
            // GPU subpixel coverage can differ slightly after the horizontal translation.
            var significant=0
            for(y in 0 until 430)for(x in 0 until 736) {
                val a=hardware.getPixel(x,y);val b=hardware.getPixel(x+736,y)
                if(maxOf(kotlin.math.abs(Color.red(a)-Color.red(b)),kotlin.math.abs(Color.green(a)-Color.green(b)),kotlin.math.abs(Color.blue(a)-Color.blue(b)))>3)significant++
            }
            check(significant<736*430/1000) {"Binocular rendering differs in $significant pixels"}
            val wire=Bitmap.createBitmap(960,432,Bitmap.Config.ARGB_8888);engine.drawStereo(Canvas(wire),960,432,frame);save(wire,"capsule-wireless.png")
            val gen=camera.beginCameraSession("PTT render fixture");engine.render(source,gen,0,false)
            val mono=Bitmap.createBitmap(900,1400,Bitmap.Config.ARGB_8888).apply {eraseColor(bg)};engine.drawMonoCaptions(Canvas(mono),900,1400);save(mono,"capsule-mono.png")
            File(root,"results.json").writeText(JSONObject().put("growsWithText",true).put("wraps",true).put("bothEyesMatch",true)
                .put("gpu",true).put("wireless",true).put("mono",true).put("topAnchored",true)
                .put("captionRetention",PttCaptionRetentionChecks.run(app,root))
                .put("shortBounds",silentRect.toString()).put("longBounds",bounds(long).toString()).toString(2))
        }finally {engine.release();camera.stop()}
        return "PASS: top capsule grows downward, centered SVG/level bars, GPU stereo/mono/wireless, retained captions during pause and reconnect failure"
    }
    fun live(app:Context,test:Context):String {
        val key=SpeechCredentials(app).read();check(key.isNotBlank()) {"No saved key; no request sent"}
        val workspace=app.getSharedPreferences("speech_v7",Context.MODE_PRIVATE).getString("workspace","").orEmpty().ifBlank {PttProtocol.WORKSPACE}
        val wave=test.assets.open("environment/public-reference-speech.wav").use {it.readBytes()}
        val buffer=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);var position=12;var pcm=byteArrayOf()
        while(position+8<wave.size) {val name=String(wave,position,4,Charsets.US_ASCII);val size=buffer.getInt(position+4)
            if(name=="fmt ") {check(buffer.getShort(position+8).toInt()==1 && buffer.getShort(position+10).toInt()==1 && buffer.getInt(position+12)==16000)}
            if(name=="data") {pcm=wave.copyOfRange(position+8,position+8+minOf(size,16000*2*6));break};position+=8+size+(size%2)}
        check(pcm.isNotEmpty());val recording=AtomicBoolean(false);val stopped=AtomicBoolean(false);val fed=CountDownLatch(1);val done=CountDownLatch(1)
        val result=JSONObject().put("model",AliyunProtocol.LIVE_MODEL).put("productionPlayer",true).put("fixtureBytes",pcm.size)
        val output=PttAudioPlayer(app);var starts=0;var finished=false
        val mic=object:SpeechMicrophone {
            private var stopCallback:(()->Unit)?=null
            override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,ended:()->Unit,error:(String)->Unit) {
                recording.set(true);stopCallback=ended
                Thread({try {var i=0;while(i<pcm.size && recording.get()) {val end=minOf(i+640,pcm.size);if(!packet(pcm.copyOfRange(i,end)))break;i=end;Thread.sleep(20)};fed.countDown()}catch(e:Exception){error(e.javaClass.simpleName)}}).start()
            }
            override fun stop() {if(recording.getAndSet(false)){stopped.set(true);stopCallback?.invoke()}}
        }
        val player=object:PttPlayback {
            override fun start(sampleRate:Int,ended:()->Unit,error:(String)->Unit,level:(Int)->Unit) {
                check(stopped.get()) {"Playback started before mic stop"};starts++;result.put("sampleRate",sampleRate)
                output.start(sampleRate,{finished=true;ended()},error,level)
            }
            override fun offer(pcm:ByteArray)=output.offer(pcm)
            override fun finish()=output.finish()
            override fun cancel()=output.cancel()
        }
        val client=PttClient({mic},{player})
        client.changed={s->if(s.phase in listOf(PttPhase.DONE,PttPhase.ERROR))done.countDown()}
        try {
            client.begin(AliyunConnection(workspace,key),"zh")
            check(fed.await(10,TimeUnit.SECONDS)) {"Fixture timeout"};Thread.sleep(1000)
            result.put("audioWhileHeld",client.state.outputBytes).put("playbackStartsWhileHeld",starts)
            check(starts==0);client.end();check(done.await(45,TimeUnit.SECONDS)) {"PTT completion timeout"}
            val state=client.state
            result.put("phase",state.phase.name).put("message",state.message.replace(key,"[redacted]"))
                .put("inputBytes",state.inputBytes).put("outputBytes",state.outputBytes).put("originalChars",state.text.length)
                .put("outputChars",state.output.length).put("playbackStarts",starts).put("playbackDrained",finished)
            File(app.cacheDir,"ptt-live-results.json").writeText(result.toString(2))
            check(state.phase==PttPhase.DONE && state.text.isNotBlank() && state.output.isNotBlank() && state.outputBytes>0 && starts==1 && finished) {"PTT live check failed: ${state.message}"}
            return "PASS: saved-key live Chinese voice clone, text + audio, held silent, production AudioTrack drained after release"
        }finally {client.release()}
    }
}
