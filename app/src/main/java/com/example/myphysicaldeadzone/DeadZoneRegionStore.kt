package com.example.myphysicaldeadzone

import android.content.SharedPreferences
import android.graphics.Color
import kotlin.math.roundToInt

data class DeadZoneRegion(
    val enabled: Boolean = true,
    val centerX: Int,
    val centerY: Int,
    val width: Int = DeadZoneService.DEFAULT_WIDTH,
    val height: Int = DeadZoneService.DEFAULT_HEIGHT,
    val rotation: Int = 0,
    val corner: Int = DeadZoneService.DEFAULT_CORNER,
    val visualMode: String = DeadZoneService.DEFAULT_VISUAL_MODE,
    val fillAlpha: Int = DeadZoneService.DEFAULT_ALPHA,
    val labelText: String = DeadZoneService.DEFAULT_LABEL_TEXT,
    val textSize: Int = DeadZoneService.DEFAULT_TEXT_SIZE,
    val textColor: Int = DeadZoneService.DEFAULT_TEXT_COLOR,
    val textAlpha: Int = DeadZoneService.DEFAULT_TEXT_ALPHA,
    val textFont: String = DeadZoneService.DEFAULT_TEXT_FONT,
    val textBold: Boolean = true,
    val textItalic: Boolean = false,
    val textStroke: Int = DeadZoneService.DEFAULT_TEXT_STROKE
)

object DeadZoneRegionStore {
    const val MAX_REGIONS = 8
    private const val KEY_COUNT = "REGION_COUNT"
    private const val KEY_SELECTED = "REGION_SELECTED"
    private const val KEY_LAST_W = "REGION_LAST_DISPLAY_W"
    private const val KEY_LAST_H = "REGION_LAST_DISPLAY_H"

    fun ensureMigrated(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int) {
        if (!prefs.contains(KEY_COUNT)) {
            val first = legacyRegion(prefs, displayWidth, displayHeight)
            prefs.edit()
                .putInt(KEY_COUNT, 1)
                .putInt(KEY_SELECTED, 0)
                .putInt(KEY_LAST_W, displayWidth)
                .putInt(KEY_LAST_H, displayHeight)
                .apply()
            save(prefs, 0, first, displayWidth, displayHeight)
        }
        val count = count(prefs)
        val selected = selectedIndex(prefs).coerceIn(0, count - 1)
        prefs.edit().putInt(KEY_SELECTED, selected).apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
    }

    fun count(prefs: SharedPreferences): Int = prefs.getInt(KEY_COUNT, 1).coerceIn(1, MAX_REGIONS)

    fun selectedIndex(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_SELECTED, 0).coerceIn(0, count(prefs) - 1)

    fun regions(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int): List<DeadZoneRegion> {
        return (0 until count(prefs)).map { load(prefs, it, displayWidth, displayHeight) }
    }

