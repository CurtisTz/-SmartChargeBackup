package com.example.smartchargebackup

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MqttReportSender(ctx: Context) {
    companion object {
        private const val BROKER = "tcp://10.53.121.80:1883"   // 改成 Pi 4 的 IP
        private const val USER = "scb"
        private const val PASS = "1234"
        const val TOPIC_REPORT = "smartcharge/backup/report"
        const val TOPIC_PHOTOS = "smartcharge/backup/photos"
        private val mutex = Mutex()
        private val seq = AtomicLong()
    }

    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("BackupPrefs", Context.MODE_PRIVATE)
    private val outbox = File(app.filesDir, "mqtt_outbox").apply { mkdirs() }

    fun deviceId(): String =
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    private fun connect(): MqttClient {
        val c = MqttClient(BROKER, "scb-" + UUID.randomUUID(), MemoryPersistence())
        c.connect(MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 5
            userName = USER
            password = PASS.toCharArray()
        })
        return c
    }

    private fun close(c: MqttClient?) { try { c?.disconnect(); c?.close() } catch (_: Exception) {} }

    /** 一則訊息 = outbox 裡一個檔案，檔名以時間排序，確保 report 先於 photos */
    suspend fun enqueue(topic: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val name = "%013d-%06d.json".format(System.currentTimeMillis(), seq.incrementAndGet())
            File(outbox, name).writeText(JSONObject().put("topic", topic).put("payload", payload).toString())
        }
    }

    /** 補送所有尚未送達的訊息（App 啟動時也呼叫） */
    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = outbox.listFiles()?.sortedBy { it.name }.orEmpty()
            if (files.isEmpty()) return@withLock
            val client = try { connect() } catch (e: Exception) {
                Log.w("MQTT", "連線失敗，保留佇列: ${e.message}"); return@withLock
            }
            try {
                for (f in files) {
                    try {
                        val o = JSONObject(f.readText())
                        val m = MqttMessage(o.getJSONObject("payload").toString().toByteArray(Charsets.UTF_8))
                        m.qos = 1
                        client.publish(o.getString("topic"), m)   // 阻塞到收到 PUBACK
                        f.delete()
                    } catch (e: MqttException) { throw e }        // 網路問題：中止，下次再補
                    catch (e: Exception) { f.delete() }           // 壞檔案：丟棄
                }
            } catch (e: MqttException) {
                Log.w("MQTT", "送出中斷: ${e.message}")
            } finally { close(client) }
        }
    }

    /** OTG / 備份狀態：不進佇列，retained，Pi 永遠看到最新狀態 */
    suspend fun publishStatus(state: String, extra: JSONObject? = null) = withContext(Dispatchers.IO) {
        var client: MqttClient? = null
        try {
            val payload = JSONObject().apply {
                put("device_id", deviceId()); put("device_name", Build.MODEL)
                put("state", state); put("ts_ms", System.currentTimeMillis())
                extra?.keys()?.forEach { put(it, extra.get(it)) }
            }
            client = connect()
            val m = MqttMessage(payload.toString().toByteArray(Charsets.UTF_8))
            m.qos = 1; m.isRetained = true
            client.publish("smartcharge/device/${deviceId()}/status", m)
        } catch (e: Exception) {
            Log.w("MQTT", "status 送出失敗: ${e.message}")
        } finally { close(client) }
    }
}