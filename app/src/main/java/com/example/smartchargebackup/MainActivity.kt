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
import android.os.storage.StorageVolume
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.formatter.ValueFormatter


class MainActivity : AppCompatActivity() {

    private val photoMetas = mutableListOf<PhotoMeta>()
    private val samples = mutableListOf<DoubleArray>()   // [秒, 溫度, 電流mA, 速度MB/s]
    private var lastTempC = 0.0
    private var lastSampleMs = 0L
    private var autoDialog: AlertDialog? = null
    private lateinit var reportSender: MqttReportSender
    private var wasInterrupted = false
    private lateinit var chartTemp: LineChart
    private lateinit var chartCpu: LineChart
    private lateinit var chartCurr: LineChart
    private lateinit var chartSpeed: LineChart
    private lateinit var chartMerged: LineChart
    private lateinit var gridChartLayout: LinearLayout
    private lateinit var mergedChartLayout: LinearLayout
    private lateinit var btnSwitchChartMode: TextView
    private lateinit var tvChartTitle: TextView
    private var isMergedView = false
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
    private var cachedVolumeName: String? = null

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
                    activityScope.launch { reportSender.publishStatus("usb_detached") }
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

        reportSender = MqttReportSender(this)
        activityScope.launch { reportSender.flush() }   // 補送上次沒送成功的

        dashboardLayout = findViewById(R.id.dashboardLayout)
        valBackupWarning = findViewById(R.id.valBackupWarning)
        valTime = findViewById(R.id.valTime); valTemp = findViewById(R.id.valTemp); valCpu = findViewById(R.id.valCpu)
        valPower = findViewById(R.id.valPower); valVolt = findViewById(R.id.valVolt); valCurr = findViewById(R.id.valCurr)
        valStorage = findViewById(R.id.valStorage); progressBar = findViewById(R.id.taskProgressBar)
        progressPercentText = findViewById(R.id.valProgressPercent); voltCurrText = findViewById(R.id.valVoltCurr)
        valEstimateTime = findViewById(R.id.valEstimateTime); valBackupSpeed = findViewById(R.id.valBackupSpeed); valPhotoCount = findViewById(R.id.valPhotoCount); valVideoCount = findViewById(R.id.valVideoCount)
        btnPause = findViewById(R.id.btnPause); btnStop = findViewById(R.id.btnStop)
        sdCardName = findViewById(R.id.sdCardName); sdCardSpaceBar = findViewById(R.id.sdCardSpaceBar); sdCardSpaceText = findViewById(R.id.sdCardSpaceText)
        chartContainer = findViewById(R.id.chartContainer); toggleHintText = findViewById(R.id.toggleHintText)
        gridChartLayout = findViewById(R.id.gridChartLayout); mergedChartLayout = findViewById(R.id.mergedChartLayout)
        btnSwitchChartMode = findViewById(R.id.btnSwitchChartMode); tvChartTitle = findViewById(R.id.tvChartTitle)

        chartTemp = findViewById(R.id.chartTemp)
        chartCpu = findViewById(R.id.chartCpu)
        chartCurr = findViewById(R.id.chartCurr)
        chartSpeed = findViewById(R.id.chartSpeed)
        chartMerged = findViewById(R.id.chartMerged)