    fun load(prefs: SharedPreferences, index: Int, displayWidth: Int, displayHeight: Int): DeadZoneRegion {
        val prefix = prefix(index)
        return DeadZoneRegion(
            enabled = prefs.getBoolean(prefix + "ENABLED", true),
            centerX = prefs.getInt(prefix + "CENTER_X", displayWidth / 2),
            centerY = prefs.getInt(prefix + "CENTER_Y", displayHeight / 2),
            width = prefs.getInt(prefix + "WIDTH", DeadZoneService.DEFAULT_WIDTH).coerceIn(40, 5000),
            height = prefs.getInt(prefix + "HEIGHT", DeadZoneService.DEFAULT_HEIGHT).coerceIn(20, 5000),
            rotation = prefs.getInt(prefix + "ROTATION", 0).coerceIn(-180, 180),
            corner = prefs.getInt(prefix + "CORNER", DeadZoneService.DEFAULT_CORNER).coerceIn(0, 240),
            visualMode = prefs.getString(prefix + "VISUAL_MODE", DeadZoneService.DEFAULT_VISUAL_MODE)
                ?: DeadZoneService.DEFAULT_VISUAL_MODE,
            fillAlpha = prefs.getInt(prefix + "FILL_ALPHA", DeadZoneService.DEFAULT_ALPHA).coerceIn(20, 230),
            labelText = prefs.getString(prefix + "LABEL_TEXT", DeadZoneService.DEFAULT_LABEL_TEXT)
                ?: DeadZoneService.DEFAULT_LABEL_TEXT,
            textSize = prefs.getInt(prefix + "TEXT_SIZE", DeadZoneService.DEFAULT_TEXT_SIZE).coerceIn(6, 240),
            textColor = prefs.getInt(prefix + "TEXT_COLOR", Color.WHITE),
            textAlpha = prefs.getInt(prefix + "TEXT_ALPHA", DeadZoneService.DEFAULT_TEXT_ALPHA).coerceIn(0, 255),
            textFont = prefs.getString(prefix + "TEXT_FONT", DeadZoneService.DEFAULT_TEXT_FONT)
                ?: DeadZoneService.DEFAULT_TEXT_FONT,
            textBold = prefs.getBoolean(prefix + "TEXT_BOLD", true),
            textItalic = prefs.getBoolean(prefix + "TEXT_ITALIC", false),
            textStroke = prefs.getInt(prefix + "TEXT_STROKE", DeadZoneService.DEFAULT_TEXT_STROKE).coerceIn(0, 12)
        )
    }

    fun save(
        prefs: SharedPreferences,
        index: Int,
        region: DeadZoneRegion,
        displayWidth: Int,
        displayHeight: Int
    ) {
        val prefix = prefix(index)
        val dw = displayWidth.coerceAtLeast(1).toFloat()
        val dh = displayHeight.coerceAtLeast(1).toFloat()
        prefs.edit()
            .putBoolean(prefix + "ENABLED", region.enabled)
            .putInt(prefix + "CENTER_X", region.centerX)
            .putInt(prefix + "CENTER_Y", region.centerY)
            .putFloat(prefix + "CENTER_X_PCT", region.centerX / dw)
            .putFloat(prefix + "CENTER_Y_PCT", region.centerY / dh)
            .putInt(prefix + "WIDTH", region.width.coerceIn(40, 5000))
            .putInt(prefix + "HEIGHT", region.height.coerceIn(20, 5000))
            .putInt(prefix + "ROTATION", region.rotation.coerceIn(-180, 180))
            .putInt(prefix + "CORNER", region.corner.coerceIn(0, 240))
            .putString(prefix + "VISUAL_MODE", normalizeVisualMode(region.visualMode))
            .putInt(prefix + "FILL_ALPHA", region.fillAlpha.coerceIn(20, 230))
            .putString(prefix + "LABEL_TEXT", region.labelText.take(120))
            .putInt(prefix + "TEXT_SIZE", region.textSize.coerceIn(6, 240))
            .putInt(prefix + "TEXT_COLOR", region.textColor)
            .putInt(prefix + "TEXT_ALPHA", region.textAlpha.coerceIn(0, 255))
            .putString(prefix + "TEXT_FONT", region.textFont)
            .putBoolean(prefix + "TEXT_BOLD", region.textBold)
            .putBoolean(prefix + "TEXT_ITALIC", region.textItalic)
            .putInt(prefix + "TEXT_STROKE", region.textStroke.coerceIn(0, 12))
            .apply()
    }

