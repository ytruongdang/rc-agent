package com.you.rcagent.core

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

/** Quyết định làm mờ tách khỏi WindowManager để test trên JVM. */
object DimPolicy {
    const val MIN_LEVEL = 5
    const val MAX_LEVEL = 80
    const val DEFAULT_LEVEL = 30
    private const val MAX_AFTER_MIN = 24 * 60

    fun clampLevel(p: Int): Int = p.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /**
     * Lớp đen phủ lên: level 30 → 70% đen. Trần 0.8: Android 12+ chặn chạm đi xuyên qua overlay của app khác
     * nếu độ đục > 0.8 (kể cả NOT_TOUCHABLE), kiosk sẽ "chết" cảm ứng.
     */
    fun alphaFor(level: Int): Float = (1f - clampLevel(level) / 100f).coerceAtMost(0.8f)

    /** "off"/số âm → 0 (tắt). null = không hiểu giá trị, giữ nguyên cấu hình cũ. */
    fun parseAfterMin(raw: String): Int? {
        val s = raw.trim().lowercase()
        if (s == "off" || s == "false") return 0
        val n = s.toIntOrNull() ?: return null
        return if (n <= 0) 0 else n.coerceAtMost(MAX_AFTER_MIN)
    }

    /** Không dim khi đang có phiên remote: người xem phải thấy màn hình thật. */
    fun shouldDim(
        idleMs: Long,
        afterMs: Long,
        sessionActive: Boolean,
        overlayOk: Boolean,
        interactive: Boolean,
    ): Boolean = afterMs > 0 && overlayOk && interactive && !sessionActive && idleMs >= afterMs
}

/**
 * Sau một thời gian không ai chạm màn hình thì làm mờ bằng một cửa sổ overlay đen bán trong suốt,
 * không cần WRITE_SETTINGS. Chạm lần đầu sẽ gỡ ngay. Mặc định TẮT (dim_after_min=0): khi tắt không
 * tạo cửa sổ nào.
 */
object DimController {
    private const val TICK_MS = 5_000L
    private const val TOUCH_LOG_MS = 30_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var watcher: View? = null
    private var dimView: View? = null
    private var lastTouch = System.currentTimeMillis()
    private var lastTouchLog = 0L
    private var started = false

    @Volatile var dimmed: Boolean = false
        private set

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        wm = app?.getSystemService(WindowManager::class.java)
        AgentState.onChange { p ->
            if (p == AgentState.Phase.ACQUIRING || p == AgentState.Phase.STREAMING) main.post { touched() }
        }
        main.postDelayed(object : Runnable {
            override fun run() {
                runCatching { tick() }.onFailure { Log.w(Config.TAG, "dim tick: ${it.message}") }
                main.postDelayed(this, TICK_MS)
            }
        }, TICK_MS)
    }

    private fun tick() {
        val ctx = app ?: return
        val afterMs = BackendPrefs.dimAfterMin(ctx) * 60_000L
        if (afterMs <= 0) {
            lastTouch = System.currentTimeMillis()
            removeWatcher()
            undim("off")
            return
        }
        val overlayOk = Settings.canDrawOverlays(ctx)
        if (overlayOk) addWatcher(ctx)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val session = AgentState.phase == AgentState.Phase.ACQUIRING ||
            AgentState.phase == AgentState.Phase.STREAMING
        // Mốc "không tương tác" chỉ tính trong lúc dim thật sự áp dụng được: hết phiên remote, màn hình vừa bật,
        // hay vừa bật cấu hình thì phải đếm lại từ đầu, không dim ngay vì lastTouch đã cũ.
        if (session || !pm.isInteractive || !overlayOk) lastTouch = System.currentTimeMillis()
        val want = DimPolicy.shouldDim(
            System.currentTimeMillis() - lastTouch, afterMs, session, overlayOk, pm.isInteractive,
        )
        if (want && !dimmed) dim(ctx) else if (!want && dimmed) undim(if (session) "session" else "idle-reset")
    }

    /** Có tương tác: gỡ mờ ngay, không chờ nhịp kế tiếp. */
    private fun touched() {
        val now = System.currentTimeMillis()
        lastTouch = now
        if (now - lastTouchLog > TOUCH_LOG_MS) {
            lastTouchLog = now
            Log.i(Config.TAG, "dim touch dimmed=$dimmed")
        }
        if (dimmed) undim("touch")
    }

    private fun addWatcher(ctx: Context) {
        if (watcher != null) return
        // 1x1 chạm được + WATCH_OUTSIDE_TOUCH: nhận ACTION_OUTSIDE cho mọi chạm ở nơi khác.
        val v = object : View(ctx) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_OUTSIDE) touched()
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Nằm ngoài màn hình: không bao giờ nuốt cú chạm nào, vẫn nhận ACTION_OUTSIDE.
            x = -2
        }
        runCatching { wm?.addView(v, lp); watcher = v }
            .onFailure { Log.w(Config.TAG, "dim watcher: ${it.message}") }
    }

    private fun removeWatcher() {
        watcher?.let { v -> runCatching { wm?.removeView(v) } }
        watcher = null
    }

    private fun dim(ctx: Context) {
        val level = DimPolicy.clampLevel(BackendPrefs.dimLevel(ctx))
        // Samsung bỏ qua screenBrightness của cửa sổ overlay (đã thử trên SM-S918B: mBrightness không đổi),
        // nên dùng lớp đen bán trong suốt: giảm công suất trên OLED, không giảm đèn nền trên LCD.
        val v = View(ctx).apply { setBackgroundColor(Color.argb((DimPolicy.alphaFor(level) * 255).toInt(), 0, 0, 0)) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { wm?.addView(v, lp) }.isSuccess) {
            dimView = v
            dimmed = true
            Log.i(Config.TAG, "dim on level=$level")
        }
    }

    private fun undim(why: String) {
        val v = dimView
        dimView = null
        if (v != null) runCatching { wm?.removeView(v) }
        if (dimmed) Log.i(Config.TAG, "dim off ($why)")
        dimmed = false
    }
}
