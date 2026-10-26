package com.you.rcagent.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.InputDevice
import com.you.rcagent.RcApplication
import com.you.rcagent.transport.MqttClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lấy mẫu phần cứng định kỳ, ghi đĩa (sống qua process chết) và báo lên khi bất thường.
 * Mục đích: màn hình vật lý đen/lag/không chạm được nhưng remote vẫn dùng tốt — cần
 * chuỗi thời gian *trước* lúc hỏng, không phải ảnh chụp sau khi hỏng.
 */
object HwMonitor {
    private const val FILE = "rc_hw.log"
    private const val MAX_BYTES = 200_000L
    private const val REPORT_COOLDOWN_MS = 10 * 60_000L
    private const val LAG_PROBE_MS = 5_000L
    const val MAX_DUMP = 100

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val recent = ArrayDeque<HwSample>()
    private val lastReport = HashMap<String, Long>()
    private val unsent = HashSet<String>()
    private var prevCpu: LongArray? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var latest: HwSample? = null

    fun start(ctx: Context) {
        if (thread != null) return
        val t = HandlerThread("rc-mon").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val app = ctx.applicationContext
        h.post(object : Runnable {
            override fun run() {
                runCatching { tick(app) }.onFailure { Log.w(Config.TAG, "hw tick: ${it.message}") }
                h.postDelayed(this, Config.HW_SAMPLE_MS)
            }
        })
    }

    /** Vài trường gọn để gắn vào `volatile` của heartbeat (cộng thêm, không đổi key cũ). */
    fun summary(): Map<String, Any> {
        val s = latest ?: return emptyMap()
        val m = HashMap<String, Any>()
        m["thermal"] = s.thermal
        s.battTempC?.let { m["battTempC"] = it }
        m["memAvailMb"] = s.memAvailMb
        m["diskFreeMb"] = s.diskFreeMb
        s.cpuPct?.let { m["cpuPct"] = it }
        s.gpuPct?.let { m["gpuPct"] = it }
        m["display"] = s.display
        m["touchDevs"] = s.touchDevs
        m["mainLagMs"] = s.mainLagMs
        s.fgPkg?.let { m["fgPkg"] = it }
        m["uiIdleMs"] = s.uiIdleMs
        return m
    }

    /** Trả `n` dòng cuối (cũ → mới) cho lệnh `hw.dump`. */
    fun tail(n: Int): List<String> {
        val dir = RcApplication.app.filesDir
        val lines = ArrayList<String>()
        for (name in listOf("$FILE.1", FILE)) {
            runCatching { File(dir, name).readLines() }.getOrNull()?.let { lines += it }
        }
        return lines.takeLast(n.coerceIn(1, 500))
    }

    /** Đọc + parse file chạy trên rc-mon: lệnh MQTT đến từ main, không được chặn main/keepalive. */
    fun dump(nonce: String?, n: Int) {
        val run = Runnable {
            val arr = JSONArray()
            tail(n.coerceIn(1, MAX_DUMP)).forEach { l -> runCatching { arr.put(JSONObject(l)) } }
            MqttClient.publishHw(nonce, arr, ConnLog.tail(50))
        }
        handler?.post(run) ?: run.run()
    }

    private fun tick(ctx: Context) {
        val s = collect(ctx)
        val codes = HwAnomaly.classify(s, recent.toList())
        latest = s
        recent.addLast(s)
        while (recent.size > HwAnomaly.BUSY_SAMPLES) recent.removeFirst()
        append(toJson(s, codes).toString(), sync = codes.isNotEmpty())
        val now = System.currentTimeMillis()
        // Mã phát hiện theo cạnh (TOUCH_GONE, PANEL_OFF) sẽ không lặp lại ở mẫu sau, nên mã chưa gửi được
        // (offline) phải nhớ riêng và gửi lại khi có mạng.
        for (c in (codes + unsent).distinct()) {
            if (!HwAnomaly.shouldReport(c, now, lastReport, REPORT_COOLDOWN_MS)) {
                unsent.remove(c)
                continue
            }
            if (c in codes) Log.w(Config.TAG, "hw anomaly $c ${toJson(s, codes)}")
            if (MqttClient.publishEvent("HW_$c", brief(s))) {
                lastReport[c] = now
                unsent.remove(c)
            } else {
                unsent.add(c)
            }
        }
    }

