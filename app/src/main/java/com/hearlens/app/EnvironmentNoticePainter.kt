package com.hearlens.app

import android.graphics.*
import androidx.core.graphics.withSave

/** In-app notification card, using the same warm panel, border and blue switch as settings. */
internal object EnvironmentNoticePainter {
    fun draw(canvas:Canvas,boxes:List<StereoGeometry.Box>,notice:EnvironmentNotice,now:Long) {
        val frame=notice.frame(now)
        if(!frame.active || frame.reveal<=0 || boxes.isEmpty())return
        val unit=minOf(boxes.minOf {it.width}/736f,boxes.minOf {it.height}/430f).coerceAtLeast(.05f)
        val w=400*unit;val h=68*unit;val p=Paint(Paint.ANTI_ALIAS_FLAG)
        for(box in boxes)canvas.withSave {
            clipRect(box.x,box.y,box.x+box.width,box.y+box.height)
            val x=box.x+(box.width-w)/2
            val y=box.y+16*unit-(h+16*unit)*(1-frame.reveal)
            val bounds=RectF(x,y,x+w,y+h)
            val layer=saveLayerAlpha(bounds,(255*frame.reveal).toInt())
            SettingsPainter.panel(this,bounds,p,18*unit)
            val status=notice.message
            SettingsPainter.text(this,p,"环境音感知",x+24*unit,y+(if(status.isBlank())41 else 30)*unit,22*unit,280*unit,SettingsPalette.text,true,Paint.Align.LEFT)
            if(status.isNotBlank())SettingsPainter.text(this,p,status,x+24*unit,y+52*unit,13*unit,280*unit,SettingsPalette.muted,align=Paint.Align.LEFT)
            SettingsPainter.animatedToggle(this,RectF(x+w-78*unit,y+20*unit,x+w-24*unit,y+48*unit),frame.switchPosition,p)
            restoreToCount(layer)
        }
    }
}
