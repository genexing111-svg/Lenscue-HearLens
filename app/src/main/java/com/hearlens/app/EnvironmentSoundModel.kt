package com.hearlens.app

import android.content.Context
import org.json.JSONArray
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal class SoundLabels(context:Context) {
    private val labels=JSONArray(context.assets.open("environment/labels.json").bufferedReader().use {it.readText()})
    init {require(labels.length()==521);for(i in 0..520)require(labels.getJSONObject(i).getInt("index")==i)}
    fun name(index:Int,language:String):String=labels.getJSONObject(index).optString(language,labels.getJSONObject(index).getString("en"))
}

/** One persistent CPU interpreter, called only on the environment inference thread. */
internal class EnvironmentSoundModel(context:Context):AutoCloseable {
    private val labels=SoundLabels(context)
    private val interpreter:Interpreter
    private val input=ByteBuffer.allocateDirect(15600*4).order(ByteOrder.nativeOrder())
    private val output=arrayOf(FloatArray(521))
    init {
        val data=context.assets.open("environment/yamnet.tflite").use {it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it.toInt() and 255)}
        require(hash==MODEL_SHA256) {"环境音模型校验失败"}
        val model=ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {put(data);rewind()}
        interpreter=Interpreter(model,Interpreter.Options().setNumThreads(2))
        require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(15600)) && interpreter.getInputTensor(0).dataType()==DataType.FLOAT32)
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1,521)) && interpreter.getOutputTensor(0).dataType()==DataType.FLOAT32)
    }
    fun classify(samples:FloatArray):List<SoundCategory> {
        require(samples.size==15600)
        input.rewind();input.asFloatBuffer().put(samples);interpreter.run(input,output)
        return output[0].mapIndexed {i,v->SoundCategory(i,labels.name(i,"en"),v.toDouble())}.sortedByDescending {it.score}.take(8)
    }
    override fun close()=interpreter.close()
    companion object {const val MODEL_SHA256="4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"}
}
