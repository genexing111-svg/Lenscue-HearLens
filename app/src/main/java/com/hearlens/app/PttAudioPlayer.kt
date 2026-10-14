package com.hearlens.app

import android.content.Context
import android.media.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One streaming AudioTrack, opened only after the PTT capture has physically stopped. */
internal class PttAudioPlayer(context:Context):PttPlayback {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val queue=LinkedBlockingQueue<ByteArray>(512)
    @Volatile private var cancelled=false
    @Volatile private var finished=false
    @Volatile private var track:AudioTrack?=null
    private var thread:Thread?=null
    override fun start(sampleRate:Int,done:()->Unit,error:(String)->Unit,level:(Int)->Unit) {
        check(thread==null)
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener {change->if(change<=AudioManager.AUDIOFOCUS_LOSS) {cancel();error("音频播放被系统中断")}}.build()
        thread=Thread({
            var audio:AudioTrack?=null
            try {
                check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"暂时无法取得音频播放焦点"}
                val minimum=AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum>0)
                audio=AudioTrack.Builder().setAudioAttributes(attributes).setTransferMode(AudioTrack.MODE_STREAM)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum,sampleRate/5*2)).build()
                check(audio.state==AudioTrack.STATE_INITIALIZED);track=audio
                if(cancelled)return@Thread
                audio.play();var written=0L
                while(!cancelled) {
                    val bytes=queue.poll(100,TimeUnit.MILLISECONDS)
                    if(bytes==null) {if(finished)break;level(0);continue}
                    var offset=0
                    // Short writes keep meter updates and cancellation responsive.
                    while(offset<bytes.size && !cancelled) {
                        val size=minOf(bytes.size-offset,sampleRate/50*2)
                        val count=audio.write(bytes,offset,size,AudioTrack.WRITE_BLOCKING)
                        check(count>0) {"AudioTrack 写入失败（$count）"}
                        var peak=0
                        for(i in offset until offset+count-1 step 2)peak=maxOf(peak,abs(((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()))
                        level(peak*100/32768);offset+=count;written+=count/2
                    }
                }
                val deadline=System.nanoTime()+3_000_000_000L
                while(!cancelled && (audio.playbackHeadPosition.toLong() and 0xffffffffL)<written) {
                    check(System.nanoTime()<deadline) {"播放未能正常结束"};Thread.sleep(10)
                }
                if(!cancelled) {level(0);done()}
            } catch(e:Exception) {if(!cancelled)error(e.message ?: "播放失败")}
            finally {track=null;runCatching {audio?.stop()};audio?.release();manager.abandonAudioFocusRequest(focus);queue.clear()}
        },"HearLens-PTT-playback").apply {start()}
    }
    override fun offer(pcm:ByteArray)=!cancelled && !finished && queue.offer(pcm)
    override fun finish() {finished=true}
    override fun cancel() {cancelled=true;queue.clear();runCatching {track?.pause();track?.flush()};thread?.interrupt()}
}