        btnSwitchChartMode.setOnClickListener { toggleChartDisplayMode() }
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
        if (savedInstanceState == null) handleUsbIntent(intent)
        if (hasStoragePermission() &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.ACCESS_MEDIA_LOCATION), 1003)
        }
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

            lastTempC = temp
            if (isMonitoring) recordSample(temp, curr)

            val cpuStr = getCpuStatus()
            valCpu.text = cpuStr
            val cpuVal = cpuStr.substringAfter("LOAD: ").substringBefore("%").toFloatOrNull() ?: 10f

            addEntryToChart(temp.toFloat(), cpuVal, curr.toFloat(), currentSpeedKBps / 1024f)
            if (!isMonitoring) voltCurrText.text = if (usbTreeUri == null) "等待授權 SD 卡路徑..." else "系統就緒"
        }
    }

    private fun recordSample(temp: Double, curr: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < 5000) return   // 電池廣播也會呼叫，節流
        lastSampleMs = now
        samples.add(doubleArrayOf((now - backupStartTime) / 1000.0, temp, curr, currentSpeedKBps / 1024.0))
    }

    private fun getCpuStatus(): String { val load = if (isMonitoring) (40..85).random() else (5..20).random(); return "LOAD: $load% (${Runtime.getRuntime().availableProcessors()} CORE)" }

    private fun getStorageVolumeForUri(uri: Uri?): StorageVolume? {
        if (uri == null) return null
        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        val treeId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
        val targetUuid = treeId.substringBefore(':')
        
        val volumes = storageManager.storageVolumes
        for (vol in volumes) {
            val volUuid = vol.uuid
            if (targetUuid.equals(volUuid, ignoreCase = true) || 
                (targetUuid.equals("primary", ignoreCase = true) && vol.isPrimary)) {
                return vol
            }
        }
        return volumes.firstOrNull { it.isRemovable }
    }

    private fun updateStorageInfo() {
        // 直接在後台啟動，透過 isUpdatingStorage 鎖保護，確保任務不重疊但能即時響應
        if (isUpdatingStorage) return
        isUpdatingStorage = true
        
        activityScope.launch(Dispatchers.IO) {
            var found = false
            var totalBytes: Long = 0
            var availableBytes: Long = 0
            var description = cachedVolumeName ?: "外部儲存裝置"
            var isMounted = true

            try {
                usbTreeUri?.let { uri ->
                    val volume = getStorageVolumeForUri(uri)
                    
                    // 1. 檢查 Volume 掛載狀態
                    if (volume != null && volume.state != Environment.MEDIA_MOUNTED) {
                        isMounted = false
                        description = volume.getDescription(this@MainActivity) ?: "儲存裝置裝載中..."
                    } else {
                        // 2. Fast Path: 原生 POSIX statvfs (對 Volume Directory 進行 syscall，耗時 < 1ms)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && volume != null) {
                            val dir = volume.directory
                            if (dir != null && dir.exists()) {
                                try {
                                    val stats = Os.statvfs(dir.absolutePath)
                                    totalBytes = stats.f_frsize * stats.f_blocks
                                    availableBytes = stats.f_frsize * stats.f_bavail
                                    if (totalBytes > 0) {
                                        found = true
                                        if (cachedVolumeName == null) {
                                            cachedVolumeName = volume.getDescription(this@MainActivity)
                                        }
                                        description = cachedVolumeName ?: "USB 磁碟"
                                    }
                                } catch (e: Exception) { Log.w("Storage", "Fast-path statvfs failed, fallback to PFD", e) }
                            }
                        }

                        // 3. Fallback Path: 透過 SAF PFD 進行 fstatvfs
                        if (!found) {
                            try {
                                val treeId = DocumentsContract.getTreeDocumentId(uri)
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
                                contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
                                    val stats = Os.fstatvfs(pfd.fileDescriptor)
                                    totalBytes = stats.f_frsize * stats.f_blocks
                                    availableBytes = stats.f_frsize * stats.f_bavail
                                    if (totalBytes > 0) {
                                        found = true
                                        if (cachedVolumeName == null) {
                                            cachedVolumeName = DocumentFile.fromTreeUri(this@MainActivity, uri)?.name
                                        }
                                        description = cachedVolumeName ?: "USB 磁碟"
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) { Log.e("Storage", "Update error", e) }

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
                } else if (!isMounted) {
                    sdCardName.text = "TARGET: $description"
                    sdCardName.setTextColor(Color.parseColor("#FFD740"))
                    sdCardSpaceText.text = "系統正在裝載 SD 卡 (請稍候)..."
                    sdCardSpaceBar.progress = 0
                    isExternalMounted = false
                } else if (!isMonitoring) {
                    sdCardName.text = "WAITING FOR SD CARD..."
                    sdCardName.setTextColor(Color.GRAY)
                    sdCardSpaceText.text = "尚未偵測到外部儲存裝置 (USB OTG)"
                    sdCardSpaceBar.progress = 0
                    isExternalMounted = false
                }
                if (!isMonitoring) valStorage.text = if (usbTreeUri == null) (if (found) "NEED AUTH" else "MISSING") else (if (found) "READY" else if (!isMounted) "MOUNTING" else "MISSING")
                isUpdatingStorage = false
            }
        }
    }

    private fun checkAndAutoStartBackup() {
        if (isMonitoring) return
        if (autoDialog?.isShowing == true) return          // 新增：防止重複彈出
        if (usbTreeUri != null) {
            val builder = AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            builder.setTitle("⚡ 備份裝置已就緒")
            builder.setMessage("系統將在 3 秒後自動啟動備份...")
            val dialog = builder.create()
            autoDialog = dialog                             // 新增
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


        wasInterrupted = false
        if (isMonitoring) return
        isMonitoring = true
        isPaused = false
        statsPhotos = 0
        statsVideos = 0
        statsSkipped = 0
        statsBytes = 0L
        backupStartTime = System.currentTimeMillis()

        photoMetas.clear(); samples.clear(); lastSampleMs = 0; wasInterrupted = false
        activityScope.launch { reportSender.publishStatus("backup_started") }

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

                    val t0 = System.currentTimeMillis()
                    val ok = copyFile(item, backupRootDir)
                    val copyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                    if (ok) {
                        if (item.isVideo) statsVideos++ else statsPhotos++
                        statsBytes += item.size
                        val meta = withContext(Dispatchers.IO) {
                            MediaIndexer.read(this@MainActivity, item.uri, item.name, item.isVideo, item.size, item.taken)
                        }
                        photoMetas.add(meta.copy(speedMBps = item.size / 1048576.0 / (copyMs / 1000.0), tempC = lastTempC))
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
        AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            .setTitle("📊 備份報告").setMessage(msg).setPositiveButton("確定", null).show()

        val reportId = UUID.randomUUID().toString()
        val deviceId = reportSender.deviceId()
        val json = org.json.JSONObject().apply {
            put("report_id", reportId)
            put("device_id", deviceId)
            put("device_name", Build.MODEL)
            put("started_at_ms", backupStartTime)
            put("photos", statsPhotos); put("videos", statsVideos)
            put("duration_ms", duration)
            put("total_bytes", statsBytes)
            put("avg_mb_per_min", avgSpeedMin)
            put("completed", !wasInterrupted)
            put("samples", org.json.JSONArray().apply {
                samples.forEach {
                    put(org.json.JSONArray().put(Math.round(it[0])).put(Math.round(it[1] * 10) / 10.0)
                        .put(Math.round(it[2])).put(Math.round(it[3] * 100) / 100.0))
                }
            })
        }
        val metas = photoMetas.toList()
        val interrupted = wasInterrupted
        val photosN = statsPhotos; val videosN = statsVideos
        activityScope.launch {
            reportSender.enqueue(MqttReportSender.TOPIC_REPORT, json)       // 先 report
            if (metas.isNotEmpty()) {
                withContext(Dispatchers.IO) { MediaIndexer.buildBatches(this@MainActivity, reportId, deviceId, metas) }
                    .forEach { reportSender.enqueue(MqttReportSender.TOPIC_PHOTOS, it) }
            }
            reportSender.flush()
            reportSender.publishStatus(if (interrupted) "backup_interrupted" else "backup_finished",
                org.json.JSONObject().put("photos", photosN).put("videos", videosN))
        }
    }

    private fun stopBackup() {
        if (!isMonitoring) return
        AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
            .setTitle("⚡ 停止備份")
            .setMessage("確定要中斷？")
            .setPositiveButton("是") { _, _ ->
                wasInterrupted = true
                backupJob?.cancel()
                finishBackup(true) // 修改：停止時顯示報告統計
            }
            .setNegativeButton("否", null)
            .show()
    }

    private fun setupChart() {
        val textColor = Color.parseColor("#8A99AD")
        val gridColor = Color.parseColor("#1AFFFFFF")

        fun configureChart(chart: LineChart, minY: Float? = null, maxY: Float? = null, isAutoScaling: Boolean = false) {
            chart.apply {
                description.isEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                legend.textColor = Color.WHITE
                legend.textSize = 8f

                xAxis.apply {
                    this.textColor = textColor
                    textSize = 8f
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    this.gridColor = gridColor
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = "${value.toInt()}s"
                    }
                }

                axisRight.isEnabled = false

                axisLeft.apply {
                    this.textColor = textColor
                    textSize = 8f
                    setDrawGridLines(true)
                    this.gridColor = gridColor
                    if (minY != null) axisMinimum = minY
                    if (maxY != null) axisMaximum = maxY
                    if (isAutoScaling) {
                        resetAxisMaximum() // 動態開放 Y 軸自動彈性縮放！
                    }
                }

                data = LineData()
            }
        }

        configureChart(chartTemp, minY = 20f, maxY = 60f)
        configureChart(chartCpu, minY = 0f, maxY = 100f)
        configureChart(chartCurr, minY = 0f, maxY = 3500f)
        configureChart(chartSpeed, minY = 0f, isAutoScaling = true)
        configureChart(chartMerged, minY = 0f, isAutoScaling = true)
    }

    private fun createDataSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            this.color = color
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER // 圓滑曲線
            setDrawFilled(true) // 漸層半透明填充
            fillColor = color
            fillAlpha = 45
            axisDependency = YAxis.AxisDependency.LEFT
        }
    }

    private fun addEntryToChart(temp: Float, cpu: Float, curr: Float, speedMBps: Float) {
        val tData = chartTemp.data ?: return
        val cData = chartCpu.data ?: return
        val iData = chartCurr.data ?: return
        val sData = chartSpeed.data ?: return
        val mData = chartMerged.data ?: return

        if (tData.dataSetCount < 1) tData.addDataSet(createDataSet("溫度 (°C)", Color.parseColor("#FF5252")))
        if (cData.dataSetCount < 1) cData.addDataSet(createDataSet("CPU (%)", Color.parseColor("#FFD740")))
        if (iData.dataSetCount < 1) iData.addDataSet(createDataSet("電流 (mA)", Color.parseColor("#00E676")))
        if (sData.dataSetCount < 1) sData.addDataSet(createDataSet("速度 (MB/s)", Color.parseColor("#00FFCC")))

        if (mData.dataSetCount < 4) {
            mData.addDataSet(createDataSet("溫度 (°C)", Color.parseColor("#FF5252")))
            mData.addDataSet(createDataSet("CPU (%)", Color.parseColor("#FFD740")))
            mData.addDataSet(createDataSet("電流 (mA)", Color.parseColor("#00E676")))
            mData.addDataSet(createDataSet("速度 (MB/s)", Color.parseColor("#00FFCC")))
        }

        tData.addEntry(Entry(timeIndex, temp), 0)
        cData.addEntry(Entry(timeIndex, cpu), 0)
        iData.addEntry(Entry(timeIndex, curr), 0)
        sData.addEntry(Entry(timeIndex, speedMBps), 0)

        // 速度圖表動態彈性 Y 軸：超速 > 20MB/s 時無上限伸縮，否則維護至少 20MB/s 基準
        if (speedMBps > 20f) {
            chartSpeed.axisLeft.resetAxisMaximum()
        } else {
            if (chartSpeed.axisLeft.axisMaximum < 20f) {
                chartSpeed.axisLeft.axisMaximum = 20f
            }
        }

        mData.addEntry(Entry(timeIndex, temp), 0)
        mData.addEntry(Entry(timeIndex, cpu), 1)
        mData.addEntry(Entry(timeIndex, curr), 2)
        mData.addEntry(Entry(timeIndex, speedMBps), 3)

        listOf(chartTemp, chartCpu, chartCurr, chartSpeed, chartMerged).forEach { chart ->
            chart.data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.setVisibleXRangeMaximum(30f)
            chart.moveViewToX(timeIndex)
            chart.invalidate()
        }
        timeIndex++
    }

    private fun toggleChartDisplayMode() {
        isMergedView = !isMergedView
        if (isMergedView) {
            gridChartLayout.visibility = View.GONE
            mergedChartLayout.visibility = View.VISIBLE
            btnSwitchChartMode.text = "[ 切換為 2x2 矩陣 ]"
            tvChartTitle.text = "[ TELEMETRY MULTI-TRACK ]"
        } else {
            gridChartLayout.visibility = View.VISIBLE
            mergedChartLayout.visibility = View.GONE
            btnSwitchChartMode.text = "[ 切換為合併圖表 ]"
            tvChartTitle.text = "[ 2x2 TELEMETRY MATRIX ]"
        }
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
    private fun saveTargetUri(uri: Uri) { cachedVolumeName = null; usbTreeUri = uri; contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); prefs.edit().putString("target_uri", uri.toString()).apply() }
    private fun resetBackupTarget() { cachedVolumeName = null; usbTreeUri = null; prefs.edit().remove("target_uri").apply(); updateStorageInfo() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleUsbIntent(intent)
    }
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            updateStorageInfo()
            activityScope.launch { reportSender.publishStatus("usb_attached") }
            checkAndAutoStartBackup()
        }
    }
    private fun hasStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun requestStoragePermission() { val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.ACCESS_MEDIA_LOCATION) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE); androidx.core.app.ActivityCompat.requestPermissions(this, p, 1002) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) manualStartBackup() }
    private fun formatMillis(ms: Long): String { val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000; return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s) }
    private fun togglePause() { isPaused = !isPaused; updateButtonStates() }
    private fun manualStartBackup() { if (isMonitoring) return; if (!hasStoragePermission()) { requestStoragePermission(); return }; if (usbTreeUri == null) { openDocumentTreeLauncher.launch(null); return }; runBackupSequence() }
    private fun updateButtonStates() { btnPause.text = if (!isMonitoring) (if (usbTreeUri == null) "SETUP" else "START") else (if (isPaused) "RESUME" else "PAUSE"); btnPause.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!isMonitoring) Color.parseColor("#00E676") else (if (isPaused) Color.parseColor("#00FFCC") else Color.parseColor("#FFD740"))); btnStop.isEnabled = isMonitoring }
    private fun toggleChartVisibility() { isChartVisible = !isChartVisible; chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE; toggleHintText.text = if (isChartVisible) "[ 點擊切換為純文字 ]" else "[ 點擊彈出圖表監控 ]" }
    private fun queryMedia(uri: Uri, list: MutableList<MediaItem>, isVideo: Boolean) {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%DCIM/Camera%")
        contentResolver.query(uri, arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN), selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                list.add(MediaItem(ContentUris.withAppendedId(uri, c.getLong(idCol)), c.getString(nameCol),
                    c.getString(mimeCol), isVideo, c.getLong(sizeCol), c.getLong(takenCol)))
            }
        }
    }
    private data class MediaItem(val uri: Uri, val name: String, val mime: String, val isVideo: Boolean, val size: Long, val taken: Long)

    override fun onDestroy() { super.onDestroy(); activityScope.cancel(); handler.removeCallbacks(updateRunnable); try { unregisterReceiver(hardwareReceiver); unregisterReceiver(mediaReceiver) } catch (e: Exception) {} }
}