    fun saveSelectedFromLegacy(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int) {
        val index = selectedIndex(prefs)
        val old = load(prefs, index, displayWidth, displayHeight)
        val updated = old.copy(
            centerX = prefs.getInt(DeadZoneService.KEY_CENTER_X, old.centerX),
            centerY = prefs.getInt(DeadZoneService.KEY_CENTER_Y, old.centerY),
            width = prefs.getInt(DeadZoneService.KEY_WIDTH, old.width),
            height = prefs.getInt(DeadZoneService.KEY_HEIGHT, old.height),
            rotation = prefs.getInt(DeadZoneService.KEY_ROTATION, old.rotation),
            corner = prefs.getInt(DeadZoneService.KEY_CORNER, old.corner),
            visualMode = prefs.getString(DeadZoneService.KEY_VISUAL_MODE, old.visualMode) ?: old.visualMode,
            fillAlpha = prefs.getInt(DeadZoneService.KEY_ALPHA, old.fillAlpha),
            labelText = prefs.getString(DeadZoneService.KEY_LABEL_TEXT, old.labelText) ?: old.labelText,
            textSize = prefs.getInt(DeadZoneService.KEY_TEXT_SIZE, old.textSize),
            textColor = prefs.getInt(DeadZoneService.KEY_TEXT_COLOR, old.textColor),
            textAlpha = prefs.getInt(DeadZoneService.KEY_TEXT_ALPHA, old.textAlpha),
            textFont = prefs.getString(DeadZoneService.KEY_TEXT_FONT, old.textFont) ?: old.textFont,
            textBold = prefs.getBoolean(DeadZoneService.KEY_TEXT_BOLD, old.textBold),
            textItalic = prefs.getBoolean(DeadZoneService.KEY_TEXT_ITALIC, old.textItalic),
            textStroke = prefs.getInt(DeadZoneService.KEY_TEXT_STROKE, old.textStroke)
        )
        save(prefs, index, updated, displayWidth, displayHeight)
    }

    fun loadSelectedIntoLegacy(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int) {
        val region = load(prefs, selectedIndex(prefs), displayWidth, displayHeight)
        prefs.edit()
            .putInt(DeadZoneService.KEY_CENTER_X, region.centerX)
            .putInt(DeadZoneService.KEY_CENTER_Y, region.centerY)
            .putInt(DeadZoneService.KEY_WIDTH, region.width)
            .putInt(DeadZoneService.KEY_HEIGHT, region.height)
            .putInt(DeadZoneService.KEY_ROTATION, region.rotation)
            .putInt(DeadZoneService.KEY_CORNER, region.corner)
            .putString(DeadZoneService.KEY_VISUAL_MODE, region.visualMode)
            .putInt(DeadZoneService.KEY_ALPHA, region.fillAlpha)
            .putString(DeadZoneService.KEY_LABEL_TEXT, region.labelText)
            .putInt(DeadZoneService.KEY_TEXT_SIZE, region.textSize)
            .putInt(DeadZoneService.KEY_TEXT_COLOR, region.textColor)
            .putInt(DeadZoneService.KEY_TEXT_ALPHA, region.textAlpha)
            .putString(DeadZoneService.KEY_TEXT_FONT, region.textFont)
            .putBoolean(DeadZoneService.KEY_TEXT_BOLD, region.textBold)
            .putBoolean(DeadZoneService.KEY_TEXT_ITALIC, region.textItalic)
            .putInt(DeadZoneService.KEY_TEXT_STROKE, region.textStroke)
            .putBoolean(DeadZoneService.KEY_QUAD_ENABLED, false)
            .apply()
    }

    fun select(prefs: SharedPreferences, index: Int, displayWidth: Int, displayHeight: Int) {
        saveSelectedFromLegacy(prefs, displayWidth, displayHeight)
        prefs.edit().putInt(KEY_SELECTED, index.coerceIn(0, count(prefs) - 1)).apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
    }

    fun add(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int, offsetPx: Int): Int? {
        val oldCount = count(prefs)
        if (oldCount >= MAX_REGIONS) return null
        saveSelectedFromLegacy(prefs, displayWidth, displayHeight)
        val source = load(prefs, selectedIndex(prefs), displayWidth, displayHeight)
        val next = source.copy(
            centerX = (source.centerX + offsetPx).coerceAtMost(displayWidth),
            centerY = (source.centerY + offsetPx).coerceAtMost(displayHeight),
            labelText = "阻断 ${oldCount + 1}"
        )
        save(prefs, oldCount, next, displayWidth, displayHeight)
        prefs.edit().putInt(KEY_COUNT, oldCount + 1).putInt(KEY_SELECTED, oldCount).apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
        return oldCount
    }

