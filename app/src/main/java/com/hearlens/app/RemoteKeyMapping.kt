package com.hearlens.app

/** Android button names need not match this controller's printed letters. */
internal object RemoteKeyMapping {
    // The previous four-key calibration called the printed Y button "minus".
    // Interpret that legacy value without rewriting the saved profile, so rollback keeps working.
    fun savedAction(action:String?)=when(action) {"menu"->"language";"minus"->"perception";else->action}
    fun overrideAction(deviceName:String,code:Int,customAction:String?,volumeDownIsConfirm:Boolean):String? {
        // Explicit learning always wins, including after changing the controller's mode.
        if(customAction!=null)return savedAction(customAction)
        if(!deviceName.contains("SC-B03",ignoreCase=true))return null
        return when(code) {
            101,66,85 -> "shoulder" // BUTTON_Z; existing ENTER / PLAY_PAUSE aliases.
            // Measured shoulder=96, printed A=25. Only enable with that calibrated profile;
            // ordinary gamepads and an uncalibrated BUTTON_A retain the Android default.
            96 -> if(volumeDownIsConfirm)"shoulder" else null
            else -> null
        }
    }
}

/** Key-up and repeated down packets never toggle a second time during one physical press. */
internal class ShortcutPressGate {
    private val held=mutableSetOf<String>()
    fun key(binding:String,down:Boolean,repeatCount:Int):Boolean {
        if(!down) {held.remove(binding);return false}
        return repeatCount==0 && held.add(binding)
    }
    fun reset() {held.clear()}
}

/** Pair keyboard, trigger and mouse channels: capture until all shoulder channels are released. */
internal class ShoulderHoldGate {
    private val held=mutableSetOf<String>()
    fun press(binding:String):Boolean {val first=held.isEmpty();return held.add(binding) && first}
    fun release(binding:String):Boolean=held.remove(binding) && held.isEmpty()
    fun reset():Boolean {val was=held.isNotEmpty();held.clear();return was}
}
