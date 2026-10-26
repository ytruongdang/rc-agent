package com.you.rcagent.core

/** null / -1 = máy không cho app đọc (SELinux chặn sysfs là chuyện thường), không phải giá trị 0. */
data class HwSample(
    val ts: Long,
    val phase: String,
    val thermal: Int = -1,
    val battTempC: Float? = null,
    val zoneMaxC: Float? = null,
    val memAvailMb: Int = -1,
    val memTotalMb: Int = -1,
    val lowMem: Boolean = false,
    val diskFreeMb: Int = -1,
    val cpuPct: Int? = null,
    val cpuKHz: Int? = null,
    val gpuPct: Int? = null,
    val gpuMHz: Int? = null,
    val display: Int = 0,
    val interactive: Boolean = true,
    val touchDevs: Int = -1,
    val mainLagMs: Long = 0,
    val appPssMb: Int = -1,
    val threads: Int = -1,
    val fds: Int = -1,
    val fgPkg: String? = null,
    val uiIdleMs: Long = -1,
    val lockTask: Int = -1,
    val anrDialog: Boolean = false,
)

/**
 * Quyết định "bất thường" tách khỏi việc đọc phần cứng để test trên JVM.
 * `recent` = các mẫu trước `cur`, mới nhất ở cuối.
 */
object HwAnomaly {
    const val STATE_OFF = 1
    const val STATE_DOZE = 3
    const val STATE_DOZE_SUSPEND = 4

    const val THERMAL_SEVERE = 3
    const val BATT_HOT_C = 47f
    const val ZONE_HOT_C = 85f
    const val LOW_MEM_PCT = 7
    const val LOW_DISK_MB = 300
    const val MAIN_LAG_MS = 1_500L
    const val PEGGED_PCT = 95
    const val BUSY_SAMPLES = 3
    const val MAX_FDS = 800
    const val MAX_THREADS = 400

    fun classify(cur: HwSample, recent: List<HwSample>): List<String> {
        val out = ArrayList<String>()
        if (cur.thermal >= THERMAL_SEVERE ||
            (cur.battTempC ?: 0f) >= BATT_HOT_C ||
            (cur.zoneMaxC ?: 0f) >= ZONE_HOT_C
        ) out += "THERMAL"
        if (cur.lowMem || (cur.memTotalMb > 0 && cur.memAvailMb * 100 / cur.memTotalMb < LOW_MEM_PCT)) {
            out += "LOW_MEM"
        }
        if (cur.diskFreeMb in 0 until LOW_DISK_MB) out += "DISK_LOW"
        // Máy tưởng màn hình đang sáng nhưng panel không ở STATE_ON: đúng triệu chứng "đen mà remote vẫn thấy".
        // Phải tắt ở 2 mẫu liên tiếp: chuyển trạng thái/AOD thoáng qua không phải panel chết.
        if (panelDark(cur) && recent.lastOrNull()?.let { panelDark(it) } == true) out += "PANEL_OFF"
        // Input device cảm ứng biến mất: remote vẫn bấm được vì Knox/gesture không đi qua driver cảm ứng.
        if (cur.touchDevs == 0 && (recent.lastOrNull()?.touchDevs ?: 0) > 0) out += "TOUCH_GONE"
        if (cur.mainLagMs >= MAIN_LAG_MS) out += "MAIN_LAG"
        if (sustained(cur, recent) { it.gpuPct }) out += "GPU_PEGGED"
        if (sustained(cur, recent) { it.cpuPct }) out += "CPU_PEGGED"
        if (cur.anrDialog) out += "ANR_DIALOG"
        if (cur.fds >= MAX_FDS) out += "FD_LEAK"
        if (cur.threads >= MAX_THREADS) out += "THREAD_LEAK"
        return out
    }

    private fun panelDark(s: HwSample): Boolean =
        s.interactive && (s.display == STATE_OFF || s.display == STATE_DOZE || s.display == STATE_DOZE_SUSPEND)

    private fun sustained(cur: HwSample, recent: List<HwSample>, pick: (HwSample) -> Int?): Boolean {
        if (recent.size < BUSY_SAMPLES - 1) return false
        return (recent.takeLast(BUSY_SAMPLES - 1) + cur).all { (pick(it) ?: 0) >= PEGGED_PCT }
    }

    /** Cùng một code chỉ báo lại sau [cooldownMs]; log đĩa vẫn ghi mọi lần. */
    fun shouldReport(code: String, now: Long, last: Map<String, Long>, cooldownMs: Long): Boolean =
        now - (last[code] ?: Long.MIN_VALUE / 2) >= cooldownMs
}
