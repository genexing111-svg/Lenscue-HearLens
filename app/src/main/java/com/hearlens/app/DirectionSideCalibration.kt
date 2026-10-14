package com.hearlens.app

import kotlin.math.*

internal enum class CalibrationSide(val label:String) { LEFT("左侧"), RIGHT("右侧") }
internal sealed interface SideCalibrationResult {
    data class Success(val clockwise:Boolean,val meanRawDegrees:Double,val sampleCount:Int):SideCalibrationResult
    data class Rejected(val reason:String):SideCalibrationResult
}

/** Calibrates handedness only. Never moves the saved front or warps reported angles. */
internal object DirectionSideCalibration {
    const val DURATION_MS=4000L
    fun evaluate(front:Double,side:CalibrationSide,samples:List<DirectionReading>):SideCalibrationResult {
        fun reject(text:String)=SideCalibrationResult.Rejected(text)
        if(!front.isFinite() || front !in 0.0..<360.0)return reject("请先校准正前方")
        val values=samples.distinctBy {it.receivedAt}
        if(values.size<3 || values.maxOf {it.receivedAt}-values.minOf {it.receivedAt}<1000)
            return reject("新鲜角度不足，请在${side.label}持续发声后重试")
        if(values.any {!it.degrees.isFinite() || it.degrees !in 0.0..<360.0})return reject("收到无效角度，请重试")
        val x=values.sumOf {cos(Math.toRadians(it.degrees))}/values.size
        val y=values.sumOf {sin(Math.toRadians(it.degrees))}/values.size
        if(hypot(x,y)<.9)return reject("方向跳动较大，请固定声源并减少其他声音后重试")
        val mean=DirectionMath.normalize(Math.toDegrees(atan2(y,x)))
        val maxDeviation=values.maxOf {abs(DirectionMath.normalize(it.degrees-mean+180)-180)}
        if(maxDeviation>25)return reject("方向跳动较大，本次未保存，请重试")
        val delta=DirectionMath.relative(mean,front,true)
        // Near front/back the sign is ambiguous; require a lateral source at least 45° away.
        val increasingRight=when(delta) {
            in 45.0..135.0->true
            in 225.0..315.0->false
            else->return reject("声源过于接近前方或后方，请放在正${side.label}并核对前方基准")
        }
        return SideCalibrationResult.Success(if(side==CalibrationSide.RIGHT)increasingRight else !increasingRight,mean,values.size)
    }
}

/** Only post-button, distinct, fresh records enter a calibration attempt. */
internal class SideCalibrationCapture(val startedAt:Long,val front:Double,val side:CalibrationSide,val generation:Long) {
    private val samples=mutableListOf<DirectionReading>()
    private var problem:String?=null
    fun observe(snapshot:DirectionSnapshot,currentGeneration:Long) {
        if(problem!=null)return
        if(currentGeneration!=generation || !snapshot.enabled || !snapshot.connected) {
            problem="USB 连接已变化，本次未保存，请重试";return
        }
        if(snapshot.config.frontDegrees!=front) {problem="正前方基准已变化，请重新校准左右";return}
        val reading=snapshot.reading ?: return
        if(snapshot.fresh && reading.receivedAt>startedAt && samples.none {it.receivedAt==reading.receivedAt} && samples.size<32)samples+=reading
    }
    fun finish(snapshot:DirectionSnapshot,currentGeneration:Long):SideCalibrationResult {
        observe(snapshot,currentGeneration)
        problem?.let {return SideCalibrationResult.Rejected(it)}
        if(!snapshot.fresh)return SideCalibrationResult.Rejected("当前角度已失效，本次未保存，请重试")
        return DirectionSideCalibration.evaluate(front,side,samples)
    }
    val count get()=samples.size
}
