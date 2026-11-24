package com.angadlobo.camera360

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Drawn over the camera preview: target dots on the sphere, a centre ring, and an
 * arrow + instruction telling the user which way to move the phone.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** A target projected into camera image coordinates (x right, y down, units of focal length). */
    class Dot(val x: Double, val y: Double, val isNext: Boolean)

    private var dots: List<Dot> = emptyList()
    private var arrowAngle: Double? = null
    private var instruction: String = ""
    private var progress: String = ""
    private var onTarget = false
    private var mirrored = false

    /** focal length / long side of the preview image (same ratio the stitcher uses). */
    var focalRatio: Double = 0.77

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(22f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(dp(4f), 0f, 0f, Color.BLACK)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(14f); textAlign = Paint.Align.CENTER
        setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val arrowPath = Path()

    fun update(
        dots: List<Dot>, arrowAngle: Double?, instruction: String, progress: String,
        onTarget: Boolean, mirrored: Boolean,
    ) {
        this.dots = dots
        this.arrowAngle = arrowAngle
        this.instruction = instruction
        this.progress = progress
        this.onTarget = onTarget
        this.mirrored = mirrored
        invalidate()
    }

    fun clear() = update(emptyList(), null, "", "", false, false)

    /** Pixels per unit of normalised camera coordinate, for a 3:4 portrait preview filling the view. */
    private fun focalPx(): Float {
        val unit = max(width / 3f, height / 4f) // PreviewView FILL_CENTER scaling of a 3:4 image
        return (focalRatio * 4 * unit).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (instruction.isEmpty() && dots.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val f = focalPx()
        val sx = if (mirrored) -1f else 1f

        for (d in dots) {
            val x = cx + sx * (d.x * f).toFloat()
            val y = cy + (d.y * f).toFloat()
            if (x < -50 || x > width + 50 || y < -50 || y > height + 50) continue
            if (d.isNext) canvas.drawCircle(x, y, dp(16f), nextPaint)
            else canvas.drawCircle(x, y, dp(7f), dotPaint)
        }

        // Centre ring: put the orange dot inside it.
        val ringR = (Math.tan(CaptureGuide.CAPTURE_TOLERANCE) * f).toFloat().coerceAtLeast(dp(22f))
        ringPaint.color = if (onTarget) ACCENT else Color.WHITE
        ringPaint.strokeWidth = dp(if (onTarget) 5f else 3f)
        canvas.drawCircle(cx, cy, ringR, ringPaint)

        arrowAngle?.let { a0 ->
            val a = if (mirrored) Math.PI - a0 else a0
            val start = ringR + dp(10f)
            val len = min(width, height) * 0.16f
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            val tipX = cx + ux * (start + len)
            val tipY = cy + uy * (start + len)
            val baseX = cx + ux * start
            val baseY = cy + uy * start
            val w = dp(9f)
            val head = dp(26f)
            arrowPath.reset()
            arrowPath.moveTo(baseX - uy * w, baseY + ux * w)
            arrowPath.lineTo(tipX - ux * head - uy * w, tipY - uy * head + ux * w)
            arrowPath.lineTo(tipX - ux * head - uy * w * 2.4f, tipY - uy * head + ux * w * 2.4f)
            arrowPath.lineTo(tipX, tipY)
            arrowPath.lineTo(tipX - ux * head + uy * w * 2.4f, tipY - uy * head - ux * w * 2.4f)
            arrowPath.lineTo(tipX - ux * head + uy * w, tipY - uy * head - ux * w)
            arrowPath.lineTo(baseX + uy * w, baseY - ux * w)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }

        if (instruction.isNotEmpty()) {
            val y = height - dp(28f)
            val tw = textPaint.measureText(instruction) / 2 + dp(16f)
            canvas.drawRoundRect(cx - tw, y - dp(30f), cx + tw, y + dp(10f), dp(20f), dp(20f), pillPaint)
            canvas.drawText(instruction, cx, y, textPaint)
        }
        if (progress.isNotEmpty()) canvas.drawText(progress, cx, dp(24f), smallText)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        private val ACCENT = Color.rgb(255, 152, 0)
    }
}
