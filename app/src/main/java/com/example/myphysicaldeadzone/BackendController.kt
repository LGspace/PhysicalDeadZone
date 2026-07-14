package com.example.myphysicaldeadzone

import android.content.Context
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class BackendStatus(
    val root: String = "Not checked",
    val backend: String = "Not checked",
    val device: String = "Not detected",
    val screen: String = "Not detected",
    val running: String = "Not checked",
    val daemonState: String = "UNKNOWN",
    val message: String = ""
)

class BackendController(private val context: Context) {
    companion object {
        const val PREF_BACKEND = "BackendConfig"
        const val KEY_ROOT = "ROOT"
        const val KEY_DEVICE = "DEVICE"
        const val KEY_SCREEN = "SCREEN"
        const val KEY_STOP_ON_APP_CLOSE = "STOP_ON_APP_CLOSE"
        const val KEY_DEBUG_LOG_ACTIVE = "DEBUG_LOG_ACTIVE"
        const val TARGET_PATH = "/data/local/tmp/deadzone_daemon"
        const val LOG_PATH = "/data/local/tmp/deadzone_daemon.log"
        const val LAUNCH_LOG_PATH = "/data/local/tmp/deadzone_launch.log"
        const val STATE_PATH = "/data/local/tmp/deadzone_state.json"
        const val COMMAND_NAME = "deadzone_command.json"
        const val REQUIRED_BACKEND_VERSION = "deadzone_daemon file-control-v6"
        private const val ASSET_PATH = "backend/arm64-v8a/deadzone_daemon"
    }

    private val prefs = context.getSharedPreferences(PREF_BACKEND, Context.MODE_PRIVATE)

    private fun commandPath(): String = File(context.filesDir, COMMAND_NAME).absolutePath

    private data class TouchInfo(
        val device: String,
        val naturalScreen: String,
        val name: String,
        val score: Int
    )

    fun currentScreen(): String {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return "${metrics.widthPixels}x${metrics.heightPixels}"
    }

    fun loadStatus(): BackendStatus {
        val screen = normalizeScreen(prefs.getString(KEY_SCREEN, null) ?: currentScreen())
        val root = prefs.getString(KEY_ROOT, null) ?: "Not checked"
        val canUseRoot = root == "Granted"
        val stateJson = if (canUseRoot) readStateJson() else ""
        val state = jsonString(stateJson, "state") ?: "UNKNOWN"
        val stateMessage = jsonString(stateJson, "message").orEmpty()
        val processRunning = canUseRoot && isRunning()
        val running = when {
            state == "RUNNING" && processRunning -> "Running"
            state == "RUNNING_DISABLED" && processRunning -> "Running"
            state == "RUNNING_ENABLED" && processRunning -> "Running"
            state == "FAILED" -> "Failed"
            state == "STOPPED" || !processRunning -> "Stopped"
            state != "UNKNOWN" -> state
            else -> "Stopped"
        }
        return BackendStatus(
            root = root,
            backend = if (canUseRoot && runSu("test -x $TARGET_PATH").exitCode == 0) "Installed" else "Not installed",
            device = prefs.getString(KEY_DEVICE, null) ?: "Not detected",
            screen = screen,
            running = running,
            daemonState = state,
            message = stateMessage
        )
    }

    fun checkRoot(): BackendStatus {
        var result = runSu("id; whoami", timeoutMs = 10000)
        if (!hasRootIdentity(result.output)) {
            val fallback = runSuInteractive("id; whoami", timeoutMs = 10000)
            if (hasRootIdentity(fallback.output) || fallback.exitCode == 0 && result.exitCode != 0) result = fallback
        }
        val root = if (hasRootIdentity(result.output)) "Granted" else "Failed"
        prefs.edit().putString(KEY_ROOT, root).apply()
        return loadStatus().copy(root = root, message = result.output.ifBlank { result.error })
    }

