package com.hearlens.app

import android.graphics.*
import android.icu.text.BreakIterator


import java.util.Locale
import kotlin.math.ceil


/** Per-render-thread painter shared geometrically by native phone and wireless output. */
internal class CaptionPainter {
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);color=Color.WHITE }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var cachedTurns:List<CaptionTurn> = emptyList()
    private var cachedWidth=0f;private var cachedFont=0f;private var cachedRows:List<CaptionRow> = emptyList()
    private val iterator=BreakIterator.getCharacterInstance(Locale.ROOT)
    private fun clusters(text:String):List<String> {
        iterator.setText(text);val result=ArrayList<String>();var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE) { result.add(text.substring(start,end));start=end;end=iterator.next() }
        return result
    }
    fun draw(canvas:Canvas,box:StereoGeometry.Box,turns:List<CaptionTurn>,style:CaptionStyle,bottomLift:Float=0f):Int {
        if(turns.isEmpty())return 0
        // Screenshot: max column ~52% of the camera image, bottom margin ~8%, glyph size ~2.6% of its width.
        val font=minOf(box.width*.026f,box.height*.047f)*style.sizePercent/100f
        if(font<1)return 0
        val unit=font/22f;val lineHeight=font*1.45f
        val gutter=20*unit;val padR=6*unit;val padY=5*unit;val gap=8*unit
        val column=box.width*style.widthPercent/100f
        val maxText=(column-gutter-padR).coerceAtLeast(font)
        textPaint.textSize=font
        if(turns!=cachedTurns || maxText!=cachedWidth || font!=cachedFont) {
            cachedTurns=turns;cachedWidth=maxText;cachedFont=font
            cachedRows=CaptionLayout.rows(turns,maxText,textPaint::measureText,::clusters)
        }
        val groups=mutableListOf<MutableList<CaptionRow>>()
        for(row in cachedRows)if(groups.lastOrNull()?.lastOrNull()?.turn==row.turn)groups.last().add(row) else groups.add(mutableListOf(row))
        val total=cachedRows.size*lineHeight+groups.size*2*padY+(groups.size-1).coerceAtLeast(0)*gap
        var y=(box.y+box.height*(1-style.bottomPercent/100f)-bottomLift-total).coerceAtLeast(box.y+unit*12)
        val x=box.x+(box.width-column)/2
        for(group in groups) {
            val coreW=ceil(group.maxOf { it.width }+gutter+padR).toInt()
            val coreH=ceil(group.size*lineHeight+2*padY).toInt()
            // Exact settings material: a live alpha overlay, with no scene copy, blur texture or feather pass.
            val bounds=RectF(x,y,x+coreW,y+coreH)
            SettingsPainter.panel(canvas,bounds,paint,radius=minOf(8*unit,coreH*.22f),opacity=(100-style.transparency)/89f)
            val speaker=group.first().speaker
            paint.color=if(speaker==null)Color.rgb(220,225,222) else intArrayOf(0xFF9CDEC8.toInt(),0xFFEFC393.toInt(),0xFFAFCAFA.toInt())[Math.floorMod(speaker.hashCode()-"1".hashCode(),3)]
            canvas.drawRoundRect(RectF(x+7*unit,y+9*unit,x+10*unit,y+9*unit+maxOf(12*unit,group.size*lineHeight-8*unit)),unit,unit,paint)
            textPaint.color=Color.WHITE;textPaint.setShadowLayer(unit,0f,unit,0x22000000)
            group.forEachIndexed { i,row ->
                val baseline=y+padY+i*lineHeight+(lineHeight-textPaint.fontMetrics.ascent-textPaint.fontMetrics.descent)/2
                canvas.drawText(row.text,x+gutter,baseline,textPaint)
            }
            y+=coreH+gap
        }
        return cachedRows.size
    }
}


