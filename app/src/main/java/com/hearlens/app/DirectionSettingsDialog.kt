package com.hearlens.app

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/** Kept in the camera Activity so a USB permission dialog cannot create a second serial owner. */
internal class DirectionSettingsDialog(private val activity:Activity,private val controller:UsbDirectionController) {
    private var dialog:AlertDialog?=null
    private val handler=Handler(Looper.getMainLooper())
    private var capture:SideCalibrationCapture?=null
    fun dismiss() {capture=null;handler.removeCallbacksAndMessages(null);dialog?.dismiss();dialog=null}
    fun show(onClosed:()->Unit={}) {
        dismiss()
        val dp=activity.resources.displayMetrics.density
        val panel=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding((20*dp).toInt(),0,(20*dp).toInt(),0)}
        fun label(value:String)=TextView(activity).apply {text=value;textSize=16f;setPadding(0,(12*dp).toInt(),0,(4*dp).toInt());panel.addView(this)}
        fun button(value:String,run:()->Unit)=Button(activity).apply {text=value;setOnClickListener {run()};panel.addView(this,LinearLayout.LayoutParams(-1,-2))}
        fun choices(values:List<String>)=Spinner(activity).apply {adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,values);panel.addView(this)}
        label("通过 USB OTG 直连小板。已适配 CH340：115200 / 8N1，angle: 123.0 deg。仅读取角度，不向小板发送业务命令。")
        val status=label("").apply {setTextColor(Color.rgb(180,245,190));setBackgroundColor(Color.rgb(24,38,31));setPadding((10*dp).toInt(),(10*dp).toInt(),(10*dp).toInt(),(10*dp).toInt());setTextIsSelectable(true)}
        var devices=controller.devices()
        label("USB 设备")
        val devicePicker=choices(devices.map {it.label+if(it.supported)"" else "（非支持串口）"}.ifEmpty {listOf("未发现 USB 设备")})
        fun refreshDevices() {
            devices=controller.devices()
            devicePicker.adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,devices.map {it.label+if(it.supported)"" else "（非支持串口）"}.ifEmpty {listOf("未发现 USB 设备")})
        }
        button("刷新 USB 列表",::refreshDevices)
        val current=controller.snapshot().config
        label("波特率（固定 8 数据位、无校验、1 停止位）")
        val baud=choices(DirectionConfig.BAUD_RATES.map(Int::toString)).apply {setSelection(DirectionConfig.BAUD_RATES.indexOf(current.baudRate))}
        label("方向数据格式")
        val format=choices(DirectionFormat.entries.map {it.label}).apply {setSelection(current.format.ordinal)}
        label("正前方对应的小板角度（0–360°）")
        val front=EditText(activity).apply {contentDescription="正前方对应角度";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setSingleLine();setText(String.format(Locale.US,"%.3f",current.frontDegrees));panel.addView(this)}
        label("小板角度增大时的方向（面朝前方，水平俯视）")
        val clockwise=choices(listOf("顺时针：0 前 / 90 右 / 180 后 / 270 左","逆时针：增大的角度朝左" )).apply {setSelection(if(current.clockwise)0 else 1)}
        label("未收到新角度后清除提示的时间")
        val timeouts=listOf(500L,1000L,2000L,3000L,5000L,10000L)
        val timeout=choices(timeouts.map {"${it/1000.0} 秒"}).apply {setSelection(timeouts.indexOf(current.staleMs).coerceAtLeast(0))}
        val overlay=CheckBox(activity).apply {text="在预览、左右眼和无线画面显示方向";isChecked=current.showOverlay;panel.addView(this)}
        fun save():Boolean=try {
            val degrees=front.text.toString().toDouble()
            require(degrees.isFinite() && degrees in 0.0..360.0)
            controller.configure(DirectionConfig(DirectionConfig.BAUD_RATES[baud.selectedItemPosition],DirectionFormat.entries[format.selectedItemPosition],
                DirectionMath.normalize(degrees),clockwise.selectedItemPosition==0,timeouts[timeout.selectedItemPosition],overlay.isChecked))
            true
        } catch(_:Exception) {Toast.makeText(activity,"请输入 0–360 的有效前方角度",Toast.LENGTH_LONG).show();false}
        button("应用参数并保存") {if(save())Toast.makeText(activity,"方向参数已保存",Toast.LENGTH_SHORT).show()}
        button("连接 / 授权") {
            val selected=devices.getOrNull(devicePicker.selectedItemPosition)
            if(selected==null)Toast.makeText(activity,"请先用 OTG 连接小板并刷新",Toast.LENGTH_LONG).show()
            else if(save())controller.connect(selected.name)
        }
        button("把当前有效方向设为正前方") {
            if(controller.calibrateFront()) {
                front.setText(String.format(Locale.US,"%.3f",controller.snapshot().config.frontDegrees))
                Toast.makeText(activity,"已保存：当前方向为正前方",Toast.LENGTH_SHORT).show()
            } else Toast.makeText(activity,"尚无新鲜有效角度，请先在正前方发声",Toast.LENGTH_LONG).show()
        }
        label("左右校准：先保存正前方。只需纠正左右反向，可点“一键左右互换”；也可把声源放在指定一侧，采集 4 秒自动判断。")
        val calibrationStatus=label("尚未进行左右校准；现有方向设置保持不变").apply {setTextIsSelectable(true)}
        var lockDuringCalibration:List<android.view.View> = emptyList()
        fun unlock() {lockDuringCalibration.forEach {it.isEnabled=true}}
        fun syncDirectionChoice() {clockwise.setSelection(if(controller.snapshot().config.clockwise)0 else 1)}
        button("一键左右互换（立即保存）") {
            controller.swapLeftRight();syncDirectionChoice()
            calibrationStatus.text="已互换左右，正前方基准保持不变。可再次点击恢复。"
        }
        fun begin(side:CalibrationSide) {
            if(capture!=null)return
            val s=controller.snapshot()
            if(!s.enabled || !s.connected) {
                calibrationStatus.text="请先连接小板并允许 USB 访问，再开始左右校准";return
            }
            val draft=front.text.toString().toDoubleOrNull()
            if(draft==null || !draft.isFinite() || draft !in 0.0..360.0 || kotlin.math.abs(DirectionMath.normalize(draft)-s.config.frontDegrees)>.001) {
                calibrationStatus.text="正前方输入尚未保存，请先应用参数或重新设为正前方";return
            }
            capture=SideCalibrationCapture(android.os.SystemClock.elapsedRealtime(),s.config.frontDegrees,side,controller.calibrationGeneration())
            lockDuringCalibration.forEach {it.isEnabled=false}
        }
        button("声源在右侧：开始 4 秒校准") {begin(CalibrationSide.RIGHT)}
        button("声源在左侧：开始 4 秒校准") {begin(CalibrationSide.LEFT)}
        lockDuringCalibration=(0 until panel.childCount).map {panel.getChildAt(it)}.filter {it is Button || it is Spinner || it is EditText || it is CheckBox}
        button("取消本次左右校准") {
            if(capture!=null) {capture=null;unlock();calibrationStatus.text="已取消，未更改左右设置"}
        }
        button("断开并关闭读取",controller::disconnect)
        label("最近原始文本 / HEX（仅内存显示；未知格式不会生成方向）")
        val raw=label("").apply {typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true);maxLines=16}
        val scroll=ScrollView(activity).apply {addView(panel)}
        val shown=AlertDialog.Builder(activity).setTitle("小板连接与详细校准").setView(scroll).setPositiveButton("返回环境音感知",null).create()
        dialog=shown
        shown.setOnDismissListener {capture=null;handler.removeCallbacksAndMessages(null);if(dialog===shown)dialog=null;onClosed()}
        shown.show()
        shown.window?.setLayout((activity.resources.displayMetrics.widthPixels*.92f).toInt().coerceAtMost((700*dp).toInt()),ViewGroup.LayoutParams.WRAP_CONTENT)
        val update=object:Runnable {
            override fun run() {
                if(dialog!==shown)return
                val s=controller.snapshot()
                capture?.let {attempt ->
                    val elapsed=android.os.SystemClock.elapsedRealtime()-attempt.startedAt
                    attempt.observe(s,controller.calibrationGeneration())
                    if(elapsed>=DirectionSideCalibration.DURATION_MS) {
                        val result=controller.applySideCalibration(attempt)
                        capture=null;unlock();syncDirectionChoice()
                        calibrationStatus.text=when(result) {
                            is SideCalibrationResult.Success->activity.getString(R.string.direction_side_success,attempt.side.label,result.sampleCount,DirectionMath.degrees(result.meanRawDegrees))
                            is SideCalibrationResult.Rejected->result.reason
                        }
                    } else calibrationStatus.text=activity.getString(R.string.direction_side_progress,attempt.side.label,((DirectionSideCalibration.DURATION_MS-elapsed+999)/1000).toInt(),attempt.count)
                }
                val angle=if(s.fresh) "原始 ${DirectionMath.degrees(s.reading!!.degrees)} → ${DirectionMath.label(s.relative!!)} ${DirectionMath.degrees(s.relative!!)}"
                    else if(s.connected && s.reading!=null)"角度已过期，等待新信号" else "尚无有效角度"
                status.text=activity.getString(R.string.direction_status,s.message,angle,DirectionMath.degrees(s.config.frontDegrees),s.receivedBytes,s.accepted,s.rejected)
                raw.text=activity.getString(R.string.direction_raw,s.rawText.ifBlank {"（尚未收到完整文本行）"},s.rawHex.ifBlank {"无"})
                handler.postDelayed(this,250)
            }
        }
        handler.post(update)
    }
}
