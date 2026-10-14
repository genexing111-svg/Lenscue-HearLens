package com.hearlens.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Created after an explicit consumer start and runtime permission. Never saves audio. */
internal class PhoneMicrophone(private val context:Context,private val rawPreferred:Boolean=false):SpeechMicrophone {
    private val started=AtomicBoolean(false)
    private val stopping=AtomicBoolean(false)
    private val lock=Any()
    private var recorder:AudioRecord?=null
    override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
        check(started.compareAndSet(false,true))
        Thread({
            var mic:AudioRecord?=null
            val effects=mutableListOf<android.media.audiofx.AudioEffect>()
            try {
                check(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
                if(stopping.get())return@Thread
                val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum>0) { "手机未提供 16 kHz 单声道录音格式" }
                val manager=context.getSystemService(AudioManager::class.java)
                val unprocessed=rawPreferred && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)=="true"
                val source=if(unprocessed)MediaRecorder.AudioSource.UNPROCESSED else if(rawPreferred)MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
                val record=AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum*2,6400)).build()
                mic=record
                check(record.state==AudioRecord.STATE_INITIALIZED && record.sampleRate==16000 && record.channelCount==1) { "无法初始化 16 kHz 手机麦克风" }
                if(rawPreferred) {
                    runCatching {android.media.audiofx.AutomaticGainControl.create(record.audioSessionId)}.getOrNull()?.let {effects+=it;runCatching {it.enabled=false}}
                    runCatching {android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)}.getOrNull()?.let {effects+=it;runCatching {it.enabled=false}}
                    runCatching {android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)}.getOrNull()?.let {effects+=it;runCatching {it.enabled=false}}
                }
                val builtIn=manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if(builtIn!=null)check(record.setPreferredDevice(builtIn)) { "无法选择手机内置麦克风，请断开其他音频输入设备后重试" }
                synchronized(lock) {
                    if(stopping.get())return@Thread
                    recorder=record;record.startRecording()
                }
                check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "麦克风被占用或系统麦克风开关已关闭" }
                val pcm=PcmPackets(packet);val samples=ShortArray(320)
                while(!stopping.get()) {
                    val count=record.read(samples,0,samples.size,AudioRecord.READ_BLOCKING)
                    if(count<0) { if(stopping.get())break else error("麦克风读取失败（$count）").also { return@Thread } }
                    if(count==0)continue
                    val route=record.routedDevice
                    if(builtIn!=null && route!=null && route.type!=AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        error("录音路由已切换到外部设备，已停止；请使用手机内置麦克风");return@Thread
                    }
                    val peak=(0 until count).maxOf { abs(samples[it].toInt()) }*100/32768
                    level(peak,"16000 Hz · 单声道 · PCM16LE · ${if(route?.type==AudioDeviceInfo.TYPE_BUILTIN_MIC)"手机内置麦克风" else "等待路由确认"} · ${if(unprocessed)"UNPROCESSED" else if(rawPreferred)"VOICE_RECOGNITION" else "MIC"} · 音效仍启用 ${effects.count {runCatching {it.enabled}.getOrDefault(false)}}")
                    if(!pcm.append(samples,count))return@Thread
                }
                pcm.finish()
            } catch(e:Exception) {
                if(!stopping.get())error(e.message ?: "麦克风初始化失败")
            } finally {
                synchronized(lock) { recorder=null;runCatching { mic?.stop() };effects.forEach {runCatching {it.release()}};mic?.release() }
                stopped()
            }
        },"HearLens-speech-microphone").start()
    }
    override fun stop() {
        stopping.set(true)
        synchronized(lock) { runCatching { recorder?.stop() } }
    }
}
