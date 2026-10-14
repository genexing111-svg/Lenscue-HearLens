package com.hearlens.app

import kotlin.math.*

/** Software counterpart of the GPU glow. Geometry and Gaussian tables are reused across angles.
 * No per-pixel trigonometry, exponentials or allocations on a changing USB direction. */
internal class SoundGlowRaster(val logicalWidth:Float,val logicalHeight:Float) {
    val width=ceil(logicalWidth).toInt().coerceIn(1,1024)
    val height=ceil(logicalHeight).toInt().coerceIn(1,1024)
    private val angles=ShortArray(width*height)
    private val distances=ShortArray(width*height)
    private val pixels=IntArray(width*height)
    private val radial=IntArray((min(logicalWidth,logicalHeight)*DISTANCE_STEPS/2).roundToInt()+1)
    private val colors=IntArray(radial.size)
    private val sectors=IntArray(ANGLE_STEPS)
    private var previousDepth=-1
    private var previousIntensity=-1
    private var previousSpread=-1

    init {
        for(y in 0 until height)for(x in 0 until width) {
            val px=(x+.5)*logicalWidth/width;val py=(y+.5)*logicalHeight/height
            val i=y*width+x
            angles[i]=angleIndex((px-logicalWidth/2)/(logicalWidth/2),-(py-logicalHeight/2)/(logicalHeight/2)).toShort()
            distances[i]=(minOf(px,py,logicalWidth-px,logicalHeight-py)*DISTANCE_STEPS).roundToInt().coerceIn(0,radial.lastIndex).toShort()
        }
        for(i in colors.indices) {
            val near=exp(-(i.toDouble()/DISTANCE_STEPS)/13)
            colors[i]=(floor(48*near+.5).toInt() shl 16) or (floor(114+86*near+.5).toInt() shl 8) or 255
        }
    }

    /** Buffer belongs to this instance; Bitmap.createBitmap copies it before the next call. */
    fun render(bearing:Double,settings:SoundGlowSettings):IntArray {
        if(previousDepth!=settings.depth || previousIntensity!=settings.intensity) {
            for(i in radial.indices) {
                val d=i.toDouble()/DISTANCE_STEPS
                radial[i]=((.65*exp(-d/2.2)+.35*exp(-d/18)+.35*exp(-.5*d*d/(settings.depth*settings.depth)))*settings.intensity/100*255*256).roundToInt()
            }
            previousDepth=settings.depth
            previousIntensity=settings.intensity
        }
        if(previousSpread!=settings.spread) {
            val sigma=Math.toRadians(settings.spread.toDouble())/2.355
            for(i in sectors.indices) {
                val delta=min(i,ANGLE_STEPS-i)*(2*PI/ANGLE_STEPS)
                sectors[i]=(exp(-.5*delta*delta/(sigma*sigma))*4096).roundToInt()
            }
            previousSpread=settings.spread
        }
        val bearingIndex=(DirectionMath.normalize(bearing)*ANGLE_STEPS/360).roundToInt() and ANGLE_MASK
        for(i in pixels.indices) {
            val d=distances[i].toInt() and 0xffff
            val sector=(angles[i].toInt()-bearingIndex) and ANGLE_MASK
            // 8-bit radial fraction × 12-bit angular fraction fits Int, including the unclipped core.
            val alpha=((radial[d]*sectors[sector]+(1 shl 19)) ushr 20).coerceAtMost(255)
            pixels[i]=(alpha shl 24) or colors[d]
        }
        return pixels
    }

    companion object {
        private const val DISTANCE_STEPS=8
        private const val ANGLE_STEPS=8192
        private const val ANGLE_MASK=ANGLE_STEPS-1
        private const val ATAN_STEPS=2048
        private val atanTable=IntArray(ATAN_STEPS+1) {(atan(it.toDouble()/ATAN_STEPS)*ANGLE_STEPS/(2*PI)).roundToInt()}
        private fun angleIndex(y:Double,x:Double):Int {
            val ax=abs(x);val ay=abs(y)
            if(ax==0.0 && ay==0.0)return 0
            var angle=if(ax>=ay)atanTable[(ay/ax*ATAN_STEPS).roundToInt()] else ANGLE_STEPS/4-atanTable[(ax/ay*ATAN_STEPS).roundToInt()]
            if(x<0)angle=ANGLE_STEPS/2-angle
            if(y<0)angle=ANGLE_STEPS-angle
            return angle and ANGLE_MASK
        }
    }
}
