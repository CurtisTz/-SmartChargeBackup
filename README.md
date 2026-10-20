# SmartCharge

整合型專案:手機備份 APP、NodeMCU 電流量測韌體、樹莓派伺服器,透過 MQTT 串接。

## 結構
| 資料夾 | 內容 | 開啟方式 |
|--------|------|----------|
| `android-app/` | Android 備份 APP(Kotlin) | Android Studio 開啟 **android-app/** |
| `nodemcu/` | ESP8266 + INA219 韌體 | Arduino IDE / PlatformIO 開啟 `nodemcu/` |
| `pi-server/` | Django + MQTT 設定 | 見該資料夾 README |
| `docs/` | 架構圖、通訊協定 | |

## 快速開始
### Android
1. 複製 `android-app/local.properties.example` 為 `local.properties` 並填入設定
2. `cd android-app && ./gradlew assembleDebug`

### NodeMCU
複製 `nodemcu/secrets.h.example` 為 `secrets.h` 並填入 WiFi/MQTT 設定。

## 文件
- [架構](docs/architecture.md)
- [MQTT 協定](docs/mqtt-protocol.md)
