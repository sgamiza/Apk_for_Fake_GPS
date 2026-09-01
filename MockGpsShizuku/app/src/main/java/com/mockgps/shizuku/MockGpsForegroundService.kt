package com.mockgps.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台服务，仅用于在锁屏/后台时让 MockLocationProvider 持续运行不被系统杀。
 * 真正的注入循环在 MockLocationProvider 内部协程里跑。
 */
class MockGpsForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.mockgps.shizuku.START"
        const val ACTION_STOP = "com.mockgps.shizuku.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ACCURACY = "accuracy"
        private const val CHANNEL_ID = "mock_gps_channel"
        private const val NOTIFICATION_ID = 7311

        @Volatile
        private var sharedProvider: MockLocationProvider? = null

        /** 主 Activity 直接拿全局 provider 来更新坐标，避免重复 binder 跳转 */
        fun providerInstance(context: Context): MockLocationProvider {
            return sharedProvider ?: synchronized(this) {
                sharedProvider ?: MockLocationProvider(context.applicationContext).also {
                    sharedProvider = it
                }
            }
        }

        fun isRunning(): Boolean = sharedProvider?.isRunning == true

        fun startService(context: Context, lat: Double, lng: Double, accuracy: Float) {
            val intent = Intent(context, MockGpsForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
                putExtra(EXTRA_ACCURACY, accuracy)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MockGpsForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 39.908823)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 116.397470)
                val acc = intent.getFloatExtra(EXTRA_ACCURACY, 1f)

                startForeground(NOTIFICATION_ID, buildNotification(lat, lng))
                val provider = providerInstance(this)
                provider.updateTarget(lat, lng, acc)
                try {
                    provider.start()
                } catch (e: SecurityException) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                sharedProvider?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sharedProvider?.stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "虚拟定位运行状态",
                    NotificationManager.IMPORTANCE_LOW,
                )
                ch.description = "保持虚拟定位在后台运行"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(lat: Double, lng: Double): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("虚拟定位运行中")
            .setContentText(String.format("当前坐标 %.6f, %.6f", lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
