package com.example.myphysicaldeadzone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class DeadZoneView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 68, 68)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(180, 255, 255, 255)
    }

    private val path = Path()

    var rectWidthPx: Int = 360
        private set

    var rectHeightPx: Int = 72
        private set

    var rotationDegrees: Float = 0f
        private set

    var fillAlpha: Int = 150
        private set

    var cornerRadiusPx: Float = 18f
        private set

    private val localPoints = FloatArray(8)

    fun setConfig(widthPx: Int, heightPx: Int, rotation: Int, alpha: Int, corner: Int, points: FloatArray? = null) {
        rectWidthPx = widthPx.coerceAtLeast(20)
        rectHeightPx = heightPx.coerceAtLeast(20)
        rotationDegrees = rotation.toFloat()
        fillAlpha = alpha.coerceIn(20, 230)
        cornerRadiusPx = corner.coerceAtLeast(0).toFloat()
        val nextPoints = points ?: defaultLocalPoints(rectWidthPx, rectHeightPx)
        for (i in 0 until 8) localPoints[i] = nextPoints[i]
        invalidate()
    }

    fun cornerAt(x: Float, y: Float): Int {
        val radius = 26f * resources.displayMetrics.density
        for (i in 0 until 4) {
            val sx = screenPointX(i)
            val sy = screenPointY(i)
            if (hypot(x - sx, y - sy) <= radius) return i
        }
        return -1
    }

    fun copyLocalPoints(): FloatArray = localPoints.copyOf()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val halfW = rectWidthPx / 2f
        val halfH = rectHeightPx / 2f
        val radius = min(cornerRadiusPx, min(halfW, halfH))
        val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)

        fillPaint.alpha = fillAlpha
        fillPaint.setShadowLayer(16f, 0f, 0f, Color.argb(130, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, fillPaint)
        val isDefaultRect = isDefaultRectangle()
        if (isDefaultRect) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else {
            buildPath(cx, cy)
            canvas.drawPath(path, fillPaint)
        }

        strokePaint.alpha = 230
        if (isDefaultRect) {
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        } else {
            canvas.drawPath(path, strokePaint)
        }

        val centerLocalX = (localPoints[0] + localPoints[2] + localPoints[4] + localPoints[6]) / 4f
        val centerLocalY = (localPoints[1] + localPoints[3] + localPoints[5] + localPoints[7]) / 4f
        val crossX = cx + centerLocalX
        val crossY = cy + centerLocalY
        val inset = 14f
        centerPaint.alpha = 190
        canvas.drawLine(crossX - inset, crossY, crossX + inset, crossY, centerPaint)
        canvas.drawLine(crossX, crossY - inset, crossX, crossY + inset, centerPaint)

        canvas.restore()
    }

    private fun buildPath(cx: Float, cy: Float) {
        path.reset()
        path.moveTo(cx + localPoints[0], cy + localPoints[1])
        path.lineTo(cx + localPoints[2], cy + localPoints[3])
        path.lineTo(cx + localPoints[4], cy + localPoints[5])
        path.lineTo(cx + localPoints[6], cy + localPoints[7])
        path.close()
    }

    private fun screenPointX(index: Int): Float {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val lx = localPoints[index * 2]
        val ly = localPoints[index * 2 + 1]
        return width / 2f + (lx * cos(radians) - ly * sin(radians)).toFloat()
    }

    private fun screenPointY(index: Int): Float {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val lx = localPoints[index * 2]
        val ly = localPoints[index * 2 + 1]
        return height / 2f + (lx * sin(radians) + ly * cos(radians)).toFloat()
    }

    private fun isDefaultRectangle(): Boolean {
        val defaults = defaultLocalPoints(rectWidthPx, rectHeightPx)
        return localPoints.indices.all { kotlin.math.abs(localPoints[it] - defaults[it]) < 0.5f }
    }

    companion object {
        fun defaultLocalPoints(widthPx: Int, heightPx: Int): FloatArray {
            val halfW = widthPx.coerceAtLeast(20) / 2f
            val halfH = heightPx.coerceAtLeast(20) / 2f
            return floatArrayOf(
                -halfW, -halfH,
                halfW, -halfH,
                halfW, halfH,
                -halfW, halfH
            )
        }
    }
}
