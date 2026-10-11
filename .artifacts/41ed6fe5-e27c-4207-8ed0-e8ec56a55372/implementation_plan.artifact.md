# 圖表範圍調整與備份報告優化計畫

本計畫旨在精確化圖表的數值呈現範圍，並優化備份結束後的數據統計報告，以提供更實用的效能資訊。

## 使用者需求摘要
1.  **硬體圖表 (TEMP&CURR)**：將左側 Y 軸（溫度）刻度範圍調整為 **25°C ~ 50°C**。
2.  **速度圖表 (SPEED)**：將 Y 軸刻度範圍調整為 **0 ~ 20 MB/s**。
3.  **備份報告**：
    - 移除「跳過重複」項（因已實作全量預篩選，該值恆為 0）。
    - 新增 **「平均備份速度 (MB/min)」**，提供更高層級的效能統計。

## 提出的變更內容

### 1. 圖表座標優化

#### [MODIFY] [MainActivity.kt](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/java/com/example/smartchargebackup/MainActivity.kt)
- **`setupChart()`**：
    - 修改 `lineChart.axisLeft` 的 `axisMinimum = 25f` 與 `axisMaximum = 50f`。
    - 修改 `speedChart.axisLeft` 的 `axisMaximum = 20f`。

### 2. 統計數據與報告優化

#### [MODIFY] [MainActivity.kt](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/java/com/example/smartchargebackup/MainActivity.kt)
- **新增變數**：`private var statsBytes: Long = 0` 用於追蹤總傳輸位元組。
- **`runBackupSequence()`**：
    - 初始化 `statsBytes = 0`。
    - 每次 `copyFile` 成功後，累加 `item.size` 到 `statsBytes`。
- **`showBackupReport()`**：
    - 計算平均速度：`(總 MB) / (總分鐘)`。
    - 更新對話框訊息內容，移除「跳過重複」，加入「平均備份速度」。

## 驗證計畫

### 手動驗證
1.  **圖表刻度檢查**：啟動 App 後展開圖表，確認溫度軸的起點為 25，終點為 50；速度軸的上限為 20。
2.  **報告內容驗證**：備份完成或中斷後，檢查彈出的報告是否已無「跳過重複」字樣，並確認「平均備份速度 (MB/min)」顯示正確。
    - *測試案例*：備份約 60MB 的檔案耗時 1 分鐘，報告應顯示約 60.0 MB/min。
