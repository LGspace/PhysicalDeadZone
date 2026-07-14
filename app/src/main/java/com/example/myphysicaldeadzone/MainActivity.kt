package com.example.myphysicaldeadzone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Page(val title: String, val tab: String) {
        BACKEND("后端初始化", "后端"),
        OVERLAY("阻断条调节", "阻断"),
        PRESETS("配置预设", "预设"),
        STATUS("真实状态", "状态"),
        CONTROL("控制状态", "控制"),
        DEBUG("调试工具", "调试")
    }

    private val bg = Color.rgb(8, 11, 15)
    private val side = Color.rgb(12, 16, 22)
    private val card = Color.rgb(18, 23, 31)
    private val card2 = Color.rgb(25, 31, 40)
    private val red = Color.rgb(235, 54, 61)
    private val cyan = Color.rgb(45, 185, 235)
    private val white = Color.rgb(245, 248, 252)
    private val gray = Color.rgb(160, 172, 190)

    private lateinit var backend: BackendController
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private var debugLogView: TextView? = null
    private val statusHandler = Handler(Looper.getMainLooper())

    private var page = Page.BACKEND
    private var status = BackendStatus()
    private var overlayRunning = false
    private var screenW = 1440
    private var screenH = 3200
    private var debugLogText = "暂无日志"
    private var controlTrace = "暂无控制记录"
    private var statusPollRunning = false
    private var debugLogPollRunning = false
    private var debugLogReadInFlight = false
    private var sideNavMode = false
    private var lastOverlaySyncMs = 0L
    private val pendingOverlaySync = Runnable {
        lastOverlaySyncMs = android.os.SystemClock.uptimeMillis()
        applyOverlay()
    }
    private val overlaySyncIntervalMs = 16L

    private val statusPollRunnable = object : Runnable {
        override fun run() {
            if (!statusPollRunning) return
            thread {
                val next = backend.loadStatus()
                runOnUiThread {
                    val changed = next.running != status.running ||
                        next.backend != status.backend ||
                        next.root != status.root ||
                        next.device != status.device ||
                        next.screen != status.screen ||
                        next.daemonState != status.daemonState ||
                        next.message != status.message
                    status = status.copy(
                        root = next.root,
                        backend = next.backend,
                        device = next.device,
                        screen = next.screen,
                        running = next.running,
                        daemonState = next.daemonState,
                        message = next.message
                    )
                    if (changed) render()
                    statusHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private val debugLogPollRunnable = object : Runnable {
        override fun run() {
            if (!debugLogPollRunning || page != Page.DEBUG) return
            if (!debugLogReadInFlight) {
                debugLogReadInFlight = true
                thread {
                    val text = backend.readLog(120)
                    runOnUiThread {
                        debugLogReadInFlight = false
                        if (page == Page.DEBUG) {
                            debugLogText = text
                            debugLogView?.text = text
                            statusHandler.postDelayed(this, 600L)
                        }
                    }
                }
            } else {
                statusHandler.postDelayed(this, 600L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = BackendController(this)
        prefs = getSharedPreferences(DeadZoneService.PREFS, Context.MODE_PRIVATE)
        refresh()
        buildUi()
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (sideNavMode != useSideNav()) {
            buildUi()
        }
        render()
        startStatusPolling()
    }

    override fun onPause() {
        stopStatusPolling()
        stopDebugLogPolling()
        super.onPause()
    }

    override fun onDestroy() {
        stopStatusPolling()
        stopDebugLogPolling()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::nav.isInitialized && sideNavMode != useSideNav()) {
            buildUi()
            render()
        }
    }

    private fun refresh() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels.coerceAtLeast(1)
        screenH = metrics.heightPixels.coerceAtLeast(1)
        DeadZoneRegionStore.ensureMigrated(prefs, screenW, screenH)
        restorePixelsFromNormalizedPrefsIfNeeded()
        status = backend.loadStatus()
        overlayRunning = DeadZoneService.instance != null
    }

    private fun startStatusPolling() {
        if (statusPollRunning) return
        statusPollRunning = true
        statusHandler.removeCallbacks(statusPollRunnable)
        statusHandler.postDelayed(statusPollRunnable, 1000L)
    }

    private fun stopStatusPolling() {
        statusPollRunning = false
        statusHandler.removeCallbacks(statusPollRunnable)
    }

    private fun startDebugLogPolling() {
        if (debugLogPollRunning) return
        debugLogPollRunning = true
        statusHandler.removeCallbacks(debugLogPollRunnable)
        statusHandler.post(debugLogPollRunnable)
    }

    private fun stopDebugLogPolling() {
        debugLogPollRunning = false
        statusHandler.removeCallbacks(debugLogPollRunnable)
    }

    private fun buildUi() {
        val sideNav = useSideNav()
        sideNavMode = sideNav
        val root = LinearLayout(this).apply {
            orientation = if (sideNav) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        nav = LinearLayout(this).apply {
            orientation = if (sideNav) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), if (sideNav) dp(16) else dp(8), dp(10), if (sideNav) dp(16) else dp(8))
            setBackgroundColor(side)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = if (isCompactWindow()) dp(12) else dp(18)
            setPadding(horizontalPadding, dp(18), horizontalPadding, dp(18))
        }
        if (sideNav) {
            root.addView(nav, LinearLayout.LayoutParams(dp(132), LinearLayout.LayoutParams.MATCH_PARENT))
            root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)))
        }
        setContentView(root)
    }

    private fun render() {
        val sideNav = useSideNav()
        if (sideNav != sideNavMode) {
            buildUi()
            return render()
        }
        nav.removeAllViews()
        if (sideNav) {
            nav.addView(label("阻断条", 21f, white, true, Gravity.CENTER), lp(-1, 48))
            nav.addView(label("ROOT 触控", 11f, gray, false, Gravity.CENTER), lp(-1, 26))
        }
        Page.values().forEach {
            val tabParams = if (sideNav) {
                lp(-1, 48).apply { topMargin = dp(8) }
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
            }
            nav.addView(tab(it), tabParams)
        }

        content.removeAllViews()
        debugLogView = null
        content.addView(header(page.title), lp(-1, 42))
        when (page) {
            Page.BACKEND -> backendPage()
            Page.OVERLAY -> overlayPage()
            Page.PRESETS -> presetsPage()
            Page.STATUS -> statusPage()
            Page.CONTROL -> controlPage()
            Page.DEBUG -> debugPage()
        }
        if (page == Page.DEBUG) startDebugLogPolling() else stopDebugLogPolling()
    }

    private fun backendPage() {
        content.addView(row(
            tile("Root权限", cn(status.root), status.root == "Granted"),
            tile("后端", cn(status.backend), status.backend == "Installed"),
            tile("守护进程", cn(status.running), status.running == "Running")
        ), lp(-1, tileRowHeight()))
        content.addView(info("设备信息", listOf(
            "触摸设备" to cn(status.device),
            "屏幕尺寸" to cn(status.screen),
            "后端状态" to cn(status.daemonState),
            "安装路径" to BackendController.TARGET_PATH
        )))
        if (status.message.isNotBlank()) {
            content.addView(log(zh(status.message)), lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        content.addView(section("初始化"))
        content.addView(buttons(
            mainButton("检测 Root") { task { backend.checkRoot() } },
            mainButton("自动检测") { task { backend.autoDetect() } },
            mainButton("安装后端") { task { backend.installBackend() } }
        ))
        content.addView(buttons(
            mainButton("启动后端") {
                startRealBackend()
            },
            darkButton("停止后端") {
                stopOverlayService()
                task { backend.stopBackendViaControl() }
            },
            darkButton("卸载后端") {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("卸载后端")
                    .setMessage("将停止正在运行的后端，并删除 /data/local/tmp 中的后端程序和日志。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("卸载") { _, _ ->
                        task { backend.uninstallBackend() }
                    }
                    .show()
            }
        ))
    }

    private fun overlayPage() {
        val editableNow = prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)
        val regionCount = DeadZoneRegionStore.count(prefs)
        val selectedRegion = DeadZoneRegionStore.selectedIndex(prefs)
        content.addView(row(
            tile("悬浮窗", if (overlayRunning) "显示中" else "未显示", overlayRunning),
            tile("模式", if (prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)) "编辑" else "穿透", true),
            tile("区域", "${selectedRegion + 1} / $regionCount", true)
        ), lp(-1, tileRowHeight()))
        content.addView(buttons(mainButton(if (overlayRunning) "隐藏悬浮窗" else "显示悬浮窗") { toggleOverlay() }))
        content.addView(switchCard("编辑模式", prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)) {
            prefs.edit().putBoolean(DeadZoneService.KEY_EDITABLE, it).apply()
            applyOverlay()
            if (it) {
                task { backend.disableBackend() }
            } else {
                startRealBackend()
            }
            render()
        })
        content.addView(section("阻断区域（最多8个矩形）"))
        DeadZoneRegionStore.regions(prefs, screenW, screenH).forEachIndexed { index, region ->
            content.addView(darkButton(
                "${if (index == selectedRegion) "✓ " else ""}区域 ${index + 1} · ${if (region.enabled) "启用" else "停用"} · ${if (region.visualMode == DeadZoneService.VISUAL_MODE_ORIGINAL) "原始框" else region.labelText.ifBlank { "无文字" }}"
            ) {
                if (!ensureEditModeForAdjustment()) return@darkButton
                DeadZoneRegionStore.select(prefs, index, screenW, screenH)
                DeadZoneService.instance?.reloadRegions()
                render()
            }, lp(-1, 48).apply { bottomMargin = dp(7) })
        }
        content.addView(buttons(
            darkButton("新增区域") {
                if (!ensureEditModeForAdjustment()) return@darkButton
                if (DeadZoneRegionStore.add(prefs, screenW, screenH, dp(32)) == null) {
                    Toast.makeText(this, "最多只能创建8个阻断区域", Toast.LENGTH_SHORT).show()
                }
                DeadZoneService.instance?.reloadRegions()
                writeConfig()
                render()
            },
            darkButton("复制当前") {
                if (!ensureEditModeForAdjustment()) return@darkButton
                if (DeadZoneRegionStore.duplicate(prefs, screenW, screenH, dp(32)) == null) {
                    Toast.makeText(this, "最多只能创建8个阻断区域", Toast.LENGTH_SHORT).show()
                }
                DeadZoneService.instance?.reloadRegions()
                writeConfig()
                render()
            },
            darkButton("删除当前") {
                if (!ensureEditModeForAdjustment()) return@darkButton
                if (!DeadZoneRegionStore.deleteSelected(prefs, screenW, screenH)) {
                    Toast.makeText(this, "至少需要保留一个阻断区域", Toast.LENGTH_SHORT).show()
                }
                DeadZoneService.instance?.reloadRegions()
                writeConfig()
                render()
            }
        ))
        content.addView(switchCard("启用当前区域", DeadZoneRegionStore.load(prefs, selectedRegion, screenW, screenH).enabled) {
            if (!ensureEditModeForAdjustment()) return@switchCard
            DeadZoneRegionStore.setEnabled(prefs, DeadZoneRegionStore.selectedIndex(prefs), it, screenW, screenH)
            DeadZoneService.instance?.reloadRegions()
            writeConfig()
            render()
        })
        content.addView(section("位置"))
        val currentWidth = prefs.getInt(DeadZoneService.KEY_WIDTH, DeadZoneService.DEFAULT_WIDTH).coerceIn(40, 5000)
        val currentHeight = prefs.getInt(DeadZoneService.KEY_HEIGHT, DeadZoneService.DEFAULT_HEIGHT).coerceIn(20, 5000)
        val currentRotation = prefs.getInt(DeadZoneService.KEY_ROTATION, 0).coerceIn(-180, 180)
        val centerLimits = currentCenterLimits(currentWidth, currentHeight, currentRotation)
        seek("中心 X", centerLimits.minX, centerLimits.maxX, DeadZoneService.KEY_CENTER_X, screenW / 2)
        seek("中心 Y", centerLimits.minY, centerLimits.maxY, DeadZoneService.KEY_CENTER_Y, screenH / 2)
        content.addView(section("形状"))
        seek("宽度", 40, screenW.coerceAtLeast(1400), DeadZoneService.KEY_WIDTH, DeadZoneService.DEFAULT_WIDTH)
        seek("高度", 20, screenH.coerceAtLeast(900), DeadZoneService.KEY_HEIGHT, DeadZoneService.DEFAULT_HEIGHT)
        seek("旋转", -180, 180, DeadZoneService.KEY_ROTATION, 0)
        seek("圆角", 0, 240, DeadZoneService.KEY_CORNER, DeadZoneService.DEFAULT_CORNER)
        content.addView(section("显示模式"))
        val selectedVisualMode = prefs.getString(DeadZoneService.KEY_VISUAL_MODE, DeadZoneService.DEFAULT_VISUAL_MODE)
            ?: DeadZoneService.DEFAULT_VISUAL_MODE
        fun selectVisualMode(mode: String) {
            if (!ensureEditModeForAdjustment()) return
            prefs.edit().putString(DeadZoneService.KEY_VISUAL_MODE, mode).apply()
            applyOverlay()
            render()
        }
        content.addView(buttons(
            darkButton(
                if (selectedVisualMode == DeadZoneService.VISUAL_MODE_ORIGINAL) "✓ 原始阻断框" else "原始阻断框"
            ) { selectVisualMode(DeadZoneService.VISUAL_MODE_ORIGINAL) },
            darkButton(
                if (selectedVisualMode == DeadZoneService.VISUAL_MODE_TEXT) "✓ 镶嵌文字" else "镶嵌文字"
            ) { selectVisualMode(DeadZoneService.VISUAL_MODE_TEXT) }
        ))

        if (selectedVisualMode == DeadZoneService.VISUAL_MODE_ORIGINAL) {
            content.addView(log("显示原始红色半透明矩形和白色边框，实际阻断范围与矩形完全一致。"), lp(-1, -2).apply {
                bottomMargin = dp(10)
            })
            seek("矩形透明", 20, 230, DeadZoneService.KEY_ALPHA, DeadZoneService.DEFAULT_ALPHA)
        } else {
        content.addView(section("镶嵌文字"))
        content.addView(log("运行时矩形背景和边框完全透明，只显示下方文字；矩形本身仍是实际阻断范围。"), lp(-1, -2).apply {
            bottomMargin = dp(10)
        })

        val textInput = EditText(this).apply {
            setText(prefs.getString(DeadZoneService.KEY_LABEL_TEXT, DeadZoneService.DEFAULT_LABEL_TEXT))
            hint = "输入一行文字"
            isEnabled = editableNow
            setTextColor(white)
            setHintTextColor(gray)
            setSingleLine(true)
            maxLines = 1
            setPadding(dp(14), 0, dp(14), 0)
            background = round(card, 8)
        }
        content.addView(textInput, lp(-1, 52).apply { bottomMargin = dp(8) })

        val storedTextColor = prefs.getInt(DeadZoneService.KEY_TEXT_COLOR, DeadZoneService.DEFAULT_TEXT_COLOR)
        val colorInput = EditText(this).apply {
            setText(String.format(Locale.US, "#%06X", storedTextColor and 0xFFFFFF))
            hint = "文字颜色，例如 #FFFFFF"
            isEnabled = editableNow
            setTextColor(white)
            setHintTextColor(gray)
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = round(card, 8)
        }
        content.addView(colorInput, lp(-1, 52).apply { bottomMargin = dp(8) })
        content.addView(buttons(mainButton("应用文字和颜色") {
            if (!ensureEditModeForAdjustment()) return@mainButton
            val colorText = colorInput.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
            val parsedColor = try {
                Color.parseColor(colorText)
            } catch (_: IllegalArgumentException) {
                Toast.makeText(this, "颜色格式无效，请使用 #RRGGBB", Toast.LENGTH_SHORT).show()
                return@mainButton
            }
            prefs.edit()
                .putString(DeadZoneService.KEY_LABEL_TEXT, textInput.text.toString().take(120))
                .putInt(DeadZoneService.KEY_TEXT_COLOR, parsedColor or 0xFF000000.toInt())
                .apply()
            applyOverlay()
            render()
        }))

        val selectedFont = prefs.getString(DeadZoneService.KEY_TEXT_FONT, DeadZoneService.DEFAULT_TEXT_FONT)
        fun selectFont(font: String) {
            if (!ensureEditModeForAdjustment()) return
            prefs.edit().putString(DeadZoneService.KEY_TEXT_FONT, font).apply()
            applyOverlay()
            render()
        }
        content.addView(buttons(
            darkButton(if (selectedFont == "sans") "✓ 默认字体" else "默认字体") { selectFont("sans") },
            darkButton(if (selectedFont == "serif") "✓ 衬线字体" else "衬线字体") { selectFont("serif") },
            darkButton(if (selectedFont == "mono") "✓ 等宽字体" else "等宽字体") { selectFont("mono") }
        ))
        seek("字号上限", 6, 240, DeadZoneService.KEY_TEXT_SIZE, DeadZoneService.DEFAULT_TEXT_SIZE)
        seek("文字透明", 0, 255, DeadZoneService.KEY_TEXT_ALPHA, DeadZoneService.DEFAULT_TEXT_ALPHA)
        seek("黑色描边", 0, 12, DeadZoneService.KEY_TEXT_STROKE, DeadZoneService.DEFAULT_TEXT_STROKE)
        content.addView(switchCard("粗体", prefs.getBoolean(DeadZoneService.KEY_TEXT_BOLD, true)) {
            prefs.edit().putBoolean(DeadZoneService.KEY_TEXT_BOLD, it).apply()
            applyOverlay()
            render()
        })
        content.addView(switchCard("斜体", prefs.getBoolean(DeadZoneService.KEY_TEXT_ITALIC, false)) {
            prefs.edit().putBoolean(DeadZoneService.KEY_TEXT_ITALIC, it).apply()
            applyOverlay()
            render()
        })
        }
        content.addView(buttons(
            darkButton("回到中心") {
                if (!ensureEditModeForAdjustment()) return@darkButton
                prefs.edit()
                    .putInt(DeadZoneService.KEY_CENTER_X, screenW / 2)
                    .putInt(DeadZoneService.KEY_CENTER_Y, screenH / 2)
                    .apply()
                applyOverlay()
                render()
            },
            darkButton("重置形状") {
                if (!ensureEditModeForAdjustment()) return@darkButton
                prefs.edit()
                    .putInt(DeadZoneService.KEY_WIDTH, DeadZoneService.DEFAULT_WIDTH)
                    .putInt(DeadZoneService.KEY_HEIGHT, DeadZoneService.DEFAULT_HEIGHT)
                    .putInt(DeadZoneService.KEY_ROTATION, 0)
                    .putInt(DeadZoneService.KEY_CORNER, DeadZoneService.DEFAULT_CORNER)
                    .putInt(DeadZoneService.KEY_ALPHA, DeadZoneService.DEFAULT_ALPHA)
                    .apply()
                prefs.edit().putBoolean(DeadZoneService.KEY_QUAD_ENABLED, false).apply()
                applyOverlay()
                render()
            }
        ))
    }

    private fun presetsPage() {
        content.addView(section("整体配置预设"))
        content.addView(log("新预设会保存并恢复全部1～8个阻断区域。旧版预设仍只应用到当前区域。"), lp(-1, -2).apply {
            bottomMargin = dp(10)
        })
        val names = presetNames().toList().sorted()
        if (names.isEmpty()) content.addView(log("还没有保存的配置"), lp(-1, -2))
        names.forEach { name ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = round(card, 8)
                addView(label(name, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(smallButton("应用") { applyPreset(name); render() }, lp(72, 40))
                addView(smallButton("删除") { deletePreset(name); render() }, lp(72, 40))
            }, lp(-1, 58).apply { bottomMargin = dp(8) })
        }
        val input = EditText(this).apply {
            hint = "配置名称"
            setTextColor(white)
            setHintTextColor(gray)
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = round(card, 8)
        }
        content.addView(input, lp(-1, 52).apply { topMargin = dp(8) })
        content.addView(buttons(mainButton("保存当前配置") {
            savePreset(input.text.toString().trim().ifBlank { "配置 ${presetNames().size + 1}" })
            render()
        }))
    }

    private fun statusPage() {
        val file = File(filesDir, DeadZoneService.CONFIG_FILE)
        content.addView(section("真实生效配置"))
        if (!file.exists()) {
            content.addView(log("配置文件尚未生成。请先显示悬浮窗或启动一次后端。"), lp(-1, -2))
            return
        }
        val json = file.readText()
        content.addView(info("配置文件", listOf("路径" to file.absolutePath)))
        content.addView(info("坐标", listOf(
            "显示宽度" to (jsonValue(json, "displayWidth") ?: "-"),
            "显示高度" to (jsonValue(json, "displayHeight") ?: "-"),
            "中心 X" to (jsonValue(json, "centerX") ?: "-"),
            "中心 Y" to (jsonValue(json, "centerY") ?: "-")
        )))
        content.addView(info("外观", listOf(
            "宽度" to (jsonValue(json, "width") ?: "-"),
            "高度" to (jsonValue(json, "height") ?: "-"),
            "旋转" to (jsonValue(json, "rotation") ?: "-"),
            "透明度" to (jsonValue(json, "alpha") ?: "-"),
            "圆角" to (jsonValue(json, "corner") ?: "-")
        )))
        content.addView(buttons(darkButton("刷新") { render() }))
    }

    private fun debugPage() {
        val bp = getSharedPreferences(BackendController.PREF_BACKEND, Context.MODE_PRIVATE)
        val device = bp.getString(BackendController.KEY_DEVICE, "/dev/input/eventX")
        val screen = bp.getString(BackendController.KEY_SCREEN, backend.currentScreen())
        val commandPath = File(filesDir, BackendController.COMMAND_NAME).absolutePath
        val cmd = "${BackendController.TARGET_PATH} --device $device --config ${File(filesDir, DeadZoneService.CONFIG_FILE).absolutePath} --screen $screen --state ${BackendController.STATE_PATH} --command $commandPath --rotate ${currentDisplayRotation()} --emit --grab --verbose --log-state"
        content.addView(section("启动命令"))
        content.addView(log(cmd), lp(-1, -2).apply { bottomMargin = dp(10) })
        content.addView(info("校准字段", listOf(
            "raw" to "真实触摸设备坐标",
            "screen" to "映射到屏幕后的坐标",
            "inside/blocked" to "是否进入/正在阻断区",
            "virtual/crossed" to "虚拟触摸是否按下/是否穿过阻断区"
        )))
        content.addView(buttons(
            mainButton("调试启动") {
                startDebugBackend()
            },
            mainButton("刷新状态") {
                status = backend.loadStatus()
                render()
            },
            darkButton("写入配置") {
                writeConfig()
                render()
            }
        ))
        content.addView(section("后端日志"))
        debugLogView = log(debugLogText)
        content.addView(debugLogView, lp(-1, -2).apply { bottomMargin = dp(10) })
        content.addView(buttons(
            mainButton("读取日志") { readBackendLog() },
            darkButton("清空日志") { clearBackendLog() }
        ))
    }

    private fun controlPage() {
        content.addView(row(
            tile("控制", "文件命令/20ms", true),
            tile("后端", cn(status.running), status.running == "Running"),
            tile("状态", cn(status.daemonState), status.daemonState == "RUNNING_ENABLED")
        ), lp(-1, tileRowHeight()))
        content.addView(info("控制说明", listOf(
            "通道" to "文件命令轮询，20ms 检查一次",
            "SET_CONFIG" to "同步屏幕上最多8个矩形区域坐标",
            "ENABLE" to "启用阻断并接管真实触摸",
            "DISABLE" to "暂停阻断并释放真实触摸"
        )))
        content.addView(buttons(
            mainButton("测试 PING") {
                controlTask("PING") { backend.pingBackend() }
            },
            mainButton("发送 DISABLE") {
                controlTask("DISABLE") { backend.disableBackend() }
            },
            darkButton("刷新状态") {
                status = backend.loadStatus()
                render()
            }
        ))
        content.addView(section("最近控制"))
        content.addView(log(controlTrace), lp(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun task(block: () -> BackendStatus) {
        status = status.copy(message = "执行中...")
        render()
        thread {
            val next = block()
            runOnUiThread {
                status = next
                render()
            }
        }
    }

    private fun controlTask(action: String, block: () -> BackendStatus) {
        status = status.copy(message = "执行中...")
        render()
        thread {
            val next = block()
            runOnUiThread {
                recordControl(action, next.message)
                status = next
                render()
            }
        }
    }

    private fun startRealBackend() {
        if (!ensureOverlayService()) return
        refresh()
        DeadZoneService.instance?.refreshDaemonConfigFromActualView()
        prefs.edit().putBoolean(DeadZoneService.KEY_EDITABLE, false).apply()
        applyOverlay()
        status = status.copy(message = "正在准备触摸接管...")
        render()
        Toast.makeText(this, "正在准备触摸接管", Toast.LENGTH_SHORT).show()
        runWhenOverlayReady {
            val config = DeadZoneService.instance?.actualConfigJson()
            if (config.isNullOrBlank()) {
                status = status.copy(message = "无法读取悬浮窗真实位置")
                render()
                return@runWhenOverlayReady
            }
            thread {
                val started = backend.startBackend(File(filesDir, DeadZoneService.CONFIG_FILE).absolutePath)
                if (!backendStartReady(started)) {
                    runOnUiThread {
                        recordControl("启动", started.message)
                        status = started
                        Toast.makeText(this, "阻断启动失败", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    return@thread
                }
                val configured = backend.sendConfig(config)
                val enabled = if (controlOk(configured.message)) backend.enableBackend() else configured
                runOnUiThread {
                    val message = backendMessages(started, configured, enabled)
                    recordControl("启动/SET_CONFIG/ENABLE", message)
                    status = enabled.copy(message = message)
                    Toast.makeText(this, if (backendEnabled(enabled, enabled.message)) "阻断已生效" else "阻断启用失败", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
    }

    private fun startDebugBackend() {
        if (!ensureOverlayService()) return
        refresh()
        DeadZoneService.instance?.refreshDaemonConfigFromActualView()
        prefs.edit().putBoolean(DeadZoneService.KEY_EDITABLE, false).apply()
        applyOverlay()
        debugLogText = "正在准备调试触摸接管..."
        status = status.copy(message = "正在准备调试触摸接管...")
        render()
        Toast.makeText(this, "调试模式正在接管触摸", Toast.LENGTH_SHORT).show()

        runWhenOverlayReady {
            val config = DeadZoneService.instance?.actualConfigJson()
            if (config.isNullOrBlank()) {
                status = status.copy(message = "无法读取悬浮窗真实位置")
                debugLogText = status.message
                render()
                return@runWhenOverlayReady
            }

            thread {
                val started = backend.startBackend(
                    File(filesDir, DeadZoneService.CONFIG_FILE).absolutePath,
                    debugLog = true
                )
                if (!backendStartReady(started)) {
                    val logText = backend.readLog()
                    runOnUiThread {
                        recordControl("调试启动", started.message)
                        status = started
                        debugLogText = logText.ifBlank { started.message }
                        Toast.makeText(this, "调试触摸接管失败", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    return@thread
                }

                val configured = backend.sendConfig(config)
                val enabled = if (controlOk(configured.message)) backend.enableBackend() else configured
                val message = backendMessages(started, configured, enabled)
                val logText = backend.readLog()
                runOnUiThread {
                    recordControl("调试启动/SET_CONFIG/ENABLE", message)
                    status = enabled.copy(message = message)
                    debugLogText = logText.ifBlank { message }
                    Toast.makeText(
                        this,
                        if (backendEnabled(enabled, enabled.message)) "调试阻断已生效" else "调试阻断启用失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    render()
                }
            }
        }
    }

    private fun enableBackendFromVisibleOverlay() {
        refresh()
        status = status.copy(message = "正在同步阻断位置...")
        render()
        runWhenOverlayReady {
            val config = DeadZoneService.instance?.actualConfigJson()
            if (config.isNullOrBlank()) {
                status = status.copy(message = "无法读取悬浮窗真实位置")
                render()
                return@runWhenOverlayReady
            }
            thread {
                val liveStatus = backend.loadStatus()
                if (!backendStartReady(liveStatus)) {
                    runOnUiThread {
                        recordControl("SET_CONFIG/ENABLE", "后端未运行，无法同步阻断位置。请先启动后端。")
                        status = liveStatus.copy(message = "后端未运行，无法同步阻断位置。请先启动后端。")
                        Toast.makeText(this, "后端未运行", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    return@thread
                }
                val configured = backend.sendConfig(config)
                val enabled = if (controlOk(configured.message)) backend.enableBackend() else configured
                runOnUiThread {
                    val message = backendMessages(configured, enabled)
                    recordControl("SET_CONFIG/ENABLE", message)
                    status = enabled.copy(message = message)
                    Toast.makeText(this, if (backendEnabled(enabled, enabled.message)) "阻断已生效" else "阻断启用失败", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
    }

    private fun controlOk(reply: String): Boolean {
        return Regex("\"ok\"\\s*:\\s*true").containsMatchIn(reply)
    }

    private fun runWhenOverlayReady(attempt: Int = 0, block: () -> Unit) {
        if (DeadZoneService.instance != null) {
            statusHandler.postDelayed(block, 50L)
            return
        }
        if (attempt >= 20) {
            block()
            return
        }
        statusHandler.postDelayed({ runWhenOverlayReady(attempt + 1, block) }, 50L)
    }

    private fun backendStartReady(started: BackendStatus): Boolean {
        return started.running == "Running" && started.daemonState != "FAILED"
    }

    private fun backendMessages(vararg statuses: BackendStatus): String {
        return statuses
            .map { it.message.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    private fun backendEnabled(nextStatus: BackendStatus, reply: String): Boolean {
        val state = Regex("\"state\"\\s*:\\s*\"([^\"]+)\"").find(reply)?.groupValues?.get(1)
        return controlOk(reply) && (state == "RUNNING_ENABLED" || nextStatus.daemonState == "RUNNING_ENABLED")
    }

    private fun recordControl(action: String, reply: String) {
        controlTrace = "操作：$action\n时间：${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}\n回复：\n${reply.ifBlank { "无回复" }}"
    }

    private fun readBackendLog() {
        debugLogText = "读取中..."
        render()
        thread {
            val text = backend.readLog()
            runOnUiThread {
                debugLogText = text
                status = backend.loadStatus()
                render()
            }
        }
    }

    private fun clearBackendLog() {
        debugLogText = "清空中..."
        render()
        thread {
            val text = backend.clearLog()
            runOnUiThread {
                debugLogText = text
                status = backend.loadStatus().copy(message = text)
                render()
            }
        }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(this, DeadZoneService::class.java)
        if (overlayRunning) {
            stopService(intent)
            overlayRunning = false
        } else {
            prefs.edit().putBoolean(DeadZoneService.KEY_EDITABLE, true).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            overlayRunning = true
            applyOverlay()
        }
        render()
    }

    private fun stopOverlayService() {
        try {
            stopService(Intent(this, DeadZoneService::class.java))
        } catch (_: Exception) {
        }
        overlayRunning = false
    }

    private fun ensureOverlayService(): Boolean {
        if (DeadZoneService.instance != null) return true
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        val intent = Intent(this, DeadZoneService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        overlayRunning = true
        return true
    }

    private fun ensureEditModeForAdjustment(): Boolean {
        if (prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)) return true
        Toast.makeText(this, "请先切换到编辑模式再调整阻断条", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun seek(name: String, min: Int, max: Int, key: String, fallback: Int) {
        val safeMax = max.coerceAtLeast(min + 1)
        val editableNow = prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)
        var applyingProgrammatically = false
        lateinit var seekBar: SeekBar
        lateinit var valueInput: EditText
        var pendingValue = clampParamValue(min, safeMax, prefs.getInt(key, fallback))

        fun syncControls(value: Int) {
            applyingProgrammatically = true
            seekBar.progress = (value.coerceIn(min, safeMax) - min).coerceIn(0, safeMax - min)
            if (valueInput.text.toString() != value.toString()) {
                valueInput.setText(value.toString())
                valueInput.setSelection(valueInput.text.length)
            }
            applyingProgrammatically = false
        }

        fun applyValue(rawValue: Int, force: Boolean = false) {
            if (!ensureEditModeForAdjustment()) {
                syncControls(pendingValue)
                return
            }
            val value = clampParamValue(min, safeMax, rawValue)

            prefs.edit().putInt(key, value).apply()
            if (key == DeadZoneService.KEY_WIDTH || key == DeadZoneService.KEY_HEIGHT) {
                clampSavedCenterInsideScreen()
            }
            saveNormalizedPrefs()
            pendingValue = prefs.getInt(key, value)
            syncControls(pendingValue)
            applyOverlayThrottled(force = force)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = round(card, 8)
            alpha = if (editableNow) 1f else 0.45f
        }
        row.addView(label(name, 14f, white), lp(76, -2))
        seekBar = SeekBar(this).apply {
            this.max = safeMax - min
            progress = pendingValue - min
            isEnabled = editableNow
            minHeight = dp(46)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || applyingProgrammatically) return
                    applyValue(min + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    applyValue(pendingValue, force = true)
                    render()
                }
            })
        }
        row.addView(seekBar, LinearLayout.LayoutParams(0, dp(50), 1f))
        valueInput = EditText(this).apply {
            setText(pendingValue.toString())
            isEnabled = editableNow
            setTextColor(white)
            setHintTextColor(gray)
            textSize = 13f
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or if (min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = stroke(Color.rgb(12, 16, 22), Color.rgb(54, 66, 82), 7)
            setPadding(dp(6), 0, dp(6), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyValue(text.toString().toIntOrNull() ?: pendingValue, force = true)
                    clearFocus()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) applyValue(text.toString().toIntOrNull() ?: pendingValue, force = true)
            }
        }
        row.addView(valueInput, lp(if (isCompactWindow()) 62 else 72, 40))
        content.addView(row, lp(-1, 58).apply { bottomMargin = dp(8) })
        syncControls(pendingValue)
    }

    private fun applyOverlay() {
        clampSavedCenterInsideScreen()
        saveNormalizedPrefs()
        val serviceRunning = DeadZoneService.instance != null
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, screenW, screenH)
        DeadZoneService.instance?.reloadRegions()
        if (!serviceRunning) writeConfig()
    }

    private fun clampParamValue(min: Int, max: Int, value: Int): Int {
        return value.coerceIn(min, max)
    }

    private fun clampSavedCenterInsideScreen() {
        val width = prefs.getInt(DeadZoneService.KEY_WIDTH, DeadZoneService.DEFAULT_WIDTH).coerceAtLeast(1)
        val height = prefs.getInt(DeadZoneService.KEY_HEIGHT, DeadZoneService.DEFAULT_HEIGHT).coerceAtLeast(1)
        val rotation = prefs.getInt(DeadZoneService.KEY_ROTATION, 0).coerceIn(-180, 180)
        val limits = currentCenterLimits(width, height, rotation)
        val centerX = prefs.getInt(DeadZoneService.KEY_CENTER_X, screenW / 2)
        val centerY = prefs.getInt(DeadZoneService.KEY_CENTER_Y, screenH / 2)
        val clampedX = limits.clampX(centerX)
        val clampedY = limits.clampY(centerY)
        if (clampedX != centerX || clampedY != centerY) {
            prefs.edit()
                .putInt(DeadZoneService.KEY_CENTER_X, clampedX)
                .putInt(DeadZoneService.KEY_CENTER_Y, clampedY)
                .apply()
        }
    }

    private fun restorePixelsFromNormalizedPrefsIfNeeded() {
        DeadZoneRegionStore.restoreForDisplay(prefs, screenW, screenH, dp(32))
        DeadZoneRegionStore.loadSelectedIntoLegacy(prefs, screenW, screenH)
    }

    private fun saveNormalizedPrefs() {
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, screenW, screenH)
    }

    private fun currentCenterLimits(width: Int, height: Int, rotation: Int): DeadZoneGeometry.CenterLimits {
        return DeadZoneGeometry.centerLimits(
            DeadZoneView.defaultLocalPoints(width, height),
            rotation,
            screenW,
            screenH,
            dp(32)
        )
    }

    private fun applyOverlayThrottled(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastOverlaySyncMs < overlaySyncIntervalMs) {
            statusHandler.removeCallbacks(pendingOverlaySync)
            statusHandler.postDelayed(pendingOverlaySync, overlaySyncIntervalMs - (now - lastOverlaySyncMs))
            return
        }
        statusHandler.removeCallbacks(pendingOverlaySync)
        lastOverlaySyncMs = now
        applyOverlay()
        if (force) {
            DeadZoneService.instance?.refreshDaemonConfigFromActualView() ?: writeConfig()
        }
    }

    private fun writeConfig() {
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, screenW, screenH)
        File(filesDir, DeadZoneService.CONFIG_FILE).writeText(
            backendConfigJson(DeadZoneRegionStore.regions(prefs, screenW, screenH))
        )
    }

    private fun backendConfigJson(regions: List<DeadZoneRegion>): String {
        val first = regions.firstOrNull() ?: DeadZoneRegion(centerX = screenW / 2, centerY = screenH / 2)
        return buildString {
            appendLine("{")
            appendLine("  \"enabled\": true,")
            appendLine("  \"displayWidth\": $screenW,")
            appendLine("  \"displayHeight\": $screenH,")
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
            appendLine("  \"editable\": ${prefs.getBoolean(DeadZoneService.KEY_EDITABLE, true)}")
            appendLine("}")
        }
    }

    private fun savePreset(name: String) {
        DeadZoneRegionStore.saveSelectedFromLegacy(prefs, screenW, screenH)
        getSharedPreferences("DeadZonePresets", Context.MODE_PRIVATE).edit()
            .putStringSet("names", presetNames().plus(name))
            .putString(name, presetValue())
            .apply()
    }

    private fun applyPreset(name: String) {
        val value = getSharedPreferences("DeadZonePresets", Context.MODE_PRIVATE).getString(name, null) ?: return
        if (!ensureEditModeForAdjustment()) return
        if (value.trimStart().startsWith("{")) {
            applyWholePresetJson(value)
        } else {
            applyLegacyPreset(value)
        }
    }

    private fun applyLegacyPreset(value: String) {
        val rawParts = value.split(",")
        val parts = rawParts.take(16).mapNotNull { it.toFloatOrNull() }
        if (parts.size < 7) return
        prefs.edit()
            .putInt(DeadZoneService.KEY_CENTER_X, parts[0].roundToInt())
            .putInt(DeadZoneService.KEY_CENTER_Y, parts[1].roundToInt())
            .putInt(DeadZoneService.KEY_WIDTH, parts[2].roundToInt())
            .putInt(DeadZoneService.KEY_HEIGHT, parts[3].roundToInt())
            .putInt(DeadZoneService.KEY_ROTATION, parts[4].roundToInt())
            .putInt(DeadZoneService.KEY_ALPHA, parts[5].roundToInt())
            .putInt(DeadZoneService.KEY_CORNER, parts[6].roundToInt())
            .apply()
        prefs.edit().putBoolean(DeadZoneService.KEY_QUAD_ENABLED, false).apply()
        if (rawParts.size >= 24) {
            val decodedText = try {
                String(
                    Base64.decode(rawParts[16], Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            } catch (_: IllegalArgumentException) {
                DeadZoneService.DEFAULT_LABEL_TEXT
            }
            prefs.edit()
                .putString(DeadZoneService.KEY_LABEL_TEXT, decodedText)
                .putString(DeadZoneService.KEY_TEXT_FONT, rawParts[17])
                .putInt(DeadZoneService.KEY_TEXT_SIZE, rawParts[18].toIntOrNull() ?: DeadZoneService.DEFAULT_TEXT_SIZE)
                .putInt(DeadZoneService.KEY_TEXT_COLOR, rawParts[19].toIntOrNull() ?: DeadZoneService.DEFAULT_TEXT_COLOR)
                .putInt(DeadZoneService.KEY_TEXT_ALPHA, rawParts[20].toIntOrNull() ?: DeadZoneService.DEFAULT_TEXT_ALPHA)
                .putBoolean(DeadZoneService.KEY_TEXT_BOLD, rawParts[21] == "1")
                .putBoolean(DeadZoneService.KEY_TEXT_ITALIC, rawParts[22] == "1")
                .putInt(DeadZoneService.KEY_TEXT_STROKE, rawParts[23].toIntOrNull() ?: DeadZoneService.DEFAULT_TEXT_STROKE)
                .apply()
        }
        prefs.edit().putString(
            DeadZoneService.KEY_VISUAL_MODE,
            if (rawParts.size >= 25 && rawParts[24] == DeadZoneService.VISUAL_MODE_ORIGINAL) {
                DeadZoneService.VISUAL_MODE_ORIGINAL
            } else {
                DeadZoneService.VISUAL_MODE_TEXT
            }
        ).apply()
        applyOverlay()
        writeConfig()
        Toast.makeText(this, "旧预设已应用到当前区域", Toast.LENGTH_SHORT).show()
    }

    private fun applyWholePresetJson(value: String) {
        try {
            val root = JSONObject(value)
            if (root.optInt("version", 0) < 2) throw IllegalArgumentException("不支持的预设版本")
            val array = root.optJSONArray("regions") ?: throw IllegalArgumentException("预设没有区域数据")
            if (array.length() == 0) throw IllegalArgumentException("预设区域为空")
            val restored = ArrayList<DeadZoneRegion>()
            for (index in 0 until minOf(array.length(), DeadZoneRegionStore.MAX_REGIONS)) {
                val item = array.getJSONObject(index)
                val width = item.optInt("width", DeadZoneService.DEFAULT_WIDTH).coerceIn(40, 5000)
                val height = item.optInt("height", DeadZoneService.DEFAULT_HEIGHT).coerceIn(20, 5000)
                val rotation = item.optInt("rotation", 0).coerceIn(-180, 180)
                val centerX = (item.optDouble("centerXPct", 0.5) * screenW).roundToInt()
                val centerY = (item.optDouble("centerYPct", 0.5) * screenH).roundToInt()
                val limits = DeadZoneGeometry.centerLimits(
                    DeadZoneView.defaultLocalPoints(width, height),
                    rotation,
                    screenW,
                    screenH,
                    dp(32)
                )
                restored += DeadZoneRegion(
                    enabled = item.optBoolean("enabled", true),
                    centerX = limits.clampX(centerX),
                    centerY = limits.clampY(centerY),
                    width = width,
                    height = height,
                    rotation = rotation,
                    corner = item.optInt("corner", DeadZoneService.DEFAULT_CORNER).coerceIn(0, 240),
                    visualMode = if (item.optString("visualMode") == DeadZoneService.VISUAL_MODE_ORIGINAL) {
                        DeadZoneService.VISUAL_MODE_ORIGINAL
                    } else {
                        DeadZoneService.VISUAL_MODE_TEXT
                    },
                    fillAlpha = item.optInt("fillAlpha", DeadZoneService.DEFAULT_ALPHA).coerceIn(20, 230),
                    labelText = item.optString("labelText", DeadZoneService.DEFAULT_LABEL_TEXT).take(120),
                    textSize = item.optInt("textSize", DeadZoneService.DEFAULT_TEXT_SIZE).coerceIn(6, 240),
                    textColor = item.optInt("textColor", DeadZoneService.DEFAULT_TEXT_COLOR),
                    textAlpha = item.optInt("textAlpha", DeadZoneService.DEFAULT_TEXT_ALPHA).coerceIn(0, 255),
                    textFont = item.optString("textFont", DeadZoneService.DEFAULT_TEXT_FONT),
                    textBold = item.optBoolean("textBold", true),
                    textItalic = item.optBoolean("textItalic", false),
                    textStroke = item.optInt("textStroke", DeadZoneService.DEFAULT_TEXT_STROKE).coerceIn(0, 12)
                )
            }
            DeadZoneRegionStore.replaceAll(
                prefs,
                restored,
                root.optInt("selectedRegion", 0),
                screenW,
                screenH
            )
            applyOverlay()
            writeConfig()
            Toast.makeText(this, "已恢复 ${restored.size} 个阻断区域", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "预设读取失败：${e.message.orEmpty()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deletePreset(name: String) {
        getSharedPreferences("DeadZonePresets", Context.MODE_PRIVATE).edit()
            .putStringSet("names", presetNames().minus(name))
            .remove(name)
            .apply()
    }

    private fun presetNames(): Set<String> {
        return getSharedPreferences("DeadZonePresets", Context.MODE_PRIVATE).getStringSet("names", emptySet()) ?: emptySet()
    }

    private fun presetValue(): String {
        val regions = DeadZoneRegionStore.regions(prefs, screenW, screenH)
        val dw = screenW.coerceAtLeast(1).toDouble()
        val dh = screenH.coerceAtLeast(1).toDouble()
        val array = JSONArray()
        regions.forEach { region ->
            array.put(JSONObject().apply {
                put("enabled", region.enabled)
                put("centerXPct", region.centerX / dw)
                put("centerYPct", region.centerY / dh)
                put("width", region.width)
                put("height", region.height)
                put("rotation", region.rotation)
                put("corner", region.corner)
                put("visualMode", region.visualMode)
                put("fillAlpha", region.fillAlpha)
                put("labelText", region.labelText)
                put("textSize", region.textSize)
                put("textColor", region.textColor)
                put("textAlpha", region.textAlpha)
                put("textFont", region.textFont)
                put("textBold", region.textBold)
                put("textItalic", region.textItalic)
                put("textStroke", region.textStroke)
            })
        }
        return JSONObject().apply {
            put("version", 2)
            put("selectedRegion", DeadZoneRegionStore.selectedIndex(prefs))
            put("displayWidth", screenW)
            put("displayHeight", screenH)
            put("regions", array)
        }.toString()
    }

    private fun cn(value: String): String = when (value) {
        "Not checked" -> "未检测"
        "Not installed" -> "未安装"
        "Installed" -> "已安装"
        "Install failed" -> "安装失败"
        "Not detected" -> "未识别"
        "Running" -> "运行中"
        "Stopped" -> "已停止"
        "STARTING" -> "启动中"
        "INPUT_OPENED" -> "输入已打开"
        "CONFIG_LOADED" -> "配置已加载"
        "UINPUT_CREATED" -> "虚拟触摸已创建"
        "UINPUT_READY" -> "虚拟触摸已就绪"
        "GRAB_DONE" -> "真实触摸已接管"
        "RUNNING" -> "运行中"
        "RUNNING_DISABLED" -> "运行中/阻断暂停"
        "RUNNING_ENABLED" -> "运行中/阻断生效"
        "FAILED" -> "失败"
        "STOPPED" -> "已停止"
        "UNKNOWN" -> "未知"
        "Granted" -> "已授权"
        "Failed" -> "失败"
        "Unknown" -> "未知"
        else -> value
    }

    private fun zh(value: String): String = when {
        value.contains("Run auto detect first") -> "请先自动检测触摸设备"
        value.contains("No multitouch device found") -> "没有识别到多点触控设备"
        value.contains("Detected ") -> value.replace("Detected", "已识别")
        value.contains("Start command sent") -> "启动命令已发送"
        value.contains("Stop command sent") -> "停止命令已发送"
        value.contains("Timeout") -> "命令超时"
        value.contains("backend process started") -> "后端进程已启动"
        value.contains("real input device opened") -> "真实触摸设备已打开"
        value.contains("deadzone config loaded") -> "阻断配置已加载"
        value.contains("virtual touch device created") -> "虚拟触摸设备已创建"
        value.contains("virtual touch device registered") -> "虚拟触摸设备已注册"
        value.contains("real input device grabbed") -> "真实触摸已接管"
        value.contains("backend running") -> "后端运行中"
        value.contains("backend stopped") -> "后端已停止"
        value.contains("virtual touch device was not registered") -> "虚拟触摸设备未被系统注册，已停止"
        value.contains("EVIOCGRAB failed") -> "接管真实触摸失败"
        value.contains("config file missing or unreadable") -> "配置文件不存在或无法读取"
        value.contains("selected device is not a multitouch touchscreen") -> "选择的设备不是多点触摸屏"
        else -> value
    }

    private fun jsonValue(json: String, key: String): String? {
        return Regex("\"$key\"\\s*:\\s*([^,}\\n]+)").find(json)?.groupValues?.get(1)?.trim()
    }

    private fun tab(p: Page): TextView {
        val selected = p == page
        return label(p.tab, if (useSideNav()) 15f else 12f, if (selected) cyan else Color.rgb(170, 180, 194), selected, Gravity.CENTER).apply {
            background = if (selected) stroke(Color.rgb(15, 25, 34), cyan, 8) else round(Color.TRANSPARENT, 8)
            setOnClickListener { page = p; render() }
        }
    }

    private fun header(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, if (isCompactWindow()) 21f else 25f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (!isCompactWindow()) {
            addView(label(if (status.running == "Running") "后端运行中" else "后端未运行", 13f, if (status.running == "Running") cyan else gray, false, Gravity.CENTER).apply {
                background = round(Color.rgb(22, 28, 38), 999)
                setPadding(dp(16), 0, dp(16), 0)
            }, lp(-2, 34))
        }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        val stacked = useStackedControls()
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            val params = if (stacked) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70))
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            if (stacked && index < views.lastIndex) params.bottomMargin = dp(8)
            if (!stacked && index < views.lastIndex) params.rightMargin = dp(10)
            addView(view, params)
        }
    }

    private fun tile(name: String, value: String, ok: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        background = stroke(card, if (ok) cyan else Color.rgb(70, 78, 94), 8)
        addView(label(name, 12f, gray))
        addView(label(value, 18f, white, true))
    }

    private fun info(title: String, rows: List<Pair<String, String>>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round(card, 8)
        addView(label(title, 15f, white, true))
        rows.forEach { addView(label("${it.first}   ${it.second}", 13f, gray).apply { setPadding(0, dp(6), 0, 0) }) }
    }.also { it.layoutParams = lp(-1, -2).apply { bottomMargin = dp(10) } }

    private fun switchCard(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        background = round(card, 8)
        addView(label(title, 15f, white), LinearLayout.LayoutParams(0, -2, 1f))
        addView(SwitchCompat(this@MainActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onChange(value) }
        })
    }.also { it.layoutParams = lp(-1, 56).apply { bottomMargin = dp(10) } }

    private fun buttons(vararg views: Button): LinearLayout = LinearLayout(this).apply {
        val stacked = useStackedControls() || views.size >= 3 && currentWindowWidthDp() < 620
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(2))
        views.forEachIndexed { index, view ->
            val params = if (stacked) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            } else {
                LinearLayout.LayoutParams(0, dp(48), 1f)
            }
            if (stacked && index < views.lastIndex) params.bottomMargin = dp(8)
            if (!stacked && index < views.lastIndex) params.rightMargin = dp(10)
            addView(view, params)
        }
    }

    private fun mainButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = if (isCompactWindow()) 13f else 14f
        typeface = Typeface.DEFAULT_BOLD
        background = round(cyan, 8)
        setOnClickListener { click() }
    }

    private fun darkButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = if (isCompactWindow()) 13f else 14f
        background = round(card2, 8)
        setOnClickListener { click() }
    }

    private fun smallButton(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 12f
        background = round(card2, 7)
        setOnClickListener { click() }
    }

    private fun section(text: String): TextView = label(text, 16f, white, true).apply {
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun log(text: String): TextView = label(text, 13f, Color.rgb(205, 214, 230)).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round(Color.rgb(13, 17, 24), 8)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, gravityValue: Int = Gravity.LEFT): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun round(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun stroke(color: Int, strokeColor: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(1), strokeColor)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(width: Int, height: Int): LinearLayout.LayoutParams {
        val realWidth = if (width > 0) dp(width) else width
        val realHeight = if (height > 0) dp(height) else height
        return LinearLayout.LayoutParams(realWidth, realHeight)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun currentWindowWidthDp(): Int {
        val configWidth = resources.configuration.screenWidthDp
        if (configWidth > 0) return configWidth
        return (resources.displayMetrics.widthPixels / max(resources.displayMetrics.density, 1f)).roundToInt()
    }

    private fun useSideNav(): Boolean = currentWindowWidthDp() >= 720

    private fun isCompactWindow(): Boolean = currentWindowWidthDp() < 520

    private fun useStackedControls(): Boolean = currentWindowWidthDp() < 560

    private fun tileRowHeight(): Int = if (useStackedControls()) 226 else 86

    private fun currentDisplayRotation(): Int {
        @Suppress("DEPRECATION")
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
