package com.mockgps.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成助手（与 BilibiliExporter 同一套思路）。
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_PERMISSION_CODE = 20071

    enum class Status {
        NOT_INSTALLED,
        NOT_RUNNING,
        NEED_PERMISSION,
        READY,
    }

    fun isShizukuInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun checkStatus(context: Context): Status {
        if (!isShizukuInstalled(context)) return Status.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return Status.NOT_RUNNING
        return try {
            if (Shizuku.isPreV11()) {
                Status.READY
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Status.READY
            } else {
                Status.NEED_PERMISSION
            }
        } catch (_: Exception) {
            Status.NOT_RUNNING
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
            }
        } catch (_: Exception) {
        }
    }

    fun statusDescription(status: Status): String = when (status) {
        Status.NOT_INSTALLED ->
            "未检测到 Shizuku APP。\n请前往 GitHub 下载安装：\n${DOWNLOAD_URL}"
        Status.NOT_RUNNING ->
            "Shizuku 已安装但未运行。\n请打开 Shizuku APP 并按提示用 ADB 启动服务。"
        Status.NEED_PERMISSION ->
            "Shizuku 已运行但未授权本 APP。\n点击「申请 Shizuku 权限」并在弹窗中选择「允许」"
        Status.READY ->
            "Shizuku 就绪 ✓"
    }

    fun bindUserService(context: Context, connection: ServiceConnection) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, MockGpsRemoteService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("mockgps")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }

    fun unbindUserService(context: Context, connection: ServiceConnection) {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, MockGpsRemoteService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("mockgps")
                .version(1)
            Shizuku.unbindUserService(args, connection, true)
        } catch (_: Exception) {
        }
    }

    const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"
}

fun IBinder?.toMockGpsService(): IMockGpsService? =
    if (this == null) null else IMockGpsService.Stub.asInterface(this)
