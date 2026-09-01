package com.mockgps.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * "GPS 围栏" VpnService —— 只拦截高德/百度的网络定位 API 域名的 DNS 解析。
 *
 * 工作原理：
 *  1. 起一个 Android 自带的 VpnService（无需 root，只需用户首次同意 VPN 弹窗）
 *  2. Builder 配置：
 *     - addAddress("10.7.7.7/30") — VPN 接口本地 IP
 *     - addRoute("10.7.7.8/32") — 只把 10.7.7.8 这一个 IP 路由进 VPN
 *     - addDnsServer("10.7.7.8") — 强制系统所有 APP 的 DNS 走 10.7.7.8
 *     - 不设 0.0.0.0/0 路由 — 普通流量（地图瓦片/搜索/导航/微信/抖音）走系统网络，本服务完全不接管
 *  3. 监听 VPN fd，只处理 UDP dst port 53（DNS 查询）
 *  4. 解析 DNS QName：
 *     - 命中黑名单（apilocate.amap.com 等）→ 直接构造 NXDOMAIN 响应
 *     - 否则转发到上游 114.114.114.114，把响应原封写回
 *
 * 效果：
 *   - 高德地图、百度地图的"网络定位 API"（Wi-Fi/基站 → 服务器换坐标）解析失败
 *   - 高德 SDK 自动 fallback 到 LocationManager.GPS_PROVIDER
 *   - 而 GPS_PROVIDER 已经被 MockLocationProvider 接管 → 显示我们设的坐标
 *   - 高德其他功能（地图、搜索、导航）完全不受影响
 *
 * 限制：
 *   - 高德如果用 IP 直连或 DoH/DoT 绕过系统 DNS，本方案无效（实测国内 APP 极少这么做）
 *   - 用户状态栏会有 VPN 钥匙图标提示
 */
class LocationFenceVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mockgps.shizuku.FENCE_START"
        const val ACTION_STOP = "com.mockgps.shizuku.FENCE_STOP"
        private const val TAG = "GpsFence"
        private const val CHANNEL_ID = "gps_fence_channel"
        private const val NOTI_ID = 7312

        /** 上游 DNS。可改成 8.8.8.8 / 1.1.1.1 / 114.114.114.114 */
        private const val UPSTREAM_DNS = "114.114.114.114"

        /**
         * 黑名单：高德/百度/腾讯的"网络定位"专用域名。
         * 只屏蔽这些，不影响其他网络功能。
         */
        val BLOCK_DOMAINS = setOf(
            // 高德地图（apilocate 是核心定位 API；wifi/cgi 是 Wi-Fi 和基站采集）
            "apilocate.amap.com",
            "alocation.amap.com",
            "ulocation.amap.com",
            "wifi.amap.com",
            "cgicollector.amap.com",
            "xnass.amap.com",
            "loccollect.amap.com",
            // 百度地图
            "loc.map.baidu.com",
            "ofloc.map.baidu.com",
            // 腾讯位置服务
            "ext.map.qq.com",
            "loc.map.qq.com",
        )

        @Volatile var isRunning = false
            private set
        private val blockedCount = AtomicLong(0)
        private val passedCount = AtomicLong(0)

        fun stats(): String =
            "已拦截 ${blockedCount.get()} 次 | 转发 ${passedCount.get()} 次"

        fun start(context: Context) {
            val i = Intent(context, LocationFenceVpnService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LocationFenceVpnService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }

    private val running = AtomicBoolean(false)
    private var pfd: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // 用户在系统 VPN 设置里点了"断开"
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (running.get()) return
        ensureChannel()
        startForeground(NOTI_ID, buildNotification())

        val builder = Builder()
            .setSession("MockGps · 高德定位拦截")
            .addAddress("10.7.7.7", 30)
            .addRoute("10.7.7.8", 32)
            .addDnsServer("10.7.7.8")
            .setMtu(1500)
        // 排除自己，避免环路（虽然此 VPN 不接管普通流量，但防御性写一下）
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VPN establish error", e)
            null
        }
        if (fd == null) {
            Log.e(TAG, "VPN establish returned null (用户没授权 VpnService.prepare?)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        pfd = fd
        running.set(true)
        isRunning = true
        blockedCount.set(0)
        passedCount.set(0)

        workerThread = Thread({ runDnsLoop(fd) }, "GpsFence-DNS").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "GPS fence VPN started")
    }

    private fun stopVpn() {
        if (!running.compareAndSet(true, false)) {
            // already stopped
        }
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
        Log.i(TAG, "GPS fence VPN stopped")
    }

    // -------- DNS 主循环 --------

    private fun runDnsLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val n = input.read(buf)
                if (n <= 0) {
                    Thread.sleep(5)
                    continue
                }
                if (n >= 28) handlePacket(buf, n, output)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "loop error: ${e.message}")
            }
        }
    }

    private fun handlePacket(packet: ByteArray, len: Int, output: FileOutputStream) {
        // IPv4 only
        val versionIhl = packet[0].toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return // DNS only

        val dnsOffset = ihl + 8
        val dnsLen = len - dnsOffset
        if (dnsLen < 12) return

        val qName = parseDnsQName(packet, dnsOffset, dnsLen) ?: return
        val shouldBlock = BLOCK_DOMAINS.any { qName == it || qName.endsWith(".$it") }

        if (shouldBlock) {
            val responseDns = buildNxDomainResponse(packet, dnsOffset, dnsLen)
            val responsePacket = wrapInIpUdp(packet, ihl, srcPort, dstPort, responseDns)
            try { output.write(responsePacket) } catch (_: Exception) {}
            blockedCount.incrementAndGet()
            Log.i(TAG, "BLOCKED  $qName")
        } else {
            forwardDns(packet, ihl, srcPort, dstPort, dnsOffset, dnsLen, output)
        }
    }

    /** 解析 DNS 报文中第一个 question 的 QName，转小写。失败返回 null。 */
    private fun parseDnsQName(buf: ByteArray, offset: Int, len: Int): String? {
        if (len < 13) return null
        var p = offset + 12
        val sb = StringBuilder()
        var safety = 0
        while (p < offset + len && safety < 64) {
            val l = buf[p].toInt() and 0xFF
            if (l == 0) { p++; break }
            if (l and 0xC0 != 0) {
                // pointer/compression — DNS query 一般不出现，遇到就跳过
                p += 2; break
            }
            p++
            if (p + l > offset + len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append(Char(buf[p + i].toInt() and 0xFF))
            p += l
            safety++
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    /** 构造 NXDOMAIN 响应：保留 ID 和 question 段，把 flags 改成响应+域名不存在 */
    private fun buildNxDomainResponse(req: ByteArray, dnsOffset: Int, dnsLen: Int): ByteArray {
        val resp = req.copyOfRange(dnsOffset, dnsOffset + dnsLen)
        // bytes 2-3 = flags
        // QR=1 (response), Opcode=0, AA=0, TC=0, RD=1 (复制原值更稳，这里直接 81)
        resp[2] = 0x81.toByte()
        // RA=1, Z=0, RCODE=3 (NXDOMAIN)
        resp[3] = 0x83.toByte()
        // ANCOUNT/NSCOUNT/ARCOUNT 全部清零
        resp[6] = 0; resp[7] = 0
        resp[8] = 0; resp[9] = 0
        resp[10] = 0; resp[11] = 0
        return resp
    }

    /** 把 DNS 响应包裹回 IP+UDP，源/目的 IP 与端口反转 */
    private fun wrapInIpUdp(
        req: ByteArray,
        ihl: Int,
        originalSrcPort: Int,
        originalDstPort: Int,
        dnsPayload: ByteArray,
    ): ByteArray {
        val totalLen = ihl + 8 + dnsPayload.size
        val pkt = ByteArray(totalLen)
        // IP header: 拷贝原包前 ihl 字节
        System.arraycopy(req, 0, pkt, 0, ihl)
        // 调整 total length
        pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        // 反转源/目的 IP（offset 12..15 与 16..19）
        for (i in 0 until 4) {
            val tmp = pkt[12 + i]
            pkt[12 + i] = pkt[16 + i]
            pkt[16 + i] = tmp
        }
        pkt[8] = 64.toByte() // TTL
        // 重新计算 IP checksum
        pkt[10] = 0; pkt[11] = 0
        val ipChecksum = computeChecksum(pkt, 0, ihl)
        pkt[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        pkt[11] = (ipChecksum and 0xFF).toByte()

        // UDP header at offset ihl: src=originalDst, dst=originalSrc
        pkt[ihl] = ((originalDstPort shr 8) and 0xFF).toByte()
        pkt[ihl + 1] = (originalDstPort and 0xFF).toByte()
        pkt[ihl + 2] = ((originalSrcPort shr 8) and 0xFF).toByte()
        pkt[ihl + 3] = (originalSrcPort and 0xFF).toByte()
        val udpLen = 8 + dnsPayload.size
        pkt[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
        pkt[ihl + 5] = (udpLen and 0xFF).toByte()
        // UDP checksum 在 IPv4 中可置 0（接收方不校验）
        pkt[ihl + 6] = 0; pkt[ihl + 7] = 0
        // DNS payload
        System.arraycopy(dnsPayload, 0, pkt, ihl + 8, dnsPayload.size)
        return pkt
    }

    /** RFC 1071 16-bit one's complement checksum */
    private fun computeChecksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + len
        while (i < end - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** 把原始 DNS 查询转发到上游 DNS（114.114.114.114），把响应写回 VPN */
    private fun forwardDns(
        req: ByteArray, ihl: Int, srcPort: Int, dstPort: Int,
        dnsOffset: Int, dnsLen: Int, output: FileOutputStream,
    ) {
        val dnsPayload = req.copyOfRange(dnsOffset, dnsOffset + dnsLen)
        val reqCopy = req.copyOf(ihl + 8 + dnsLen) // 截取相关部分
        Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                protect(sock) // 排除 VPN，防止环路
                sock.soTimeout = 3000
                sock.send(
                    DatagramPacket(
                        dnsPayload, dnsPayload.size,
                        InetAddress.getByName(UPSTREAM_DNS), 53
                    )
                )
                val respBuf = ByteArray(1500)
                val resp = DatagramPacket(respBuf, respBuf.size)
                sock.receive(resp)
                val responseDns = respBuf.copyOf(resp.length)
                val packet = wrapInIpUdp(reqCopy, ihl, srcPort, dstPort, responseDns)
                synchronized(this@LocationFenceVpnService) {
                    output.write(packet)
                }
                passedCount.incrementAndGet()
            } catch (e: Exception) {
                // DNS 转发失败，悄悄丢弃；客户端重试
                Log.w(TAG, "DNS forward fail: ${e.message}")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    // -------- 通知 --------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "高德/百度定位 API 拦截",
                    NotificationManager.IMPORTANCE_LOW,
                )
                ch.description = "通过 VpnService DNS 黑洞拦截高德/百度的网络定位 API"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("GPS 围栏运行中")
            .setContentText("仅拦截高德/百度定位 API，其他网络不受影响")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
