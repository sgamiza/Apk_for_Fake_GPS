package com.mockgps.shizuku

import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 远程服务（运行在 shell UID = 2000 进程中）。
 *
 * 关键点：
 *  1. 必须有无参构造函数（Shizuku 框架反射创建实例）
 *  2. destroy() transactionCode 固定 16777114，由 Shizuku 在进程结束前调用
 *  3. 此进程跟主 APP 进程独立，所有方法返回值通过 Binder IPC 传输
 *
 * 提供的高权限能力：
 *  - 给主 APP 自己授予 mock_location AppOps（cmd appops set ... mock_location allow）
 *  - 强制打开系统定位主开关（cmd location set-location-enabled true）
 *  - dumpsys location 诊断（看 test provider 是否生效）
 */
class MockGpsRemoteService : IMockGpsService.Stub() {

    override fun grantMockLocation(packageName: String): Int {
        val safePkg = sanitizePackage(packageName)
        return execAndWait("cmd appops set $safePkg android:mock_location allow")
    }

    override fun revokeMockLocation(packageName: String): Int {
        val safePkg = sanitizePackage(packageName)
        return execAndWait("cmd appops set $safePkg android:mock_location deny")
    }

    override fun queryMockLocation(packageName: String): String {
        val safePkg = sanitizePackage(packageName)
        return execAndCapture("cmd appops get $safePkg android:mock_location")
    }

    override fun execShell(cmd: String): String = execAndCapture(cmd)

    /**
     * 强制打开系统定位主开关。
     * Android 9+：优先 `cmd location set-location-enabled true`
     * 兜底：`settings put secure location_mode 3`（高精度，GPS+网络都开）
     */
    override fun enableLocationServices(): Int {
        var code = execAndWait("cmd location set-location-enabled true")
        if (code != 0) {
            // 部分 ROM 不支持上面的命令，回退到老版 API
            code = execAndWait("settings put secure location_mode 3")
        }
        return code
    }

    override fun queryLocationMode(): String {
        // 0=off, 1=sensors only, 2=battery saving (network only), 3=high accuracy
        return execAndCapture("settings get secure location_mode")
    }

    /**
     * 截取 dumpsys location 中跟 test provider 相关的关键段落，
     * 避免一次性吐几十 KB 把 UI 拖慢。
     */
    override fun dumpLocation(): String {
        val raw = execAndCapture("dumpsys location")
        if (raw.isBlank()) return "(empty dump)"
        val keepKeywords = listOf(
            "Location Manager state", "gps provider:", "network provider:",
            "MockProvider", "TestProvider", "mock=", "isMock", "Last Locations",
            "last location", "User 0:", "Location Services:",
        )
        val lines = raw.lineSequence()
        val filtered = StringBuilder()
        var keepNextN = 0
        for (line in lines) {
            val low = line.lowercase()
            val hit = keepKeywords.any { low.contains(it.lowercase()) }
            when {
                hit -> {
                    filtered.appendLine(line)
                    keepNextN = 4 // 命中后再带 4 行上下文
                }
                keepNextN > 0 -> {
                    filtered.appendLine(line)
                    keepNextN--
                }
            }
        }
        return if (filtered.isEmpty()) raw.take(4000) else filtered.toString().trim()
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun asBinder(): IBinder = this

    // -------- 内部工具 --------

    private fun sanitizePackage(pkg: String): String =
        pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun execAndWait(cmd: String): Int = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        p.waitFor()
        p.exitValue()
    } catch (e: Exception) {
        -1
    }

    private fun execAndCapture(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            r.forEachLine { out.appendLine(it) }
        }
        BufferedReader(InputStreamReader(p.errorStream)).use { r ->
            r.forEachLine { out.appendLine("[err] $it") }
        }
        p.waitFor()
        out.toString().trim()
    } catch (e: Exception) {
        "exec failed: ${e.message}"
    }
}
