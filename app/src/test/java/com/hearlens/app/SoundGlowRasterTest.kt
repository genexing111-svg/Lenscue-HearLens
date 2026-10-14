package com.hearlens.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class SoundGlowRasterTest {
    @Test fun movingBearingsMatchReferenceAcrossTheFullCircle() {
        val random=Random(512)
        for((w,h) in listOf(736f to 552f,735.8f to 429.7f,573.33f to 430f)) {
            val raster=SoundGlowRaster(w,h)
            val positions=List(160) {random.nextInt(raster.width) to random.nextInt(raster.height)} +
                listOf(0 to raster.height/2,raster.width-1 to raster.height/2,raster.width/2 to 0,raster.width/2 to raster.height-1)
            for(s in listOf(SoundGlowSettings(),SoundGlowSettings(100,160,20),SoundGlowSettings(100,20,160))) {
                for(bearing in listOf(0.0,.1,44.7,90.0,134.9,180.0,225.2,270.0,315.1,359.9)) {
                    val pixels=raster.render(bearing,s)
                    for((x,y) in positions) {
                        val expected=SoundGlowKernel.sample((x+.5)*w/raster.width,(y+.5)*h/raster.height,w.toDouble(),h.toDouble(),bearing,s)
                        val color=pixels[y*raster.width+x]
                        assertTrue("Alpha at $x,$y bearing $bearing",abs((color ushr 24)-(expected[3]*255).roundToInt())<=6)
                        assertTrue(abs((color ushr 16 and 255)-expected[0])<=1)
                        assertTrue(abs((color ushr 8 and 255)-expected[1])<=1)
                        assertEquals(255,color and 255)
                    }
                }
            }
        }
    }

    @Test fun settingsChangesAndWrappedBearingsNeverLeaveAStaleMask() {
        val raster=SoundGlowRaster(736f,430f)
        val base=raster.render(90.0,SoundGlowSettings()).copyOf()
        assertArrayEquals(base,raster.render(-270.0,SoundGlowSettings()))
        assertTrue(raster.render(90.0,SoundGlowSettings(intensity=0)).all {it ushr 24==0})
        assertFalse(base.contentEquals(raster.render(270.0,SoundGlowSettings(spread=160,depth=160))))
        assertArrayEquals(base,raster.render(90.0,SoundGlowSettings()))
    }
}
