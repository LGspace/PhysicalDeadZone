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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class DeadZoneService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var deadZoneView: DeadZoneView? = null
    private var controlPanelView: View? = null
    private var controlPanelParams: WindowManager.LayoutParams? = null
    private var pendingDaemonConfigWrite: Runnable? = null
    private var windowOffsetX = 0
    private var windowOffsetY = 0
    private var lastViewScreenX = Int.MIN_VALUE
    private var lastViewScreenY = Int.MIN_VALUE

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

        var instance: DeadZoneService? = null

        fun updateLayout(
            centerX: Int,
            centerY: Int,
            width: Int,
            height: Int,
            rotation: Int,
            alpha: Int,
            corner: Int,
            editable: Boolean,
            persist: Boolean = true
        ) {
            instance?.updateWindow(centerX, centerY, width, height, rotation, alpha, corner, editable, persist)
        }
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
        deadZoneView = DeadZoneView(this).apply {
            setOnTouchListener { _, event -> handleDeadZoneTouch(event) }
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                view.post { syncActualLocationIfChanged(view) }
            }
            setOnApplyWindowInsetsListener { view, insets ->
                view.post { syncActualLocationIfChanged(view) }
                insets
            }
        }
        params = WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyPrefsToWindow()
        windowManager.addView(deadZoneView, params)
    }

    private fun handleDeadZoneTouch(event: MotionEvent): Boolean {
        if (!prefs.getBoolean(KEY_EDITABLE, true)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                longPressStartRawX = event.rawX
                longPressStartRawY = event.rawY
                longPressTriggered = false
                isOverlayDragging = true
                deadZoneView?.let { updateWindowOffsetFromView(it) }
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
                deadZoneView?.let { updateWindowOffsetFromView(it) }
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
                        prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
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
                        prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
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

    private fun applyPrefsToWindow() {
        restorePixelsFromNormalizedPrefsIfNeeded()
        updateWindow(
            prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X),
            prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
            prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
            prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
            prefs.getInt(KEY_ROTATION, 0),
            prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
            prefs.getInt(KEY_CORNER, DEFAULT_CORNER),
            prefs.getBoolean(KEY_EDITABLE, true),
            persist = false
        )
    }

    fun updateWindow(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean,
        persist: Boolean = true
    ) {
        val safeWidth = width.coerceIn(40, 5000)
        val safeHeight = height.coerceIn(20, 5000)
        val safeRotation = rotation.coerceIn(-180, 180)
        val safeAlpha = alpha.coerceIn(20, 230)
        val safeCorner = corner.coerceIn(0, 240)

        val points = loadLocalPoints(safeWidth, safeHeight)
        deadZoneView?.setConfig(safeWidth, safeHeight, safeRotation, safeAlpha, safeCorner, points)

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
        val safeCenterX = centerLimits.clampX(centerX)
        val safeCenterY = centerLimits.clampY(centerY)
        params.width = bounds.first.coerceIn(1, displayWidth)
        params.height = bounds.second.coerceIn(1, displayHeight)
        params.x = safeCenterX - windowOffsetX - params.width / 2
        params.y = safeCenterY - windowOffsetY - params.height / 2
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (editable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        val actualCenterX = safeCenterX
        val actualCenterY = safeCenterY
        if (persist) {
            prefs.edit()
                .putInt(KEY_CENTER_X, actualCenterX)
                .putInt(KEY_CENTER_Y, actualCenterY)
                .putInt(KEY_WIDTH, safeWidth)
                .putInt(KEY_HEIGHT, safeHeight)
                .putInt(KEY_ROTATION, safeRotation)
                .putInt(KEY_ALPHA, safeAlpha)
                .putInt(KEY_CORNER, safeCorner)
            .putBoolean(KEY_EDITABLE, editable)
                .apply()
            saveNormalizedPrefs(actualCenterX, actualCenterY, displayWidth, displayHeight)
        }

        deadZoneView?.let {
            try {
                windowManager.updateViewLayout(it, params)
            } catch (_: IllegalArgumentException) {
            }
            it.post {
                updateWindowOffsetFromView(it)
                if (!editable) {
                    scheduleDaemonConfigWrite(it, safeWidth, safeHeight, safeRotation, safeAlpha, safeCorner, editable)
                }
            }
        }
    }

    private fun updateWindowOffsetFromView(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        windowOffsetX = location[0] - params.x
        windowOffsetY = location[1] - params.y
    }

    private fun syncActualLocationIfChanged(view: View) {
        if (view.width <= 0 || view.height <= 0 || isOverlayDragging) return
        val deadZone = view as? DeadZoneView ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val changed = location[0] != lastViewScreenX || location[1] != lastViewScreenY
        windowOffsetX = location[0] - params.x
        windowOffsetY = location[1] - params.y
        if (!changed) return

        lastViewScreenX = location[0]
        lastViewScreenY = location[1]
        if (!prefs.getBoolean(KEY_EDITABLE, true)) {
            scheduleDaemonConfigWrite(
                deadZone,
                prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(40, 5000),
                prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(20, 5000),
                prefs.getInt(KEY_ROTATION, 0).coerceIn(-180, 180),
                prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(20, 230),
                prefs.getInt(KEY_CORNER, DEFAULT_CORNER).coerceIn(0, 240),
                false
            )
        }
    }

    private fun restorePixelsFromNormalizedPrefsIfNeeded() {
        val displayWidth = currentDisplayWidth().coerceAtLeast(1)
        val displayHeight = currentDisplayHeight().coerceAtLeast(1)
        val lastWidth = prefs.getInt(KEY_LAST_DISPLAY_W, 0)
        val lastHeight = prefs.getInt(KEY_LAST_DISPLAY_H, 0)
        val hasNormalized = prefs.contains(KEY_CENTER_X_PCT) && prefs.contains(KEY_CENTER_Y_PCT)
        if (!hasNormalized) {
            saveNormalizedPrefs(
                prefs.getInt(KEY_CENTER_X, displayWidth / 2),
                prefs.getInt(KEY_CENTER_Y, displayHeight / 2),
                displayWidth,
                displayHeight
            )
            return
        }
        if (lastWidth == displayWidth && lastHeight == displayHeight) return
        val width = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(40, 5000)
        val height = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(20, 5000)
        val centerX = (prefs.getFloat(KEY_CENTER_X_PCT, 0.5f) * displayWidth).roundToInt()
        val centerY = (prefs.getFloat(KEY_CENTER_Y_PCT, 0.5f) * displayHeight).roundToInt()
        val points = loadLocalPoints(width, height)
        val limits = DeadZoneGeometry.centerLimits(
            points,
            prefs.getInt(KEY_ROTATION, 0).coerceIn(-180, 180),
            displayWidth,
            displayHeight,
            dp(MINIMUM_VISIBLE_DP)
        )
        prefs.edit()
            .putInt(KEY_CENTER_X, limits.clampX(centerX))
            .putInt(KEY_CENTER_Y, limits.clampY(centerY))
            .putInt(KEY_WIDTH, width)
            .putInt(KEY_HEIGHT, height)
            .putInt(KEY_LAST_DISPLAY_W, displayWidth)
            .putInt(KEY_LAST_DISPLAY_H, displayHeight)
            .apply()
    }

    private fun saveNormalizedPrefs(centerX: Int, centerY: Int, displayWidth: Int, displayHeight: Int) {
        val dw = displayWidth.coerceAtLeast(1).toFloat()
        val dh = displayHeight.coerceAtLeast(1).toFloat()
        prefs.edit()
            .putFloat(KEY_CENTER_X_PCT, centerX / dw)
            .putFloat(KEY_CENTER_Y_PCT, centerY / dh)
            .putInt(KEY_LAST_DISPLAY_W, displayWidth)
            .putInt(KEY_LAST_DISPLAY_H, displayHeight)
            .apply()
    }

    fun refreshDaemonConfigFromActualView() {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        pendingDaemonConfigWrite = null
        val view = deadZoneView ?: return
        writeDaemonConfigFromActualView(
            view,
            prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(40, 5000),
            prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(20, 5000),
            prefs.getInt(KEY_ROTATION, 0).coerceIn(-180, 180),
            prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(20, 230),
            prefs.getInt(KEY_CORNER, DEFAULT_CORNER).coerceIn(0, 240),
            prefs.getBoolean(KEY_EDITABLE, true)
        )
    }

    fun actualConfigJson(): String? {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        pendingDaemonConfigWrite = null
        val view = deadZoneView ?: return null
        return daemonConfigJsonFromActualView(
            view,
            prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH).coerceIn(40, 5000),
            prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT).coerceIn(20, 5000),
            prefs.getInt(KEY_ROTATION, 0).coerceIn(-180, 180),
            prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(20, 230),
            prefs.getInt(KEY_CORNER, DEFAULT_CORNER).coerceIn(0, 240),
            prefs.getBoolean(KEY_EDITABLE, true)
        )
    }

    private fun scheduleDaemonConfigWrite(
        view: DeadZoneView,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean
    ) {
        pendingDaemonConfigWrite?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingDaemonConfigWrite = null
            writeDaemonConfigFromActualView(view, width, height, rotation, alpha, corner, editable)
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
            loadLocalPoints(currentWidth, currentHeight),
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
            text = "阻断条调节"
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
            scaleQuadToSize(newWidth = it, newHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT))
            updateFromPanel(width = it)
        }
        addPanelSeekRow(panel, "高度", 20, currentDisplayHeight().coerceAtLeast(900), prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)) {
            scaleQuadToSize(newWidth = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH), newHeight = it)
            updateFromPanel(height = it)
        }
        addPanelSeekRow(panel, "旋转", -180, 180, prefs.getInt(KEY_ROTATION, 0)) {
            updateFromPanel(rotation = it)
        }
        addPanelSeekRow(panel, "圆角", 0, 240, prefs.getInt(KEY_CORNER, DEFAULT_CORNER)) {
            updateFromPanel(corner = it)
        }
        addPanelSeekRow(panel, "透明", 20, 230, prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA)) {
            updateFromPanel(alpha = it)
        }
        panel.addView(panelButton("重置为矩形") {
            resetQuadToRectangle()
            updateFromPanel()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

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
        updateWindow(
            prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X),
            prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
            prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
            prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
            prefs.getInt(KEY_ROTATION, 0),
            prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
            prefs.getInt(KEY_CORNER, DEFAULT_CORNER),
            editable = false
        )
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
        alpha: Int = prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
        corner: Int = prefs.getInt(KEY_CORNER, DEFAULT_CORNER)
    ) {
        updateWindow(centerX, centerY, width, height, rotation, alpha, corner, prefs.getBoolean(KEY_EDITABLE, true))
    }

    private fun resetQuadToRectangle(
        width: Int = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
        height: Int = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
    ) {
        saveLocalPoints(DeadZoneView.defaultLocalPoints(width, height))
    }

    private fun scaleQuadToSize(newWidth: Int, newHeight: Int) {
        if (!prefs.getBoolean(KEY_QUAD_ENABLED, false)) return
        val oldWidth = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)
        val oldHeight = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
        if (oldWidth <= 0 || oldHeight <= 0 || newWidth <= 0 || newHeight <= 0) return
        val points = loadLocalPoints(oldWidth, oldHeight)
        val scaleX = newWidth.toFloat() / oldWidth.toFloat()
        val scaleY = newHeight.toFloat() / oldHeight.toFloat()
        for (i in 0 until 4) {
            points[i * 2] *= scaleX
            points[i * 2 + 1] *= scaleY
        }
        saveLocalPoints(points)
    }

    private fun loadLocalPoints(width: Int, height: Int): FloatArray {
        val defaults = DeadZoneView.defaultLocalPoints(width, height)
        if (!prefs.getBoolean(KEY_QUAD_ENABLED, false)) return defaults
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = prefs.getFloat("P${i}_X", defaults[i * 2])
            out[i * 2 + 1] = prefs.getFloat("P${i}_Y", defaults[i * 2 + 1])
        }
        return out
    }

    private fun saveLocalPoints(points: FloatArray) {
        prefs.edit()
            .putBoolean(KEY_QUAD_ENABLED, true)
            .putFloat("P0_X", points[0])
            .putFloat("P0_Y", points[1])
            .putFloat("P1_X", points[2])
            .putFloat("P1_Y", points[3])
            .putFloat("P2_X", points[4])
            .putFloat("P2_Y", points[5])
            .putFloat("P3_X", points[6])
            .putFloat("P3_Y", points[7])
            .apply()
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

    private fun absoluteQuadPoints(centerX: Float, centerY: Float, width: Int, height: Int, rotation: Int): FloatArray {
        val points = loadLocalPoints(width, height)
        val radians = Math.toRadians(rotation.toDouble())
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val lx = points[i * 2]
            val ly = points[i * 2 + 1]
            out[i * 2] = centerX + (lx * cos(radians) - ly * sin(radians)).toFloat()
            out[i * 2 + 1] = centerY + (lx * sin(radians) + ly * cos(radians)).toFloat()
        }
        return out
    }

    private fun writeDaemonConfig(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean
    ) {
        val quad = absoluteQuadPoints(centerX.toFloat(), centerY.toFloat(), width, height, rotation)
        writeDaemonConfigWithQuad(centerX, centerY, width, height, rotation, alpha, corner, editable, quad)
    }

    private fun writeDaemonConfigWithQuad(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean,
        quad: FloatArray
    ) {
        val json = daemonConfigJsonWithQuad(centerX, centerY, width, height, rotation, alpha, corner, editable, quad)
        try {
            File(filesDir, CONFIG_FILE).writeText(json)
        } catch (_: Exception) {
        }
    }

    private fun daemonConfigJsonWithQuad(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean,
        quad: FloatArray
    ): String {
        return String.format(
            Locale.US,
            """{
  "enabled": true,
  "displayWidth": %d,
  "displayHeight": %d,
  "centerX": %d,
  "centerY": %d,
  "width": %d,
  "height": %d,
  "rotation": %d,
  "alpha": %d,
  "corner": %d,
  "editable": %s,
  "shape": "quad",
  "points": [
    { "x": %.1f, "y": %.1f },
    { "x": %.1f, "y": %.1f },
    { "x": %.1f, "y": %.1f },
    { "x": %.1f, "y": %.1f }
  ],
  "p0x": %.1f,
  "p0y": %.1f,
  "p1x": %.1f,
  "p1y": %.1f,
  "p2x": %.1f,
  "p2y": %.1f,
  "p3x": %.1f,
  "p3y": %.1f
}
""",
            currentDisplayWidth(),
            currentDisplayHeight(),
            centerX,
            centerY,
            width,
            height,
            rotation,
            alpha,
            corner,
            editable.toString(),
            quad[0],
            quad[1],
            quad[2],
            quad[3],
            quad[4],
            quad[5],
            quad[6],
            quad[7],
            quad[0],
            quad[1],
            quad[2],
            quad[3],
            quad[4],
            quad[5],
            quad[6],
            quad[7]
        )
    }

    private fun writeDaemonConfigFromActualView(
        view: DeadZoneView,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean
    ) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val quad = absoluteQuadPointsFromView(view, location)
        val centerX = ((quad[0] + quad[2] + quad[4] + quad[6]) / 4f).roundToInt()
        val centerY = ((quad[1] + quad[3] + quad[5] + quad[7]) / 4f).roundToInt()
        writeDaemonConfigWithQuad(centerX, centerY, width, height, rotation, alpha, corner, editable, quad)
    }

    private fun daemonConfigJsonFromActualView(
        view: DeadZoneView,
        width: Int,
        height: Int,
        rotation: Int,
        alpha: Int,
        corner: Int,
        editable: Boolean
    ): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val quad = absoluteQuadPointsFromView(view, location)
        val centerX = ((quad[0] + quad[2] + quad[4] + quad[6]) / 4f).roundToInt()
        val centerY = ((quad[1] + quad[3] + quad[5] + quad[7]) / 4f).roundToInt()
        return daemonConfigJsonWithQuad(centerX, centerY, width, height, rotation, alpha, corner, editable, quad)
    }

    private fun absoluteQuadPointsFromView(view: DeadZoneView, location: IntArray): FloatArray {
        val points = view.copyLocalPoints()
        val radians = Math.toRadians(view.rotationDegrees.toDouble())
        val centerX = location[0] + view.width / 2f
        val centerY = location[1] + view.height / 2f
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val lx = points[i * 2]
            val ly = points[i * 2 + 1]
            out[i * 2] = centerX + (lx * cos(radians) - ly * sin(radians)).toFloat()
            out[i * 2 + 1] = centerY + (lx * sin(radians) + ly * cos(radians)).toFloat()
        }
        return out
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
        deadZoneView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        deadZoneView = null
        instance = null
        stopForegroundAndOverlay()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            restorePixelsFromNormalizedPrefsIfNeeded()
            updateWindow(
                prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X),
                prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
                prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
                prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
                prefs.getInt(KEY_ROTATION, 0),
                prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA),
                prefs.getInt(KEY_CORNER, DEFAULT_CORNER),
                prefs.getBoolean(KEY_EDITABLE, true)
            )
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