    fun duplicate(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int, offsetPx: Int): Int? {
        val oldCount = count(prefs)
        if (oldCount >= MAX_REGIONS) return null
        saveSelectedFromLegacy(prefs, displayWidth, displayHeight)
        val source = load(prefs, selectedIndex(prefs), displayWidth, displayHeight)
        val next = source.copy(
            centerX = (source.centerX + offsetPx).coerceAtMost(displayWidth),
            centerY = (source.centerY + offsetPx).coerceAtMost(displayHeight)
        )
        save(prefs, oldCount, next, displayWidth, displayHeight)
        prefs.edit().putInt(KEY_COUNT, oldCount + 1).putInt(KEY_SELECTED, oldCount).apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
        return oldCount
    }

    fun deleteSelected(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int): Boolean {
        val oldCount = count(prefs)
        if (oldCount <= 1) return false
        val selected = selectedIndex(prefs)
        saveSelectedFromLegacy(prefs, displayWidth, displayHeight)
        for (i in selected until oldCount - 1) {
            save(prefs, i, load(prefs, i + 1, displayWidth, displayHeight), displayWidth, displayHeight)
        }
        clear(prefs, oldCount - 1)
        val newCount = oldCount - 1
        prefs.edit()
            .putInt(KEY_COUNT, newCount)
            .putInt(KEY_SELECTED, selected.coerceAtMost(newCount - 1))
            .apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
        return true
    }

    fun setEnabled(
        prefs: SharedPreferences,
        index: Int,
        enabled: Boolean,
        displayWidth: Int,
        displayHeight: Int
    ) {
        save(prefs, index, load(prefs, index, displayWidth, displayHeight).copy(enabled = enabled), displayWidth, displayHeight)
    }

    fun replaceAll(
        prefs: SharedPreferences,
        regions: List<DeadZoneRegion>,
        selectedIndex: Int,
        displayWidth: Int,
        displayHeight: Int
    ) {
        val safeRegions = regions.take(MAX_REGIONS).ifEmpty {
            listOf(DeadZoneRegion(centerX = displayWidth / 2, centerY = displayHeight / 2))
        }
        val oldCount = count(prefs)
        safeRegions.forEachIndexed { index, region ->
            save(prefs, index, region, displayWidth, displayHeight)
        }
        for (index in safeRegions.size until oldCount) clear(prefs, index)
        prefs.edit()
            .putInt(KEY_COUNT, safeRegions.size)
            .putInt(KEY_SELECTED, selectedIndex.coerceIn(0, safeRegions.lastIndex))
            .putInt(KEY_LAST_W, displayWidth)
            .putInt(KEY_LAST_H, displayHeight)
            .apply()
        loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
    }

    fun restoreForDisplay(
        prefs: SharedPreferences,
        displayWidth: Int,
        displayHeight: Int,
        minimumVisiblePx: Int
    ) {
        val lastWidth = prefs.getInt(KEY_LAST_W, displayWidth)
        val lastHeight = prefs.getInt(KEY_LAST_H, displayHeight)
        if (lastWidth == displayWidth && lastHeight == displayHeight) return
        for (i in 0 until count(prefs)) {
            val region = load(prefs, i, lastWidth.coerceAtLeast(1), lastHeight.coerceAtLeast(1))
            val prefix = prefix(i)
            val centerX = (prefs.getFloat(prefix + "CENTER_X_PCT", region.centerX / lastWidth.coerceAtLeast(1).toFloat()) * displayWidth).roundToInt()
            val centerY = (prefs.getFloat(prefix + "CENTER_Y_PCT", region.centerY / lastHeight.coerceAtLeast(1).toFloat()) * displayHeight).roundToInt()
            val limits = DeadZoneGeometry.centerLimits(
                DeadZoneView.defaultLocalPoints(region.width, region.height),
                region.rotation,
                displayWidth,
                displayHeight,
                minimumVisiblePx
            )
            save(
                prefs,
                i,
                region.copy(centerX = limits.clampX(centerX), centerY = limits.clampY(centerY)),
                displayWidth,
                displayHeight
            )
        }
        prefs.edit().putInt(KEY_LAST_W, displayWidth).putInt(KEY_LAST_H, displayHeight).apply()
    }

