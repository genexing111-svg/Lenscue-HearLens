package com.hearlens.app

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit

/** Standard Android HID input: no Bluetooth scanning, pairing, or extra permission. */
class RemoteInput(private val activity:Activity,private val engine:LocalStereoEngine,
    private val active:()->Boolean,private val command:(String)->Unit,private val enterStereo:()->Unit,
    private val shoulder:()->Unit={},private val language:()->Unit={},
    private val vector:(Float,Float)->Boolean={_,_->false},private val cancelShortcut:()->Unit={},
    private val perception:()->Unit={},private val shortcutActive:()->Boolean=active,
    private val shoulderReleased:()->Unit={},private val shoulderCancelled:()->Unit={}):InputManager.InputDeviceListener {
    private val prefs=activity.getSharedPreferences("remote_v6",Activity.MODE_PRIVATE)
    private val handler=Handler(Looper.getMainLooper())
    private var direction:String?=null
    private var directionDevice=-1
    private val inputManager=activity.getSystemService(InputManager::class.java)
    private var learning:String?=null
    private var learnDialog:AlertDialog?=null
    private var lastKeyAt=0L
    private val sequence=listOf("confirm" to "A","language" to "X","back" to "B","perception" to "Y")
    private var step=-1
    private val learned=linkedMapOf<String,String>()
    private var lastMouseAt=0L
    private var lastMouseButton=0
    private val shoulderPress=ShoulderHoldGate()
    private val perceptionPress=ShortcutPressGate()
    private var analogHeld=false
    private val heldDirections=mutableSetOf<Int>()
    private val actions=linkedMapOf("confirm" to "开启菜单 / 确认（建议 A）","back" to "关闭 / 返回（建议 B）","perception" to "环境音感知开关（建议 Y）",
        "up" to "上一项","down" to "下一项","left" to "减少 / 上一个","right" to "增加 / 下一个",
        "shoulder" to "按住说话、松开播放（肩键）","language" to "字幕语言滑块（X / @）")
    private val repeat=object:Runnable {
        override fun run() { val d=direction ?: return;if(!active()) { cancel();return };command(d);handler.postDelayed(this,220) }
    }
    init { inputManager.registerInputDeviceListener(this,handler) }
    override fun onInputDeviceAdded(deviceId:Int) = Unit
    override fun onInputDeviceChanged(deviceId:Int) { cancel() }
    override fun onInputDeviceRemoved(deviceId:Int) { cancel() }
    fun release() { cancel();cancelCalibration();inputManager.unregisterInputDeviceListener(this);learnDialog?.dismiss() }
    private fun record(value:String) {
        engine.remoteStatus=value
        engine.remoteEvents=(engine.remoteEvents+value).takeLast(16)
        android.util.Log.d("HearLensRemote",value)
    }
    fun key(event:KeyEvent):Boolean {
        val external=event.device?.let { isController(it) }==true
        if(!external || (!active() && !shortcutActive() && learning==null))return false
        val code=event.keyCode
        if(code in listOf(KeyEvent.KEYCODE_POWER,KeyEvent.KEYCODE_HOME))return false
        val custom=prefs.getString("${event.device?.descriptor}:$code",null)
        val volumeDownIsConfirm=prefs.getString("${event.device?.descriptor}:${KeyEvent.KEYCODE_VOLUME_DOWN}",null)=="confirm"
        val action=RemoteKeyMapping.overrideAction(event.device?.name.orEmpty(),code,custom,volumeDownIsConfirm) ?: when(code) {
            KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2 -> "shoulder"
            KeyEvent.KEYCODE_AT,KeyEvent.KEYCODE_BUTTON_MODE,KeyEvent.KEYCODE_BUTTON_SELECT -> "language"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_SPACE -> "confirm"
            KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_B,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE -> "back"
            KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_X,KeyEvent.KEYCODE_MENU,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "language"
            KeyEvent.KEYCODE_BUTTON_Y,KeyEvent.KEYCODE_Y -> "perception"
            KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "minus"
            KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_MEDIA_NEXT -> "right"
            else -> null
        }
        // Y also works in the ordinary camera preview; all other controls retain their VR scope.
        if(learning==null && !active() && action !in listOf("perception","shoulder"))return false
        if(learning==null && action=="perception") {
            if(event.action==KeyEvent.ACTION_DOWN || event.action==KeyEvent.ACTION_UP) {
                if(perceptionPress.key("${event.deviceId}:$code",event.action==KeyEvent.ACTION_DOWN,event.repeatCount)) {
                    quietStick();record("${event.device?.name}: Y 环境音感知 ($code) → 开关切换");perception()
                }
            }
            return true
        }
        // X is now a direct language shortcut; existing saved "menu" mappings migrate to it.
        if(learning==null && action=="language") {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                quietStick();record("${event.device?.name}: 字幕语言键 ($code) → 六档滑块");language()
            }
            return true
        }
        if(learning==null && action=="shoulder") {
            val binding="${event.deviceId}:$code"
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && shoulderPress.press(binding)) {
                quietStick();record("${event.device?.name}: 肩键按住 ($code) → 说话");shoulder()
            } else if(event.action==KeyEvent.ACTION_UP) {
                if(shoulderPress.release(binding)) {record("${event.device?.name}: 肩键松开 ($code) → 播放");shoulderReleased()}
            }
            return true
        }
        if(learning==null && code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            if(event.action==KeyEvent.ACTION_DOWN)heldDirections.add(code) else if(event.action==KeyEvent.ACTION_UP)heldDirections.remove(code)
            val x=(if(KeyEvent.KEYCODE_DPAD_RIGHT in heldDirections)1f else 0f)-(if(KeyEvent.KEYCODE_DPAD_LEFT in heldDirections)1f else 0f)
            val y=(if(KeyEvent.KEYCODE_DPAD_DOWN in heldDirections)1f else 0f)-(if(KeyEvent.KEYCODE_DPAD_UP in heldDirections)1f else 0f)
            if(vector(x,y))return true
        }
        if(event.action==KeyEvent.ACTION_DOWN) {
            if(event.repeatCount==0)record("${event.device?.name}: ${KeyEvent.keyCodeToString(code)} ($code) → ${learning ?: action ?: "未映射"}")
            learning?.let { chosen ->
                if(event.repeatCount==0) {
                    saveLearning(event.device,code,chosen)
                }
                return true
            }
            if(action!=null) {
                val now=SystemClock.uptimeMillis()
                if(event.repeatCount==0 || action in listOf("up","down","left","right","minus") && now-lastKeyAt>180) {
                    if(action=="language") {quietStick();language()} else command(action);lastKeyAt=now
                }
            }
        }
        // Unknown controller buttons are consumed only in VR, with a visible diagnostic.
        return true
    }
    fun motion(event:MotionEvent):Boolean {
        if(mouse(event))return true
        if(!active() || !event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action!=MotionEvent.ACTION_MOVE)return false
        if(learning!=null)return true
        fun axis(id:Int):Float {
            val v=event.getAxisValue(id);val flat=event.device?.getMotionRange(id,event.source)?.flat ?: .1f
            return if(kotlin.math.abs(v)<=maxOf(.15f,flat))0f else v
        }
        val hx=axis(MotionEvent.AXIS_HAT_X);val hy=axis(MotionEvent.AXIS_HAT_Y)
        val x=if(hx!=0f)hx else axis(MotionEvent.AXIS_X);val y=if(hy!=0f)hy else axis(MotionEvent.AXIS_Y)
        val trigger=maxOf(axis(MotionEvent.AXIS_LTRIGGER),axis(MotionEvent.AXIS_RTRIGGER))>.6f
        if(trigger!=analogHeld) {
            analogHeld=trigger
            val binding="${event.deviceId}:trigger"
            if(trigger && shoulderPress.press(binding)) {quietStick();shoulder()}
            else if(!trigger && shoulderPress.release(binding))shoulderReleased()
        }
        if(vector(x,y)) {direction=null;handler.removeCallbacks(repeat);return true}
        val next=RemoteDirection.choose(x,y)
        if(next!=direction) {
            handler.removeCallbacks(repeat);direction=next;directionDevice=event.deviceId
            if(next!=null) { record("${event.device?.name}: 摇杆 $next");command(next);handler.postDelayed(repeat,450) }
        }
        return true
    }
    private fun quietStick() {direction=null;directionDevice=-1;heldDirections.clear();handler.removeCallbacks(repeat)}
    fun cancel() {direction=null;directionDevice=-1;if(shoulderPress.reset())shoulderCancelled();perceptionPress.reset();analogHeld=false;heldDirections.clear();handler.removeCallbacks(repeat);cancelShortcut()}
    fun beginCalibration() { enterStereo();cancel();learned.clear();step=0;showStep() }
    fun beginLearning(action:String) {
        require(action in actions);enterStereo();cancel();cancelCalibration();learning=action
        engine.remoteLearning=actions.getValue(action)
        engine.calibrationMenu=HeadsetMenuState(listOf("按一下要绑定的实体按键" to actions.getValue(action)),0,"在电脑可取消此操作",title="学习遥控器按键")
        engine.onFrame?.invoke()
    }
    fun cancelCalibration() { learning=null;step=-1;learned.clear();engine.remoteLearning=null;engine.calibrationMenu=null;engine.onFrame?.invoke() }
    private fun showStep() {
        learning=sequence[step].first;engine.remoteLearning=sequence[step].second
        engine.calibrationMenu=HeadsetMenuState(sequence.mapIndexed { i,v ->
            "第 ${i+1} 步：短按实体 ${v.second} 键" to if(i<step)"已保存" else if(i==step)"请现在按一下，再松开" else "稍后再按"
        },step,"请勿按组合键；摇杆暂停；屏幕返回可取消",title="遥控器校准")
        engine.onFrame?.invoke()
    }
    private fun saveLearning(device:InputDevice?,code:Int,chosen:String) {
        val binding="${device?.descriptor}:$code"
        if(step>=0) {
            if(learned.containsKey(binding)) { record("重复键值，请松开并短按实体 ${sequence[step].second} 键");return }
            learned[binding]=chosen
            step++
            if(step<sequence.size)showStep() else {
                prefs.edit {
                    for((k,v) in prefs.all) if(k.startsWith("${device?.descriptor}:") && v in sequence.map { it.first }+listOf("menu","minus"))remove(k)
                    learned.forEach { (k,v)->putString(k,v) }
                }
                cancelCalibration();record("SC-B03 四键校准完成：A 菜单 / 确认，X 字幕语言，B 返回，Y 环境音感知")
                command("confirm")
            }
        } else { prefs.edit { putString(binding,chosen) };cancelCalibration();learnDialog?.dismiss();Toast.makeText(activity,"按键映射已保存",Toast.LENGTH_SHORT).show() }
    }
    fun mouse(event:MotionEvent):Boolean {
        if((!active() && learning==null) || !event.isFromSource(InputDevice.SOURCE_MOUSE) || event.device?.name?.contains("SC-B03",true)!=true)return false
        val button=when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP -> MotionEvent.BUTTON_PRIMARY
            MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_BUTTON_RELEASE -> event.actionButton
            else -> return false
        }
        val binding="${event.deviceId}:${-1000-button}"
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE) {
            if(shoulderPress.release(binding))shoulderReleased()
            return true
        }
        val now=SystemClock.uptimeMillis()
        if(button==lastMouseButton && now-lastMouseAt<120)return true
        lastMouseAt=now;lastMouseButton=button
        val code=-1000-button
        val savedAction=prefs.getString("${event.device?.descriptor}:$code",null)
        val action=RemoteKeyMapping.savedAction(savedAction) ?: when(button) {
            MotionEvent.BUTTON_PRIMARY->"confirm";MotionEvent.BUTTON_SECONDARY->"back";else->"language"
        }
        record("${event.device?.name}: MOUSE_BUTTON_$button → ${learning ?: action}")
        val chosen=learning
        if(chosen!=null)saveLearning(event.device,code,chosen)
        else if(action=="shoulder") {if(shoulderPress.press(binding)) {quietStick();shoulder()}}
        else if(action=="language") {quietStick();language()}
        else if(action=="perception") {quietStick();perception()} else command(action)
        return true
    }
    fun setup() {
        val devices=InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isController(it) }
        AlertDialog.Builder(activity).setTitle(ControllerHints.span("SC-B03 / 蓝牙遥控器 · 可学习肩键与 @"))
            .setItems(arrayOf("查看设备与最近按键","学习单个按键映射","恢复默认按键","一键校准 A / X / B / Y").map {ControllerHints.span(it)}.toTypedArray()) { _,which ->when(which) {
                0->AlertDialog.Builder(activity).setTitle("遥控器诊断").setMessage(ControllerHints.span("${devices.joinToString("\n") { it.name }}\n\n${engine.remoteEvents.joinToString("\n")}\n\n默认：A 开启菜单 / 确认，X 字幕语言，B 返回，Y 环境音感知开关，摇杆 导航。Y 在普通预览和双眼观看时均可使用，长按只切换一次。减少数值用摇杆左。若与实体按键不符，请学习映射；优先选择手柄 / 游戏模式。鼠标模式也可点击双眼菜单。")).setPositiveButton("关闭",null).show()
                1->AlertDialog.Builder(activity).setTitle("选择要学习的功能").setItems(actions.values.map {ControllerHints.span(it)}.toTypedArray()) { _,i ->
                    learning=actions.keys.elementAt(i)
                    learnDialog=object:AlertDialog(activity) {
                        override fun dispatchKeyEvent(event:KeyEvent):Boolean=key(event) || super.dispatchKeyEvent(event)
                        override fun dispatchGenericMotionEvent(event:MotionEvent):Boolean=mouse(event) || super.dispatchGenericMotionEvent(event)
                        override fun dispatchTouchEvent(event:MotionEvent):Boolean=mouse(event) || super.dispatchTouchEvent(event)
                    }.apply {
                        setTitle("现在按一下遥控器按键");setMessage(ControllerHints.span(actions[learning].orEmpty()));setButton(AlertDialog.BUTTON_NEGATIVE,"取消") { _,_-> }
                        setOnDismissListener { learning=null }
                        show()
                    }
                }.setNegativeButton("取消",null).show()
                2->{prefs.edit { clear() };Toast.makeText(activity,"已恢复默认映射",Toast.LENGTH_SHORT).show()}
                3->beginCalibration()
            } }.setNegativeButton("关闭",null).show()
    }
    private fun isController(device:InputDevice):Boolean =
        if(Build.VERSION.SDK_INT>=29)device.isExternal
        else device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK) || device.supportsSource(InputDevice.SOURCE_DPAD)
}

