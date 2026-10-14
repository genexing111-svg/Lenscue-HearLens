package com.hearlens.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback

internal class GlassSettingsDialog(private val activity:Activity,private val menu:HeadsetMenu) {
    private var dialog:Dialog?=null
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun background(color:Int,radius:Int=20,border:Boolean=false)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat();if(border)setStroke(dp(1),SettingsPalette.border)
    }
    private fun label(value:String,size:Float=16f,color:Int=SettingsPalette.text)=TextView(activity).apply {
        text=ControllerHints.span(value);textSize=size;setTextColor(color);includeFontPadding=false;setLineSpacing(dp(3).toFloat(),1f)
    }
    fun dismiss() {dialog?.dismiss()}
    fun show() {
        if(dialog?.isShowing==true)return
        menu.open()
        val windowDialog=object:ComponentDialog(activity) {
            override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_ESCAPE) {menu.back();return true}
                return super.dispatchKeyEvent(event)
            }
        }
        windowDialog.onBackPressedDispatcher.addCallback(object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {menu.back()}
        })
        dialog=windowDialog;windowDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val container=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(12));background=background(SettingsPalette.panel,32,true)}
        val header=FrameLayout(activity)
        val title=label("设置",25f).apply {gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD}
        header.addView(title,FrameLayout.LayoutParams(-1,dp(52)))
        val back=SettingsCloseButton(activity).apply {contentDescription="关闭设置";setOnClickListener {menu.back()}}
        header.addView(back,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START or Gravity.CENTER_VERTICAL))
        container.addView(header)
        val scroll=ScrollView(activity).apply {isFillViewport=false;clipToPadding=false}
        val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(8))}
        scroll.addView(rows)
        container.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val hint=label("肩键 按住说话    Y 环境音    X 字幕语言",13f,SettingsPalette.muted).apply {gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(6))}
        container.addView(hint)
        val refreshers=mutableListOf<()->Unit>()
        val landscape=activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        var structure:List<Pair<String,Int>> = emptyList()
        fun render() {
            if(!menu.opened) {windowDialog.dismiss();return}
            val state=menu.state();title.text=state.title;back.showBack=menu.inMore;back.contentDescription=state.backLabel
            structure=state.rows.mapIndexed {i,row->row.first to state.controls[i].count}
            if(windowDialog.isShowing)windowDialog.window?.setLayout(minOf(dp(if(landscape)720 else 520),activity.resources.displayMetrics.widthPixels-dp(28)),
                minOf((activity.resources.displayMetrics.heightPixels*.88f).toInt(),if(menu.inMore)Int.MAX_VALUE else dp(660)))
            rows.removeAllViews()
            refreshers.clear()
            val sections=mapOf("手机显示分辨率" to "相机与显示","双眼整体大小" to "双眼校准","画面亮度" to "画面效果",
                "语音字幕宽度" to "语音字幕外观","手动文字输入" to "手动文字","无线双眼总宽度" to "无线传输","观看预设" to "工具与连接")
            state.rows.forEachIndexed {i,row->
                if(menu.inMore)sections[row.first]?.let {name->rows.addView(label(name,14f,SettingsPalette.muted).apply {setPadding(dp(10),dp(14),0,dp(8))})}
                val control=state.controls[i]
                val sideBySide=landscape && control.count>1
                val card=LinearLayout(activity).apply {orientation=if(sideBySide)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;background=background(SettingsPalette.card,18);setPadding(dp(16),dp(13),dp(16),dp(13))}
                val top=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
                val text=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                text.addView(label(row.first,if(sideBySide)15f else 16f,if(sideBySide)SettingsPalette.muted else SettingsPalette.text).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)})
                val value=label(row.second,if(control.count>1)if(landscape)18f else 19f else 13f,if(control.count>1)SettingsPalette.accent else SettingsPalette.muted).apply {gravity=Gravity.START;setPadding(0,dp(if(landscape)4 else 9),0,dp(if(landscape)0 else 3))}
                text.addView(value)
                top.addView(text,LinearLayout.LayoutParams(0,-2,1f))
                if(control.toggle!=null) {
                    var refreshing=false
                    val toggle=SettingsSwitch(activity).apply {
                        isChecked=control.toggle;contentDescription=row.first;showText=false
                        setOnCheckedChangeListener {_,_->if(!refreshing) {menu.activate(i);if(windowDialog.isShowing)render()}}
                    }
                    refreshers+= {
                        val now=menu.state()
                        value.text=ControllerHints.span(now.rows[i].second)
                        refreshing=true;toggle.isChecked=now.controls[i].toggle==true;refreshing=false
                    }
                    top.addView(toggle,LinearLayout.LayoutParams(dp(64),dp(48)))
                } else if(control.count<=1)top.addView(label("›",28f,SettingsPalette.muted))
                card.addView(top,if(sideBySide)LinearLayout.LayoutParams(dp(178),-2).apply {marginEnd=dp(14)} else LinearLayout.LayoutParams(-1,-2))
                if(control.count>1) {
                    val slider=SettingsSlider(activity).apply {
                        max=control.count-1;progress=control.position;contentDescription=row.first
                        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                            private var tracking=false
                            override fun onStartTrackingTouch(bar:SeekBar) {tracking=true}
                            override fun onProgressChanged(bar:SeekBar,p:Int,fromUser:Boolean) {
                                if(!fromUser)return
                                // Caption choices have long names: always show the full selected label.
                                value.text=ControllerHints.span(control.labels.getOrNull(p) ?: String.format(java.util.Locale.getDefault(),"第 %d 档 / %d · 松开应用",p+1,control.count))
                                if(!tracking) {menu.choose(i,p,true);value.text=ControllerHints.span(menu.state().rows[i].second)}
                            }
                            override fun onStopTrackingTouch(bar:SeekBar) {
                                tracking=false
                                menu.choose(i,bar.progress,true);val after=menu.state();value.text=ControllerHints.span(after.rows[i].second)
                                Toast.makeText(activity,after.message,Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                    refreshers+= {
                        if(!slider.isPressed) {
                            val now=menu.state();value.text=ControllerHints.span(now.rows[i].second)
                            slider.progress=now.controls[i].position
                        }
                    }
                    card.addView(slider,if(sideBySide)LinearLayout.LayoutParams(0,dp(56),1f) else LinearLayout.LayoutParams(-1,dp(48)))
                } else if(control.toggle==null)card.setOnClickListener {menu.activate(i)}
                if(control.hasDetails) {
                    card.contentDescription="${row.first}，点击名称查看设置"
                    card.setOnClickListener {menu.details(i)}
                }
                rows.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(7)})
            }
        }
        menu.onNavigate={render();scroll.scrollTo(0,0)}
        windowDialog.setContentView(container)
        val refresh=object:Runnable {
            override fun run() {if(windowDialog.isShowing) {
                val state=menu.state()
                if(structure!=state.rows.mapIndexed {i,row->row.first to state.controls[i].count})render()
                else refreshers.toList().forEach {it()}
                container.postDelayed(this,500)
            }}
        }
        windowDialog.setOnDismissListener {container.removeCallbacks(refresh);menu.onNavigate=null;dialog=null;menu.close()}
        windowDialog.setCanceledOnTouchOutside(true)
        windowDialog.window?.apply {
            val blur=Build.VERSION.SDK_INT>=31 && activity.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
            container.background=null
            // Some OEMs report cross-window blur enabled without blurring underlying app text.
            // Keep the phone sheet readable even in that case; per-eye panels retain their translucency.
            setBackgroundDrawable(background(Color.argb(250,126,121,108),32,true))
            if(Build.VERSION.SDK_INT>=31 && blur)setBackgroundBlurRadius(dp(28))
            setDimAmount(.12f)
        }
        windowDialog.show()
        val metrics=activity.resources.displayMetrics
        windowDialog.window?.setLayout(minOf(dp(if(landscape)720 else 520),metrics.widthPixels-dp(28)),minOf(dp(660),(metrics.heightPixels*.88f).toInt()))
        render()
        container.postDelayed(refresh,500)
    }
}