    fun autoDetect(): BackendStatus {
        val result = runSu("getevent -lp")
        val touchInfo = parseTouchInfo(result.output)
        val device = touchInfo?.device
        val screen = normalizeScreen(touchInfo?.naturalScreen ?: currentScreen())
        val root = if (result.exitCode == 0) "Granted" else "Failed"
        if (device != null) {
            prefs.edit()
                .putString(KEY_ROOT, root)
                .putString(KEY_DEVICE, device)
                .putString(KEY_SCREEN, screen)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_ROOT, root)
                .putString(KEY_SCREEN, screen)
                .apply()
        }
        return loadStatus().copy(
            root = root,
            device = device ?: "Not detected",
            screen = screen,
            message = if (touchInfo == null) {
                "No multitouch device found"
            } else {
                "Detected ${touchInfo.device} ${touchInfo.name} screen=$screen score=${touchInfo.score}"
            }
        )
    }

    fun installBackend(): BackendStatus {
        val localFile = File(context.filesDir, "deadzone_daemon")
        return try {
            val rootCheck = runSu("id", timeoutMs = 10000)
            if (!hasRootIdentity(rootCheck.output)) {
                prefs.edit().putString(KEY_ROOT, "Failed").apply()
                return loadStatus().copy(
                    root = "Failed",
                    backend = "Install failed",
                    message = rootCheck.output.ifBlank { rootCheck.error.ifBlank { "Root permission unavailable" } }
                )
            }
            prefs.edit().putString(KEY_ROOT, "Granted").apply()

            context.assets.open(ASSET_PATH).use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            }
            localFile.setReadable(true, true)
            localFile.setExecutable(true, true)

            val cmd = "cat ${quote(localFile.absolutePath)} > $TARGET_PATH && " +
                "chmod 755 $TARGET_PATH && test -x $TARGET_PATH && " +
                "ls -l $TARGET_PATH && $TARGET_PATH --version"
            val result = runSu(cmd)
            val installed = result.exitCode == 0 && result.output.contains(REQUIRED_BACKEND_VERSION)
            loadStatus().copy(
                root = "Granted",
                backend = if (installed) "Installed" else "Install failed",
                message = result.output.ifBlank { result.error }
            )
        } catch (e: Exception) {
            loadStatus().copy(
                root = prefs.getString(KEY_ROOT, null) ?: "Not checked",
                backend = "Install failed",
                message = e.message.orEmpty()
            )
        }
    }

    fun uninstallBackend(): BackendStatus {
        val rootCheck = runSu("id", timeoutMs = 10000)
        if (!hasRootIdentity(rootCheck.output)) {
            prefs.edit().putString(KEY_ROOT, "Failed").apply()
            return loadStatus().copy(
                root = "Failed",
                message = rootCheck.output.ifBlank { rootCheck.error.ifBlank { "Root permission unavailable" } }
            )
        }

        prefs.edit().putString(KEY_ROOT, "Granted").apply()
        val pidPath = "/data/local/tmp/deadzone_daemon.pid"
        val result = runSu(
            "pkill deadzone_daemon >/dev/null 2>&1 || true; " +
                "rm -f $TARGET_PATH $LOG_PATH $LAUNCH_LOG_PATH $STATE_PATH $pidPath; " +
                "test ! -e $TARGET_PATH"
        )
        File(commandPath()).delete()
        File(context.filesDir, "deadzone_daemon").delete()
        prefs.edit().putBoolean(KEY_DEBUG_LOG_ACTIVE, false).apply()
        Thread.sleep(150)

        val removed = result.exitCode == 0 && runSu("test ! -e $TARGET_PATH").exitCode == 0
        return loadStatus().copy(
            root = "Granted",
            backend = if (removed) "Not installed" else "Uninstall failed",
            running = if (isRunning()) "Running" else "Stopped",
            daemonState = if (isRunning()) "UNKNOWN" else "STOPPED",
            message = if (removed) "后端已停止并卸载" else result.output.ifBlank {
                result.error.ifBlank { "卸载后端失败" }
            }
        )
    }

    fun startBackend(configPath: String, debugLog: Boolean = false): BackendStatus {
        val device = prefs.getString(KEY_DEVICE, null)
        val screen = normalizeScreen(prefs.getString(KEY_SCREEN, null) ?: currentScreen())
        if (device.isNullOrBlank()) {
            return loadStatus().copy(message = "Run auto detect first")
        }
        if (runSu("test -x $TARGET_PATH").exitCode != 0) {
            return loadStatus().copy(
                backend = "Not installed",
                message = "Backend is not installed. Please install backend manually first."
            )
        }
        val version = runSu("$TARGET_PATH --version", timeoutMs = 1000).output.trim()
        if (!version.contains(REQUIRED_BACKEND_VERSION)) {
            return loadStatus().copy(
                backend = "Version mismatch",
                message = "后端版本过旧或不匹配，请重新点击“安装后端”。\n当前: ${version.ifBlank { "unknown" }}\n需要: $REQUIRED_BACKEND_VERSION"
            )
        }
        prefs.edit().putString(KEY_SCREEN, screen).apply()

        val rotateArg = "--rotate ${currentDisplayRotation()}"
        val modeArgs = if (debugLog) "--emit --grab --verbose --log-state" else "--emit --grab"
        val commandPath = commandPath()
        val daemonCommand = "$TARGET_PATH --device ${quote(device)} --config ${quote(configPath)} " +
            "--screen ${quote(screen)} --state $STATE_PATH --command ${quote(commandPath)} $rotateArg $modeArgs"
        val pidPath = "/data/local/tmp/deadzone_daemon.pid"
        val cmd = "pkill deadzone_daemon >/dev/null 2>&1; " +
            "sleep 0.25; " +
            "rm -f $STATE_PATH; " +
            "rm -f $LOG_PATH; " +
            "rm -f $LAUNCH_LOG_PATH; " +
            "rm -f ${quote(commandPath)}; " +
            "rm -f $pidPath; " +
            "echo ${quote("launch debug=$debugLog device=$device screen=$screen")} > $LAUNCH_LOG_PATH; " +
            "echo 'root identity:' >> $LAUNCH_LOG_PATH; " +
            "id >> $LAUNCH_LOG_PATH 2>&1; " +
            "echo ${quote("cmd: $daemonCommand")} >> $LAUNCH_LOG_PATH; " +
            "($daemonCommand >> $LOG_PATH 2>&1 & echo ${'$'}! > $pidPath); " +
            "echo launch_pid=${'$'}(cat $pidPath 2>/dev/null) >> $LAUNCH_LOG_PATH; " +
            "sleep 0.05; " +
            "pidof deadzone_daemon >> $LAUNCH_LOG_PATH 2>&1 || echo 'pidof: missing' >> $LAUNCH_LOG_PATH"
        val result = runSu(cmd)
        prefs.edit().apply {
            if (result.exitCode == 0) putString(KEY_ROOT, "Granted")
            putBoolean(KEY_DEBUG_LOG_ACTIVE, debugLog)
        }.apply()
        val settled = waitForBackendState()
        val controlReady = if (settled.running == "Running" && settled.daemonState != "FAILED") pingControlFile() else ""
        val failureDetails = if (settled.running != "Running" || settled.daemonState == "UNKNOWN") startFailureDetails(result)
            else ""
        val fallbackMessage = when {
            settled.daemonState == "FAILED" && settled.message.isNotBlank() -> settled.message
            failureDetails.isNotBlank() -> failureDetails
            else -> if (debugLog) "Debug start command sent" else "Start command sent"
        }
        return settled.copy(
            root = if (result.exitCode == 0) "Granted" else prefs.getString(KEY_ROOT, null) ?: "Unknown",
            message = result.output.ifBlank {
                result.error.ifBlank {
                    listOf(
                        if (failureDetails.isNotBlank()) failureDetails else settled.message.ifBlank { fallbackMessage },
                        controlReady
                    ).filter { it.isNotBlank() }.joinToString("\n")
                }
            }
        )
    }

    private fun startFailureDetails(startResult: CommandResult): String {
        val state = runSu("if [ -f $STATE_PATH ]; then cat $STATE_PATH; else echo 'state file missing'; fi", timeoutMs = 1000).output
        val target = runSu("ls -l $TARGET_PATH 2>&1; file $TARGET_PATH 2>&1", timeoutMs = 1000).output
        val pid = runSu("pidof deadzone_daemon 2>/dev/null || echo 'pid missing'", timeoutMs = 1000).output
        val launch = runSu("if [ -f $LAUNCH_LOG_PATH ]; then tail -n 80 $LAUNCH_LOG_PATH; else echo 'launch log missing'; fi", timeoutMs = 1000).output
        val log = runSu("if [ -f $LOG_PATH ]; then tail -n 40 $LOG_PATH; else echo 'log file missing'; fi", timeoutMs = 1000).output
        return listOf(
            "Backend did not reach running state.",
            startResult.output.ifBlank { startResult.error }.ifBlank { null },
            "state: $state",
            "pid: $pid",
            "launch: $launch",
            "binary: $target",
            "log: $log"
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun currentDisplayRotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun readLog(maxLines: Int = 200): String {
        if (!prefs.getBoolean(KEY_DEBUG_LOG_ACTIVE, false)) {
            return "当前不是调试模式，普通启动不会输出实时日志。"
        }
        val safeLines = maxLines.coerceIn(20, 1000)
        val result = runSu("if [ -f $LOG_PATH ]; then tail -n $safeLines $LOG_PATH; else echo 'log file missing'; fi")
        val text = result.output.ifBlank { result.error }
        return if (text.contains("log file missing")) {
            startFailureDetails(CommandResult(result.exitCode, "", text))
        } else {
            text.ifBlank { "暂无日志" }
        }
    }

    fun sendConfig(configJson: String): BackendStatus {
        val body = configJson.trim().removePrefix("{").removeSuffix("}").trim()
        val payload = """{"id":${nextCommandId()},"cmd":"SET_CONFIG",$body}"""
        val reply = sendFileCommand(payload)
        return loadStatus().copy(message = reply)
    }

    fun enableBackend(): BackendStatus {
        val reply = sendFileCommand("""{"id":${nextCommandId()},"cmd":"ENABLE"}""")
        return loadStatus().copy(message = reply)
    }

    fun disableBackend(): BackendStatus {
        val reply = sendFileCommand("""{"id":${nextCommandId()},"cmd":"DISABLE"}""")
        return loadStatus().copy(message = reply)
    }

    fun pingBackend(): BackendStatus {
        val reply = sendFileCommand("""{"id":${nextCommandId()},"cmd":"PING"}""")
        return loadStatus().copy(message = reply)
    }

    fun stopBackendViaControl(): BackendStatus {
        val reply = try {
            sendFileCommand("""{"id":${nextCommandId()},"cmd":"STOP"}""")
        } catch (_: Exception) {
            ""
        }
        Thread.sleep(150)
        return if (reply.isNotBlank() && !reply.contains("失败")) {
            loadStatus().copy(message = reply)
        } else {
            stopBackend()
        }
    }

    fun clearLog(): String {
        val result = runSu("cat /dev/null > $LOG_PATH")
        return if (result.exitCode == 0) "日志已清空" else result.error.ifBlank { "清空日志失败" }
    }

    fun stopBackend(): BackendStatus {
        val result = runSu("pkill deadzone_daemon")
        if (result.exitCode == 0) {
            prefs.edit()
                .putString(KEY_ROOT, "Granted")
                .putBoolean(KEY_DEBUG_LOG_ACTIVE, false)
                .apply()
        }
        Thread.sleep(150)
        return loadStatus().copy(
            root = if (result.exitCode == 0) "Granted" else "Unknown",
            running = if (isRunning()) "Running" else "Stopped",
            message = result.output.ifBlank { result.error.ifBlank { "Stop command sent" } }
        )
    }

    private fun nextCommandId(): Int {
        return (System.currentTimeMillis() % 1000000L).toInt()
    }

    private fun sendFileCommand(json: String): String {
        if (!isRunning()) {
            return "file命令失败: 后端未运行"
        }
        return try {
            val commandFile = File(commandPath())
            val tmpFile = File(commandFile.parentFile, "${commandFile.name}.tmp")
            tmpFile.writeText(json.trim() + "\n", Charsets.UTF_8)
            if (commandFile.exists()) commandFile.delete()
            if (!tmpFile.renameTo(commandFile)) {
                tmpFile.copyTo(commandFile, overwrite = true)
                tmpFile.delete()
            }
            waitForFileCommand(json)
        } catch (e: Exception) {
            "file命令失败: ${e.message.orEmpty()}"
        }
    }

    private fun waitForFileCommand(json: String): String {
        val cmd = Regex("\"cmd\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1).orEmpty()
        val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1).orEmpty()
        val deadline = System.currentTimeMillis() + if (cmd == "ENABLE") 10000 else 2500
        var last = loadStatus()
        while (System.currentTimeMillis() < deadline) {
            last = loadStatus()
            if (last.daemonState == "FAILED" && (id.isBlank() || last.message.contains("id=$id"))) {
                return "file命令失败: cmd=$cmd state=${last.daemonState} message=${last.message}"
            }
            if (fileCommandDone(cmd, id, last)) {
                return """{"ok":true,"state":"${last.daemonState}","transport":"file","message":"${last.message.replace("\"", "\\\"")}"}"""
            }
            Thread.sleep(20)
        }
        return "file命令超时: cmd=$cmd state=${last.daemonState} message=${last.message}"
    }

    private fun fileCommandDone(cmd: String, id: String, status: BackendStatus): Boolean {
        val matchedId = id.isBlank() || status.message.contains("id=$id")
        return when (cmd) {
            "PING", "GET_STATUS" -> status.running == "Running" && matchedId
            "SET_CONFIG" -> status.daemonState == "RUNNING_DISABLED" && matchedId
            "ENABLE" -> status.daemonState == "RUNNING_ENABLED" && matchedId
            "DISABLE" -> status.daemonState == "RUNNING_DISABLED" && matchedId
            "STOP" -> status.daemonState == "STOPPED" || status.running == "Stopped"
            else -> status.daemonState == "FAILED"
        }
    }

    private fun pingControlFile(): String {
        val reply = sendFileCommand("""{"id":${nextCommandId()},"cmd":"PING"}""")
        return if (controlOk(reply)) "Control ready via file: $reply" else reply
    }

    private fun controlOk(reply: String): Boolean {
        return Regex("\"ok\"\\s*:\\s*true").containsMatchIn(reply)
    }

    fun stopBackendDetached() {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "su",
                "-c",
                "nohup sh -c 'sleep 0.15; pkill deadzone_daemon' >/dev/null 2>&1 &"
            ))
            prefs.edit()
                .putString(KEY_ROOT, "Granted")
                .putBoolean(KEY_DEBUG_LOG_ACTIVE, false)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun isRunning(): Boolean {
        return runSu("pidof deadzone_daemon").exitCode == 0
    }

    private fun waitForBackendState(): BackendStatus {
        var last = loadStatus()
        repeat(30) {
            if (last.daemonState == "RUNNING" ||
                last.daemonState == "RUNNING_DISABLED" ||
                last.daemonState == "RUNNING_ENABLED" ||
                last.daemonState == "FAILED" ||
                last.daemonState == "STOPPED") {
                return last
            }
            Thread.sleep(100)
            last = loadStatus()
        }
        return last
    }

    private fun readStateJson(): String {
        val result = runSu("if [ -f $STATE_PATH ]; then cat $STATE_PATH; fi", timeoutMs = 1000)
        return result.output
    }

    private fun jsonString(json: String, key: String): String? {
        if (json.isBlank()) return null
        return Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\r", "\r")
            ?.replace("\\t", "\t")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun hasRootIdentity(output: String): Boolean {
        return output.lineSequence().any { line ->
            val value = line.trim()
            value.contains("uid=0") || value == "root"
        }
    }

    private fun parseTouchInfo(text: String): TouchInfo? {
        val blocks = text.split("add device")
        val candidates = mutableListOf<TouchInfo>()
        for (block in blocks) {
            val path = Regex("(/dev/input/event\\d+)").find(block)?.value ?: continue
            val hasX = block.contains("ABS_MT_POSITION_X")
            val hasY = block.contains("ABS_MT_POSITION_Y")
            val hasTracking = block.contains("ABS_MT_TRACKING_ID")
            if (!hasX || !hasY || !hasTracking) continue

            val name = Regex("name:\\s+\"([^\"]+)\"").find(block)?.groupValues?.get(1).orEmpty()
            if (name.contains("DeadZone", ignoreCase = true) ||
                name.contains("Virtual", ignoreCase = true) ||
                name.contains("mouse", ignoreCase = true) ||
                name.contains("keyboard", ignoreCase = true)) {
                continue
            }

            val width = parseAbsSize(block, "ABS_MT_POSITION_X")
            val height = parseAbsSize(block, "ABS_MT_POSITION_Y")
            val screen = if (width != null && height != null) "${width}x${height}" else currentScreen()
            val score = touchDeviceScore(block, name, width, height)
            candidates += TouchInfo(path, screen, name.ifBlank { "unknown" }, score)
        }
        return candidates.maxByOrNull { it.score }
    }

    private fun touchDeviceScore(block: String, name: String, width: Int?, height: Int?): Int {
        var score = 0
        if (block.contains("INPUT_PROP_DIRECT")) score += 60
        if (block.contains("BTN_TOUCH")) score += 20
        if (block.contains("ABS_MT_SLOT")) score += 10
        if (name.contains("touch", ignoreCase = true)) score += 30
        if (name.contains("screen", ignoreCase = true)) score += 20
        if (name.contains("panel", ignoreCase = true)) score += 15
        if (name.contains("stylus", ignoreCase = true) || name.contains("pen", ignoreCase = true)) score -= 30
        if (width != null && height != null) {
            val area = width.toLong() * height.toLong()
            if (area >= 800L * 800L) score += 20
            if (width >= 600 && height >= 600) score += 10
        }
        return score
    }

    private fun parseAbsSize(block: String, axis: String): Int? {
        val line = block.lineSequence().firstOrNull { it.contains(axis) } ?: return null
        val min = Regex("min\\s+(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val max = Regex("max\\s+(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return (max - min + 1).coerceAtLeast(1)
    }

    private fun normalizeScreen(screen: String): String {
        val match = Regex("(\\d+)x(\\d+)").find(screen) ?: return screen
        var width = match.groupValues[1].toIntOrNull() ?: return screen
        var height = match.groupValues[2].toIntOrNull() ?: return screen
        while (width >= 10000 || height >= 10000) {
            width = (width / 10.0f).roundToInt()
            height = (height / 10.0f).roundToInt()
        }
        if (width > 6000 || height > 6000) {
            width = (width / 10.0f).roundToInt()
            height = (height / 10.0f).roundToInt()
        }
        return "${width}x${height}"
    }

    private fun runSu(command: String, timeoutMs: Long = 8000): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val readerThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }.apply { start() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return CommandResult(124, "", "Timeout")
            }
            readerThread.join(500)
            CommandResult(process.exitValue(), output.toString().trim(), "")
        } catch (e: Exception) {
            CommandResult(1, "", e.message.orEmpty())
        }
    }

    private fun runSuInteractive(command: String, timeoutMs: Long = 8000): CommandResult {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            val output = StringBuilder()
            val readerThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }.apply { start() }
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$command\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return CommandResult(124, output.toString().trim(), "Timeout")
            }
            readerThread.join(500)
            CommandResult(process.exitValue(), output.toString().trim(), "")
        } catch (e: Exception) {
            CommandResult(1, "", e.message.orEmpty())
        }
    }

    private fun quote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )
}
