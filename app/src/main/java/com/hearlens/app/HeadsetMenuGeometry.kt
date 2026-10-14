package com.hearlens.app

/** Shared by phone, wireless rendering and touch hit testing. Coordinates are full-canvas pixels. */
internal object HeadsetMenuGeometry {
    const val VISIBLE_ROWS = 3
    const val ROW_TOP = .18f
    const val ROW_PITCH = .21f
    const val ROW_HEIGHT = .195f

    data class Panel(val x:Float,val y:Float,val width:Float,val height:Float) {
        val centerX get()=x+width/2
        fun contains(px:Float,py:Float)=px>=x && px<=x+width && py>=y && py<=y+height
    }

    fun panels(width:Int,height:Int,sourceWidth:Int,sourceHeight:Int,s:StereoSettings):List<Panel> {
        val ew=width/2f;val eh=height.toFloat()
        val boxes=listOf(false,true).map { right ->
            StereoGeometry.fit(sourceWidth,sourceHeight,ew,eh,s.scale,if(right)s.rightSize else s.leftSize,
                s.spacing,if(right)s.rightY else s.leftY,right)
        }
        // Identical panel and font sizes in both eyes; their centers follow the calibrated image centers.
        val pw=minOf(ew*.60f,boxes.minOf { it.width }*.80f)
        val ph=minOf(eh*.64f,boxes.minOf { it.height }*.86f)
        return boxes.mapIndexed { eye,box ->
            Panel(eye*ew+box.x+(box.width-pw)/2,box.y+(box.height-ph)/2,pw,ph)
        }
    }

    fun firstRow(selected:Int,count:Int)=(selected-VISIBLE_ROWS/2).coerceIn(0,(count-VISIBLE_ROWS).coerceAtLeast(0))
    data class ControlBox(val left:Float,val top:Float,val right:Float,val bottom:Float) {
        val width get()=right-left
        val height get()=bottom-top
        fun contains(x:Float,y:Float)=x in left..right && y in top..bottom
    }
    fun slider(panel:Panel,rowTop:Float,landscape:Boolean):ControlBox {
        if(!landscape)return ControlBox(panel.x+panel.width*.12f,panel.y+panel.height*(rowTop+ROW_HEIGHT*.77f),panel.x+panel.width*.88f,panel.y+panel.height*(rowTop+ROW_HEIGHT*.94f))
        val height=minOf(panel.height*ROW_HEIGHT*.50f,panel.width*.08f)
        val center=panel.y+panel.height*(rowTop+ROW_HEIGHT*.5f)
        return ControlBox(panel.x+panel.width*.46f,center-height/2,panel.x+panel.width*.90f,center+height/2)
    }
    fun toggle(panel:Panel,rowTop:Float):ControlBox {
        val height=minOf(panel.height*ROW_HEIGHT*.58f,panel.width*.078f)
        val width=height*1.75f;val cx=panel.x+panel.width*.835f;val cy=panel.y+panel.height*(rowTop+ROW_HEIGHT*.5f)
        return ControlBox(cx-width/2,cy-height/2,cx+width/2,cy+height/2)
    }
    fun position(x:Float,box:ControlBox,count:Int):Int {
        val usable=(box.width-box.height).coerceAtLeast(1f)
        return kotlin.math.round(((x-box.left-box.height/2)/usable)*(count-1).coerceAtLeast(0)).toInt().coerceIn(0,(count-1).coerceAtLeast(0))
    }
    fun quickSlider(panel:Panel):ControlBox {
        val w=panel.width*.87f;val h=panel.height*.34f;val x=panel.x+panel.width-w;val y=panel.y+panel.height-h
        return ControlBox(x+w*.075f,y+h*.56f,x+w*.925f,y+h*.72f)
    }
}
