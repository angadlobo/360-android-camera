package com.angadlobo.camera360

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI

/**
 * Shows the panorama as it is being filled in, with markers for where the
 * back (white) and front (orange) cameras are currently pointing.
 */
class PanoPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE
    }
    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(255, 152, 0)
    }
    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(90, 255, 255, 255)
    }

    private var backLon = Double.NaN
    private var backLat = 0.0
    private var frontLon = Double.NaN
    private var frontLat = 0.0

    fun setPanorama(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun setPointing(back: Pair<Double, Double>?, front: Pair<Double, Double>?) {
        backLon = back?.first ?: Double.NaN; backLat = back?.second ?: 0.0
        frontLon = front?.first ?: Double.NaN; frontLat = front?.second ?: 0.0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w / 2)
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0, 0, width, height)
        bitmap?.let { canvas.drawBitmap(it, null, dst, bmpPaint) } ?: canvas.drawColor(Color.rgb(26, 26, 26))
        canvas.drawRect(dst, framePaint)
        drawMarker(canvas, backLon, backLat, backPaint)
        drawMarker(canvas, frontLon, frontLat, frontPaint)
    }

    private fun drawMarker(canvas: Canvas, lon: Double, lat: Double, paint: Paint) {
        if (lon.isNaN()) return
        val x = ((lon / (2 * PI) + 0.5) * width).toFloat()
        val y = ((0.5 - lat / PI) * height).toFloat()
        val r = height * 0.06f
        canvas.drawCircle(x, y, r, paint)
        canvas.drawLine(x - r * 1.6f, y, x - r * 0.5f, y, paint)
        canvas.drawLine(x + r * 0.5f, y, x + r * 1.6f, y, paint)
    }
}
