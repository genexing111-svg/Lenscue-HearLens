package com.hearlens.app

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class EnvironmentSoundSession(context:Context,private val audio:SharedPhoneAudio,private val direction:UsbDirectionController,
    private val changed:()->Unit) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("environment_sound_v12",Context.MODE_PRIVATE)
    val labels=SoundLabels(app)
    @Volatile var settings=runCatching {SoundGlowSettings(prefs.getInt("intensity",85),prefs.getInt("depth",80),prefs.getInt("spread",60),
        prefs.getInt("transparency",80),prefs.getString("language","zh-Hans")!!,prefs.getBoolean("associate",true),prefs.getBoolean("reduced",false))}.getOrDefault(SoundGlowSettings());private set
    @Volatile var enabled=false;private set
    @Volatile var status="环境音识别未开启";private set
    @Volatile private var generation=0L
    @Volatile private var released=false
    @Volatile private var microphone:SpeechMicrophone?=null
    @Volatile private var route="尚未采集"
    @Volatile private var last:SoundDecision?=null
    @Volatile private var costMs=0.0
    @Volatile private var processed=0L
    @Volatile private var dropped=0L
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"HearLens-environment-inference").apply {isDaemon=true}}
    private var model:EnvironmentSoundModel?=null
    private var filter=EnvironmentEventFilter()
    private val timeline=SoundEventTimeline()
    private val lifeLock=Any()
    private var lastDirectionGeneration=Long.MIN_VALUE
    private data class Job(val generation:Long,val audio:AudioWindow,val endedAt:Long,val directions:List<DirectionReading>,val directionGeneration:Long)
    private val pending=AtomicReference<Job?>()
    private val running=AtomicBoolean(false)
    fun configure(value:SoundGlowSettings) {
        val old=settings;settings=value
        prefs.edit {putInt("intensity",value.intensity);putInt("depth",value.depth);putInt("spread",value.spread);putInt("transparency",value.transparency)
            putString("language",value.language);putBoolean("associate",value.associateDirection);putBoolean("reduced",value.reducedMotion)}
        if(old.associateDirection!=value.associateDirection)timeline.clear()
        changed()
    }
    fun state():SoundHudState = synchronized(lifeLock) {
        val token=direction.calibrationGeneration()
        if(lastDirectionGeneration!=token) {timeline.clear();lastDirectionGeneration=token}
        val now=SystemClock.elapsedRealtime();SoundHudState(enabled,settings,timeline.snapshot(now,settings.reducedMotion),now)
    }
    fun text(event:SoundHudEvent)=event.textOverride ?: (if(event.possible)when(settings.language){"en"->"Possibly ";"zh-Hant"->"可能是";else->"可能是"} else "")+labels.name(event.index,settings.language)
    fun start() {
        val ticket=synchronized(lifeLock) {
            if(enabled || released)return
            ++generation;enabled=true;status="正在加载离线环境音模型";last=null;processed=0;dropped=0;timeline.clear();pending.set(null)
            lastDirectionGeneration=direction.calibrationGeneration();generation
        }
        executor.execute {
            try {
                if(model==null)model=EnvironmentSoundModel(app)
                if(!enabled || ticket!=generation)return@execute
                filter=EnvironmentEventFilter()
                val framer=EnvironmentAudioFramer(16000) {window ->
                    if(ticket==generation && enabled) {
                        val end=SystemClock.elapsedRealtime()
                        val ds=direction.captureDirectionWindow(end-975,end)
                        val job=Job(ticket,window,end,ds.second,ds.first)
                        if(pending.getAndSet(job)!=null)dropped++
                        schedule()
                    }
                }
                val mic=audio.microphone()
                synchronized(lifeLock) {
                    if(!enabled || ticket!=generation)return@execute
                    microphone=mic;status="本地识别中 · 等待声音"
                }
                mic.start({bytes ->
                    if(ticket!=generation || !enabled)false else {
                        framer.feed(FloatArray(bytes.size/2) {i->((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()/32768f});true
                    }
                },{_,value->if(ticket==generation)route=value},{
                    if(ticket==generation && enabled)fail("麦克风采集已停止",ticket)
                },{message->fail(message,ticket)})
            } catch(e:Throwable) {fail("环境音启动失败：${e.message ?: e.javaClass.simpleName}",ticket)}
            changed()
        }
    }
    private fun schedule() {
        if(running.compareAndSet(false,true))executor.execute {
            try {
                while(true) {
                    val job=pending.getAndSet(null) ?: break
                    if(!enabled || job.generation!=generation)continue
                    try {
                        val start=SystemClock.elapsedRealtimeNanos()
                        val categories=model!!.classify(job.audio.samples)
                        val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                        if(!enabled || job.generation!=generation)continue
                        val decision=filter.evaluate(categories,job.audio.rms,job.audio.endMs)
                        synchronized(lifeLock) {
                            if(enabled && job.generation==generation) {
                                last=decision;costMs=elapsed;processed++
                                status=when(decision.kind) {"quiet"->"本地识别中 · 安静";"confirming"->"本地识别中 · 正在确认";"uncertain"->"本地识别中 · 暂不确定";else->"本地识别中"}
                                val now=SystemClock.elapsedRealtime()
                                decision.event?.let {
                                    if(now-job.endedAt<=1800) {
                                        val bearing=if(settings.associateDirection && job.directionGeneration==direction.calibrationGeneration())SoundDirectionAssociation.estimate(job.directions,job.endedAt-975,job.endedAt) else null
                                        timeline.accept(it.index,decision.kind=="possible",bearing,now,job.endedAt,settings.reducedMotion)
                                    }
                                }
                            }
                        }
                        changed()
                    } catch(e:Throwable) {fail("环境音推理失败：${e.message ?: e.javaClass.simpleName}",job.generation)}
                }
            } finally {running.set(false);if(pending.get()!=null && !released)schedule()}
        }
    }
    private fun fail(message:String,ticket:Long) {if(ticket==generation) {stop();status=message;changed()}}
    fun stop() {
        val old=synchronized(lifeLock) {enabled=false;generation++;pending.set(null);val previous=microphone;microphone=null;timeline.clear();status="环境音识别已停止";previous}
        old?.stop();changed()
    }
    fun release() {released=true;stop();executor.execute {model?.close();model=null};executor.shutdown()}
    fun diagnostic():JSONObject=JSONObject().put("enabled",enabled).put("message",status).put("model","YAMNet handoff 2026-10-03")
        .put("modelSha256",EnvironmentSoundModel.MODEL_SHA256).put("runtime","LiteRT 1.4.2 CPU / 2 threads").put("audioRoute",route)
        .put("processedWindows",processed).put("replacedPendingWindows",dropped).put("inferenceMs",costMs).put("decision",last?.json() ?: JSONObject.NULL)
        .put("directionAssociation","temporal-estimate-only").put("events",JSONArray(state().events.map {JSONObject().put("classIndex",it.index).put("possible",it.possible)
            .put("name",text(it)).put("bearingDeg",it.bearing ?: JSONObject.NULL).put("sourceEndMs",it.sourceEnd).put("bornMs",it.born)}))
}