internal class SettingsCloseButton(context:android.content.Context):View(context) {
    var showBack=false;set(value) {field=value;invalidate()}
    init {isClickable=true;isFocusable=true}
    override fun getAccessibilityClassName():CharSequence=Button::class.java.name
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        ControllerGlyphs.close(canvas,width/2f,height/2f,minOf(width,height)*.46f,showBack)
    }
}

/** Native switch semantics with a fixed pill aspect ratio; never inherits compressed OEM track assets. */
internal class SettingsSwitch(context:android.content.Context):SwitchCompat(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {showText=false;thumbDrawable=null;trackDrawable=null;setPadding(0,0,0,0);switchMinWidth=(60*resources.displayMetrics.density).toInt()}
    override fun onDraw(canvas:Canvas) {
        val h=minOf(height*.72f,34*resources.displayMetrics.density,width/1.75f)
        val w=h*1.75f
        SettingsPainter.toggle(canvas,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),isChecked,paint)
    }
}

/** Keeps native SeekBar keyboard/accessibility semantics while drawing the requested stepped track. */
internal class SettingsSlider(context:android.content.Context):androidx.appcompat.widget.AppCompatSeekBar(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val track=RectF()
    init {setPadding((24*resources.displayMetrics.density).toInt(),0,(24*resources.displayMetrics.density).toInt(),0)}
    override fun onDraw(canvas:Canvas) {
        val trackHeight=minOf(height*.62f,32*resources.displayMetrics.density)
        track.set(paddingLeft-trackHeight/2,height/2f-trackHeight/2,width-paddingRight+trackHeight/2,height/2f+trackHeight/2)
        SettingsPainter.slider(canvas,track,progress,max+1,paint)
    }
}
