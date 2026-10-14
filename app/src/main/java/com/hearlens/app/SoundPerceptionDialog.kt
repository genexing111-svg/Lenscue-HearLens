package com.hearlens.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog

internal class SoundPerceptionDialog(private val activity:Activity,private val session:EnvironmentSoundSession,private val direction:UsbDirectionController,
    private val start:()->Unit,private val stop:()->Unit,private val details:()->Unit,private val requested:()->Boolean) {
    private val handler=Handler(Looper.getMainLooper())
    private var dialog:AlertDialog?=null
    fun dismiss() {handler.removeCallbacksAndMessages(null);dialog?.dismiss();dialog=null}
    fun show() {
        dismiss()
        val dp=activity.resources.displayMetrics.density
        val panel=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding((20*dp).toInt(),0,(20*dp).toInt(),0)}
        fun text(value:String)=TextView(activity).apply {text=value;textSize=16f;setPadding(0,(12*dp).toInt(),0,(6*dp).toInt());panel.addView(this)}
        fun button(value:String,action:()->Unit)=Button(activity).apply {text=value;setOnClickListener {action()};panel.addView(this)}
        text("开关同时控制声音类型识别与方位显示。进入应用时先准备小板授权，关闭开关仍保留前台 USB 连接。首次连接或拔插后系统可能需要确认。识别在手机本地运行；只有另行开启云端语音字幕时才按原设置上传语音。")
        val status=text("").apply {setTextIsSelectable(true)}
        var refreshing=false
        val switchRow=LinearLayout(activity).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        switchRow.addView(TextView(activity).apply {text="环境音感知";textSize=18f},LinearLayout.LayoutParams(0,-2,1f))
        val perceptionSwitch=SettingsSwitch(activity).apply {
            contentDescription="环境音感知开关";isChecked=requested()
            setOnCheckedChangeListener {_,checked->if(!refreshing) {if(checked)start() else stop()}}
        }
        switchRow.addView(perceptionSwitch,LinearLayout.LayoutParams((64*dp).toInt(),(48*dp).toInt()));panel.addView(switchRow)
        val directionStatus=text("").apply {setTextIsSelectable(true)}
        button("重试 USB 授权",direction::retryPermission)
        button("当前方向设为正前方") {
            Toast.makeText(activity,if(direction.calibrateFront())"正前方已保存" else "尚无有效角度，请先连接小板并在正前方发声",Toast.LENGTH_LONG).show()
        }
        button("一键左右互换") {direction.swapLeftRight();Toast.makeText(activity,"左右已互换，前方基准保留",Toast.LENGTH_SHORT).show()}
        button("小板连接与详细校准") {dismiss();details()}
        panel.addView(CheckBox(activity).apply {setText(R.string.perception_show_glow);isChecked=direction.snapshot().config.showOverlay
            setOnCheckedChangeListener {_,checked->direction.configure(direction.snapshot().config.copy(showOverlay=checked))}})
        text("声音名称语言（不改变模型判定）")
        val locales=listOf("zh-Hans","zh-Hant","en")
        val language=Spinner(activity).apply {adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("简体中文","繁體中文","English"));setSelection(locales.indexOf(session.settings.language));panel.addView(this)}
        language.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {session.configure(session.settings.copy(language=locales[position]))}
        }
        fun slider(title:String,min:Int,max:Int,value:Int,apply:(Int)->Unit) {
            val label=text("$title：$value")
            panel.addView(SeekBar(activity).apply {
                contentDescription=title;this.max=max-min;progress=value-min
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean) {label.text=activity.getString(R.string.environment_slider_value,title,p+min);if(user)apply(p+min)}
                    override fun onStartTrackingTouch(bar:SeekBar) {}
                    override fun onStopTrackingTouch(bar:SeekBar) {}
                })
            })
        }
        slider("光晕亮度 %",0,100,session.settings.intensity) {session.configure(session.settings.copy(intensity=it))}
        slider("向内扩散（参考像素）",20,160,session.settings.depth) {session.configure(session.settings.copy(depth=it))}
        slider("方向范围 FWHM（度）",20,160,session.settings.spread) {session.configure(session.settings.copy(spread=it))}
        slider("标签蒙版透明度 %",0,100,session.settings.transparency) {session.configure(session.settings.copy(transparency=it))}
        text("默认 85% / 80 / 60°；蒙版透明度 80%。透明度只改变灰色底色，文字、磨砂和细边保留。")
        panel.addView(CheckBox(activity).apply {text="按同期 USB 信号估计方位";isChecked=session.settings.associateDirection;setOnCheckedChangeListener {_,checked->session.configure(session.settings.copy(associateDirection=checked))}})
        text("无声音名称的光晕来自小板实时角度；名称与方位按同期数据估计关联，无法保证来自同一声源。缺少匹配角度时名称居中。上沿是前方，下沿是后方；不表示高度。")
        panel.addView(CheckBox(activity).apply {text="减少动态效果（静态显示 2.6 秒）";isChecked=session.settings.reducedMotion;setOnCheckedChangeListener {_,checked->session.configure(session.settings.copy(reducedMotion=checked))}})
        val info=text("")
        val shown=AlertDialog.Builder(activity).setTitle("环境音感知").setView(ScrollView(activity).apply {addView(panel)}).setPositiveButton("关闭",null).create()
        dialog=shown;shown.setOnDismissListener {handler.removeCallbacksAndMessages(null);if(dialog===shown)dialog=null};shown.show()
        handler.post(object:Runnable {override fun run() {
            if(dialog!==shown)return
            refreshing=true;perceptionSwitch.isChecked=requested();refreshing=false
            val diagnostic=session.diagnostic();val decision=diagnostic.optJSONObject("decision")
            val event=decision?.optJSONObject("event")
            val name=event?.optInt("index",-1)?.takeIf {it in 0..520}?.let {session.labels.name(it,session.settings.language)} ?: "—"
            status.text=activity.getString(R.string.environment_status,session.status,name,decision?.optString("kind") ?: "未开始")
            val d=direction.snapshot()
            directionStatus.text=activity.getString(R.string.perception_direction_status,d.message,
                if(d.fresh)DirectionMath.degrees(d.relative!!) else "暂无有效方位",DirectionMath.degrees(d.config.frontDegrees),if(d.config.clockwise)"顺时针" else "逆时针")
            info.text=activity.getString(R.string.environment_diagnostics,diagnostic.optLong("processedWindows"),diagnostic.optDouble("inferenceMs"),diagnostic.optLong("replacedPendingWindows"),diagnostic.optString("audioRoute"))
            handler.postDelayed(this,500)
        }})
    }
}
