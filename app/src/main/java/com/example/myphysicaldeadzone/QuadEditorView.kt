package com.example.myphysicaldeadzone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class QuadEditorView(context: Context) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(45, 255, 255, 255)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(95, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 235, 54, 61)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(235, 54, 61)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(235, 54, 61)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(220, 255, 255, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 190, 205)
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private val path = Path()
    private var rectWidthPx = DeadZoneService.DEFAULT_WIDTH
    private var rectHeightPx = DeadZoneService.DEFAULT_HEIGHT
    private var rotationDegrees = 0
    private var points = DeadZoneView.defaultLocalPoints(rectWidthPx, rectHeightPx)
    private var draggingIndex = -1
    private var onPointsChanged: ((FloatArray) -> Unit)? = null

    fun setEditorState(widthPx: Int, heightPx: Int, rotation: Int, nextPoints: FloatArray, onChanged: (FloatArray) -> Unit) {
        rectWidthPx = widthPx.coerceAtLeast(20)
        rectHeightPx = heightPx.coerceAtLeast(20)
        rotationDegrees = rotation
        points = nextPoints.copyOf()
        onPointsChanged = onChanged
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (220f * resources.displayMetrics.density).roundToInt()
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val scale = scaleForContent()

        path.reset()
        for (i in 0 until 4) {
            val x = cx + rotatedX(i) * scale
            val y = cy + rotatedY(i) * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        val center = centerPoint()
        val centerX = cx + rotateX(center.first, center.second) * scale
        val centerY = cy + rotateY(center.first, center.second) * scale
        val cross = 14f * resources.displayMetrics.density
        canvas.drawLine(centerX - cross, centerY, centerX + cross, centerY, centerPaint)
        canvas.drawLine(centerX, centerY - cross, centerX, centerY + cross, centerPaint)

        val radius = 8f * resources.displayMetrics.density
        for (i in 0 until 4) {
            val x = cx + rotatedX(i) * scale
            val y = cy + rotatedY(i) * scale
            canvas.drawCircle(x, y, radius, handlePaint)
            canvas.drawCircle(x, y, radius, handleStrokePaint)
        }

        canvas.drawText("拖动四个角点调整四边形", 14f * resources.displayMetrics.density, 24f * resources.displayMetrics.density, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingIndex = hitCorner(event.x, event.y)
                return draggingIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex < 0) return false
                updatePointFromView(draggingIndex, event.x, event.y)
                onPointsChanged?.invoke(points.copyOf())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingIndex >= 0) {
                    onPointsChanged?.invoke(points.copyOf())
                }
                draggingIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 24f * resources.displayMetrics.density
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), axisPaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, axisPaint)
    }

    private fun hitCorner(x: Float, y: Float): Int {
        val cx = width / 2f
        val cy = height / 2f
        val scale = scaleForContent()
        val radius = 28f * resources.displayMetrics.density
        for (i in 0 until 4) {
            val px = cx + rotatedX(i) * scale
            val py = cy + rotatedY(i) * scale
            if (hypot(x - px, y - py) <= radius) return i
        }
        return -1
    }

    private fun updatePointFromView(index: Int, x: Float, y: Float) {
        val scale = scaleForContent()
        val rotatedLocalX = ((x - width / 2f) / scale).coerceIn(-2500f, 2500f)
        val rotatedLocalY = ((y - height / 2f) / scale).coerceIn(-2500f, 2500f)
        val radians = Math.toRadians((-rotationDegrees).toDouble())
        points[index * 2] = (rotatedLocalX * cos(radians) - rotatedLocalY * sin(radians)).toFloat()
        points[index * 2 + 1] = (rotatedLocalX * sin(radians) + rotatedLocalY * cos(radians)).toFloat()
    }

    private fun scaleForContent(): Float {
        var maxAbsX = rectWidthPx / 2f
        var maxAbsY = rectHeightPx / 2f
        for (i in 0 until 4) {
            maxAbsX = max(maxAbsX, abs(rotatedX(i)))
            maxAbsY = max(maxAbsY, abs(rotatedY(i)))
        }
        val usableW = (width - 48f * resources.displayMetrics.density).coerceAtLeast(1f)
        val usableH = (height - 56f * resources.displayMetrics.density).coerceAtLeast(1f)
        return min(usableW / (maxAbsX * 2f).coerceAtLeast(1f), usableH / (maxAbsY * 2f).coerceAtLeast(1f))
    }

    private fun rotatedX(index: Int): Float = rotateX(points[index * 2], points[index * 2 + 1])

    private fun rotatedY(index: Int): Float = rotateY(points[index * 2], points[index * 2 + 1])

    private fun rotateX(x: Float, y: Float): Float {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        return (x * cos(radians) - y * sin(radians)).toFloat()
    }

    private fun rotateY(x: Float, y: Float): Float {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        return (x * sin(radians) + y * cos(radians)).toFloat()
    }

    private fun centerPoint(): Pair<Float, Float> {
        return Pair(
            (points[0] + points[2] + points[4] + points[6]) / 4f,
            (points[1] + points[3] + points[5] + points[7]) / 4f
        )
    }
}
