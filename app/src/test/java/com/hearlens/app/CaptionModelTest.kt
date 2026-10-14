package com.hearlens.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptionModelTest {
    @Test fun newAsrSessionStartingAtZeroAppendsWithoutReplacingRetainedCaptions() {
        val t=CaptionTranscript()
        fun asr(text:String)=JSONObject().put("type","result").put("sentence",JSONObject().put("sentence_id",0).put("text",text))
        t.accept(asr("先前已经识别的字幕"),"zh","before")
        t.accept(asr("继续识"),"zh","after")
        t.accept(asr("继续识别的新字幕"),"zh","after")
        assertEquals(listOf("先前已经识别的字幕","继续识别的新字幕"),t.turns(false).map {it.text})
    }
    @Test fun speakerAndItemIdsFromDifferentSessionsRemainSeparate() {
        val t=CaptionTranscript()
        t.accept(event("item",1,"旧原文","Old caption"),"en","before")
        t.accept(event("item",1,"新原文","New caption"),"en","after")
        assertEquals(listOf("旧原文","新原文"),t.turns(false).map {it.text})
        assertEquals(listOf("Old caption","New caption"),t.turns(true).map {it.text})
    }
    private fun event(id:String,speaker:Any?,text:String,translation:String="")=JSONObject().put("type","segment").put("segment",JSONObject()
        .put("id",id).put("speakerId",speaker ?: JSONObject.NULL).put("original",text)
        .put("translations",JSONObject().put("en",translation)))
    private fun rows(turns:List<CaptionTurn>,width:Float=5f)=CaptionLayout.rows(turns,width,{it.length.toFloat()},{it.map(Char::toString)})
    @Test fun changingSpeakerAlwaysStartsAnotherLineEvenIfSpaceRemains() {
        val t=CaptionTranscript();t.accept(event("a",0,"你好"),"en");t.accept(event("b",1,"早上好"),"en")
        val result=rows(t.turns(false),20f)
        assertEquals(listOf("你好","早上好"),result.map { it.text });assertEquals(listOf("0","1"),result.map { it.speaker })
    }
    @Test fun sameSpeakerContinuesButReturningSpeakerStartsNewBlock() {
        val t=CaptionTranscript()
        listOf(event("a",1,"第一句"),event("b",1,"第二句"),event("c",2,"换人"),event("d",1,"返回")).forEach { t.accept(it,"en") }
        assertEquals(listOf("第一句第二句","换人","返回"),t.turns(false).map { it.text })
    }
    @Test fun hypothesisCorrectionReplacesWithoutDuplicateOrReordering() {
        val t=CaptionTranscript();t.accept(event("a",1,"我们"),"en");t.accept(event("b",2,"你好"),"en")
        t.accept(event("a",1,"我们明天见"),"en")
        assertEquals(listOf("我们明天见","你好"),t.turns(false).map { it.text })
    }
    @Test fun fourMeansRenderedLinesAcrossAllSpeakersNotFourParagraphs() {
        val result=rows(listOf(CaptionTurn("a","1","一二三四五六七八九十"),CaptionTurn("b","2","甲乙丙丁戊己庚辛壬癸"),CaptionTurn("c","3","最新")))
        assertEquals(4,result.size)
        assertEquals(listOf("六七八九十","甲乙丙丁戊","己庚辛壬癸","最新"),result.map { it.text })
    }
    @Test fun aLongSingleTurnKeepsLatestFourRows() {
        val result=rows(listOf(CaptionTurn("a","1","一二三四五六七八九十甲乙丙丁戊己庚辛壬癸天地方圆")))
        assertEquals(4,result.size);assertEquals("六七八九十",result.first().text);assertEquals("天地方圆",result.last().text)
    }
    @Test fun translationLagDoesNotMergeAcrossAnEmptyOtherSpeaker() {
        val t=CaptionTranscript();t.accept(event("a",1,"你好","hello"),"en")
        t.accept(event("b",2,"好的"),"en");t.accept(event("c",1,"再见","bye"),"en")
        assertEquals(2,t.turns(true).size);assertEquals(listOf("hello","bye"),t.turns(true).map { it.text })
    }
    @Test fun noTextNoPanelAndUnknownSpeakerIsNotInvented() {
        val t=CaptionTranscript();t.accept(event("a",null,""),"en");assertTrue(t.turns(false).isEmpty())
        t.accept(event("a",null,"你好"),"en");assertNull(t.turns(false).single().speaker)
        t.clear();assertTrue(t.turns(false).isEmpty())
    }
    @Test fun suppliedGraphemeClustersAreNeverSplitAndEnglishWordsWrap() {
        val family="👨‍👩‍👦";val accents="e\u0301"
        val result=CaptionLayout.rows(listOf(CaptionTurn("a","1",family+accents)),1f,{if(it==family || it==accents)1f else 2f},{listOf(family,accents)})
        assertEquals(listOf(family,accents),result.map { it.text })
        assertEquals(listOf("hello","world"),rows(listOf(CaptionTurn("a","1","hello world")),7f).map { it.text })
    }
    @Test fun featherIsFullInsideHalfAtHalfDistanceAndZeroAtOutsideEdge() {
        assertEquals(1f,CaptionLayout.coverage(10f,10f,100f,50f,12f),0f)
        assertEquals(.5f,CaptionLayout.coverage(-6f,10f,100f,50f,12f),.0001f)
        assertEquals(0f,CaptionLayout.coverage(-12f,10f,100f,50f,12f),0f)
        assertEquals(1f,CaptionLayout.coverage(0f,0f,100f,50f,0f),0f)
    }
}
