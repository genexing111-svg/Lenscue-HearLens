package com.hearlens.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject

/** Native, scrollable phone controls; a headset/PC is never required to change them. */
internal class PhoneSettingsDialog(private val activity: Activity, private val engine: LocalStereoEngine, private val camera: UsbVideoServer, private val wifi: WifiVideoServer, private val remoteSetup:()->Unit,private val captionSetup:()->Unit={},private val menu:HeadsetMenu,private val perceptionSetup:()->Unit={}) {
    private val glass=GlassSettingsDialog(activity,menu)
    fun dismiss()=glass.dismiss()
    fun auxiliary(name:String) {
        glass.dismiss()
        when(name) {
            "text"->layout("手动文字",listOf(Field("caption","手动输入（最多 120 字）")))
            "connection"->connection()
            "presets"->presets()
            "remote"->remoteSetup()
            "speechStatus"->captionSetup()
            "speechSetup"->activity.startActivity(android.content.Intent(activity,SpeechActivity::class.java))
            "perception"->perceptionSetup()
        }
    }
    private data class Field(val key:String,val label:String,val choices:List<Pair<String,String>> = emptyList())
    private fun options(vararg values:Int)=values.map { it.toString() to it.toString() }
    fun show() {
        glass.show()
    }
    private fun layout(title:String,fields:List<Field>) = form(title,LocalStereoEngine.encode(engine.settings),fields) { engine.configure(LocalStereoEngine.decode(it,engine.settings)) }
    private fun form(title:String,initial:JSONObject,fields:List<Field>,apply:(JSONObject)->Unit) {
        val density=activity.resources.displayMetrics.density
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding((20*density).toInt(),0,(20*density).toInt(),0) }
        val readers=mutableMapOf<String,()->Any>()
        fields.forEach { f ->
            panel.addView(TextView(activity).apply { text=f.label;textSize=16f;setPadding(0,(14*density).toInt(),0,0) })
            val value=initial.get(f.key)
            when {
                value is Boolean -> {
                    val check=CheckBox(activity).apply { text="开启";isChecked=value };panel.addView(check);readers[f.key]={check.isChecked}
                }
                f.choices.isNotEmpty()-> {
                    val spinner=Spinner(activity).apply { adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,f.choices.map { it.second });setSelection(f.choices.indexOfFirst { it.first==value.toString() }.coerceAtLeast(0)) }
                    panel.addView(spinner);readers[f.key]={ val selected=f.choices[spinner.selectedItemPosition].first;if(value is Number)selected.toInt() else selected }
                }
                else -> {
                    val input=EditText(activity).apply {
                        setText(value.toString());textSize=17f;contentDescription=f.label
                        inputType=if(value is Number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        filters=arrayOf(InputFilter.LengthFilter(if(f.key=="caption")120 else 32));maxLines=if(f.key=="caption")4 else 1
                    }
                    panel.addView(input,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
                    readers[f.key]={ val t=input.text.toString();when { f.key=="zoomRatio"->t.toDouble();value is Number->t.toInt();else->t } }
                }
            }
        }
        val scroll=ScrollView(activity).apply { addView(panel) }
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("应用并保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { val json=JSONObject();readers.forEach { (k,v)->json.put(k,v()) };apply(json);dialog.dismiss();Toast.makeText(activity,"已应用",Toast.LENGTH_SHORT).show() }
            catch(e:Exception) { Toast.makeText(activity,"请检查数值范围：${e.message ?: "输入无效"}",Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
    private fun presets() {
        AlertDialog.Builder(activity).setTitle("观看预设").setItems(arrayOf("高清流畅：1280 采集 / 自动帧率 / 无线 960", "最高采集清晰度：1920 / 自动帧率 / 无线 1280", "大字舒适：85% 画面 / 大字号", "恢复默认画面设置")) { _,i ->
            val current=engine.settings
            when(i) {
                0,1 -> {
                    engine.configure(current.copy(outputWidth=if(i==0)960 else 1280,wirelessFps=15,wirelessQuality=if(i==0)60 else 75))
                    runCatching { camera.applyPhoneSettings(camera.currentSettings().copy(fps=0,maxWidth=if(i==0)1280 else 1920)) }
                }
                2->engine.configure(current.copy(scale=85,fontSize=44,background=82))
                3->engine.configure(StereoSettings())
            }
            Toast.makeText(activity,"预设已应用",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("取消",null).show()
    }
    fun connection() {
        val address=wifi.url
        AlertDialog.Builder(activity).setTitle("电脑无线观看").setMessage(if(address.isBlank()) wifi.error ?: "请开启无线共享并连接 Wi-Fi 或热点。" else "电脑连接同一 Wi-Fi / 手机热点，在浏览器打开完整链接：\n\n$address\n\n手机本地合成，电脑可输入字幕。链接含访问密钥，请仅分享给需要观看的人；使用可信局域网。无需 USB 调试或数据线。")
            .setPositiveButton("关闭",null).apply { if(address.isNotBlank())setNeutralButton("复制完整链接") { _,_->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("听见视界无线观看",address))
            } }.show()
    }
}
