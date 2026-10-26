package com.you.rcagent.core

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import com.you.rcagent.RcApplication
import java.io.File
import java.io.FileOutputStream

/** Quyết định phát hiện "nối lại dồn dập" tách khỏi I/O để test trên JVM. */
object ConnPolicy {
    const val FLAP_DROPS = 4
    const val FLAP_WINDOW_MS = 10 * 60_000L
    const val FLAP_COOLDOWN_MS = 30 * 60_000L

    fun dropsIn(times: List<Long>, now: Long, windowMs: Long): Int = times.count { now - it in 0..windowMs }

    fun shouldReportFlap(times: List<Long>, now: Long, lastReportAt: Long): Boolean =
        dropsIn(times, now, FLAP_WINDOW_MS) >= FLAP_DROPS && (lastReportAt == 0L || now - lastReportAt >= FLAP_COOLDOWN_MS)

    /** Tên mã lý do MQTT 5 (DISCONNECT reason code) để nhật ký đọc được: "rc=152" → "administrative_action(152)". */
    fun reasonName(code: Int?): String? = when (code) {
        null -> null
        0 -> "normal"
        4 -> "disconnect_with_will"
        128 -> "unspecified_error"
        129 -> "malformed_packet"
        130 -> "protocol_error"
        131 -> "implementation_specific_error"
        135 -> "not_authorized"
        137 -> "server_busy"
        139 -> "server_shutting_down"
        141 -> "keepalive_timeout"
        142 -> "session_taken_over"
        147 -> "receive_maximum_exceeded"
        149 -> "packet_too_large"
        150 -> "message_rate_too_high"
        151 -> "quota_exceeded"
        152 -> "administrative_action"
        160 -> "maximum_connect_time"
        else -> null
    }?.let { "$it($code)" }

    /** Gom các lý do giống nhau: "Connection lost x3, EOF x1". */
    fun summarize(reasons: List<String>): String =
        reasons.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} x${it.value}" }.take(400)
}

/**
 * Nhật ký kết nối MQTT bền vững (sống qua process chết): mỗi lần nối, ngắt, nối lại và *ai* ép nối lại.
 * Đây là phần "máy thấy gì" — ghép với lý do do broker báo ở backend để biết vì sao rớt.
 */
object ConnLog {
    private const val FILE = "rc_conn.log"
    private const val MAX_BYTES = 60_000L

    private val dropTimes = ArrayList<Long>()
    private val recentReasons = ArrayList<String>()
    private var lastFlapReportAt = 0L

    @Synchronized
    fun add(ev: String, reason: String? = null, extra: Map<String, Any?> = emptyMap()) {
        val o = JSONObject().put("ts", System.currentTimeMillis()).put("ev", ev)
        if (!reason.isNullOrBlank()) o.put("reason", reason.take(200))
        for ((k, v) in extra) if (v != null) o.put(k, v)
        append(o.toString())
    }

    /** Một lần rớt thật (không phải do mình chủ động thay kết nối). */
    @Synchronized
    fun drop(reason: String, upMs: Long, extra: Map<String, Any?> = emptyMap()) {
        val now = System.currentTimeMillis()
        dropTimes += now
        recentReasons += reason.take(60)
        while (dropTimes.size > 30) dropTimes.removeAt(0)
        while (recentReasons.size > 30) recentReasons.removeAt(0)
        add("disconnected", reason, extra + ("upMs" to upMs))
    }

    /** Có đang nối lại dồn dập không? Trả tóm tắt để gửi lên và đánh dấu đã báo (cooldown 30 phút). */
    @Synchronized
    fun takeFlapSummary(): String? {
        val now = System.currentTimeMillis()
        if (!ConnPolicy.shouldReportFlap(dropTimes, now, lastFlapReportAt)) return null
        lastFlapReportAt = now
        val n = ConnPolicy.dropsIn(dropTimes, now, ConnPolicy.FLAP_WINDOW_MS)
        val reasons = recentReasons.takeLast(n)
        return "$n drops/${ConnPolicy.FLAP_WINDOW_MS / 60_000}min: ${ConnPolicy.summarize(reasons)}"
    }

    @Synchronized
    fun tail(n: Int): JSONArray {
        val arr = JSONArray()
        val dir = RcApplication.app.filesDir
        val lines = ArrayList<String>()
        for (name in listOf("$FILE.1", FILE)) {
            runCatching { File(dir, name).readLines() }.getOrNull()?.let { lines += it }
        }
        lines.takeLast(n.coerceIn(1, 100)).forEach { l -> runCatching { arr.put(JSONObject(l)) } }
        return arr
    }

    fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    private fun append(line: String) {
        val dir = RcApplication.app.filesDir
        val f = File(dir, FILE)
        runCatching {
            if (f.length() > MAX_BYTES) {
                File(dir, "$FILE.1").delete()
                f.renameTo(File(dir, "$FILE.1"))
            }
            FileOutputStream(File(dir, FILE), true).use { it.write((line + "\n").toByteArray()) }
        }
    }
}
