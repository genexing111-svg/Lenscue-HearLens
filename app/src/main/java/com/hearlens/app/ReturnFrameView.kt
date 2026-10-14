package com.hearlens.app

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.concurrent.Executors

/** Displays pixels composed on the PC. Both compressed and decoded queues keep only the newest frame. */
@SuppressLint("ViewConstructor") // Created in code with session-validation callbacks, never from XML.
class ReturnFrameView(
    context: Context,
    private val isCurrent: (UsbVideoServer.ReturnFrame) -> Boolean,
    private val onDisplayed: (UsbVideoServer.ReturnFrame) -> Unit,
) : View(context) {
    private data class Decoded(val frame: UsbVideoServer.ReturnFrame, val bitmap: Bitmap, val epoch: Long)
    private val lock = Any()
    private val decoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HearLens-return-decoder").apply { isDaemon = true }
    }
    private var pending: UsbVideoServer.ReturnFrame? = null
    private var ready: Decoded? = null
    private var running = false
    private var posted = false
    private var closed = false
    private var epoch = 0L
    private var shown: Decoded? = null
    private var acknowledged: Decoded? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var waitingMessage = "等待电脑回传"

    init {
        setBackgroundColor(Color.BLACK)
        contentDescription = "电脑生成的左右眼画面"
        isClickable = true
    }

    fun submit(frame: UsbVideoServer.ReturnFrame) {
        if (!isCurrent(frame)) return
        synchronized(lock) {
            if (closed) return
            pending = frame
            if (!running) {
                running = true
                decoder.execute(::decodeLoop)
            }
        }
    }

    private fun decodeLoop() {
        while (true) {
            val work = synchronized(lock) {
                val next = pending
                pending = null
                if (closed || next == null) { running = false; return }
                next to epoch
            }
            if (!isCurrent(work.first)) continue
            val bitmap = try {
                BitmapFactory.decodeByteArray(work.first.jpeg, 0, work.first.jpeg.size)
            } catch (_: Exception) { null } ?: continue
            synchronized(lock) {
                if (closed || work.second != epoch || !isCurrent(work.first)) {
                    bitmap.recycle()
                } else {
                    ready?.bitmap?.recycle() // This bitmap has never been submitted to Canvas.
                    ready = Decoded(work.first, bitmap, work.second)
                    if (!posted) { posted = true; post(::presentNewest) }
                }
            }
        }
    }

    private fun presentNewest() {
        val next = synchronized(lock) {
            posted = false
            ready.also { ready = null }
        } ?: return
        if (next.epoch != synchronized(lock) { epoch } || !isCurrent(next.frame)) {
            next.bitmap.recycle()
            return
        }
        // Let Android release previously drawn bitmaps after its render thread has finished.
        shown = next
        invalidate()
    }

    fun refreshFreshness() {
        if (shown?.let { !isCurrent(it.frame) } == true) clear()
    }

    fun clear(message: String = "等待电脑回传") {
        synchronized(lock) {
            epoch++
            pending = null
            ready?.bitmap?.recycle()
            ready = null
        }
        shown = null
        acknowledged = null
        waitingMessage = message
        invalidate()
    }

    fun release() {
        synchronized(lock) { closed = true }
        clear()
        decoder.shutdownNow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = shown
        if (frame != null && isCurrent(frame.frame)) {
            val bitmap = frame.bitmap
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(bitmap, null, destination, paint)
            if (acknowledged !== frame) { acknowledged = frame; onDisplayed(frame.frame) }
        } else {
            paint.color = Color.rgb(185, 210, 200)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = minOf(width / 38f, 24f * resources.displayMetrics.scaledDensity)
            canvas.drawText(waitingMessage, width * .25f, height * .48f, paint)
            canvas.drawText(waitingMessage, width * .75f, height * .48f, paint)
            paint.textSize *= .65f
            val hint = "轻触屏幕可退出双眼模式"
            canvas.drawText(hint, width * .25f, height * .57f, paint)
            canvas.drawText(hint, width * .75f, height * .57f, paint)
        }
    }
}
