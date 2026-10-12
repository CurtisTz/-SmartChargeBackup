# 📊 儀表板圖表重構與動態刻度彈性縮放完成報告

已成功完成專案的圖表區域重構，將原有的圖表優化為 **2x2 賽博朋克風格獨立矩陣儀表板**，並新增**顯示清晰 Y 軸 / X 軸刻度**與**備份速度動態無上限彈性縮放 (Auto-scaling)** 功能！

---

## 🛠 修改重點 (Key Improvements)

### 1. 2x2 矩陣佈局與獨立儀表 (2x2 Dashboard Matrix)
- **左上 (紅 `#FF5252`)**：`chartTemp` (溫度儀表, 20°C ~ 60°C)
- **右上 (黃 `#FFD740`)**：`chartCpu` (CPU 負載儀表, 0% ~ 100%)
- **左下 (綠 `#00E676`)**：`chartCurr` (充電電流儀表, 0mA ~ 3500mA)
- **右下 (青 `#00FFCC`)**：`chartSpeed` (備份速度儀表, 動態彈性縮放)

### 2. 「2x2 獨立矩陣」與「多軌合併圖表」自由切換
- 在圖表頂部新增切換按鈕 `[ 切換為合併圖表 ]` / `[ 切換為 2x2 矩陣 ]`，點擊即可切換單一多軌圖表或四分格矩陣。

### 3. 清晰座標刻度與動態彈性 Y 軸 (Axis Ticks & Dynamic Auto-scaling)
- **刻度顯示**：全圖表開啓 Y 軸與 X 軸數值標籤與暗色虛線網格 (`gridColor = #1AFFFFFF`)。
- **備份速度彈性縮放**：當備份寫入速度突破 20MB/s、40MB/s 或高達 80MB/s+ 時，`chartSpeed` 會調用 `resetAxisMaximum()` 自動擴展 Y 軸上限並重新發布刻度，精準呈現爆發寫入速度！

### 4. 曲線平滑度與漸層美化 (Cubic Bezier & Alpha Gradient)
- 使用 `Cubic Bezier` 三次方平滑曲線，避免折線生硬抖動。
- 下方帶有半透明滿版漸層 (`fillAlpha = 45`)，呈現夜間高質感 HUD 遙測數據。

---

## 🧪 驗證結果 (Validation)

- **Gradle Build**：`app:assembleDebug` 建置成功。
- **介面響應**：2x2 獨立儀表板佈局已套用，頂部切換按鈕運作正常，所有 Y 軸刻度均可正確顯示。
