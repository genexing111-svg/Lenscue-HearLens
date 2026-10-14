package com.hearlens.app

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class EnvironmentHandoffTest {
    private fun resource(path:String)=javaClass.classLoader!!.getResourceAsStream(path)!!.use {it.readBytes()}
    private fun json(path:String)=JSONObject(resource(path).toString(Charsets.UTF_8))
    private fun compare(expected:Any,actual:Any,path:String) {
        when(expected) {
            is JSONObject->{assertTrue(path,actual is JSONObject);actual as JSONObject
                val keys=expected.keys().asSequence().toSet();assertEquals(path,keys,actual.keys().asSequence().toSet());keys.forEach {compare(expected.get(it),actual.get(it),"$path.$it")}}
            is JSONArray->{assertTrue(path,actual is JSONArray);actual as JSONArray;assertEquals(path,expected.length(),actual.length());for(i in 0 until expected.length())compare(expected.get(i),actual.get(i),"$path[$i]")}
            is Number->{assertTrue(path,actual is Number);assertEquals(path,expected.toDouble(),(actual as Number).toDouble(),1e-10)}
            else->assertEquals(path,expected,actual)
        }
    }
    @Test fun reproducesAll167FrozenEventDecisions() {
        val cases=json("environment/postprocess-cases.json").getJSONArray("cases");var count=0
        for(i in 0 until cases.length()) {
            val scenario=cases.getJSONObject(i);val filter=EnvironmentEventFilter();val frames=scenario.getJSONArray("frames")
            for(j in 0 until frames.length()) {
                val frame=frames.getJSONObject(j);val input=frame.getJSONObject("input")
                val actual=filter.evaluate(SoundCategory.parse(input.optJSONArray("categories") ?: JSONArray()),input.optDouble("rms",0.0),input.optDouble("endMs",0.0)).json()
                compare(frame.getJSONObject("expected"),actual,"${scenario.getString("id")}[$j]");count++
            }
        }
        assertEquals(167,count)
    }
    private fun floats(path:String):FloatArray {val buffer=ByteBuffer.wrap(resource(path)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();return FloatArray(buffer.remaining()).also {buffer.get(it)}}
    @Test fun reproducesAllAudioWindowsAtThreeRatesAndArbitraryChunkBoundaries() {
        val cases=json("environment/audio-framing-cases.json").getJSONArray("cases")
        for(i in 0 until cases.length())for(chunks in listOf(listOf(128),listOf(1,73,511,2048,17,129))) {
            val c=cases.getJSONObject(i);val results=mutableListOf<AudioWindow>()
            val framer=EnvironmentAudioFramer(c.getInt("source_sample_rate_hz"),results::add)
            val input=floats("environment/"+c.getString("input_file"));var offset=0;var n=0
            while(offset<input.size) {val end=min(input.size,offset+chunks[n++%chunks.size]);framer.feed(input.copyOfRange(offset,end));offset=end}
            val expected=c.getJSONArray("expected_windows");assertEquals(expected.length(),results.size)
            for(j in results.indices) {
                val e=expected.getJSONObject(j);val actual=results[j]
                assertArrayEquals(floats("environment/"+e.getString("file")),actual.samples,1e-6f)
                assertEquals(e.getDouble("endMs"),actual.endMs,0.0);assertEquals(e.getDouble("rms"),actual.rms,1e-10);assertEquals(e.getDouble("peak"),actual.peak,1e-10)
            }
        }
    }
    @Test fun glowMathAndTimeMatchSuppliedReferenceProbes() {
        val reference=json("glow/render-reference.json");val points=reference.getJSONArray("probes")
        for(i in 0 until points.length()) {
            val p=points.getJSONObject(i);val xy=p.getJSONArray("point");val expected=p.getJSONArray("expectedRgba")
            val actual=SoundGlowKernel.sample(xy.getDouble(0),xy.getDouble(1),736.0,430.0,p.getDouble("bearing"))
            for(k in 0..3)assertEquals("probe $i channel $k",expected.getDouble(k),actual[k],1e-10)
        }
        val time=reference.getJSONArray("temporal")
        for(i in 0 until time.length()) {val t=time.getJSONObject(i);assertEquals(t.getDouble("opacity"),SoundGlowKernel.opacity(t.getLong("elapsedMs")),1e-10)}
        assertEquals(1.0,SoundGlowKernel.opacity(2599,true),0.0);assertEquals(0.0,SoundGlowKernel.opacity(2600,true),0.0)
    }
    @Test fun everyDirectionLabelFitsNarrowAndWideViews() {
        for(w in listOf(160f,320f,736f))for(h in listOf(240f,430f))for(angle in 0..359) {
            val box=SoundGlowKernel.labelBox(w,h,120f,37.5f,angle.toDouble())
            assertTrue(box[0]>=0 && box[1]>=0 && box[0]+box[2]<=w && box[1]+box[3]<=h)
        }
    }
    @Test fun timeAssociationNeverUsesStaleOrDisagreeingDirections() {
        assertNull(SoundDirectionAssociation.estimate(listOf(DirectionReading(90.0,10)),100,1000))
        assertNull(SoundDirectionAssociation.estimate(listOf(DirectionReading(90.0,500),DirectionReading(270.0,600)),100,1000))
        val north=SoundDirectionAssociation.estimate(listOf(DirectionReading(359.0,500),DirectionReading(1.0,600)),100,1000)!!
        assertTrue(north<.001 || north>359.999)
        assertEquals(90.0,SoundDirectionAssociation.estimate(listOf(DirectionReading(90.0,800)),100,1000)!!,0.0)
    }
    @Test fun rearLabelMovesClearOfExistingCaptionArea() {
        val label=SoundGlowKernel.labelBox(736f,430f,71f,37.5f,180.0)
        val obstacle=floatArrayOf(180f,280f,376f,130f)
        val shifted=SoundGlowKernel.avoidRectangle(label,obstacle,736f,430f)
        assertTrue(shifted[1]+shifted[3]<=280 || shifted[0]+shifted[2]<=180 || shifted[0]>=556)
        assertTrue(shifted[0]>=0 && shifted[1]>=0 && shifted[0]+shifted[2]<=736 && shifted[1]+shifted[3]<=430)
    }
    @Test fun dedupDoesNotKeepPersistentSoundsBrightForeverAndFourthEvictsOldest() {
        val timeline=SoundEventTimeline();timeline.accept(353,true,90.0,0,0,false);timeline.accept(353,false,92.0,480,480,false)
        assertEquals(1,timeline.snapshot(480,false).size);assertFalse(timeline.snapshot(480,false).single().possible)
        assertEquals(0L,timeline.snapshot(480,false).single().born)
        assertTrue(timeline.snapshot(2800,false).isEmpty())
        for(i in 1..4)timeline.accept(i,false,i*45.0,3000L+i,3000L+i,false)
        assertEquals(listOf(2,3,4),timeline.snapshot(3010,false).map {it.index})
    }
}