    private fun collect(ctx: Context): HwSample {
        val now = System.currentTimeMillis()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battTenths = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        val (gpuPct, gpuMhz) = readGpu()
        return HwSample(
            ts = now,
            phase = AgentState.phase.name,
            thermal = thermal,
            battTempC = if (battTenths == Int.MIN_VALUE) null else battTenths / 10f,
            zoneMaxC = readZoneMaxC(),
            memAvailMb = (mi.availMem shr 20).toInt(),
            memTotalMb = (mi.totalMem shr 20).toInt(),
            lowMem = mi.lowMemory,
            diskFreeMb = runCatching { (StatFs(ctx.filesDir.path).availableBytes shr 20).toInt() }.getOrDefault(-1),
            cpuPct = readCpuPct(),
            cpuKHz = readCpuKHz(),
            gpuPct = gpuPct,
            gpuMHz = gpuMhz,
            display = runCatching {
                ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).state
            }.getOrDefault(0),
            interactive = pm.isInteractive,
            touchDevs = touchDevices(),
            mainLagMs = mainLag(),
            appPssMb = runCatching { Debug.getPss() / 1024 }.getOrDefault(-1L).toInt(),
            threads = runCatching { File("/proc/self/task").list()?.size ?: -1 }.getOrDefault(-1),
            fds = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1),
            fgPkg = UiPulse.fgPkg,
            uiIdleMs = UiPulse.uiIdleMs(now),
            lockTask = runCatching { am.lockTaskModeState }.getOrDefault(-1),
            anrDialog = UiPulse.anrRecent(now),
            dimmed = DimController.dimmed,
        )
    }

    /** Post lên main và đo trễ; main bị chặn quá [LAG_PROBE_MS] thì coi như treo. */
    private fun mainLag(): Long {
        val t0 = System.nanoTime()
        val done = CountDownLatch(1)
        mainHandler.post { done.countDown() }
        val ok = done.await(LAG_PROBE_MS, TimeUnit.MILLISECONDS)
        return if (ok) (System.nanoTime() - t0) / 1_000_000 else LAG_PROBE_MS
    }

    private fun touchDevices(): Int = runCatching {
        InputDevice.getDeviceIds().count { id ->
            val d = InputDevice.getDevice(id)
            d != null && (d.sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        }
    }.getOrDefault(-1)

    /** Android 8+ chặn /proc/stat với app thường: null là bình thường, đừng coi là 0%. */
    private fun readCpuPct(): Int? = runCatching {
        val f = File("/proc/stat").useLines { it.first() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = f[3] + f.getOrElse(4) { 0 }
        val total = f.sum()
        val prev = prevCpu
        prevCpu = longArrayOf(idle, total)
        if (prev == null || total <= prev[1]) null
        else (100 - (idle - prev[0]) * 100 / (total - prev[1])).toInt().coerceIn(0, 100)
    }.getOrNull()

    private fun readCpuKHz(): Int? = runCatching {
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { runCatching { File(it, "cpufreq/scaling_cur_freq").readText().trim().toInt() }.getOrNull() }
            ?.maxOrNull()
    }.getOrNull()

    /** Không có API công khai cho GPU: thử vài sysfs của Adreno/Mali/Exynos, máy nào đọc được thì có số. */
    private fun readGpu(): Pair<Int?, Int?> {
        val pct = firstInt(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy",
        ) ?: globInt("/sys/devices/platform", ".mali", "utilization")
        val mhz = firstInt("/sys/kernel/gpu/gpu_clock")
            ?: firstInt("/sys/class/kgsl/kgsl-3d0/gpuclk")?.let { it / 1_000_000 }
        return pct to mhz
    }

    private fun readZoneMaxC(): Float? = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { runCatching { File(it, "temp").readText().trim().toInt() }.getOrNull() }
            ?.map { if (it > 1000) it / 1000f else it.toFloat() }
            ?.filter { it in 1f..150f }
            ?.maxOrNull()
    }.getOrNull()

    private fun firstInt(vararg paths: String): Int? {
        for (p in paths) {
            val v = runCatching {
                Regex("-?\\d+").find(File(p).readText())?.value?.toInt()
            }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    private fun globInt(dir: String, suffix: String, leaf: String): Int? = runCatching {
        File(dir).listFiles { f -> f.name.endsWith(suffix) }
            ?.firstNotNullOfOrNull { firstInt(File(it, leaf).path) }
    }.getOrNull()

    private fun toJson(s: HwSample, codes: List<String>): JSONObject = JSONObject()
        .put("ts", s.ts)
        .put("phase", s.phase)
        .put("thermal", s.thermal)
        .put("battTempC", s.battTempC ?: JSONObject.NULL)
        .put("zoneMaxC", s.zoneMaxC ?: JSONObject.NULL)
        .put("memAvailMb", s.memAvailMb)
        .put("memTotalMb", s.memTotalMb)
        .put("lowMem", s.lowMem)
        .put("diskFreeMb", s.diskFreeMb)
        .put("cpuPct", s.cpuPct ?: JSONObject.NULL)
        .put("cpuKHz", s.cpuKHz ?: JSONObject.NULL)
        .put("gpuPct", s.gpuPct ?: JSONObject.NULL)
        .put("gpuMHz", s.gpuMHz ?: JSONObject.NULL)
        .put("display", s.display)
        .put("interactive", s.interactive)
        .put("touchDevs", s.touchDevs)
        .put("mainLagMs", s.mainLagMs)
        .put("appPssMb", s.appPssMb)
        .put("threads", s.threads)
        .put("fds", s.fds)
        .put("fgPkg", s.fgPkg ?: JSONObject.NULL)
        .put("uiIdleMs", s.uiIdleMs)
        .put("lockTask", s.lockTask)
        .put("dimmed", s.dimmed)
        .put("anomaly", JSONArray(codes))

    private fun brief(s: HwSample): String =
        "thermal=${s.thermal} batt=${s.battTempC} zone=${s.zoneMaxC} mem=${s.memAvailMb}/${s.memTotalMb}MB " +
            "disk=${s.diskFreeMb}MB cpu=${s.cpuPct} gpu=${s.gpuPct} display=${s.display} " +
            "fg=${s.fgPkg} idle=${s.uiIdleMs}ms lockTask=${s.lockTask} interactive=${s.interactive} touch=${s.touchDevs} lag=${s.mainLagMs}ms phase=${s.phase}"

    /** Chỉ fsync khi có bất thường: bình thường để page cache lo, đỡ mòn flash trên máy chạy 24/7. */
    private fun append(line: String, sync: Boolean) {
        val dir = RcApplication.app.filesDir
        val f = File(dir, FILE)
        runCatching {
            if (f.length() > MAX_BYTES) {
                File(dir, "$FILE.1").delete()
                f.renameTo(File(dir, "$FILE.1"))
            }
            FileOutputStream(File(dir, FILE), true).use { out ->
                out.write((line + "\n").toByteArray())
                out.flush()
                if (sync) out.fd.sync()
            }
        }
    }
}
