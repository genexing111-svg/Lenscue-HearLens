package com.hearlens.app

import org.json.JSONObject

/** Validate the whole command before any mutation; settings never accept arbitrary intents or URLs. */
internal object DesktopControlProtocol {
    val sections=setOf("captions","environment","direction","speech","ptt","application","remote","preset")
    fun validate(j:JSONObject):Pair<String,JSONObject> {
        require(j.keys().asSequence().toSet()==setOf("section","values")) {"使用 section 和 values 字段"}
        val section=j.get("section");require(section is String && section in sections) {"未知设置分组"}
        val v=j.get("values");require(v is JSONObject && v.length()>0) {"设置值不能为空"}
        fun fields(vararg keys:String) {require(v.keys().asSequence().all {it in keys}) {"包含未知设置字段"}}
        fun integer(key:String,min:Int,max:Int) {if(v.has(key)) {val n=v.get(key);require(n is Int && n in min..max) {"$key 必须为 $min–$max 的整数"}}}
        fun number(key:String,min:Double,max:Double) {if(v.has(key)) {val n=v.get(key);require(n is Number && n.toDouble().isFinite() && n.toDouble() in min..max) {"$key 数值越界"}}}
        fun bool(vararg keys:String) {keys.forEach {if(v.has(it))require(v.get(it) is Boolean) {"$it 必须是开关值"}}}
        fun text(key:String,max:Int) {if(v.has(key))require(v.get(key) is String && v.getString(key).length<=max) {"$key 文本过长或类型错误"}}
        fun choice(key:String,vararg choices:String) {if(v.has(key))require(v.get(key) is String && v.getString(key) in choices) {"$key 选项无效"}}
        fun action(vararg choices:String) {choice("action",*choices)}
        when(section) {
            "captions"->{fields("choice","widthPercent","bottomPercent","sizePercent","transparency","action");integer("choice",0,5);integer("widthPercent",35,80);integer("bottomPercent",3,30);integer("sizePercent",70,160);integer("transparency",0,100);action("clear","resetStyle");if(v.has("choice"))require(v.length()==1) {"字幕模式请单独提交"}}
            "environment"->{fields("enabled","intensity","depth","spread","transparency","language","associateDirection","reducedMotion");bool("enabled","associateDirection","reducedMotion");integer("intensity",0,100);integer("depth",20,160);integer("spread",20,160);integer("transparency",0,100);choice("language","zh-Hans","zh-Hant","en")}
            "direction"->{
                fields("baudRate","format","frontDegrees","clockwise","staleMs","showOverlay","action","deviceName","side")
                if(v.has("baudRate")) {integer("baudRate",9600,921600);require(v.getInt("baudRate") in DirectionConfig.BAUD_RATES)}
                choice("format",*DirectionFormat.entries.map {it.name}.toTypedArray());number("frontDegrees",0.0,359.999999);integer("staleMs",500,10000);bool("clockwise","showOverlay")
                action("connect","disconnect","retryPermission","calibrateFront","swapSides","startSide","cancelSide");text("deviceName",200);choice("side","LEFT","RIGHT")
                if(v.optString("action")=="connect")require(v.has("deviceName"))
                if(v.optString("action")=="startSide")require(v.has("side"))
                if(v.has("deviceName"))require(v.optString("action")=="connect")
                if(v.has("side"))require(v.optString("action")=="startSide")
            }
            "speech"->{fields("workspace","key","action");text("workspace",84);text("key",8192);action("clearKey")
                if(v.has("workspace"))require(v.getString("workspace").isEmpty() || Regex("llm-[a-z0-9-]{4,80}").matches(v.getString("workspace"))) {"业务空间格式无效"}
                if(v.has("key"))require(v.getString("key").length>=10 && v.getString("key").all {it.code in 33..126}) {"API Key 格式无效"}
            }
            "ptt"->{fields("target","action","lease");choice("target","zh","en");action("start","heartbeat","end","cancel");text("lease",80)
                if(v.has("action"))require(v.has("lease") && Regex("[A-Za-z0-9_-]{12,80}").matches(v.getString("lease"))) {"缺少有效按住会话标识"}
            }
            "application"->{fields("stereo","paused","wireless");bool("stereo","paused","wireless")}
            "remote"->{fields("action","mapping");action("openMenu","closeMenu","up","down","left","right","confirm","back","language","startCalibration","cancelCalibration","reset","learn")
                choice("mapping","confirm","back","perception","up","down","left","right","shoulder","language")
                require(v.has("action"));if(v.getString("action")=="learn")require(v.has("mapping"))
                if(v.has("mapping"))require(v.getString("action")=="learn")
            }
            "preset"->{fields("name");require(v.has("name"));choice("name","smooth","sharp","comfortable","default")}
        }
        // Actions and parameter edits are separate so an invalid action cannot partially apply settings.
        if(v.has("action")) {
            val extra=when(section) {"ptt"->setOf("action","lease");"direction"->setOf("action","deviceName","side");"remote"->setOf("action","mapping");else->setOf("action")}
            require(v.keys().asSequence().all {it in extra}) {"操作与参数修改请分别提交"}
        }
        return section to v
    }
}
