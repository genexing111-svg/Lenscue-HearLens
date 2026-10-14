package com.hearlens.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsInteractionTest {
    @Test fun modeOrderAndRoutesAreExplicit() {
        assertEquals(listOf("快速识别","说话人识别","转译为简体中文","转译为繁体中文","转译为英文"),CaptionMode.entries.map {it.label})
        assertEquals("asr",CaptionMode.FAST.protocol)
        assertFalse(CaptionMode.FAST.translation)
        assertFalse(CaptionMode.SPEAKERS.translation)
        assertEquals("zh",CaptionMode.TRADITIONAL.target)
        assertTrue(CaptionMode.TRADITIONAL.sameSession(CaptionMode.SIMPLIFIED))
        assertTrue(CaptionMode.ENGLISH.sameSession(CaptionMode.SPEAKERS))
        assertFalse(CaptionMode.ENGLISH.sameSession(CaptionMode.FAST))
        assertFalse(CaptionMode.SPEAKERS.sameSession(CaptionMode.SIMPLIFIED))
    }
    @Test fun olderPreferencesMigrateWithoutChangingDisplayedLanguage() {
        assertEquals(CaptionMode.SPEAKERS,CaptionMode.restore(null,false,"en"))
        assertEquals(CaptionMode.SIMPLIFIED,CaptionMode.restore(null,true,"zh"))
        assertEquals(CaptionMode.ENGLISH,CaptionMode.restore(null,true,"en"))
        assertEquals(CaptionMode.TRADITIONAL,CaptionMode.restore("TRADITIONAL",false,"en"))
    }
    @Test fun allWheelSectorsMatchDrawnAngles() {
        assertEquals(0,ShortcutSelection.sector(0f,-1f))
        assertEquals(1,ShortcutSelection.sector(1f,0f))
        assertEquals(1,ShortcutSelection.sector(.87f,.5f))
        assertEquals(2,ShortcutSelection.sector(-.87f,.5f))
        assertEquals(2,ShortcutSelection.sector(-1f,0f))
    }
    @Test fun languagePreviewClampsAndCancelDoesNotApply() {
        val model=ShortcutSelection();model.language(2);model.step(-20);assertEquals(0,model.selected)
        model.step(30);assertEquals(5,model.selected)
        model.cancel();assertEquals(-1,model.take())
        model.language(2);model.step(1);assertEquals(3,model.take())
        assertEquals(ShortcutSelection.Kind.NONE,model.kind)
    }
    @Test fun offChoiceDoesNotShiftPersistedModesOrStartAModel() {
        assertEquals(listOf("关闭字幕","快速识别","说话人识别","转译为简体中文","转译为繁体中文","转译为英文"),CaptionChoices.labels)
        assertNull(CaptionChoices.modeAt(0))
        for(mode in CaptionMode.entries) {
            assertEquals(0,CaptionChoices.index(mode,false))
            assertEquals(mode,CaptionChoices.modeAt(CaptionChoices.index(mode,true)))
            assertEquals(mode,CaptionMode.restore(mode.name,false,"en"))
        }
    }
    @Test fun browsingAndCancellingOffOrLanguageHasNoCommittedChoice() {
        val model=ShortcutSelection();model.language(4);model.step(-30);assertEquals(0,model.selected)
        model.cancel();assertEquals(-1,model.take())
        model.language(0);model.step(1);assertEquals(CaptionMode.FAST,CaptionChoices.modeAt(model.take()))
        model.language(5);model.step(-5);assertNull(CaptionChoices.modeAt(model.take()))
    }
    @Test fun fastResultsReplaceHypothesesAndNeverInventSpeakers() {
        val model=CaptionTranscript()
        fun sentence(id:Int,text:String)=JSONObject().put("type","result").put("sentence",JSONObject().put("sentence_id",id).put("text",text))
        model.accept(sentence(0,"你好"),"en");model.accept(sentence(0,"你好世界。"),"en")
        model.accept(sentence(1,"第二句。"),"en")
        assertEquals("你好世界。第二句。",model.turns(false).single().text)
        assertNull(model.turns(false).single().speaker)
        assertTrue(model.turns(true).isEmpty())
        model.accept(sentence(2,""),"en");assertEquals("你好世界。第二句。",model.turns(false).single().text)
    }
    @Test fun nullAsrIdsFallBackToBeginTimeAndClearDropsOldModel() {
        val model=CaptionTranscript()
        for(time in listOf(1,2))model.accept(JSONObject().put("type","result").put("sentence",JSONObject().put("sentence_id",JSONObject.NULL).put("begin_time",time).put("text","句$time。")),"en")
        assertEquals("句1。句2。",model.turns(false).single().text)
        model.clear();assertTrue(model.turns(false).isEmpty())
    }
}
