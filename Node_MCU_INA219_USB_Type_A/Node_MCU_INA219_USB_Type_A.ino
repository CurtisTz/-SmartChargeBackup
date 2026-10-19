#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_INA219.h>
#include "secrets.h"

// ===== 依環境修改 =====
const char* METER_ID  = "meter1";
// ======================

Adafruit_INA219 ina;
WiFiClient wifi;
PubSubClient mqtt(wifi);

char topicSample[64], topicStatus[64];
unsigned long lastSample = 0, lastPub = 0, lastTry = 0;
float sumV = 0, sumMa = 0, peakMa = 0;
int n = 0;

void connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("WiFi");
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println(WiFi.localIP());
}

bool connectMqtt() {
  String cid = String("scb-meter-") + ESP.getChipId();
  // 遺囑訊息：斷線時 broker 自動發布 offline（retained）
  if (!mqtt.connect(cid.c_str(), MQTT_USER, MQTT_PASS, topicStatus, 0, true, "offline")) {
    Serial.print("MQTT fail rc="); Serial.println(mqtt.state());
    return false;
  }
  mqtt.publish(topicStatus, "online", true);
  Serial.println("MQTT connected");
  return true;
}

void setup() {
  Serial.begin(115200);
  snprintf(topicSample, sizeof(topicSample), "smartcharge/power/%s/sample", METER_ID);
  snprintf(topicStatus, sizeof(topicStatus), "smartcharge/power/%s/status", METER_ID);

  Wire.begin(D2, D1);                 // SDA=D2, SCL=D1
  if (!ina.begin()) {
    Serial.println("找不到 INA219，請檢查 SDA/SCL/VCC/GND");
    while (true) delay(1000);
  }
  ina.setCalibration_32V_2A();

  connectWifi();
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) connectWifi();
  if (!mqtt.connected() && millis() - lastTry > 3000) { lastTry = millis(); connectMqtt(); }
  mqtt.loop();

  unsigned long now = millis();

  // 每 100 ms 取樣一次（10 Hz）
  if (now - lastSample >= 100) {
    lastSample = now;
    float v  = ina.getBusVoltage_V();      // Vin- 對 GND 的電壓
    float ma = ina.getCurrent_mA();        // 有正負號
    sumV += v; sumMa += ma; n++;
    if (fabs(ma) > peakMa) peakMa = fabs(ma);
  }

  // 每 1 秒送出一次「這一秒的平均值 + 尖峰」
  if (now - lastPub >= 1000) {
    lastPub = now;
    if (n > 0 && mqtt.connected()) {
      float v = sumV / n, ma = sumMa / n;
      char buf[128];
      snprintf(buf, sizeof(buf),
        "{\"id\":\"%s\",\"v\":%.3f,\"ma\":%.1f,\"mw\":%.1f,\"pk\":%.1f}",
        METER_ID, v, ma, v * ma, peakMa);
      mqtt.publish(topicSample, buf);      // QoS0，丟一筆不影響
    }
    sumV = sumMa = peakMa = 0; n = 0;
  }
}
