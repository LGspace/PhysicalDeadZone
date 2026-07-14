package com.example.myphysicaldeadzone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class DeadZoneView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 68, 68)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(190, 255, 255, 255)
        pathEffect = DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 6f * resources.displayMetrics.density),
            0f
        )
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.argb(190, 255, 255, 255)
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

    private var editable = true
    private var selectedForEditing = false
    private var visualMode = DeadZoneService.DEFAULT_VISUAL_MODE
    private var originalFillAlpha = DeadZoneService.DEFAULT_ALPHA
    private var labelText = "阻断"
    private var textSizeSp = 48
    private var textColor = Color.WHITE
    private var textAlpha = 235
    private var textFont = "sans"
    private var textBold = true
    private var textItalic = false
    private var textStrokeWidthDp = 2

    private val localPoints = FloatArray(8)

    fun setConfig(
        widthPx: Int,
        heightPx: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        points: FloatArray? = null,
        editable: Boolean = true
    ) {
        rectWidthPx = widthPx.coerceAtLeast(20)
        rectHeightPx = heightPx.coerceAtLeast(20)
        rotationDegrees = rotation.toFloat()
        fillAlpha = alpha.coerceIn(20, 230)
        cornerRadiusPx = corner.coerceAtLeast(0).toFloat()
        this.editable = editable
        val nextPoints = points ?: defaultLocalPoints(rectWidthPx, rectHeightPx)
        for (i in 0 until 8) localPoints[i] = nextPoints[i]
        invalidate()
    }

    fun setTextConfig(
        text: String,
        sizeSp: Int,
        color: Int,
        alpha: Int,
        font: String,
        bold: Boolean,
        italic: Boolean,
        strokeWidthDp: Int
    ) {
        labelText = text.take(120)
        textSizeSp = sizeSp.coerceIn(6, 240)
        textColor = color
        textAlpha = alpha.coerceIn(0, 255)
        textFont = font
        textBold = bold
        textItalic = italic
        textStrokeWidthDp = strokeWidthDp.coerceIn(0, 12)
        invalidate()
    }

    fun setSelectedForEditing(selected: Boolean) {
        selectedForEditing = selected
        invalidate()
    }

    fun setVisualMode(mode: String, fillAlpha: Int) {
        visualMode = if (mode == DeadZoneService.VISUAL_MODE_ORIGINAL) {
            DeadZoneService.VISUAL_MODE_ORIGINAL
        } else {
            DeadZoneService.VISUAL_MODE_TEXT
        }
        originalFillAlpha = fillAlpha.coerceIn(20, 230)
        if (visualMode == DeadZoneService.VISUAL_MODE_ORIGINAL) {
            setLayerType(LAYER_TYPE_SOFTWARE, fillPaint)
        } else {
            setLayerType(LAYER_TYPE_NONE, null)
        }
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

        val isDefaultRect = isDefaultRectangle()
        if (!isDefaultRect) buildPath(cx, cy)

        if (visualMode == DeadZoneService.VISUAL_MODE_ORIGINAL) {
            drawOriginalRectangle(canvas, rect, radius, isDefaultRect, cx, cy)
        } else if (editable) {
            strokePaint.color = if (selectedForEditing) Color.argb(235, 70, 205, 255) else Color.argb(150, 255, 255, 255)
            strokePaint.strokeWidth = (if (selectedForEditing) 2.5f else 1.5f) * resources.displayMetrics.density
            strokePaint.pathEffect = DashPathEffect(
                floatArrayOf(8f * resources.displayMetrics.density, 6f * resources.displayMetrics.density),
                0f
            )
            if (isDefaultRect) {
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            } else {
                canvas.drawPath(path, strokePaint)
            }
        }

        if (visualMode == DeadZoneService.VISUAL_MODE_TEXT) {
            drawEmbeddedText(canvas, rect, radius, isDefaultRect, cx, cy)
        }

        canvas.restore()
    }

    private fun drawOriginalRectangle(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        isDefaultRect: Boolean,
        cx: Float,
        cy: Float
    ) {
        fillPaint.alpha = originalFillAlpha
        fillPaint.setShadowLayer(16f, 0f, 0f, Color.argb(130, 0, 0, 0))

        if (isDefaultRect) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else {
            canvas.drawPath(path, fillPaint)
        }

        strokePaint.color = Color.WHITE
        strokePaint.alpha = 230
        strokePaint.strokeWidth = 4f
        strokePaint.pathEffect = null
        if (isDefaultRect) {
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        } else {
            canvas.drawPath(path, strokePaint)
        }

        if (editable) {
            val density = resources.displayMetrics.density
            val shortSide = min(rectWidthPx, rectHeightPx).toFloat()
            val inset = (shortSide * 0.18f).coerceIn(4f * density, 14f * density)
            centerPaint.strokeWidth = (shortSide * 0.025f).coerceIn(1f * density, 2f * density)
            canvas.drawLine(cx - inset, cy, cx + inset, cy, centerPaint)
            canvas.drawLine(cx, cy - inset, cx, cy + inset, centerPaint)
        }
    }

    private fun drawEmbeddedText(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        isDefaultRect: Boolean,
        cx: Float,
        cy: Float
    ) {
        if (labelText.isEmpty() || textAlpha == 0) return

        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity
        val padding = max(2f * density, textStrokeWidthDp * density / 2f + density)
        val availableWidth = (rectWidthPx - padding * 2f).coerceAtLeast(1f)
        val availableHeight = (rectHeightPx - padding * 2f).coerceAtLeast(1f)
        val typeface = configuredTypeface()

        textPaint.typeface = typeface
        textPaint.color = textColor
        textPaint.alpha = textAlpha
        textPaint.textSize = textSizeSp * scaledDensity

        val measuredWidth = textPaint.measureText(labelText).coerceAtLeast(1f)
        val initialMetrics = textPaint.fontMetrics
        val measuredHeight = (initialMetrics.descent - initialMetrics.ascent).coerceAtLeast(1f)
        val fitScale = min(1f, min(availableWidth / measuredWidth, availableHeight / measuredHeight))
        textPaint.textSize *= fitScale

        textStrokePaint.set(textPaint)
        textStrokePaint.style = Paint.Style.STROKE
        textStrokePaint.strokeJoin = Paint.Join.ROUND
        textStrokePaint.strokeWidth = textStrokeWidthDp * density
        textStrokePaint.color = Color.BLACK
        textStrokePaint.alpha = textAlpha

        canvas.save()
        if (isDefaultRect) {
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        } else {
            canvas.clipPath(path)
        }

        val metrics = textPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        if (textStrokeWidthDp > 0) canvas.drawText(labelText, cx, baseline, textStrokePaint)
        canvas.drawText(labelText, cx, baseline, textPaint)
        canvas.restore()
    }

    private fun configuredTypeface(): Typeface {
        val family = when (textFont) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        val style = when {
            textBold && textItalic -> Typeface.BOLD_ITALIC
            textBold -> Typeface.BOLD
            textItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
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
