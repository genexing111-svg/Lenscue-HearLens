package com.hearlens.app

import android.graphics.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.graphics.PathParser

/** Original SVG button silhouettes, plus neutral diagrams for controls absent from the asset pack. */
internal object ControllerGlyphs {
    private val paths=ControllerButtonPaths.paths.mapValues { (_,data)->
        requireNotNull(PathParser.createPathFromPathData(data)).apply {fillType=Path.FillType.EVEN_ODD}
    }
    fun draw(c:Canvas,key:String,r:RectF,color:Int=SettingsPalette.text) {
        val saved=c.save();c.translate(r.left,r.top);c.scale(r.width()/32f,r.height()/32f)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;strokeWidth=1.9f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        val path=paths[key]
        if(path!=null)c.drawPath(path,p) else {
            p.style=Paint.Style.STROKE
            when(key) {
                "shoulder"->{
                    c.drawPath(Path().apply {moveTo(4f,23f);lineTo(7f,12f);quadTo(8f,8f,12f,8f);lineTo(24f,8f);quadTo(28f,8f,28f,12f);lineTo(28f,23f);close()},p)
                    c.drawLine(10f,12f,23f,12f,p)
                }
                "@"->{
                    c.drawCircle(16f,16f,13f,p)
                    c.drawOval(RectF(11f,11f,20f,22f),p)
                    c.drawPath(Path().apply {moveTo(20f,11f);lineTo(20f,21f);quadTo(25f,23f,25f,16f);cubicTo(25f,3f,6f,6f,7f,18f);quadTo(8f,27f,19f,25f)},p)
                }
                else->{
                    c.drawOval(RectF(10f,20f,22f,26f),p);c.drawLine(16f,12f,16f,22f,p);c.drawCircle(16f,10f,4f,p)
                    if(key!="vertical") {
                        c.drawPath(Path().apply {moveTo(7f,12f);lineTo(3f,16f);lineTo(7f,20f);moveTo(25f,12f);lineTo(29f,16f);lineTo(25f,20f)},p)
                    }
                    if(key!="horizontal") {
                        c.drawPath(Path().apply {moveTo(12f,5f);lineTo(16f,1f);lineTo(20f,5f);moveTo(12f,27f);lineTo(16f,31f);lineTo(20f,27f)},p)
                    }
                }
            }
        }
        c.restoreToCount(saved)
    }

    /** Draw geometry around the actual circle center instead of a font-dependent × baseline. */
    fun close(c:Canvas,cx:Float,cy:Float,radius:Float,back:Boolean=false) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.argb(66,244,241,223)}
        c.drawCircle(cx,cy,radius,p)
        p.color=SettingsPalette.text;p.style=Paint.Style.STROKE;p.strokeWidth=radius*.115f;p.strokeCap=Paint.Cap.ROUND
        val d=radius*.30f
        if(back)c.drawPath(Path().apply {moveTo(cx+d*.5f,cy-d*1.25f);lineTo(cx-d*.65f,cy);lineTo(cx+d*.5f,cy+d*1.25f)},p)
        else {c.drawLine(cx-d,cy-d,cx+d,cy+d,p);c.drawLine(cx-d,cy+d,cx+d,cy-d,p)}
    }
}

/** Keep the underlying button names for accessibility; only their visual presentation is replaced. */
internal object ControllerHints {
    private val pattern=Regex("摇杆上下|摇杆左右|摇杆|肩键|@|(?<![A-Za-z0-9_-])[AXBY](?![A-Za-z0-9_-])")
    private fun key(value:String)=when(value) {"摇杆上下"->"vertical";"摇杆左右"->"horizontal";"摇杆"->"stick";"肩键"->"shoulder";else->value}
    private data class Piece(val text:String,val glyph:String?=null)
    private fun pieces(value:String):List<Piece> {
        val out=mutableListOf<Piece>();var start=0
        for(m in pattern.findAll(value)) {
            if(m.range.first>start)out+=Piece(value.substring(start,m.range.first))
            out+=Piece(m.value,key(m.value));start=m.range.last+1
        }
        if(start<value.length)out+=Piece(value.substring(start))
        return out
    }
    fun span(value:String):CharSequence {
        val result=SpannableStringBuilder(value)
        for(m in pattern.findAll(value))result.setSpan(GlyphSpan(key(m.value)),m.range.first,m.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }
    private class GlyphSpan(private val key:String):ReplacementSpan() {
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            val size=paint.textSize*1.5f;val base=paint.fontMetricsInt
            fm?.apply {ascent=minOf(base.ascent,((base.ascent+base.descent-size)/2).toInt());descent=maxOf(base.descent,ascent+size.toInt());top=ascent;bottom=descent}
            return (size+paint.textSize*.15f).toInt()
        }
        override fun draw(c:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,p:Paint) {
            val size=p.textSize*1.5f;val cy=y+(p.fontMetrics.ascent+p.fontMetrics.descent)/2
            ControllerGlyphs.draw(c,key,RectF(x,cy-size/2,x+size,cy+size/2),p.color)
        }
    }
    fun draw(c:Canvas,p:Paint,value:String,r:RectF,size:Float,color:Int=SettingsPalette.text,bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER) {
        if(value.isBlank() || r.width()<=0 || r.height()<=0)return
        val parts=pieces(value).toMutableList()
        p.shader=null;p.style=Paint.Style.FILL;p.typeface=if(bold)Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.DEFAULT
        p.textSize=size
        val naturalHeight=maxOf(p.fontMetrics.descent-p.fontMetrics.ascent,if(parts.any {it.glyph!=null})size*1.5f else 0f)
        if(naturalHeight>r.height())p.textSize*=r.height()/naturalHeight
        fun width()=parts.sumOf {if(it.glyph==null)p.measureText(it.text).toDouble() else (p.textSize*1.65f).toDouble()}.toFloat()
        val total=width()
        if(total>r.width())p.textSize*=maxOf(.78f,r.width()/total)
        while(width()>r.width() && parts.isNotEmpty()) {
            val last=parts.removeAt(parts.lastIndex)
            if(last.glyph==null && last.text.length>2)parts+=Piece(last.text.removeSuffix("…").dropLast(1)+"…")
        }
        var x=when(align) {Paint.Align.LEFT->r.left;Paint.Align.RIGHT->r.right-width();else->r.centerX()-width()/2}
        val baseline=r.centerY()-(p.fontMetrics.ascent+p.fontMetrics.descent)/2
        p.color=color;p.textAlign=Paint.Align.LEFT
        for(part in parts) {
            if(part.glyph==null) {c.drawText(part.text,x,baseline,p);x+=p.measureText(part.text)}
            else {val s=p.textSize*1.5f;ControllerGlyphs.draw(c,part.glyph,RectF(x,r.centerY()-s/2,x+s,r.centerY()+s/2),color);x+=p.textSize*1.65f}
        }
    }
}
