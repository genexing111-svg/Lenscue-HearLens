package com.hearlens.app

/** Headerless mono PCM16LE. Full frames contain exactly 320 samples / 20 ms at 16 kHz. */
internal class PcmPackets(private val send:(ByteArray)->Boolean) {
    private val pending=ByteArray(640)
    private var used=0
    fun append(samples:ShortArray,count:Int):Boolean {
        require(count in 0..samples.size)
        for(i in 0 until count) {
            val v=samples[i].toInt()
            pending[used++]=(v and 255).toByte();pending[used++]=(v shr 8).toByte()
            if(used==640) { used=0;if(!send(pending.copyOf()))return false }
        }
        return true
    }
    fun finish():Boolean {
        if(used==0)return true
        val tail=pending.copyOf(used);used=0
        return send(tail)
    }
}

internal interface SpeechMicrophone {
    fun start(packet:(ByteArray)->Boolean,level:(Int,String)->Unit,stopped:()->Unit,error:(String)->Unit)
    fun stop()
}
