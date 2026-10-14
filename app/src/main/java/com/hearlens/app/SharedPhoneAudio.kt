package com.hearlens.app

import android.content.Context

/** One AudioRecord for local classification and an explicitly started speech session. No audio retained. */
internal class SharedPhoneAudio(private val factory:()->SpeechMicrophone) {
    constructor(context:Context):this({PhoneMicrophone(context,rawPreferred=true)})
    private data class Listener(val packet:(ByteArray)->Boolean,val level:(Int,String)->Unit,val stopped:()->Unit,val error:(String)->Unit)
    private val lock=Any()
    private val listeners=linkedMapOf<Long,Listener>()
    private var sequence=0L;private var generation=0L
    private var source:SpeechMicrophone?=null
    private var closing=false
    private var released=false
    private val idleWaiters=mutableListOf<()->Unit>()
    /** PTT needs a real microphone-stopped callback before playback, including its final PCM tail. */
    fun exclusiveMicrophone():SpeechMicrophone=object:SpeechMicrophone {
        private val own=Any();private var mic:SpeechMicrophone?=null;private var cancelled=false;private var ended=false
        private var onStopped:(()->Unit)?=null
        private fun finish()=synchronized(own) {if(!ended) {ended=true;onStopped?.invoke()}}
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
            synchronized(own) {onStopped=stopped}
            val startWhenIdle:()->Unit={synchronized(own) {
                if(cancelled || released)finish() else {
                    val next=factory();mic=next;next.start(packet,level,::finish,error)
                }
            }}
            synchronized(lock) {if(source==null)startWhenIdle() else idleWaiters+=startWhenIdle}
        }
        override fun stop()=synchronized(own) {cancelled=true;if(mic==null)finish() else mic?.stop().let {Unit}}
    }
    fun microphone():SpeechMicrophone=object:SpeechMicrophone {
        private var id:Long?=null
        private var stoppedBeforeStart=false
        override fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit) {
            synchronized(lock) {
                check(id==null)
                if(stoppedBeforeStart || released) {stopped();return}
                id=++sequence;listeners[id!!]=Listener(packet,level,stopped,error)
                ensureStarted()
            }
        }
        override fun stop() {
            synchronized(lock) {
                stoppedBeforeStart=true
                val old=id?.let {listeners.remove(it)};id=null;old?.stopped?.invoke()
                stopIfUnused()
            }
        }
    }
    private fun ensureStarted() {
        if(source!=null || closing || released || listeners.isEmpty())return
        val ticket=++generation
        val mic=factory();source=mic
        mic.start({packet->
            val copy=synchronized(lock) {if(ticket!=generation)emptyMap() else listeners.toMap()}
            for((id,l) in copy) {
                val accepted=runCatching {l.packet(packet)}.getOrDefault(false)
                if(!accepted)synchronized(lock) {listeners.remove(id)?.stopped?.invoke();stopIfUnused()}
            }
            synchronized(lock) {ticket==generation && listeners.isNotEmpty() && !closing}
        },{peak,route->
            val copy=synchronized(lock) {if(ticket!=generation)emptyList() else listeners.values.toList()}
            copy.forEach {runCatching {it.level(peak,route)}}
        },{
            synchronized(lock) {
                if(ticket==generation) {
                    source=null
                    if(!closing) {val copy=listeners.values.toList();listeners.clear();copy.forEach {it.stopped()}}
                    closing=false
                    val waiters=idleWaiters.toList();idleWaiters.clear();waiters.forEach {it()}
                    ensureStarted()
                }
            }
        },{message->
            synchronized(lock) {
                if(ticket==generation) {val copy=listeners.values.toList();listeners.clear();copy.forEach {it.error(message);it.stopped()}}
            }
        })
    }
    private fun stopIfUnused() {if(listeners.isEmpty() && source!=null && !closing) {closing=true;source?.stop()}}
    fun release()=synchronized(lock) {
        released=true;val copy=listeners.values.toList();listeners.clear();copy.forEach {it.stopped()};stopIfUnused()
    }
}
