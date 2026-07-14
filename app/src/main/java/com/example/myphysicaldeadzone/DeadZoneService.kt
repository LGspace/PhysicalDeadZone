package com.example.myphysicaldeadzone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class DeadZoneService : Service() {

    private data class RegionOverlay(
        val index: Int,
        val view: DeadZoneView,
        val layoutParams: WindowManager.LayoutParams,
        var offsetX: Int = 0,
        var offsetY: Int = 0,
        var lastScreenX: Int = Int.MIN_VALUE,
        var lastScreenY: Int = Int.MIN_VALUE
    )

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var deadZoneView: DeadZoneView? = null
    private val regionOverlays = mutableListOf<RegionOverlay>()
    private var selectedRegionIndex = 0
    private var controlPanelView: View? = null
    private var controlPanelParams: WindowManager.LayoutParams? = null
    private var pendingDaemonConfigWrite: Runnable? = null
    private var windowOffsetX = 0
    private var windowOffsetY = 0

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartCenterX = 0
    private var dragStartCenterY = 0
    private var isOverlayDragging = false
    private var longPressStartRawX = 0f
    private var longPressStartRawY = 0f
    private var longPressTriggered = false
    private var transformStartDistance = 1f
    private var transformStartAngle = 0f
    private var transformStartWidth = DEFAULT_WIDTH
    private var transformStartHeight = DEFAULT_HEIGHT
    private var transformStartRotation = 0

    private var panelDragStartRawX = 0f
    private var panelDragStartRawY = 0f
    private var panelDragStartX = 0
    private var panelDragStartY = 0

    companion object {
        private const val CHANNEL_ID = "dead_zone_overlay"
        private const val NOTIFICATION_ID = 7
        private const val MINIMUM_VISIBLE_DP = 32

        const val PREFS = "DeadZoneConfig"
        const val KEY_CENTER_X = "CENTER_X"
        const val KEY_CENTER_Y = "CENTER_Y"
        const val KEY_WIDTH = "WIDTH"
        const val KEY_HEIGHT = "HEIGHT"
        const val KEY_ROTATION = "ROTATION"
        const val KEY_ALPHA = "ALPHA"
        const val KEY_CORNER = "CORNER"
        const val KEY_EDITABLE = "EDITABLE"
        const val KEY_QUAD_ENABLED = "QUAD_ENABLED"
        const val KEY_LABEL_TEXT = "LABEL_TEXT"
        const val KEY_TEXT_SIZE = "TEXT_SIZE"
        const val KEY_TEXT_COLOR = "TEXT_COLOR"
        const val KEY_TEXT_ALPHA = "TEXT_ALPHA"
        const val KEY_TEXT_FONT = "TEXT_FONT"
        const val KEY_TEXT_BOLD = "TEXT_BOLD"
        const val KEY_TEXT_ITALIC = "TEXT_ITALIC"
        const val KEY_TEXT_STROKE = "TEXT_STROKE"
        const val KEY_VISUAL_MODE = "VISUAL_MODE"
        const val KEY_CENTER_X_PCT = "CENTER_X_PCT"
        const val KEY_CENTER_Y_PCT = "CENTER_Y_PCT"
        const val KEY_WIDTH_PCT = "WIDTH_PCT"
        const val KEY_HEIGHT_PCT = "HEIGHT_PCT"
        const val KEY_LAST_DISPLAY_W = "LAST_DISPLAY_W"
        const val KEY_LAST_DISPLAY_H = "LAST_DISPLAY_H"
        const val CONFIG_FILE = "deadzone_config.json"

        const val DEFAULT_CENTER_X = 540
        const val DEFAULT_CENTER_Y = 960
        const val DEFAULT_WIDTH = 360
        const val DEFAULT_HEIGHT = 78
        const val DEFAULT_ALPHA = 150
        const val DEFAULT_CORNER = 18
        const val DEFAULT_LABEL_TEXT = "阻断"
        const val DEFAULT_TEXT_SIZE = 48
        const val DEFAULT_TEXT_COLOR = Color.WHITE
        const val DEFAULT_TEXT_ALPHA = 235
        const val DEFAULT_TEXT_FONT = "sans"
        const val DEFAULT_TEXT_STROKE = 2
        const val VISUAL_MODE_ORIGINAL = "original"
        const val VISUAL_MODE_TEXT = "text"
        const val DEFAULT_VISUAL_MODE = VISUAL_MODE_TEXT

        var instance: DeadZoneService? = null

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotif()
        initDeadZone()
    }

    private fun initDeadZone() {
        DeadZoneRegionStore.ensureMigrated(prefs, currentDisplayWidth(), currentDisplayHeight())
        reloadRegions()
    }

    fun reloadRegions() {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        DeadZoneRegionStore.ensureMigrated(prefs, displayWidth, displayHeight)
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, displayWidth, displayHeight)
        DeadZoneRegionStore.restoreForDisplay(prefs, displayWidth, displayHeight, dp(MINIMUM_VISIBLE_DP))
        val regions = DeadZoneRegionStore.regions(prefs, displayWidth, displayHeight)

        while (regionOverlays.size > regions.size) {
            val removed = regionOverlays.removeAt(regionOverlays.lastIndex)
            try {
                windowManager.removeView(removed.view)
            } catch (_: Exception) {
            }
        }
        while (regionOverlays.size < regions.size) {
            createRegionOverlay(regionOverlays.size)
        }

        selectedRegionIndex = DeadZoneRegionStore.selectedIndex(prefs).coerceIn(0, regions.lastIndex)
        selectOverlay(selectedRegionIndex, syncPreferences = false)
        val editable = prefs.getBoolean(KEY_EDITABLE, true)
        regions.forEachIndexed { index, region -> updateRegionWindow(index, region, editable, persist = false) }
    }

    private fun createRegionOverlay(index: Int) {
        val layoutParams = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            width = 1
            height = 1
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = DeadZoneView(this).apply {
            setOnTouchListener { _, event -> handleDeadZoneTouch(index, event) }
            addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
                changedView.post { syncActualLocationIfChanged(index, changedView) }
            }
            setOnApplyWindowInsetsListener { changedView, insets ->
                changedView.post { syncActualLocationIfChanged(index, changedView) }
                insets
            }
        }
        regionOverlays += RegionOverlay(index, view, layoutParams)
        windowManager.addView(view, layoutParams)
    }

    private fun selectOverlay(index: Int, syncPreferences: Boolean) {
        val overlay = regionOverlays.getOrNull(index) ?: return
        if (syncPreferences && index != DeadZoneRegionStore.selectedIndex(prefs)) {
            DeadZoneRegionStore.select(prefs, index, currentDisplayWidth(), currentDisplayHeight())
        }
        selectedRegionIndex = index
        deadZoneView = overlay.view
        params = overlay.layoutParams
        windowOffsetX = overlay.offsetX
        windowOffsetY = overlay.offsetY
        regionOverlays.forEachIndexed { regionIndex, regionOverlay ->
            regionOverlay.view.setSelectedForEditing(regionIndex == index)
        }
    }

    private fun handleDeadZoneTouch(index: Int, event: MotionEvent): Boolean {
        if (!prefs.getBoolean(KEY_EDITABLE, true)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectOverlay(index, syncPreferences = true)
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                longPressStartRawX = event.rawX
                longPressStartRawY = event.rawY
                longPressTriggered = false
                isOverlayDragging = true
                deadZoneView?.let { updateWindowOffsetFromView(selectedRegionIndex, it) }
                dragStartCenterX = params.x + windowOffsetX + params.width / 2
                dragStartCenterY = params.y + windowOffsetY + params.height / 2
                scheduleControlPanelLongPress()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelControlPanelLongPress()
                isOverlayDragging = true
                if (event.pointerCount >= 2) {
                    transformStartDistance = pointerDistance(event).coerceAtLeast(1f)
                    transformStartAngle = pointerAngle(event)
                    transformStartWidth = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)
                    transformStartHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
                    transformStartRotation = prefs.getInt(KEY_ROTATION, 0)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                cancelControlPanelLongPress()
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                deadZoneView?.let { updateWindowOffsetFromView(selectedRegionIndex, it) }
                dragStartCenterX = params.x + windowOffsetX + params.width / 2
                dragStartCenterY = params.y + windowOffsetY + params.height / 2
                isOverlayDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hasMovedPastLongPressSlop(event)) cancelControlPanelLongPress()
                if (longPressTriggered) return true

                if (event.pointerCount >= 2) {
                    val scale = pointerDistance(event) / transformStartDistance
                    val angleDelta = pointerAngle(event) - transformStartAngle
                    updateWindow(
                        prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X),
                        prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
                        (transformStartWidth * scale).roundToInt(),
                        (transformStartHeight * scale).roundToInt(),
                        normalizeRotation((transformStartRotation + angleDelta).roundToInt()),
                        prefs.getInt(KEY_CORNER, DEFAULT_CORNER),
                        true
                    )
                } else {
                    updateWindow(
                        dragStartCenterX + (event.rawX - dragStartRawX).roundToInt(),
                        dragStartCenterY + (event.rawY - dragStartRawY).roundToInt(),
                        prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
                        prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
                        prefs.getInt(KEY_ROTATION, 0),
                        prefs.getInt(KEY_CORNER, DEFAULT_CORNER),
                        true
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelControlPanelLongPress()
                longPressTriggered = false
                isOverlayDragging = false
                refreshDaemonConfigFromActualView()
                return true
            }
        }
        return true
    }

    private fun scheduleControlPanelLongPress() {
        cancelControlPanelLongPress()
        handler.postDelayed(showPanelRunnable, 3000L)
    }

    private fun cancelControlPanelLongPress() {
        handler.removeCallbacks(showPanelRunnable)
    }

    private val showPanelRunnable = Runnable {
        longPressTriggered = true
        showControlPanel()
    }

    private fun hasMovedPastLongPressSlop(event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        return abs(event.rawX - longPressStartRawX) > slop || abs(event.rawY - longPressStartRawY) > slop
    }

    private fun pointerDistance(event: MotionEvent): Float {
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun pointerAngle(event: MotionEvent): Float {
        return Math.toDegrees(atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0)).toDouble()).toFloat()
    }

    private fun normalizeRotation(value: Int): Int {
        var normalized = value
        while (normalized > 180) normalized -= 360
        while (normalized < -180) normalized += 360
        return normalized
    }

    fun updateWindow(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        rotation: Int,
        corner: Int,
        editable: Boolean,
        persist: Boolean = true
    ) {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        val current = DeadZoneRegionStore.load(prefs, selectedRegionIndex, displayWidth, displayHeight)
        val updated = current.copy(
            centerX = centerX,
            centerY = centerY,
            width = width,
            height = height,
            rotation = rotation,
            corner = corner,
            visualMode = prefs.getString(KEY_VISUAL_MODE, current.visualMode) ?: current.visualMode,
            fillAlpha = prefs.getInt(KEY_ALPHA, current.fillAlpha),
            labelText = prefs.getString(KEY_LABEL_TEXT, current.labelText) ?: current.labelText,
            textSize = prefs.getInt(KEY_TEXT_SIZE, current.textSize),
            textColor = prefs.getInt(KEY_TEXT_COLOR, current.textColor),
            textAlpha = prefs.getInt(KEY_TEXT_ALPHA, current.textAlpha),
            textFont = prefs.getString(KEY_TEXT_FONT, current.textFont) ?: current.textFont,
            textBold = prefs.getBoolean(KEY_TEXT_BOLD, current.textBold),
            textItalic = prefs.getBoolean(KEY_TEXT_ITALIC, current.textItalic),
            textStroke = prefs.getInt(KEY_TEXT_STROKE, current.textStroke)
        )
        updateRegionWindow(selectedRegionIndex, updated, editable, persist)
        if (persist) DeadZoneRegionStore.loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
    }

    private fun updateRegionWindow(index: Int, region: DeadZoneRegion, editable: Boolean, persist: Boolean) {
        val overlay = regionOverlays.getOrNull(index) ?: return
        val safeWidth = region.width.coerceIn(40, 5000)
        val safeHeight = region.height.coerceIn(20, 5000)
        val safeRotation = region.rotation.coerceIn(-180, 180)
        val safeCorner = region.corner.coerceIn(0, 240)

        val points = DeadZoneView.defaultLocalPoints(safeWidth, safeHeight)
        overlay.view.setConfig(safeWidth, safeHeight, safeRotation, DEFAULT_ALPHA, safeCorner, points, editable)
        overlay.view.setVisualMode(region.visualMode, region.fillAlpha)
        overlay.view.setTextConfig(
            region.labelText,
            region.textSize,
            region.textColor,
            region.textAlpha,
            region.textFont,
            region.textBold,
            region.textItalic,
            region.textStroke
        )
        overlay.view.visibility = if (region.enabled) View.VISIBLE else View.GONE

        val bounds = polygonBounds(points, safeRotation)
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        val centerLimits = DeadZoneGeometry.centerLimits(
            points,
            safeRotation,
            displayWidth,
            displayHeight,
            dp(MINIMUM_VISIBLE_DP)
        )
        val safeCenterX = centerLimits.clampX(region.centerX)
        val safeCenterY = centerLimits.clampY(region.centerY)
        overlay.layoutParams.width = bounds.first.coerceIn(1, displayWidth)
        overlay.layoutParams.height = bounds.second.coerceIn(1, displayHeight)
        overlay.layoutParams.x = safeCenterX - overlay.offsetX - overlay.layoutParams.width / 2
        overlay.layoutParams.y = safeCenterY - overlay.offsetY - overlay.layoutParams.height / 2
        overlay.layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (editable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        if (persist) {
            val saved = region.copy(
                centerX = safeCenterX,
                centerY = safeCenterY,
                width = safeWidth,
                height = safeHeight,
                rotation = safeRotation,
                corner = safeCorner
            )
            DeadZoneRegionStore.save(prefs, index, saved, displayWidth, displayHeight)
            if (index == selectedRegionIndex) {
                prefs.edit().putBoolean(KEY_EDITABLE, editable).apply()
                DeadZoneRegionStore.loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
            }
        }

        try {
            windowManager.updateViewLayout(overlay.view, overlay.layoutParams)
        } catch (_: IllegalArgumentException) {
        }
        overlay.view.post {
            updateWindowOffsetFromView(index, overlay.view)
            if (!editable) scheduleDaemonConfigWrite()
        }
    }

    private fun updateWindowOffsetFromView(index: Int, view: View) {
        val overlay = regionOverlays.getOrNull(index) ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        overlay.offsetX = location[0] - overlay.layoutParams.x
        overlay.offsetY = location[1] - overlay.layoutParams.y
        if (index == selectedRegionIndex) {
            windowOffsetX = overlay.offsetX
            windowOffsetY = overlay.offsetY
        }
    }

    private fun syncActualLocationIfChanged(index: Int, view: View) {
        if (view.width <= 0 || view.height <= 0 || isOverlayDragging) return
        val overlay = regionOverlays.getOrNull(index) ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val changed = location[0] != overlay.lastScreenX || location[1] != overlay.lastScreenY
        overlay.offsetX = location[0] - overlay.layoutParams.x
        overlay.offsetY = location[1] - overlay.layoutParams.y
        if (!changed) return

        overlay.lastScreenX = location[0]
        overlay.lastScreenY = location[1]
        if (!prefs.getBoolean(KEY_EDITABLE, true)) {
            scheduleDaemonConfigWrite()
        }
    }

    private fun restorePixelsFromNormalizedPrefsIfNeeded() {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        DeadZoneRegionStore.restoreForDisplay(prefs, displayWidth, displayHeight, dp(MINIMUM_VISIBLE_DP))
        DeadZoneRegionStore.loadSelectedIntoLegacy(prefs, displayWidth, displayHeight)
    }

    fun refreshDaemonConfigFromActualView() {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        pendingDaemonConfigWrite = null
        writeDaemonConfigFromAllViews()
    }

    fun actualConfigJson(): String? {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        pendingDaemonConfigWrite = null
        if (regionOverlays.isEmpty()) return null
        return daemonConfigJsonForRegions(actualRegions())
    }

    private fun scheduleDaemonConfigWrite() {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingDaemonConfigWrite = null
            writeDaemonConfigFromAllViews()
        }
        pendingDaemonConfigWrite = runnable
        handler.postDelayed(runnable, 40L)
    }

    private fun rotatedBounds(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        val radians = Math.toRadians(rotation.toDouble())
        val rotatedWidth = abs(width * cos(radians)) + abs(height * sin(radians))
        val rotatedHeight = abs(width * sin(radians)) + abs(height * cos(radians))
        val padding = dp(96)
        return Pair(rotatedWidth.roundToInt() + padding, rotatedHeight.roundToInt() + padding)
    }

    private fun showControlPanel() {
        if (controlPanelView != null) return

        val currentWidth = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(40, 5000)
        val currentHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(20, 5000)
        val currentRotation = prefs.getInt(KEY_ROTATION, 0).coerceIn(-180, 180)
        val centerLimits = DeadZoneGeometry.centerLimits(
            DeadZoneView.defaultLocalPoints(currentWidth, currentHeight),
            currentRotation,
            currentDisplayWidth(),
            currentDisplayHeight(),
            dp(MINIMUM_VISIBLE_DP)
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 18, 22, 30))
                setStroke(dp(1), Color.argb(140, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
            setOnTouchListener { _, event -> handlePanelDrag(event) }
        }
        titleRow.addView(TextView(this).apply {
            text = "阻断条调节 · 区域 ${selectedRegionIndex + 1}"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        titleRow.addView(panelButton("锁定") {
            lockOverlayFromPanel()
        }, LinearLayout.LayoutParams(dp(76), dp(42)))
        titleRow.addView(panelButton("关闭") {
            hideControlPanel()
        }, LinearLayout.LayoutParams(dp(76), dp(42)))
        panel.addView(titleRow)

        addPanelSeekRow(panel, "X", centerLimits.minX, centerLimits.maxX, prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X)) {
            updateFromPanel(centerX = it)
        }
        addPanelSeekRow(panel, "Y", centerLimits.minY, centerLimits.maxY, prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y)) {
            updateFromPanel(centerY = it)
        }
        addPanelSeekRow(panel, "宽度", 40, currentDisplayWidth().coerceAtLeast(1400), prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)) {
            updateFromPanel(width = it)
        }
        addPanelSeekRow(panel, "高度", 20, currentDisplayHeight().coerceAtLeast(900), prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)) {
            updateFromPanel(height = it)
        }
        addPanelSeekRow(panel, "旋转", -180, 180, prefs.getInt(KEY_ROTATION, 0)) {
            updateFromPanel(rotation = it)
        }
        addPanelSeekRow(panel, "圆角", 0, 240, prefs.getInt(KEY_CORNER, DEFAULT_CORNER)) {
            updateFromPanel(corner = it)
        }
        controlPanelParams = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            width = currentDisplayWidth().coerceAtMost(dp(430))
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = clampWindowPosition(prefs.getInt("PANEL_X", dp(18)), width, currentDisplayWidth())
            y = clampWindowPosition(prefs.getInt("PANEL_Y", dp(72)), dp(64), currentDisplayHeight())
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        controlPanelView = panel
        try {
            windowManager.addView(panel, controlPanelParams)
        } catch (_: Exception) {
            controlPanelView = null
            controlPanelParams = null
        }
    }

    private fun panelButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
    }

    private fun handlePanelDrag(event: MotionEvent): Boolean {
        val panelParams = controlPanelParams ?: return false
        val panelView = controlPanelView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panelDragStartRawX = event.rawX
                panelDragStartRawY = event.rawY
                panelDragStartX = panelParams.x
                panelDragStartY = panelParams.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                panelParams.x = clampWindowPosition(
                    panelDragStartX + (event.rawX - panelDragStartRawX).roundToInt(),
                    panelParams.width,
                    currentDisplayWidth()
                )
                panelParams.y = clampWindowPosition(
                    panelDragStartY + (event.rawY - panelDragStartRawY).roundToInt(),
                    dp(64),
                    currentDisplayHeight()
                )
                try {
                    windowManager.updateViewLayout(panelView, panelParams)
                } catch (_: Exception) {
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                prefs.edit()
                    .putInt("PANEL_X", panelParams.x)
                    .putInt("PANEL_Y", panelParams.y)
                    .apply()
                return true
            }
        }
        return true
    }

    private fun lockOverlayFromPanel() {
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, currentDisplayWidth(), currentDisplayHeight())
        prefs.edit().putBoolean(KEY_EDITABLE, false).apply()
        reloadRegions()
        refreshDaemonConfigFromActualView()
        hideControlPanel()
    }

    private fun hideControlPanel() {
        controlPanelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        controlPanelView = null
        controlPanelParams = null
    }

    private fun addPanelSeekRow(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        onValueChanged: (Int) -> Unit
    ) {
        val safeMax = max.coerceAtLeast(min + 1)
        val valueText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.END
            text = value.coerceIn(min, safeMax).toString()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(210, 216, 226))
            textSize = 14f
        }, LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(SeekBar(this).apply {
            this.max = safeMax - min
            progress = value.coerceIn(min, safeMax) - min
            minHeight = dp(52)
            setPadding(dp(14), 0, dp(14), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val realValue = min + progress
                    valueText.text = realValue.toString()
                    onValueChanged(realValue)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(valueText, LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)))
    }

    private fun updateFromPanel(
        centerX: Int = prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X),
        centerY: Int = prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
        width: Int = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
        height: Int = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
        rotation: Int = prefs.getInt(KEY_ROTATION, 0),
        corner: Int = prefs.getInt(KEY_CORNER, DEFAULT_CORNER)
    ) {
        updateWindow(centerX, centerY, width, height, rotation, corner, prefs.getBoolean(KEY_EDITABLE, true))
    }

    private fun polygonBounds(points: FloatArray, rotation: Int): Pair<Int, Int> {
        val radians = Math.toRadians(rotation.toDouble())
        var maxX = 1f
        var maxY = 1f
        for (i in 0 until 4) {
            val lx = points[i * 2]
            val ly = points[i * 2 + 1]
            val rx = (lx * cos(radians) - ly * sin(radians)).toFloat()
            val ry = (lx * sin(radians) + ly * cos(radians)).toFloat()
            maxX = max(maxX, abs(rx))
            maxY = max(maxY, abs(ry))
        }
        val padding = dp(120)
        return Pair((maxX * 2f).roundToInt() + padding, (maxY * 2f).roundToInt() + padding)
    }

    private fun clampWindowPosition(position: Int, windowSize: Int, displaySize: Int): Int {
        val maxPosition = (displaySize - windowSize).coerceAtLeast(0)
        return position.coerceIn(0, maxPosition)
    }

    private fun actualRegions(): List<DeadZoneRegion> {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        val stored = DeadZoneRegionStore.regions(prefs, displayWidth, displayHeight)
        return stored.mapIndexed { index, region ->
            val overlay = regionOverlays.getOrNull(index) ?: return@mapIndexed region
            if (overlay.view.width <= 0 || overlay.view.height <= 0) return@mapIndexed region
            val location = IntArray(2)
            overlay.view.getLocationOnScreen(location)
            region.copy(
                centerX = location[0] + overlay.view.width / 2,
                centerY = location[1] + overlay.view.height / 2
            )
        }
    }

    private fun writeDaemonConfigFromAllViews() {
        try {
            File(filesDir, CONFIG_FILE).writeText(daemonConfigJsonForRegions(actualRegions()))
        } catch (_: Exception) {
        }
    }

    private fun daemonConfigJsonForRegions(regions: List<DeadZoneRegion>): String {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        val first = regions.firstOrNull() ?: DeadZoneRegion(centerX = displayWidth / 2, centerY = displayHeight / 2)
        return buildString {
            appendLine("{")
            appendLine("  \"enabled\": true,")
            appendLine("  \"displayWidth\": $displayWidth,")
            appendLine("  \"displayHeight\": $displayHeight,")
            appendLine("  \"centerX\": ${first.centerX},")
            appendLine("  \"centerY\": ${first.centerY},")
            appendLine("  \"width\": ${first.width},")
            appendLine("  \"height\": ${first.height},")
            appendLine("  \"rotation\": ${first.rotation},")
            appendLine("  \"zoneCount\": ${regions.size.coerceAtMost(DeadZoneRegionStore.MAX_REGIONS)},")
            regions.take(DeadZoneRegionStore.MAX_REGIONS).forEachIndexed { index, region ->
                appendLine("  \"z${index}Enabled\": ${region.enabled},")
                appendLine("  \"z${index}CenterX\": ${region.centerX},")
                appendLine("  \"z${index}CenterY\": ${region.centerY},")
                appendLine("  \"z${index}Width\": ${region.width},")
                appendLine("  \"z${index}Height\": ${region.height},")
                appendLine("  \"z${index}Rotation\": ${region.rotation},")
            }
            appendLine("  \"editable\": ${prefs.getBoolean(KEY_EDITABLE, true)}")
            appendLine("}")
        }
    }

    private fun currentDisplayWidth(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels
    }

    private fun currentDisplayHeight(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun startForegroundNotif() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "阻断条运行", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("物理阻断条")
            .setContentText("阻断条悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun stopForegroundAndOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        cancelControlPanelLongPress()
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        pendingDaemonConfigWrite = null
        hideControlPanel()
        regionOverlays.toList().forEach { overlay ->
            try {
                windowManager.removeView(overlay.view)
            } catch (_: Exception) {
            }
        }
        regionOverlays.clear()
        deadZoneView = null
        instance = null
        stopForegroundAndOverlay()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            restorePixelsFromNormalizedPrefsIfNeeded()
            reloadRegions()
            refreshDaemonConfigFromActualView()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForegroundAndOverlay()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
