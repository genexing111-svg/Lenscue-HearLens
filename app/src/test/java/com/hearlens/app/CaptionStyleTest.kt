package com.hearlens.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptionStyleTest {
    @Test fun oldBlurMaterialMigratesWithoutMovingOrResizingCaptions() {
        val old=JSONObject().put("widthPercent",66).put("bottomPercent",14).put("sizePercent",125)
            .put("transparency",75).put("feather",20).put("blur",16)
        val migrated=CaptionStyle.fromJson(old)
        assertEquals(66,migrated.widthPercent);assertEquals(14,migrated.bottomPercent);assertEquals(125,migrated.sizePercent)
        assertEquals(11,migrated.transparency)
        assertEquals(CaptionStyle.MATERIAL,migrated.toJson().getString("backgroundMaterial"))
        assertEquals(0,migrated.toJson().getInt("blur"));assertEquals(0,migrated.toJson().getInt("feather"))
    }
    @Test fun newMaterialKeepsCustomTransparencyAcrossReloadAndIgnoresLegacyBlurFields() {
        for(transparency in listOf(0,11,40,100)) {
            val style=CaptionStyle(60,10,120,transparency)
            val saved=style.toJson().put("blur",16).put("feather",24)
            val restored=CaptionStyle.fromJson(saved)
            assertEquals(style,restored)
            assertEquals(0,restored.toJson().getInt("blur"));assertEquals(0,restored.toJson().getInt("feather"))
        }
    }
}
