package com.hearlens.app

/** X remains a language shortcut; the camera wheel has been removed. */
internal class QuickControls(private val engine:LocalStereoEngine,private val camera:UsbVideoServer,
    private val captions:LiveCaptionSession,private val dismissSettings:()->Unit,private val applyCaptionChoice:(Int)->Unit) {
    private val selection=ShortcutSelection()
    private var message=""
    val opened get()=selection.kind!=ShortcutSelection.Kind.NONE
    fun language() {if(opened)cancel() else {dismissSettings();message="";selection.language(captions.choiceIndex);update()}}
    fun vector(x:Float,y:Float)=false
    fun cancel() {selection.cancel();engine.quickOverlay=null;engine.onFrame?.invoke()}
    fun command(action:String):Boolean {
        if(!opened)return false
        when(action) {
            "back","menu"->cancel()
            "confirm"->runCatching {applyCaptionChoice(selection.selected)}.onSuccess {cancel()}.onFailure {message=it.message ?: "无法切换";engine.remoteStatus=message}
            "left","minus"->{message="";selection.step(-1)}
            "right"->{message="";selection.step(1)}
        }
        if(opened)update();return true
    }
    fun touch(x:Float,y:Float,width:Int,height:Int) {
        val panel=engine.menuPanels(width,height).firstOrNull {it.contains(x,y)} ?: run {cancel();return}
        if((y-panel.y)/panel.height>.92f) {if(x<panel.centerX)command("confirm") else cancel()}
        else {val track=HeadsetMenuGeometry.quickSlider(panel);if(track.contains(x,y)) {selection.language(HeadsetMenuGeometry.position(x,track,CaptionChoices.labels.size));update()}}
    }
    private fun update() {engine.quickOverlay=QuickOverlay(selection.selected,CaptionChoices.labels,message);engine.onFrame?.invoke()}
}
