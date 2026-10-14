package com.hearlens.app

import android.graphics.*
import android.icu.text.BreakIterator
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave
import java.util.Locale
import kotlin.math.*

/** SVG geometry from the supplied Lucide speech icon; license and original SVG are in docs/ptt-reference. */
internal class PttPainter {
    private val paths=listOf("M8.8 20v-4.1l1.9.2a2.3 2.3 0 0 0 2.164-2.1V8.3A5.37 5.37 0 0 0 2 8.25c0 2.8.656 3.054 1 4.55a5.77 5.77 0 0 1 .029 2.758L2 20",
        "M19.8 17.8a7.5 7.5 0 0 0 .003-10.603","M17 15a3.5 3.5 0 0 0-.025-4.975").map {PathParser.createPathFromPathData(it)!!}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);color=Color.WHITE}
    private val iterator=BreakIterator.getCharacterInstance(Locale.ROOT)
    private var cachedText="";private var cachedWidth=-1f;private var cachedFont=-1f;private var cachedLines=listOf("")
    private fun lines(value:String,width:Float):List<String> {
        if(value==cachedText && width==cachedWidth && text.textSize==cachedFont)return cachedLines
        val lines=mutableListOf<String>();var line="";iterator.setText(value);var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE) {
            val c=value.substring(start,end)
            if(c=="\n") {lines+=line;line=""} else {
                if(line.isNotEmpty() && text.measureText(line+c)>width){lines+=line;line=""};line+=c
            }
            start=end;end=iterator.next()
        }
        if(line.isNotEmpty())lines+=line
        cachedText=value;cachedWidth=width;cachedFont=text.textSize;cachedLines=lines.ifEmpty {listOf("")}
        return cachedLines
    }
    fun draw(canvas:Canvas,boxes:List<StereoGeometry.Box>,state:PttState,now:Long) {
        if(state.phase==PttPhase.IDLE || boxes.isEmpty())return
        val unit=minOf(boxes.minOf {it.width}/736f,boxes.minOf {it.height}/430f).coerceAtLeast(.05f)
        val font=23*unit;text.textSize=font
        val display=when {
            state.phase==PttPhase.ERROR->state.message
            state.phase==PttPhase.WAIT_RELEASE->state.text+"\n"+state.message
            state.phase in listOf(PttPhase.PLAYING,PttPhase.DONE) && state.output.isNotBlank()->state.output
            state.text.isNotBlank()->state.text
            else->state.message.ifBlank {"请说话…"}
        }
        val maxWidth=boxes.minOf {it.width}*.90f;val sides=166*unit
        val maxText=(maxWidth-sides).coerceAtLeast(font)
        val all=lines(display,maxText)
        val maxRows=((boxes.minOf {it.height}*.66f-32*unit)/(font*1.35f)).toInt().coerceIn(1,6)
        val rows=all.takeLast(maxRows).toMutableList()
        if(all.size>maxRows)rows[0]="…"+rows[0].drop(1)
        val w=(rows.maxOf {text.measureText(it)}+sides).coerceIn(260*unit,maxWidth)
        val h=maxOf(74*unit,rows.size*font*1.35f+32*unit)
        val gain=if(now-state.levelAt>250)0f else sqrt(state.level.coerceIn(0,100)/100f)
        for(box in boxes)canvas.withSave {
            // A top-anchored island: longer text expands downward without moving the upper edge.
            val x=box.x+(box.width-w)/2;val y=box.y+minOf(16*unit,(box.height-h).coerceAtLeast(0f))
            val bounds=RectF(x,y,x+w,y+h)
            SettingsPainter.panel(this,bounds,paint,h/2) // Identical alpha-composited material to live captions.
            withSave {
                translate(x+22*unit,y+h/2-18*unit);scale(1.5f*unit,1.5f*unit)
                paint.alpha=255;paint.shader=LinearGradient(0f,0f,24f,24f,0xff82b7ff.toInt(),0xffb28aff.toInt(),Shader.TileMode.CLAMP)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
                paths.forEach {drawPath(it,paint)};paint.shader=null;paint.style=Paint.Style.FILL
            }
            val lineHeight=font*1.35f
            rows.forEachIndexed {i,row->
                val baseline=y+(h-rows.size*lineHeight)/2+i*lineHeight+(lineHeight-text.ascent()-text.descent())/2
                drawText(row,x+78*unit,baseline,text)
            }
            val bx=x+w-70*unit;val cy=y+h/2
            paint.alpha=255;paint.shader=LinearGradient(bx,cy-20*unit,bx+48*unit,cy+20*unit,0xff82b7ff.toInt(),0xffb28aff.toInt(),Shader.TileMode.CLAMP)
            val weights=floatArrayOf(.25f,.45f,.72f,1f,.65f,.88f,.58f)
            for(i in weights.indices) {
                val height=(3.5f+gain*34*weights[i])*unit;val left=bx+i*7*unit
                drawRoundRect(RectF(left,cy-height/2,left+3.5f*unit,cy+height/2),2*unit,2*unit,paint)
            }
            paint.shader=null
        }
    }
}
