package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test

class StereoSettingsTest {
    @Test fun entireImageStaysInsideEachEyeAtExtremeSettings() {
        for ((sw,sh) in listOf(640 to 480,480 to 640,1920 to 1080,1080 to 1920))
            for (scale in listOf(50,85,100)) for (size in listOf(-10,0,10))
                for (spacing in listOf(-100,0,100)) for (vertical in listOf(-10,0,10)) for(right in listOf(false,true)) {
                    val b=StereoGeometry.fit(sw,sh,480f,432f,scale,size,spacing,vertical,right)
                    assertTrue(b.x>=-0.001f && b.y>=-0.001f)
                    assertTrue(b.x+b.width<=480.001f && b.y+b.height<=432.001f)
                    assertEquals(sw.toFloat()/sh,b.width/b.height,0.0001f)
                }
    }
    @Test fun binocularCopiesHaveMatchingGeometryWithNeutralSettings() {
        val left=StereoGeometry.fit(640,480,480f,432f,90,0,0,0,false)
        val right=StereoGeometry.fit(640,480,480f,432f,90,0,0,0,true)
        assertEquals(left,right)
        assertEquals(432f,left.width,.01f)
        assertEquals(324f,left.height,.01f)
    }
    @Test fun rejectUnboundedOrInvalidRenderingAndSubtitleRequests() {
        val invalid=listOf<()->StereoSettings>(
            {StereoSettings(outputWidth=20000)},{StereoSettings(wirelessFps=0)},{StereoSettings(wirelessFps=31)},
            {StereoSettings(scale=101)},{StereoSettings(leftSize=11)},{StereoSettings(spacing=-101)},
            {StereoSettings(rotation=45)},{StereoSettings(caption="字".repeat(121))},
            {StereoSettings(textColor="red")},{StereoSettings(eyeMode="stereo3d")},{StereoSettings(calibration="bad")}
        )
        invalid.forEach { factory -> try {factory();fail("invalid request accepted")}catch(_:IllegalArgumentException){} }
        assertEquals(120,StereoSettings(caption="字".repeat(120)).caption.length)
    }
}
