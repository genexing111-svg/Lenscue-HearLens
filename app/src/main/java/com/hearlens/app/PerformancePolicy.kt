package com.hearlens.app

import kotlin.math.roundToInt

internal object PhoneResolution {
    fun size(request: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val w = (if (request == 0) maxWidth else minOf(request, maxWidth)).coerceAtLeast(2) / 2 * 2
        return w to ((w.toDouble() * maxHeight / maxWidth / 2).roundToInt() * 2).coerceAtLeast(2)
    }
}

/** Drop to the newest frame when over budget; never accumulate a render queue.
 * Fast reduction, slow recovery avoids toggling rates with every difficult frame. */
internal class DisplayPacer {
    var target = 60; private set
    var costMs = 0.0; private set
    private var samples = 0
    private var healthy = 0
    fun reset(cap: Int) { target=cap.coerceIn(1,120);costMs=0.0;samples=0;healthy=0 }
    fun sample(ms: Double, cap: Int) {
        val limit=cap.coerceIn(1,120)
        target=minOf(target,limit)
        costMs=if(samples++==0)ms else costMs*.90+ms*.10
        if(samples<20) return
        val rates=(listOf(10,15,24,30,45,60,90,120).filter { it<limit }+limit).distinct()
        if(costMs>1000.0/target*.94) {
            val lower=rates.lastOrNull { it<target && costMs<1000.0/it*.85 } ?: minOf(10,limit)
            target=lower;healthy=0;samples=0
        } else if(costMs<1000.0/target*.62) {
            if(++healthy>=target*8) { target=rates.firstOrNull { it>target } ?: limit;healthy=0;samples=0 }
        } else healthy=0
    }
}

internal object RemoteDirection {
    fun choose(x: Float, y: Float, deadZone: Float = .55f): String? = when {
        !x.isFinite() || !y.isFinite() -> null
        kotlin.math.abs(x)<deadZone && kotlin.math.abs(y)<deadZone -> null
        kotlin.math.abs(y)>=kotlin.math.abs(x) -> if(y<0)"up" else "down"
        else -> if(x<0)"left" else "right"
    }
}
