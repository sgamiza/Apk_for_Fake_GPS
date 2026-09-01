package com.mockgps.shizuku

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

/**
 * 虚拟定位主界面（方案 B：Shizuku 自动授权 + LocationManager.addTestProvider 注入）
 *
 * 工作流程（按钮顺序就是用户操作顺序）：
 *  ① 检测 Shizuku 状态 → 安装/启动/授权
 *  ② 通过 Shizuku 给本 APP 授予 mock_location AppOps 权限
 *  ③ 输入坐标 → 启动虚拟定位（前台服务保活）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvMockOpsStatus: TextView
    private lateinit var tvRunStatus: TextView
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etAccuracy: EditText
    private lateinit var btnShizukuPermit: Button
    private lateinit var btnGrantMock: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnInstallShizuku: Button
    private lateinit var btnOpenDevSettings: Button
    private lateinit var btnEnableLocation: Button
    private lateinit var btnDiagnose: Button
    private lateinit var btnFenceToggle: Button
    private lateinit var tvFenceStatus: TextView
    private lateinit var spinnerPreset: Spinner

    /** VpnService.prepare() 申请回调 */
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LocationFenceVpnService.start(this)
            toast("已启动 GPS 围栏 VPN")
        } else {
            toast("用户拒绝了 VPN 授权，无法启动 GPS 围栏")
        }
        refreshAllStatus()
    }

    private var remoteService: IMockGpsService? = null

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = service.toMockGpsService()
            runOnUiThread {
                refreshAllStatus()
                toast("Shizuku 服务已连接")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            runOnUiThread { refreshAllStatus() }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { reqCode, result ->
        if (reqCode == ShizukuHelper.REQUEST_PERMISSION_CODE) {
            runOnUiThread {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    toast("Shizuku 已授权")
                    bindRemoteIfPossible()
                } else {
                    toast("Shizuku 授权被拒绝")
                }
                refreshAllStatus()
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshAllStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            remoteService = null
            refreshAllStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("mockgps", Context.MODE_PRIVATE)

        bindViews()
        bindListeners()
        loadSavedCoords()
        ensureLocationPermission()

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (ShizukuHelper.checkStatus(this) == ShizukuHelper.Status.READY) {
            bindRemoteIfPossible()
        }
        refreshAllStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatus()
        startStatusTicker()
    }

    override fun onPause() {
        super.onPause()
        stopStatusTicker()
    }

    private val statusTickerRunnable = object : Runnable {
        override fun run() {
            refreshAllStatus()
            window.decorView.postDelayed(this, 2000)
        }
    }
    private fun startStatusTicker() {
        window.decorView.removeCallbacks(statusTickerRunnable)
        window.decorView.postDelayed(statusTickerRunnable, 2000)
    }
    private fun stopStatusTicker() {
        window.decorView.removeCallbacks(statusTickerRunnable)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    // -------- View 绑定 --------

    private fun bindViews() {
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvMockOpsStatus = findViewById(R.id.tvMockOpsStatus)
        tvRunStatus = findViewById(R.id.tvRunStatus)
        etLat = findViewById(R.id.etLat)
        etLng = findViewById(R.id.etLng)
        etAccuracy = findViewById(R.id.etAccuracy)
        btnShizukuPermit = findViewById(R.id.btnShizukuPermit)
        btnGrantMock = findViewById(R.id.btnGrantMock)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnInstallShizuku = findViewById(R.id.btnInstallShizuku)
        btnOpenDevSettings = findViewById(R.id.btnOpenDevSettings)
        btnEnableLocation = findViewById(R.id.btnEnableLocation)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnFenceToggle = findViewById(R.id.btnFenceToggle)
        tvFenceStatus = findViewById(R.id.tvFenceStatus)
        spinnerPreset = findViewById(R.id.spinnerPreset)

        val presets = Preset.ALL.map { it.label }
        spinnerPreset.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
    }

    private fun bindListeners() {
        btnShizukuPermit.setOnClickListener {
            when (ShizukuHelper.checkStatus(this)) {
                ShizukuHelper.Status.NOT_INSTALLED -> {
                    toast("请先安装 Shizuku")
                    openUrl(ShizukuHelper.DOWNLOAD_URL)
                }
                ShizukuHelper.Status.NOT_RUNNING -> {
                    toast("请先在 Shizuku APP 启动服务（ADB / 无线 ADB / Root）")
                    openShizukuApp()
                }
                ShizukuHelper.Status.NEED_PERMISSION -> ShizukuHelper.requestPermission()
                ShizukuHelper.Status.READY -> bindRemoteIfPossible()
            }
        }
        btnInstallShizuku.setOnClickListener { openUrl(ShizukuHelper.DOWNLOAD_URL) }
        btnOpenDevSettings.setOnClickListener { openDeveloperOptions() }

        btnGrantMock.setOnClickListener { grantMockOpsViaShizuku() }

        btnEnableLocation.setOnClickListener { enableLocationViaShizuku() }
        btnDiagnose.setOnClickListener { showDiagnostics() }
        btnFenceToggle.setOnClickListener { toggleFence() }

        btnStart.setOnClickListener { startMocking() }
        btnStop.setOnClickListener { stopMocking() }

        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val preset = Preset.ALL.getOrNull(pos) ?: return
                if (preset.lat == 0.0 && preset.lng == 0.0) return
                etLat.setText(preset.lat.toString())
                etLng.setText(preset.lng.toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 实时持久化坐标
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { saveCoords() }
        }
        etLat.addTextChangedListener(watcher)
        etLng.addTextChangedListener(watcher)
        etAccuracy.addTextChangedListener(watcher)
    }

    // -------- 业务 --------

    private fun bindRemoteIfPossible() {
        if (remoteService != null) return
        if (ShizukuHelper.checkStatus(this) != ShizukuHelper.Status.READY) return
        try {
            ShizukuHelper.bindUserService(this, shizukuConnection)
        } catch (e: Throwable) {
            toast("绑定 Shizuku UserService 失败：${e.message}")
        }
    }

    private fun grantMockOpsViaShizuku() {
        val rs = remoteService
        if (rs == null) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku 未连接")
                .setMessage("请先点击「① 申请 Shizuku 权限」并等待绿色「就绪」提示后再试。\n\n" +
                        "如果不想用 Shizuku，可以点最下方「打开开发者选项」手动设置：\n" +
                        "  → 选择模拟位置信息应用 → 选「阿米 虚拟定位」")
                .setPositiveButton("好") { d, _ -> d.dismiss() }
                .show()
            return
        }
        Thread {
            val code = try { rs.grantMockLocation(packageName) } catch (e: Throwable) { -1 }
            val q = try { rs.queryMockLocation(packageName) } catch (e: Throwable) { e.message ?: "" }
            runOnUiThread {
                if (code == 0) toast("已授予 mock_location 权限") else toast("授权失败 (code=$code)")
                tvMockOpsStatus.text = "mock_location 状态:\n$q"
                refreshAllStatus()
            }
        }.start()
    }

    private fun enableLocationViaShizuku() {
        val rs = remoteService
        if (rs == null) {
            toast("请先连接 Shizuku")
            return
        }
        Thread {
            val code = try { rs.enableLocationServices() } catch (e: Throwable) { -1 }
            val mode = try { rs.queryLocationMode() } catch (e: Throwable) { e.message ?: "" }
            runOnUiThread {
                if (code == 0) {
                    toast("已打开系统定位（mode=$mode）")
                    // 系统定位主开关 OFF→ON 时，部分 ROM 会清空 test provider，
                    // 这里如果服务已在跑，强制重启注入循环以重新注册。
                    if (MockGpsForegroundService.isRunning()) {
                        val (la, ln) = MockGpsForegroundService.providerInstance(this).currentLatLng()
                        val acc = etAccuracy.text.toString().toFloatOrNull() ?: 1f
                        MockGpsForegroundService.stopService(this)
                        // 给系统 200ms 处理 stopService
                        window.decorView.postDelayed({
                            MockGpsForegroundService.startService(this, la, ln, acc)
                            toast("已重新注册虚拟 provider")
                            refreshAllStatus()
                        }, 200)
                    }
                } else {
                    toast("打开失败 (code=$code)，请去「设置 → 位置信息」手动打开")
                    openSystemLocationSettings()
                }
                refreshAllStatus()
            }
        }.start()
    }

    private fun showDiagnostics() {
        val rs = remoteService
        val running = MockGpsForegroundService.isRunning()
        val (la, ln) = MockGpsForegroundService.providerInstance(this).currentLatLng()

        val header = StringBuilder()
            .append("== 应用状态 ==\n")
            .append("• 注入循环: ${if (running) "● 运行中" else "○ 未运行"}\n")
            .append("• 当前坐标: ${"%.6f".format(la)}, ${"%.6f".format(ln)}\n")
            .append("• 推送频率: 2 Hz (500ms / 次)\n\n")

        if (rs == null) {
            showLongDialog("诊断", header.toString() + "Shizuku 未连接，无法 dumpsys。")
            return
        }
        Thread {
            val mockOps = try { rs.queryMockLocation(packageName) } catch (e: Throwable) { e.message ?: "" }
            val locMode = try { rs.queryLocationMode() } catch (e: Throwable) { e.message ?: "" }
            val dump = try { rs.dumpLocation() } catch (e: Throwable) { e.message ?: "" }
            val sysCheck = StringBuilder()
                .append("== 系统状态（来自 Shizuku）==\n")
                .append("• mock_location AppOps:\n  $mockOps\n")
                .append("• 系统定位主开关 location_mode = $locMode  (3=高精度, 0=关闭)\n\n")
                .append("== dumpsys location 关键段落 ==\n")
                .append(dump.ifBlank { "(空)" })
                .append("\n\n== 高德/百度 仍读真位置时的检查 ==\n")
                .append("1. location_mode 必须是 3（不是 0/1/2）\n")
                .append("2. mock_location AppOps 必须是 allow\n")
                .append("3. dumpsys 中 gps provider 那一行应有 'mock=true' 或 'TestProvider'\n")
                .append("4. 关掉 Wi-Fi + 移动数据再开高德测一次（绕开自家网络定位）\n")
                .append("5. 高德进入「我 → 设置 → 通用 → 定位 → 仅 GPS」")
            runOnUiThread {
                showLongDialog("诊断", header.toString() + sysCheck.toString())
            }
        }.start()
    }

    private fun showLongDialog(title: String, body: String) {
        val sv = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = body
            textSize = 11f
            setPadding(40, 24, 40, 24)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun toggleFence() {
        if (LocationFenceVpnService.isRunning) {
            LocationFenceVpnService.stop(this)
            toast("已停止 GPS 围栏")
            window.decorView.postDelayed({ refreshAllStatus() }, 300)
            return
        }
        // 启动前需要 VpnService.prepare（首次会弹 Android 系统授权对话框）
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            // 已授权过
            LocationFenceVpnService.start(this)
            toast("已启动 GPS 围栏 VPN")
            window.decorView.postDelayed({ refreshAllStatus() }, 300)
        }
    }

    private fun openSystemLocationSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            toast("请到「设置 → 位置信息」手动打开")
        }
    }

    private fun startMocking() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val acc = etAccuracy.text.toString().toFloatOrNull() ?: 1f
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            toast("经纬度无效")
            return
        }
        ensureLocationPermission()

        // 启动前检查系统定位主开关。OFF 时再 addTestProvider 也是无效的，
        // 高德/百度只有在主开关开启后才会读取我们的 mock 坐标。
        if (!isSystemLocationOn()) {
            AlertDialog.Builder(this)
                .setTitle("系统定位主开关未打开")
                .setMessage("addTestProvider 需要系统定位处于开启状态。\n\n" +
                        "推荐让本 APP 通过 Shizuku 自动打开，避免 ROM 在打开瞬间清空 test provider。")
                .setPositiveButton("通过 Shizuku 打开") { _, _ ->
                    enableLocationViaShizuku()
                    // enableLocationViaShizuku 成功后会自动重启服务（如果之前启动过）
                    // 这里再主动启动一次，覆盖「之前未启动」的场景
                    window.decorView.postDelayed({ doStartMockingNow(lat, lng, acc) }, 600)
                }
                .setNeutralButton("打开系统设置") { _, _ ->
                    openSystemLocationSettings()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doStartMockingNow(lat, lng, acc)
    }

    private fun doStartMockingNow(lat: Double, lng: Double, acc: Float) {
        try {
            MockGpsForegroundService.startService(this, lat, lng, acc)
            toast("已启动虚拟定位")
        } catch (e: SecurityException) {
            AlertDialog.Builder(this)
                .setTitle("缺少 mock_location 权限")
                .setMessage("addTestProvider 被系统拒绝。请确保以下任一条件满足：\n\n" +
                        "（推荐）已通过 Shizuku 授权 mock_location\n" +
                        "（手动）开发者选项 → 选择模拟位置应用 → 选本 APP")
                .setPositiveButton("打开开发者选项") { _, _ -> openDeveloperOptions() }
                .setNegativeButton("取消", null)
                .show()
        }
        refreshAllStatus()
    }

    private fun stopMocking() {
        MockGpsForegroundService.stopService(this)
        toast("已停止")
        refreshAllStatus()
    }

    // -------- UI 状态刷新 --------

    private fun refreshAllStatus() {
        val st = ShizukuHelper.checkStatus(this)
        val sticker = when (st) {
            ShizukuHelper.Status.READY -> "✓"
            else -> "✗"
        }
        tvShizukuStatus.text = "[$sticker Shizuku]\n${ShizukuHelper.statusDescription(st)}"
        btnShizukuPermit.isEnabled = (st != ShizukuHelper.Status.READY)
        btnInstallShizuku.visibility =
            if (st == ShizukuHelper.Status.NOT_INSTALLED) android.view.View.VISIBLE else android.view.View.GONE

        // mock ops 状态由远程服务异步查询；这里只更新「未连接」时的占位
        if (remoteService == null) {
            tvMockOpsStatus.text = "mock_location 状态:\n（Shizuku 远程服务未连接）"
            btnGrantMock.isEnabled = false
        } else {
            btnGrantMock.isEnabled = true
            // 异步刷新一次
            Thread {
                val q = try { remoteService?.queryMockLocation(packageName) ?: "" }
                catch (e: Throwable) { e.message ?: "" }
                runOnUiThread { tvMockOpsStatus.text = "mock_location 状态:\n$q" }
            }.start()
        }

        val running = MockGpsForegroundService.isRunning()
        tvRunStatus.text = if (running) {
            val (lat, lng) = MockGpsForegroundService.providerInstance(this).currentLatLng()
            "● 运行中  当前坐标 ${"%.6f".format(lat)}, ${"%.6f".format(lng)}"
        } else {
            "○ 未运行"
        }
        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        // GPS 围栏 (VpnService) 状态
        val fence = LocationFenceVpnService.isRunning
        tvFenceStatus.text = if (fence) {
            "● GPS 围栏运行中  ${LocationFenceVpnService.stats()}"
        } else {
            "○ GPS 围栏未运行 — 高德/百度可能仍走自家网络定位"
        }
        btnFenceToggle.text = if (fence) "停止 GPS 围栏" else "启动 GPS 围栏（拦截高德/百度网络定位）"
    }

    // -------- 持久化 --------

    private fun loadSavedCoords() {
        etLat.setText(prefs.getString("lat", "39.908823"))
        etLng.setText(prefs.getString("lng", "116.397470"))
        etAccuracy.setText(prefs.getString("accuracy", "1.0"))
    }

    private fun saveCoords() {
        prefs.edit()
            .putString("lat", etLat.text.toString())
            .putString("lng", etLng.text.toString())
            .putString("accuracy", etAccuracy.text.toString())
            .apply()
    }

    // -------- 工具 --------

    private fun isSystemLocationOn(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                val mode = android.provider.Settings.Secure.getInt(
                    contentResolver, android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF
                )
                mode != android.provider.Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun ensureLocationPermission() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 9001)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("找不到浏览器，请手动访问 $url")
        }
    }

    private fun openShizukuApp() {
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) startActivity(launch) else toast("Shizuku 未安装")
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            toast("无法打开开发者选项，请手动进入「设置 → 系统 → 开发者选项」")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

private data class Preset(val label: String, val lat: Double, val lng: Double) {
    companion object {
        val ALL = listOf(
            Preset("（自定义）", 0.0, 0.0),
            Preset("北京 · 天安门", 39.908823, 116.397470),
            Preset("上海 · 人民广场", 31.231706, 121.472644),
            Preset("广州 · 珠江新城", 23.119741, 113.323289),
            Preset("深圳 · 平安大厦", 22.536891, 114.057868),
            Preset("杭州 · 西湖", 30.246802, 120.149408),
            Preset("成都 · 春熙路", 30.658232, 104.081534),
            Preset("武汉 · 黄鹤楼", 30.546306, 114.301946),
            Preset("西安 · 钟楼", 34.260930, 108.943109),
            Preset("香港 · 维多利亚港", 22.293108, 114.169276),
            Preset("台北 · 101", 25.033964, 121.564468),
            Preset("东京 · 涉谷", 35.658034, 139.701636),
            Preset("纽约 · 时代广场", 40.758896, -73.985130),
        )
    }
}
