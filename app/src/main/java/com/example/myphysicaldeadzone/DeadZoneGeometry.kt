package com.example.myphysicaldeadzone

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

object DeadZoneGeometry {
    data class CenterLimits(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    ) {
        fun clampX(value: Int): Int = value.coerceIn(minX, maxX)
        fun clampY(value: Int): Int = value.coerceIn(minY, maxY)
    }

    private data class LocalBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    fun centerLimits(
        localPoints: FloatArray,
        rotation: Int,
        displayWidth: Int,
        displayHeight: Int,
        minimumVisiblePx: Int
    ): CenterLimits {
        val bounds = rotatedLocalBounds(localPoints, rotation)
        val shapeWidth = (bounds.maxX - bounds.minX).coerceAtLeast(1f)
        val shapeHeight = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
        val visibleX = min(minimumVisiblePx.coerceAtLeast(1).toFloat(), shapeWidth)
        val visibleY = min(minimumVisiblePx.coerceAtLeast(1).toFloat(), shapeHeight)

        val horizontal = axisLimits(
            displaySize = displayWidth.coerceAtLeast(1),
            localMin = bounds.minX,
            localMax = bounds.maxX,
            minimumVisible = visibleX
        )
        val vertical = axisLimits(
            displaySize = displayHeight.coerceAtLeast(1),
            localMin = bounds.minY,
            localMax = bounds.maxY,
            minimumVisible = visibleY
        )
        return CenterLimits(horizontal.first, horizontal.second, vertical.first, vertical.second)
    }

    private fun rotatedLocalBounds(localPoints: FloatArray, rotation: Int): LocalBounds {
        require(localPoints.size >= 8) { "Four local points are required" }
        val radians = Math.toRadians(rotation.toDouble())
        val cosine = cos(radians)
        val sine = sin(radians)
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val x = localPoints[i * 2]
            val y = localPoints[i * 2 + 1]
            val rotatedX = (x * cosine - y * sine).toFloat()
            val rotatedY = (x * sine + y * cosine).toFloat()
            minX = kotlin.math.min(minX, rotatedX)
            maxX = kotlin.math.max(maxX, rotatedX)
            minY = kotlin.math.min(minY, rotatedY)
            maxY = kotlin.math.max(maxY, rotatedY)
        }
        return LocalBounds(minX, maxX, minY, maxY)
    }

    private fun axisLimits(
        displaySize: Int,
        localMin: Float,
        localMax: Float,
        minimumVisible: Float
    ): Pair<Int, Int> {
        val minimum = ceil(minimumVisible - localMax).toInt()
        val maximum = floor(displaySize - minimumVisible - localMin).toInt()
        if (minimum <= maximum) return minimum to maximum

        val centered = (displaySize / 2f - (localMin + localMax) / 2f).toInt()
        return centered to centered
    }
}
