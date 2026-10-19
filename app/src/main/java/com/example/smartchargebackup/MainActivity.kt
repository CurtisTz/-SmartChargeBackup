package com.example.smartchargebackup

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    private lateinit var lineChart: LineChart
    private var isMonitoring = false
    private var timeIndex = 0f
    private lateinit var chartContainer: LinearLayout
    private lateinit var toggleHintText: TextView
    private var isChartVisible = false // 預設隱藏圖表
    
    // UI 元件
    private lateinit var valTime: TextView
    private lateinit var valTemp: TextView
    private lateinit var valCpu: TextView
    private lateinit var valPower: TextView
    private lateinit var valVolt: TextView
    private lateinit var valCurr: TextView
    private lateinit var valStorage: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercentText: TextView
    private lateinit var voltCurrText: TextView
    
    private var mockProgress = 0

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateSystemStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 綁定 UI
        valTime = findViewById(R.id.valTime)
        valTemp = findViewById(R.id.valTemp)
        valCpu = findViewById(R.id.valCpu)
        valPower = findViewById(R.id.valPower)
        valVolt = findViewById(R.id.valVolt)
        valCurr = findViewById(R.id.valCurr)
        valStorage = findViewById(R.id.valStorage)
        progressBar = findViewById(R.id.taskProgressBar)
        progressPercentText = findViewById(R.id.valProgressPercent)
        voltCurrText = findViewById(R.id.valVoltCurr)
        
        lineChart = findViewById(R.id.realtimeChart)
        chartContainer = findViewById(R.id.chartContainer)
        toggleHintText = findViewById(R.id.toggleHintText)

        // 初始狀態：隱藏圖表
        chartContainer.visibility = View.GONE
        toggleHintText.text = "[ 點擊彈出圖表監控 ]"

        setupChart()
        
        val infoPanel = findViewById<View>(R.id.infoPanel)
        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)

        val toggleLayoutClickListener = View.OnClickListener {
            toggleChartVisibility()
        }
        
        infoPanel.setOnClickListener(toggleLayoutClickListener)
        toggleHintText.setOnClickListener(toggleLayoutClickListener)
        rootLayout.setOnClickListener(toggleLayoutClickListener)

        handleUsbIntent(intent)
        handler.post(updateRunnable)
    }

    private fun updateSystemStatus() {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            baseContext.registerReceiver(null, ifilter)
        }

        batteryStatus?.let {
            val rawTemp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val temperature = rawTemp / 10.0
            val rawVoltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val voltage = rawVoltage / 1000.0

            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentMicroAmp = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentStep = currentMicroAmp / 1000.0
            val currentmA = if (currentStep < -100000 || currentStep > 100000) 0.0 else currentStep
            val powerW = kotlin.math.abs((voltage * currentmA) / 1000.0)

            val cpuStatus = getCpuUsage()

            // 更新 UI 數據
            valTime.text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            valTemp.text = "$temperature °C"
            valCpu.text = "LOAD: $cpuStatus"
            valPower.text = "${String.format("%.1f", powerW)}W"
            valVolt.text = "${voltage}V"
            valCurr.text = "${currentmA}mA"
            
            // 動態進度條邏輯
            mockProgress = (mockProgress + 1) % 101
            progressBar.progress = mockProgress
            progressPercentText.text = "$mockProgress%"
            voltCurrText.text = "SYS: $voltage V / $currentmA mA"

            addEntryToChart(temperature.toFloat(), currentmA.toFloat())
        }
    }

    private fun toggleChartVisibility() {
        isChartVisible = !isChartVisible
        val infoPanel = findViewById<View>(R.id.infoPanel)
        if (isChartVisible) {
            chartContainer.visibility = View.VISIBLE
            toggleHintText.text = "[ 點擊切換為純文字模式 ]"
            (chartContainer.layoutParams as LinearLayout.LayoutParams).weight = 2f
            (infoPanel.layoutParams as LinearLayout.LayoutParams).weight = 2.2f
        } else {
            chartContainer.visibility = View.GONE
            toggleHintText.text = "[ 點擊彈出圖表監控 ]"
            (infoPanel.layoutParams as LinearLayout.LayoutParams).weight = 10f
        }
    }

    private fun getCpuUsage(): String {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            val toks = load.split(" +".toRegex())
            val cores = Runtime.getRuntime().availableProcessors()
            reader.close()
            "$cores CORE STABLE"
        } catch (e: Exception) {
            "ACTIVE"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        intent?.let {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == it.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                device?.let { usbDevice -> showBackupDialog(usbDevice) }
            }
        }
    }

    private fun showBackupDialog(device: UsbDevice) {
        val builder = AlertDialog.Builder(this, R.style.CyberpunkDialogTheme)
        builder.setTitle("⚡ 系統通知").setMessage("偵測到外部儲存裝置.\n是否要開始備份?").setCancelable(false)
            .setPositiveButton("是") { _, _ -> startBackupProcess(device) }
            .setNegativeButton("否") { _, _ -> valStorage.text = "TASK ABORTED" }
        val dialog = builder.create()
        dialog.show()
    }

    private fun setupChart() {
        lineChart.description.isEnabled = false
        lineChart.setBackgroundColor(Color.parseColor("#121829"))
        lineChart.xAxis.textColor = Color.parseColor("#8A99AD")
        lineChart.axisLeft.textColor = Color.parseColor("#FF5252")
        lineChart.axisRight.textColor = Color.parseColor("#00E676")
        
        // 調整圖例顏色為白色
        lineChart.legend.textColor = Color.WHITE
        
        val data = LineData()
        lineChart.data = data
    }


    private fun startBackupProcess(device: UsbDevice) {
        if (!isMonitoring) {
            isMonitoring = true
            valStorage.text = "BACKING UP..."
        }
    }

    private fun addEntryToChart(temp: Float, current: Float) {
        val data = lineChart.data ?: return
        var tempSet = data.getDataSetByIndex(0) ?: LineDataSet(null, "temp").apply {
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            color = Color.parseColor("#FF5252")
            setDrawValues(false)
            data.addDataSet(this)
        }
        var currentSet = data.getDataSetByIndex(1) ?: LineDataSet(null, "curr").apply {
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
            color = Color.parseColor("#00E676")
            setDrawValues(false)
            data.addDataSet(this)
        }
        data.addEntry(Entry(timeIndex, temp), 0)
        data.addEntry(Entry(timeIndex, current), 1)
        data.notifyDataChanged()
        lineChart.notifyDataSetChanged()
        lineChart.setVisibleXRangeMaximum(30f)
        lineChart.moveViewToX(timeIndex)
        timeIndex += 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }
}
