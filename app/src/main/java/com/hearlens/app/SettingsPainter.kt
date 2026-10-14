package com.hearlens.app

import android.graphics.*
import kotlin.math.*

internal object SettingsPalette {
    val panel=Color.argb(226,126,121,108)
    val card=Color.argb(126,65,64,57)
    val border=Color.argb(94,236,232,215)
    val text=Color.rgb(250,249,244)
    val muted=Color.rgb(215,213,203)
    val focus=Color.argb(155,178,174,151)
    val accent=Color.rgb(154,194,255)
    val active=accent
}

/** The same strokes, track and typography are used at native and wireless resolution. */
internal object SettingsPainter {
    fun panel(c:Canvas,r:RectF,p:Paint,radius:Float=r.width()*.055f,opacity:Float=1f) {
        fun tinted(color:Int)=Color.argb((Color.alpha(color)*opacity).roundToInt().coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
        p.style=Paint.Style.FILL;p.color=tinted(SettingsPalette.panel)
        p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,intArrayOf(tinted(SettingsPalette.panel),tinted(Color.argb(233,99,97,86))),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(r,radius,radius,p);p.shader=null
        p.color=tinted(SettingsPalette.border);p.style=Paint.Style.STROKE;p.strokeWidth=maxOf(1f,r.width()*.0015f)
        c.drawRoundRect(r,radius,radius,p);p.style=Paint.Style.FILL
    }
    fun text(c:Canvas,p:Paint,value:String,x:Float,y:Float,size:Float,maxWidth:Float,color:Int=SettingsPalette.text,bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER) {
        p.shader=null;p.style=Paint.Style.FILL;p.textAlign=align;p.color=color
        p.typeface=if(bold)Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.DEFAULT;p.textSize=size
        if(p.measureText(value)>maxWidth)p.textSize=maxOf(size*.80f,size*maxWidth/p.measureText(value))
        var label=value
        if(p.measureText(label)>maxWidth) {while(label.isNotEmpty() && p.measureText("$label…")>maxWidth)label=label.dropLast(1);label+="…"}
        c.drawText(label,x,y,p)
    }
    fun slider(c:Canvas,r:RectF,position:Int,count:Int,p:Paint) {
        p.color=Color.argb(180,68,69,64);p.style=Paint.Style.FILL
        c.drawRoundRect(r,r.height()/2,r.height()/2,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=maxOf(1f,r.height()*.02f);p.color=Color.argb(65,228,230,215)
        c.drawRoundRect(r,r.height()/2,r.height()/2,p);p.style=Paint.Style.FILL
        val pad=r.height()*.5f;val length=r.width()-2*pad;val denominator=(count-1).coerceAtLeast(1)
        val selected=r.left+pad+length*position.coerceIn(0,denominator)/denominator
        c.save();c.clipPath(Path().apply {addRoundRect(r,r.height()/2,r.height()/2,Path.Direction.CW)})
        p.color=SettingsPalette.active;c.drawRect(r.left,r.top,selected,r.bottom,p);c.restore()
        if(count in 2..16)for(i in 0 until count) {p.color=Color.argb(135,208,211,198);c.drawCircle(r.left+pad+length*i/denominator,r.centerY(),r.height()*.075f,p)}
        p.color=Color.WHITE;c.drawCircle(r.left+pad+length*position.coerceIn(0,denominator)/denominator,r.centerY(),r.height()*.50f,p)
    }
    fun toggle(c:Canvas,r:RectF,on:Boolean,p:Paint) {
        animatedToggle(c,r,if(on)1f else 0f,p)
    }
    fun animatedToggle(c:Canvas,r:RectF,position:Float,p:Paint) {
        val t=position.coerceIn(0f,1f);val off=Color.argb(210,74,73,63);val on=SettingsPalette.active
        fun channel(a:Int,b:Int)=(a+(b-a)*t).roundToInt()
        p.style=Paint.Style.FILL;p.shader=null
        p.color=Color.argb(channel(Color.alpha(off),Color.alpha(on)),channel(Color.red(off),Color.red(on)),channel(Color.green(off),Color.green(on)),channel(Color.blue(off),Color.blue(on)))
        c.drawRoundRect(r,r.height()/2,r.height()/2,p)
        p.color=Color.WHITE;c.drawCircle(r.left+r.height()/2+(r.width()-r.height())*t,r.centerY(),r.height()*.43f,p)
    }
}

internal object HeadsetMenuPainter {
    fun draw(c:Canvas,panels:List<HeadsetMenuGeometry.Panel>,state:HeadsetMenuState) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        for(panel in panels) {
            val landscape=c.width>c.height
            val w=panel.width;val h=panel.height;val x=panel.x;val y=panel.y
            SettingsPainter.panel(c,RectF(x,y,x+w,y+h),p)
            ControllerGlyphs.close(c,x+w*.09f,y+h*.09f,h*.041f,state.backLabel=="返回")
            fun text(v:String,left:Float,top:Float,right:Float,bottom:Float,size:Float=.035f,color:Int=SettingsPalette.text,bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER)=
                ControllerHints.draw(c,p,v,RectF(x+w*left,y+h*top,x+w*right,y+h*bottom),w*size,color,bold,align)
            text(state.title,.23f,.043f,.77f,.136f,.05f,bold=true)
            val start=HeadsetMenuGeometry.firstRow(state.selected,state.rows.size)
            for(i in start until minOf(start+HeadsetMenuGeometry.VISIBLE_ROWS,state.rows.size)) {
                val top=HeadsetMenuGeometry.ROW_TOP+(i-start)*HeadsetMenuGeometry.ROW_PITCH
                val r=RectF(x+w*.055f,y+h*top,x+w*.945f,y+h*(top+HeadsetMenuGeometry.ROW_HEIGHT))
                p.color=if(i==state.selected)SettingsPalette.focus else SettingsPalette.card;c.drawRoundRect(r,w*.025f,w*.025f,p)
                val control=state.controls.getOrNull(i) ?: SettingControl()
                val row=state.rows[i]
                fun rowText(value:String,a:Float,b:Float,size:Float,color:Int=SettingsPalette.text,bold:Boolean=false,left:Float=.11f,right:Float=.89f,align:Paint.Align=Paint.Align.CENTER)=
                    text(value,left,top+HeadsetMenuGeometry.ROW_HEIGHT*a,right,top+HeadsetMenuGeometry.ROW_HEIGHT*b,size,color,bold,align)
                if(control.toggle!=null) {
                    rowText(row.first,.18f,.47f,.038f,bold=true,right=.71f,align=Paint.Align.LEFT)
                    rowText(row.second,.53f,.82f,.030f,SettingsPalette.muted,right=.71f,align=Paint.Align.LEFT)
                    val track=HeadsetMenuGeometry.toggle(panel,top)
                    SettingsPainter.toggle(c,RectF(track.left,track.top,track.right,track.bottom),control.toggle,p)
                    if(control.hasDetails)text("›",.685f,top+.04f,.75f,top+.13f,.040f,SettingsPalette.muted)
                } else if(control.count>1) {
                    if(landscape) {
                        rowText(row.first,.19f,.43f,.032f,SettingsPalette.muted,left=.11f,right=.415f,align=Paint.Align.LEFT)
                        rowText(row.second+(if(control.pending)" · 待确认" else ""),.48f,.81f,.038f,SettingsPalette.accent,true,left=.11f,right=.415f,align=Paint.Align.LEFT)
                    } else {
                        rowText(row.first,.06f,.30f,.028f,SettingsPalette.muted)
                        rowText(row.second+(if(control.pending)" · 待确认" else ""),.35f,.66f,.040f,bold=true)
                    }
                    val track=HeadsetMenuGeometry.slider(panel,top,landscape)
                    SettingsPainter.slider(c,RectF(track.left,track.top,track.right,track.bottom),control.position,control.count,p)
                } else {
                    rowText(row.first,.12f,.46f,.040f,bold=true,right=.83f)
                    rowText(row.second,.52f,.86f,.028f,SettingsPalette.muted,right=.83f)
                    if(state.controls.isNotEmpty())text("›",.85f,top+.04f,.94f,top+.14f,.05f)
                }
            }
            if(state.controls.isEmpty())text(state.message,.065f,.846f,.935f,.959f,.027f,SettingsPalette.muted)
            else {
                text(state.message,.065f,.803f,.935f,.838f,.025f,SettingsPalette.muted)
                text("摇杆上下 选择    摇杆左右 调整",.065f,.851f,.935f,.910f,.030f,SettingsPalette.muted)
                text("X 字幕语言    A 确认    B ${state.backLabel}",.065f,.925f,.935f,.984f,.030f,SettingsPalette.muted)
            }
        }
    }
}

internal data class QuickOverlay(val selected:Int,val labels:List<String>,val message:String="")
internal object QuickOverlayPainter {
    fun draw(c:Canvas,panels:List<HeadsetMenuGeometry.Panel>,state:QuickOverlay) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        for(panel in panels) {
            val w=panel.width*.87f;val h=panel.height*.34f
            val x=panel.x+panel.width-w;val y=panel.y+panel.height-h
            SettingsPainter.panel(c,RectF(x,y,x+w,y+h),p,w*.085f)
            ControllerHints.draw(c,p,state.message.ifBlank {"字幕语言"},RectF(x+w*.06f,y+h*.08f,x+w*.94f,y+h*.24f),w*.035f,SettingsPalette.muted)
            ControllerHints.draw(c,p,state.labels.getOrElse(state.selected){"选择语言"},RectF(x+w*.06f,y+h*.29f,x+w*.94f,y+h*.48f),w*.048f,SettingsPalette.accent,true)
            val track=HeadsetMenuGeometry.quickSlider(panel)
            SettingsPainter.slider(c,RectF(track.left,track.top,track.right,track.bottom),state.selected,state.labels.size,p)
            ControllerHints.draw(c,p,"摇杆左右 调整 · A 确定 · B 退出",RectF(x+w*.06f,y+h*.80f,x+w*.94f,y+h*.96f),w*.031f)
        }
    }
}