    private fun legacyRegion(prefs: SharedPreferences, displayWidth: Int, displayHeight: Int): DeadZoneRegion {
        return DeadZoneRegion(
            centerX = prefs.getInt(DeadZoneService.KEY_CENTER_X, displayWidth / 2),
            centerY = prefs.getInt(DeadZoneService.KEY_CENTER_Y, displayHeight / 2),
            width = prefs.getInt(DeadZoneService.KEY_WIDTH, DeadZoneService.DEFAULT_WIDTH),
            height = prefs.getInt(DeadZoneService.KEY_HEIGHT, DeadZoneService.DEFAULT_HEIGHT),
            rotation = prefs.getInt(DeadZoneService.KEY_ROTATION, 0),
            corner = prefs.getInt(DeadZoneService.KEY_CORNER, DeadZoneService.DEFAULT_CORNER),
            visualMode = prefs.getString(DeadZoneService.KEY_VISUAL_MODE, DeadZoneService.DEFAULT_VISUAL_MODE)
                ?: DeadZoneService.DEFAULT_VISUAL_MODE,
            fillAlpha = prefs.getInt(DeadZoneService.KEY_ALPHA, DeadZoneService.DEFAULT_ALPHA),
            labelText = prefs.getString(DeadZoneService.KEY_LABEL_TEXT, DeadZoneService.DEFAULT_LABEL_TEXT)
                ?: DeadZoneService.DEFAULT_LABEL_TEXT,
            textSize = prefs.getInt(DeadZoneService.KEY_TEXT_SIZE, DeadZoneService.DEFAULT_TEXT_SIZE),
            textColor = prefs.getInt(DeadZoneService.KEY_TEXT_COLOR, DeadZoneService.DEFAULT_TEXT_COLOR),
            textAlpha = prefs.getInt(DeadZoneService.KEY_TEXT_ALPHA, DeadZoneService.DEFAULT_TEXT_ALPHA),
            textFont = prefs.getString(DeadZoneService.KEY_TEXT_FONT, DeadZoneService.DEFAULT_TEXT_FONT)
                ?: DeadZoneService.DEFAULT_TEXT_FONT,
            textBold = prefs.getBoolean(DeadZoneService.KEY_TEXT_BOLD, true),
            textItalic = prefs.getBoolean(DeadZoneService.KEY_TEXT_ITALIC, false),
            textStroke = prefs.getInt(DeadZoneService.KEY_TEXT_STROKE, DeadZoneService.DEFAULT_TEXT_STROKE)
        )
    }

    private fun clear(prefs: SharedPreferences, index: Int) {
        val prefix = prefix(index)
        val keys = listOf(
            "ENABLED", "CENTER_X", "CENTER_Y", "CENTER_X_PCT", "CENTER_Y_PCT", "WIDTH", "HEIGHT",
            "ROTATION", "CORNER", "VISUAL_MODE", "FILL_ALPHA", "LABEL_TEXT", "TEXT_SIZE", "TEXT_COLOR", "TEXT_ALPHA", "TEXT_FONT",
            "TEXT_BOLD", "TEXT_ITALIC", "TEXT_STROKE"
        )
        prefs.edit().also { editor -> keys.forEach { editor.remove(prefix + it) } }.apply()
    }

    private fun prefix(index: Int): String = "REGION_${index}_"

    private fun normalizeVisualMode(value: String): String {
        return if (value == DeadZoneService.VISUAL_MODE_ORIGINAL) {
            DeadZoneService.VISUAL_MODE_ORIGINAL
        } else {
            DeadZoneService.VISUAL_MODE_TEXT
        }
    }
}
