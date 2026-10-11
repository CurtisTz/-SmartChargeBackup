package com.example.smartchargebackup

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.os.Environment
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Button
import java.util.UUID
import android.app.usage.StorageStatsManager
import android.system.Os
import android.system.StructStatVfs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.MediaStore
import android.content.ContentUris
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.storage.StorageManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.formatter.ValueFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var lineChart: LineChart
    private lateinit var speedChart: LineChart
    private var isMonitoring = false
    private var timeIndex = 0f
    private var currentSpeedKBps = 0f
    private lateinit var chartContainer: LinearLayout
    private lateinit var toggleHintText: TextView
    private var isChartVisible = false
    
    private lateinit var valTime: TextView; private lateinit var valTemp: TextView; private lateinit var valCpu: TextView
    private lateinit var valPower: TextView; private lateinit var valVolt: TextView; private lateinit var valCurr: TextView
    private lateinit var valStorage: TextView; private lateinit var progressBar: ProgressBar
    private lateinit var progressPercentText: TextView; private lateinit var voltCurrText: TextView
    private lateinit var valEstimateTime: TextView; private lateinit var valBackupSpeed: TextView; private lateinit var valPhotoCount: TextView; private lateinit var valVideoCount: TextView
    private lateinit var valBackupWarning: TextView; private lateinit var dashboardLayout: LinearLayout
    private lateinit var btnPause: Button; private lateinit var btnStop: Button
    private lateinit var sdCardName: TextView; private lateinit var sdCardSpaceBar: ProgressBar; private lateinit var sdCardSpaceText: TextView
    
    private var backupJob: Job? = null
    private var isPaused = false
    private var usbTreeUri: Uri? = null
    private var isExternalMounted = false
    private lateinit var prefs: android.content.SharedPreferences
    private var isUpdatingStorage = false // 新增：用於防止儲存更新任務重疊

    private var backupStartTime: Long = 0
    private var statsPhotos = 0; private var statsVideos = 0; private var statsSkipped = 0; private var statsBytes = 0L

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private val openDocumentTreeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            saveTargetUri(uri)
            updateStorageInfo()
            if (!isMonitoring) handler.postDelayed({ manualStartBackup() }, 500)
        }
    }

    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateStorageInfo() }
    }

    private val hardwareReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { 
                    this@MainActivity.stopBackup()
                    updateStorageInfo() 
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { 
                    updateStorageInfo()
                    // 核心優化：偵測到連接時，如果已經有路徑設定，直接啟動自動備份邏輯
                    checkAndAutoStartBackup()
                }
                Intent.ACTION_POWER_DISCONNECTED -> { 
                    this@MainActivity.stopBackup() 
                }
                Intent.ACTION_BATTERY_CHANGED -> { 
                    updateSystemStatus(intent) 
                }
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStorageInfo(); updateSystemStatus() 
            valTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            valCpu.text = getCpuStatus(); handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenVisibility(); setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("BackupPrefs", MODE_PRIVATE)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        valBackupWarning = findViewById(R.id.valBackupWarning)
        valTime = findViewById(R.id.valTime); valTemp = findViewById(R.id.valTemp); valCpu = findViewById(R.id.valCpu)
        valPower = findViewById(R.id.valPower); valVolt = findViewById(R.id.valVolt); valCurr = findViewById(R.id.valCurr)
        valStorage = findViewById(R.id.valStorage); progressBar = findViewById(R.id.taskProgressBar)
        progressPercentText = findViewById(R.id.valProgressPercent); voltCurrText = findViewById(R.id.valVoltCurr)
        valEstimateTime = findViewById(R.id.valEstimateTime); valBackupSpeed = findViewById(R.id.valBackupSpeed); valPhotoCount = findViewById(R.id.valPhotoCount); valVideoCount = findViewById(R.id.valVideoCount)
        btnPause = findViewById(R.id.btnPause); btnStop = findViewById(R.id.btnStop)
        sdCardName = findViewById(R.id.sdCardName); sdCardSpaceBar = findViewById(R.id.sdCardSpaceBar); sdCardSpaceText = findViewById(R.id.sdCardSpaceText)
        lineChart = findViewById(R.id.realtimeChart); speedChart = findViewById(R.id.speedChart)
        chartContainer = findViewById(R.id.chartContainer); toggleHintText = findViewById(R.id.toggleHintText)
        loadTargetUri(); dashboardLayout.visibility = View.VISIBLE; updateStorageInfo()
        btnPause.setOnClickListener { if (!isMonitoring) { if (usbTreeUri == null) openDocumentTreeLauncher.launch(null) else manualStartBackup() } else togglePause() }
        btnStop.setOnClickListener { stopBackup() }
        val sdCardContainer = sdCardName.parent as View
        sdCardContainer.setOnClickListener { if (!isMonitoring) openDocumentTreeLauncher.launch(null) }
        sdCardContainer.setOnLongClickListener { resetBackupTarget(); true }
        setupChart()
        val infoPanel = findViewById<View>(R.id.infoPanel); val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        val toggleLayoutClickListener = View.OnClickListener { toggleChartVisibility() }
        infoPanel.setOnClickListener(toggleLayoutClickListener); toggleHintText.setOnClickListener(toggleLayoutClickListener); rootLayout.setOnClickListener(toggleLayoutClickListener)
        registerHardwareReceivers(); handler.post(updateRunnable)
        handleUsbIntent(intent)
    }

    private fun updateSystemStatus(providedIntent: Intent? = null) {
        val batteryIntent = providedIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val volt = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val curr = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000.0
            val pwr = kotlin.math.abs((volt * curr) / 1000.0)
            valTemp.text = String.format(Locale.getDefault(), "%.1f °C", temp)
            valPower.text = String.format(Locale.getDefault(), "%.1fW", pwr)
            valVolt.text = String.format(Locale.getDefault(), "%.1fV", volt)
            valCurr.text = String.format(Locale.getDefault(), "%.0fmA", curr)
            
            addEntryToChart(temp.toFloat(), curr.toFloat())
            if (!isMonitoring) voltCurrText.text = if (usbTreeUri == null) "等待授權 SD 卡路徑..." else "系統就緒"
        }
    }

    private fun getCpuStatus(): String { val load = if (isMonitoring) (40..85).random() else (5..20).random(); return "LOAD: $load% (${Runtime.getRuntime().availableProcessors()} CORE)" }

    private fun updateStorageInfo() {
        // 直接在後台啟動，透過 isUpdatingStorage 鎖保護，確保任務不重疊但能即時響應
        if (isUpdatingStorage) return
        isUpdatingStorage = true
        
        activityScope.launch(Dispatchers.IO) {
            var found = false
            var totalBytes: Long = 0
            var availableBytes: Long = 0
            var description = "外部儲存裝置"

            try {
                usbTreeUri?.let { uri ->
                    // 1. 內核級 fstatvfs (最快路徑)
                    try {
                        val treeId = DocumentsContract.getTreeDocumentId(uri)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
                        contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
                            val stats = android.system.Os.fstatvfs(pfd.fileDescriptor)
                            totalBytes = stats.f_frsize * stats.f_blocks
                            availableBytes = stats.f_frsize * stats.f_bavail
                            if (totalBytes > 0) {
                                description = DocumentFile.fromTreeUri(this@MainActivity, uri)?.name ?: "USB 磁碟"
                                found = true
                            }
                        }
                    } catch (e: Exception) {}
                    
                    // 2. SAF 根目錄查詢 (次快路徑)
                    if (!found) {
                        try {
                            val rootsUri = DocumentsContract.buildRootsUri(uri.authority ?: "com.android.externalstorage.documents")
                            contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                                val availCol = cursor.getColumnIndex("available_bytes")
                                val totalCol = cursor.getColumnIndex("total_bytes")
                                val titleCol = cursor.getColumnIndex("title")
                                if (cursor.moveToFirst()) {
                                    totalBytes = if (totalCol >= 0) cursor.getLong(totalCol) else 0L
                                    availableBytes = if (availCol >= 0) cursor.getLong(availCol) else 0L
                                    if (titleCol >= 0) description = cursor.getString(titleCol) ?: description
                                    found = totalBytes > 0
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { Log.e("Storage", "Update error") }

            withContext(Dispatchers.Main) {
                if (found) {
                    val used = totalBytes - availableBytes
                    val usedGB = used / 1073741824.0
                    val totalGB = totalBytes / 1073741824.0
                    val progress = if (totalBytes > 0) ((used.toDouble() / totalBytes) * 100).toInt() else 0
                    sdCardName.text = "TARGET: $description"
                    sdCardName.setTextColor(Color.parseColor("#00FFCC"))
                    sdCardSpaceText.text = String.format(Locale.getDefault(), "%.1f GB / %.1f GB (已使用 %.0f%%)", usedGB, totalGB, progress.toDouble())
                    sdCardSpaceBar.progress = progress
                    isExternalMounted = true
                } else if (!isMonitoring) {
                    sdCardName.text = "WAITING FOR SD CARD..."
                    sdCardName.setTextColor(Color.GRAY)
                    sdCardSpaceText.text = "尚未偵測到外部儲存裝置 (USB OTG)"
                    sdCardSpaceBar.progress = 0
                    isExternalMounted = false
                }
                if (!isMonitoring) valStorage.text = if (usbTreeUri == null) (if (found) "NEED AUTH" else "MISSING") else "READY"
                isUpdatingStorage = false
            }
        }
    }

    private fun checkAndAutoStartBackup() {
        if (isMonitoring) return
        if (usbTreeUri != null) {
            // 如果已經授權過 SD 卡，啟動 3 秒倒數自動備份對話框
            val builder = AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            builder.setTitle("⚡ 備份裝置已就緒")
            builder.setMessage("系統將在 3 秒後自動啟動備份...")
            val dialog = builder.create()
            dialog.show()
            
            activityScope.launch {
                delay(3000)
                if (dialog.isShowing) {
                    dialog.dismiss()
                    manualStartBackup()
                }
            }
        } else {
            showUsbDialog()
        }
    }

    private fun showUsbDialog() {
        if (!isMonitoring) {
            val builder = AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            builder.setTitle("⚡ 偵測到備份裝置").setMessage("是否現在啟動備份流程？").setCancelable(false)
                .setPositiveButton("是") { _, _ -> manualStartBackup() }.setNegativeButton("否", null).show()
        }
    }

    private fun runBackupSequence() {
        if (isMonitoring) return
        isMonitoring = true
        isPaused = false
        statsPhotos = 0
        statsVideos = 0
        statsSkipped = 0
        statsBytes = 0L
        backupStartTime = System.currentTimeMillis()
        
        updateButtonStates()
        valBackupWarning.visibility = View.VISIBLE
        valStorage.text = "BACKING UP..."

        backupJob = activityScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val list = mutableListOf<MediaItem>()
                    queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, list, false)
                    queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, list, true)
                    list
                }
                
                if (items.isEmpty()) {
                    finishBackup(false)
                    return@launch
                }

                val backupRootDir = withContext(Dispatchers.IO) {
                    val tree = DocumentFile.fromTreeUri(this@MainActivity, usbTreeUri!!)
                    tree?.findFile("SmartChargeBackup") ?: tree?.createDirectory("SmartChargeBackup")
                } ?: return@launch

                val existingFiles = withContext(Dispatchers.IO) {
                    backupRootDir.listFiles().mapNotNull { it.name }.toHashSet()
                }

                val itemsToBackup = items.filter { !existingFiles.contains(it.name) }
                
                if (itemsToBackup.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        valStorage.text = "UP TO DATE"
                        voltCurrText.text = "所有檔案皆已備份"
                        finishBackup(false)
                    }
                    return@launch
                }

                val totalPhotos = itemsToBackup.count { !it.isVideo }
                val totalVideos = itemsToBackup.count { it.isVideo }
                val totalSizeBytes = itemsToBackup.sumOf { it.size }

                withContext(Dispatchers.Main) {
                    progressBar.max = itemsToBackup.size
                    progressBar.progress = 0
                }

                var processed = 0
                var processedBytes = 0L
                val startT = System.currentTimeMillis()
                
                for (item in itemsToBackup) {
                    if (!isActive) break
                    while (isPaused) {
                        delay(500)
                        if (!isActive) break
                    }

                    if (copyFile(item, backupRootDir)) {
                        if (item.isVideo) statsVideos++ else statsPhotos++
                        statsBytes += item.size
                    }

                    processed++
                    processedBytes += item.size
                    
                    if (processed % 10 == 0 || processed == itemsToBackup.size) {
                        val now = System.currentTimeMillis()
                        val elapsed = now - startT
                        val avgSpeed = if (elapsed > 0) processedBytes.toDouble() / elapsed else 0.0 // bytes/ms
                        val remainingBytes = totalSizeBytes - processedBytes
                        val est = if (avgSpeed > 0) (remainingBytes / avgSpeed).toLong() else 0L

                        withContext(Dispatchers.Main) {
                            progressBar.progress = processed
                            progressPercentText.text = "${(processed * 100) / itemsToBackup.size}%"
                            valPhotoCount.text = "照片: $statsPhotos / $totalPhotos"
                            valVideoCount.text = "影片: $statsVideos / $totalVideos"
                            valEstimateTime.text = "剩餘: ${formatMillis(est)}"
                            valBackupSpeed.text = formatSpeed(avgSpeed)
                            currentSpeedKBps = avgSpeed.toFloat() // 更新圖表速度數據
                            voltCurrText.text = "處理中: ${item.name}"
                            updateStorageInfo()
                        }
                    }
                }
                
                // 備份成功結束，更新時間戳記
                prefs.edit().putLong("last_backup_timestamp", System.currentTimeMillis() / 1000).apply()
                finishBackup(true)
            } catch (e: Exception) {
                Log.e("Backup", "Error during backup", e)
                finishBackup(false)
            }
        }
    }

    private suspend fun copyFile(item: MediaItem, dest: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val newFile = dest.createFile(item.mime, item.name) ?: return@withContext false
            contentResolver.openInputStream(item.uri)?.use { input ->
                contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    val buffer = ByteArray(1024 * 1024) // 1MB 大緩衝區
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) { false }
    }

    private fun formatSpeed(speedBytesPerMs: Double): String {
        val speedKBps = speedBytesPerMs // bytes/ms = KB/s
        return if (speedKBps > 1024) {
            String.format(Locale.getDefault(), "%.1f MB/s", speedKBps / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.0f KB/s", speedKBps)
        }
    }

    private fun finishBackup(showReport: Boolean) {
        isMonitoring = false; currentSpeedKBps = 0f; valBackupWarning.visibility = View.GONE; updateButtonStates()
        valStorage.text = if (usbTreeUri == null) "MISSING" else "COMPLETED"
        if (!showReport) resetProgressUI()
        updateStorageInfo()
        if (showReport) handler.post { showBackupReport() }
    }

    private fun resetProgressUI() {
        progressBar.progress = 0
        progressPercentText.text = "0%"
        valPhotoCount.text = "PHOTOS: 0 / 0"
        valVideoCount.text = "VIDEOS: 0 / 0"
        valEstimateTime.text = "EST: --:--"
        valBackupSpeed.text = "0 KB/s"
        voltCurrText.text = "SYSTEM READY"
    }

    private fun showBackupReport() {
        val duration = System.currentTimeMillis() - backupStartTime
        val durationMins = duration / 60000.0
        val totalMB = statsBytes / (1024.0 * 1024.0)
        val avgSpeedMin = if (durationMins > 0) totalMB / durationMins else 0.0
        
        val msg = String.format(Locale.getDefault(), 
            "✅ 備份結束\n📸 照片: %d\n🎥 影片: %d\n⏱ 耗時: %s\n🚀 平均速度: %.1f MB/min",
            statsPhotos, statsVideos, formatMillis(duration), avgSpeedMin)
            
        AlertDialog.Builder(this, R.style.CyberpunkDialogTheme).setTitle("📊 備份報告").setMessage(msg).setPositiveButton("確定", null).show()
    }

    private fun stopBackup() {
        if (!isMonitoring) return
        AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            .setTitle("⚡ 停止備份")
            .setMessage("確定要中斷？")
            .setPositiveButton("是") { _, _ -> 
                backupJob?.cancel()
                finishBackup(true) // 修改：停止時顯示報告統計
            }
            .setNegativeButton("否", null)
            .show()
    }

    private fun setupChart() {
        val secondFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = "${value.toInt()}s"
        }

        val charts = listOf(lineChart, speedChart)
        charts.forEach { chart ->
            chart.apply {
                description.isEnabled = false; setBackgroundColor(Color.parseColor("#121829")); legend.textColor = Color.WHITE
                xAxis.apply { 
                    textColor = Color.WHITE; position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false)
                    valueFormatter = secondFormatter
                }
                // 修正：徹底禁用右側 Y 軸顯示
                axisRight.apply { 
                    isEnabled = false // 關閉右側座標標記
                    textColor = Color.WHITE; setDrawGridLines(false); axisMinimum = 0f 
                }
                data = LineData()
            }
        }
        
        // 硬體圖表專屬設定
        lineChart.axisLeft.apply { textColor = Color.WHITE; setDrawGridLines(true); gridColor = Color.parseColor("#33FFFFFF"); axisMinimum = 25f; axisMaximum = 50f }
        
        // 速度圖表專屬設定 (MB/s)
        speedChart.axisLeft.apply { textColor = Color.WHITE; setDrawGridLines(true); gridColor = Color.parseColor("#33FFFFFF"); axisMinimum = 0f; axisMaximum = 20f } // 調整為 20MB/s 上限
    }

    private fun addEntryToChart(t: Float, c: Float) {
        val hData = lineChart.data ?: return
        val sData = speedChart.data ?: return

        // 硬體圖表數據集
        if (hData.dataSetCount < 2) {
            hData.addDataSet(LineDataSet(null, "溫度 (°C)").apply { color = Color.RED; setDrawValues(false); setDrawCircles(false); lineWidth = 2.5f; axisDependency = YAxis.AxisDependency.LEFT })
            hData.addDataSet(LineDataSet(null, "電流 (mA)").apply { color = Color.GREEN; setDrawValues(false); setDrawCircles(false); lineWidth = 2f; axisDependency = YAxis.AxisDependency.RIGHT })
        }
        
        // 速度圖表數據集 (轉換為 MB/s)
        if (sData.dataSetCount < 1) {
            sData.addDataSet(LineDataSet(null, "速度 (MB/s)").apply { color = Color.CYAN; setDrawValues(false); setDrawCircles(false); lineWidth = 2f; axisDependency = YAxis.AxisDependency.LEFT; setDrawFilled(true); fillColor = Color.CYAN; fillAlpha = 50 })
        }

        hData.addEntry(Entry(timeIndex, t), 0); hData.addEntry(Entry(timeIndex, c), 1)
        val speedMBps = currentSpeedKBps / 1024f
        sData.addEntry(Entry(timeIndex, speedMBps), 0)

        listOf(lineChart, speedChart).forEach { chart ->
            chart.data.notifyDataChanged(); chart.notifyDataSetChanged()
            chart.setVisibleXRangeMaximum(30f); chart.moveViewToX(timeIndex)
            chart.invalidate()
        }
        timeIndex++
    }

    private fun registerHardwareReceivers() {
        val f = IntentFilter().apply { 
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED) 
        }
        registerReceiver(hardwareReceiver, f, Context.RECEIVER_EXPORTED)
        registerReceiver(mediaReceiver, IntentFilter().apply { 
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addDataScheme("file") 
        }, Context.RECEIVER_EXPORTED)
    }

    private fun setupLockScreenVisibility() {
        setShowWhenLocked(true); setTurnScreenOn(true)
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
    }

    private fun loadTargetUri() { prefs.getString("target_uri", null)?.let { try { usbTreeUri = Uri.parse(it); contentResolver.takePersistableUriPermission(usbTreeUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { usbTreeUri = null } } }
    private fun saveTargetUri(uri: Uri) { usbTreeUri = uri; contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); prefs.edit().putString("target_uri", uri.toString()).apply() }
    private fun resetBackupTarget() { usbTreeUri = null; prefs.edit().remove("target_uri").apply(); updateStorageInfo() }
    private fun handleUsbIntent(intent: Intent?) { if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) updateStorageInfo() }
    private fun hasStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun requestStoragePermission() { val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE); androidx.core.app.ActivityCompat.requestPermissions(this, p, 1002) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) manualStartBackup() }
    private fun formatMillis(ms: Long): String { val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000; return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s) }
    private fun togglePause() { isPaused = !isPaused; updateButtonStates() }
    private fun manualStartBackup() { if (isMonitoring) return; if (!hasStoragePermission()) { requestStoragePermission(); return }; if (usbTreeUri == null) { openDocumentTreeLauncher.launch(null); return }; runBackupSequence() }
    private fun updateButtonStates() { btnPause.text = if (!isMonitoring) (if (usbTreeUri == null) "SETUP" else "START") else (if (isPaused) "RESUME" else "PAUSE"); btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!isMonitoring) Color.parseColor("#00E676") else (if (isPaused) Color.parseColor("#00FFCC") else Color.parseColor("#FFD740"))); btnStop.isEnabled = isMonitoring }
    private fun toggleChartVisibility() { isChartVisible = !isChartVisible; chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE; toggleHintText.text = if (isChartVisible) "[ 點擊切換為純文字 ]" else "[ 點擊彈出圖表監控 ]" }
    private fun queryMedia(uri: Uri, list: MutableList<MediaItem>, isVideo: Boolean) { 
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%DCIM/Camera%")
        
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), selection, args, null)?.use { c -> 
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                list.add(MediaItem(ContentUris.withAppendedId(uri, c.getLong(idCol)), c.getString(nameCol), c.getString(mimeCol), isVideo, c.getLong(sizeCol))) 
            }
        } 
    }
    private data class MediaItem(val uri: Uri, val name: String, val mime: String, val isVideo: Boolean, val size: Long)
    override fun onDestroy() { super.onDestroy(); activityScope.cancel(); handler.removeCallbacks(updateRunnable); try { unregisterReceiver(hardwareReceiver); unregisterReceiver(mediaReceiver) } catch (e: Exception) {} }
}
