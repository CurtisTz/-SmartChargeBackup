# 📊 儀表板圖表優化與 2x2 矩陣佈局重構計畫

## 🎯 變更目標 (Goal)
將原先擠在一起的硬體與速度圖表重構為**賽博朋克風格 2x2 矩陣儀表板**，提供四項獨立遙測指標（溫度、CPU 負載、充電電流、備份速度），並支援「2x2 獨立矩陣」與「合併單一圖表」兩種檢視模式的切換。
同時**顯示清晰座標軸刻度**，並針對備份速度支援**動態自動彈性調整上限 (Auto Dynamic Scaling)**，輕鬆支援爆發超過 40MB/s~100MB/s 以上的高速寫入。

---

## 🎨 儀表板重點設計 (Dashboard Design Specification)

| 指標名稱 | 佈局位置 | 顏色主題 | Y 軸刻度範圍與自動彈性 | 視覺風格設定 |
| :--- | :--- | :--- | :--- | :--- |
| **溫度儀表 (°C)** | 左上 (Top-Left) | 霓虹紅 (`#FF5252`) | 20°C ~ 60°C (顯示刻度) | Cubic Bezier 圓滑曲線 + 半透明紅半滿填充 |
| **CPU 負載 (%)** | 右上 (Top-Right) | 警示黃 (`#FFD740`) | 0% ~ 100% (顯示刻度) | Cubic Bezier 圓滑曲線 + 半透明黃半滿填充 |
| **充電電流 (mA)** | 左下 (Bottom-Left) | 螢光綠 (`#00E676`) | 0mA ~ 3500mA (顯示刻度) | Cubic Bezier 圓滑曲線 + 半透明綠半滿填充 |
| **備份速度 (MB/s)**| 右下 (Bottom-Right) | 霓虹青 (`#00FFCC`) | **動態彈性縮放 (0MB/s ~ 自動無上限)** | Cubic Bezier 圓滑曲線 + 半透明青半滿填充 |

### 視覺與邏輯升級：
1. **獨立與合併雙模式**：
   - 頂部工具列加入模式切換控制 `[ 2x2 矩陣 ↔ 合併圖表 ]`，讓使用者可隨時切換獨立四分格顯示或單一多軌合併圖表。
2. **座標軸刻度與彈性縮放 (Axis Ticks & Auto-Scaling)**：
   - 啟用 Y 軸與 X 軸數字刻度標籤 (`setDrawLabels(true)`) 與虛線網格 (`setDrawGridLines(true)`)。
   - 備份速度圖表設為 **Dynamic Auto-Scaling**：預設基準值 20MB/s，當速度衝過 40MB/s、80MB/s 或更高時，Y 軸最大值會即時自動擴展彈性調整，並重新分配刻度標籤。
3. **視覺質感優化**：
   - 使用 `LineDataSet.Mode.CUBIC_BEZIER` 實現平滑圓滑曲線。
   - `setDrawFilled(true)` 搭配 `fillAlpha = 45` 實現科技漸層質感。
   - 座標軸文字調小 (8sp) 且使用暗色調 (`#8A99AD`)，確保深色背景下不刺眼且不搶焦點。
4. **資料同步更新**：
   - 在每秒更新 (`updateRunnable`) 及電池狀態廣播 (`ACTION_BATTERY_CHANGED`) 時，同步推送數據至四個獨立圖表及合併圖表。

---

## 📝 擬修改檔案 (Proposed Changes)

#### [MODIFY] [activity_main.xml](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/res/layout/activity_main.xml)
- 重構 `chartContainer` 內部的佈局結構：
  - 新增頂部模式切換按鈕/標籤 (`btnSwitchChartMode`)。
  - 建立 `gridChartLayout` (2x2 LinearLayout 矩陣)：
    - 第一排：`chartTemp` (溫度) + `chartCpu` (CPU 負載)
    - 第二排：`chartCurr` (充電電流) + `chartSpeed` (備份速度)
  - 建立 `mergedChartLayout` (合併單一 LineChart `chartMerged`)。

#### [MODIFY] [MainActivity.kt](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/java/com/example/smartchargebackup/MainActivity.kt)
- 綁定 4 個獨立 LineChart (`chartTemp`, `chartCpu`, `chartCurr`, `chartSpeed`) 與 1 個合併 LineChart (`chartMerged`)。
- 實作 `setupChartStyle()` 通用圖表美化函式（平滑曲線、半透明填充、顯示刻度線與標籤）。
- 配置 `chartSpeed` 動態 Y 軸調整邏輯：`axisMinimum = 0f`，`resetCustomAxisMaximum()` 或根據即時速度動態計算最高刻度。
- 實作 `addEntryToAllCharts(temp, cpu, curr, speedMBps)`：統一維護數據推送與 30 秒滑動視窗 (`moveViewToX`)。
- 實作模式切換邏輯 (`toggleChartDisplayMode`)。

---

## 🧪 驗證計畫 (Verification Plan)

### 自動化編譯測試
- 執行 Gradle Assemble (`app:assembleDebug`) 確保程式碼與引用的 MPAndroidChart API 完全無誤。

### 手動驗證項
1. 點擊「[ 點擊彈出圖表監控 ]」顯示圖表容器，確認呈現 2x2 矩陣 (溫度、CPU 負載、充電電流、備份速度)。
2. 確認 Y 軸與 X 軸均清晰顯示數值刻度與網格線。
3. 模擬/測試備份速度時，確認速度超過 20MB/s 或 40MB/s 時，Y 軸刻度能自動調大平滑縮放。
4. 點擊切換模式，確認可順暢切換為「合併圖表」與「2x2 矩陣」。
