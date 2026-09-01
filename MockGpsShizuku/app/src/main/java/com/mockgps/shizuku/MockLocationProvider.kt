package com.mockgps.shizuku

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 持续向 GPS_PROVIDER + NETWORK_PROVIDER 注入虚拟坐标。
 *
 * 工作流程：
 *  1. 调用 LocationManager.addTestProvider 注册一个伪造 provider（覆盖系统的同名 provider）
 *  2. 启动协程，每 500ms setTestProviderLocation 一次
 *  3. 反射清掉 Location.mFieldsMask 的 HAS_MOCK_PROVIDER 位（API 31+）
 *  4. 监听 LocationManager.MODE_CHANGED_ACTION：用户/ROM 切换系统定位主开关后，
 *     test provider 经常被清空，必须自动重注册
 *  5. 每次推送前检测 test provider 是否还存在，不存在就立刻重注册（自愈）
 *
 * 前提：本 APP 拥有 mock_location AppOps 权限。
 *  - 自动方式：通过 Shizuku 调用 `cmd appops set <pkg> mock_location allow`
 *  - 手动方式：开发者选项 → 选择模拟位置应用 → 选本 APP
 */
class MockLocationProvider(private val ctx: Context) {

    companion object {
        private const val TAG = "MockGps"
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        // Android 12+ 隐藏 mock 标志位的关键 mask（HAS_MOCK_PROVIDER_MASK）
        private const val HAS_MOCK_PROVIDER_MASK = 1 shl 10
        // 推送间隔：500ms（2Hz）。比 1Hz 更难被真位置事件抢占
        private const val PUSH_INTERVAL_MS = 500L
    }

    private val lm: LocationManager =
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    @Volatile
    private var lat: Double = 39.908823
    @Volatile
    private var lng: Double = 116.397470
    @Volatile
    private var accuracy: Float = 1.0f
    @Volatile
    private var altitude: Double = 30.0
    @Volatile
    private var speed: Float = 0f
    @Volatile
    private var bearing: Float = 0f

    /** 监听系统定位主开关变化（OFF→ON 时 test provider 经常被 ROM 重置） */
    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.MODE_CHANGED_ACTION) {
                Log.i(TAG, "Location mode changed → re-register test providers")
                if (isRunning) {
                    safeReregister()
                }
            }
        }
    }
    private var receiverRegistered = false

    val isRunning: Boolean
        get() = pumpJob?.isActive == true

    /** 当前位置（供 UI 显示） */
    fun currentLatLng(): Pair<Double, Double> = lat to lng

    fun updateTarget(lat: Double, lng: Double, accuracy: Float = 1f) {
        this.lat = lat
        this.lng = lng
        this.accuracy = accuracy
    }

    /**
     * 启动虚拟定位。会抛 SecurityException —— 调用方必须先确保已拿到 mock_location 权限。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        registerProviders()
        registerReceiver()
        pumpJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                // 每 8 个 tick（约 4 秒）做一次「provider 是否还活着」自检
                if (tickCount % 8 == 0 && !isProviderHealthy()) {
                    Log.w(TAG, "Test provider lost! re-register")
                    safeReregister()
                }
                pushOnce()
                tickCount++
                delay(PUSH_INTERVAL_MS)
            }
        }
        Log.i(TAG, "MockLocationProvider started at ($lat, $lng), interval=${PUSH_INTERVAL_MS}ms")
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        unregisterReceiver()
        unregisterProviders()
        Log.i(TAG, "MockLocationProvider stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    // -------- 内部 --------

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            ctx.registerReceiver(
                locationModeReceiver,
                IntentFilter(LocationManager.MODE_CHANGED_ACTION)
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            ctx.unregisterReceiver(locationModeReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    /** 检查我们注册的 test provider 是否还在 LocationManager 的 provider 列表里 */
    private fun isProviderHealthy(): Boolean {
        return try {
            val all = lm.allProviders
            PROVIDERS.all { all.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeReregister() {
        try {
            registerProviders()
        } catch (e: Exception) {
            Log.e(TAG, "safeReregister failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerProviders() {
        for (provider in PROVIDERS) {
            try {
                runCatching { lm.removeTestProvider(provider) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val props = ProviderProperties.Builder()
                        .setHasNetworkRequirement(false)
                        .setHasSatelliteRequirement(false)
                        .setHasCellRequirement(false)
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build()
                    lm.addTestProvider(
                        provider,
                        props.hasNetworkRequirement(),
                        props.hasSatelliteRequirement(),
                        props.hasCellRequirement(),
                        props.hasMonetaryCost(),
                        props.hasAltitudeSupport(),
                        props.hasSpeedSupport(),
                        props.hasBearingSupport(),
                        props.powerUsage,
                        props.accuracy,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(
                        provider,
                        false, false, false, false,
                        true, true, true,
                        0, 1,
                    )
                }
                lm.setTestProviderEnabled(provider, true)
            } catch (e: SecurityException) {
                Log.e(TAG, "addTestProvider($provider) failed: ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addTestProvider($provider) error", e)
            }
        }
    }

    private fun unregisterProviders() {
        for (provider in PROVIDERS) {
            runCatching {
                lm.setTestProviderEnabled(provider, false)
                lm.removeTestProvider(provider)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pushOnce() {
        for (provider in PROVIDERS) {
            try {
                val loc = buildFakeLocation(provider)
                lm.setTestProviderLocation(provider, loc)
            } catch (e: IllegalArgumentException) {
                // provider not registered — 立刻重注册再试一次
                Log.w(TAG, "setTestProviderLocation($provider) IAE: ${e.message}, re-register")
                safeReregister()
            } catch (e: Exception) {
                Log.w(TAG, "setTestProviderLocation($provider) failed: ${e.message}")
            }
        }
    }

    private fun buildFakeLocation(provider: String): Location {
        val loc = Location(provider).apply {
            latitude = this@MockLocationProvider.lat
            longitude = this@MockLocationProvider.lng
            altitude = this@MockLocationProvider.altitude
            accuracy = this@MockLocationProvider.accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            speed = this@MockLocationProvider.speed
            bearing = this@MockLocationProvider.bearing
            // 给一个像样的卫星数，便于绕过简单反作弊
            extras = Bundle().apply { putInt("satellites", 9) }
        }
        // 尝试清掉 mock 标志位（同进程读 Location 时有效；
        // 跨进程其它 APP 拿到的 Location 仍由系统打 mock 标志，需配合 root/Xposed 才能彻底消除）
        clearMockProviderFlag(loc)
        return loc
    }

    private fun clearMockProviderFlag(loc: Location) {
        runCatching {
            // API 31+：Location 内部用 mFieldsMask 记录 HAS_MOCK_PROVIDER
            val field = Location::class.java.getDeclaredField("mFieldsMask")
            field.isAccessible = true
            val mask = field.getInt(loc)
            field.setInt(loc, mask and HAS_MOCK_PROVIDER_MASK.inv())
        }
        runCatching {
            // 早期版本可能有 mIsFromMockProvider 字段，反射置 false
            val field = Location::class.java.getDeclaredField("mIsFromMockProvider")
            field.isAccessible = true
            field.setBoolean(loc, false)
        }
    }
}
