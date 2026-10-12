package com.example.smartchargebackup

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class PhotoMeta(
    val uri: Uri, val name: String, val isVideo: Boolean, val size: Long,
    val takenMs: Long, val lat: Double?, val lon: Double?,
    val speedMBps: Double = 0.0, val tempC: Double = 0.0
) {
    fun toJson(thumb: String?) = JSONObject().apply {
        put("name", name); put("video", isVideo); put("size", size)
        put("taken_ms", takenMs)
        if (lat != null && lon != null) { put("lat", lat); put("lon", lon) }
        put("speed_mbps", Math.round(speedMBps * 100) / 100.0)
        put("temp_c", Math.round(tempC * 10) / 10.0)
        if (thumb != null) put("thumb", thumb)
    }
}

object MediaIndexer {

    /** 先用 setRequireOriginal 取得未遮蔽 GPS 的 Uri；沒權限就退回一般 Uri（GPS 為空） */
    private fun <T> withOriginal(uri: Uri, block: (Uri) -> T): T =
        try { block(MediaStore.setRequireOriginal(uri)) } catch (e: Exception) { block(uri) }

    fun read(ctx: Context, uri: Uri, name: String, isVideo: Boolean, size: Long, dateTaken: Long): PhotoMeta {
        var taken = dateTaken
        var lat: Double? = null
        var lon: Double? = null
        try {
            withOriginal(uri) { u ->
                if (isVideo) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, u)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                            ?.let { parseIso6709(it) }?.let { lat = it.first; lon = it.second }
                    } finally { r.release() }
                } else {
                    ctx.contentResolver.openInputStream(u)?.use { s ->
                        val ex = ExifInterface(s)
                        ex.latLong?.let { lat = it[0]; lon = it[1] }
                        parseExifDate(ex)?.let { taken = it }
                    }
                }
            }
        } catch (_: Exception) { /* 沒有 EXIF 或讀取失敗：保留空值 */ }
        if (lat == 0.0 && lon == 0.0) { lat = null; lon = null }
        return PhotoMeta(uri, name, isVideo, size, taken, lat, lon)
    }

    private fun parseExifDate(ex: ExifInterface): Long? {
        val s = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: ex.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        val off = ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        return try {
            if (off != null) SimpleDateFormat("yyyy:MM:dd HH:mm:ss XXX", Locale.US).parse("$s $off")?.time
            else SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time
        } catch (e: Exception) { null }
    }

    /** 影片位置格式如 "+25.0330+121.5654+012.000/" */
    private fun parseIso6709(s: String): Pair<Double, Double>? {
        val m = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(s) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    private fun thumbBase64(ctx: Context, uri: Uri): String? = try {
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos)
        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { null }

    /** 在 IO 執行緒呼叫：30 筆一包，只有帶 GPS 的才產縮圖 */
    fun buildBatches(ctx: Context, reportId: String, deviceId: String, metas: List<PhotoMeta>): List<JSONObject> =
        metas.chunked(30).map { chunk ->
            JSONObject().apply {
                put("report_id", reportId); put("device_id", deviceId)
                put("items", JSONArray().apply {
                    chunk.forEach { put(it.toJson(if (it.lat != null) thumbBase64(ctx, it.uri) else null)) }
                })
            }
        }
}