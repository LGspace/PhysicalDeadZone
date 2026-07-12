package com.example.myphysicaldeadzone

import java.io.DataOutputStream

object RootShell {
    fun tap(x: Int, y: Int) {
        Thread {
            try {
                // 简单粗暴的单次执行，稳定性最高
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("input tap $x $y\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun checkRoot() {
        Thread { try { Runtime.getRuntime().exec("su -c ls") } catch (e: Exception) {} }.start()
    }
}