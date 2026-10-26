package com.you.rcagent.boot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.you.rcagent.core.AgentState
import com.you.rcagent.core.BackendPrefs
import com.you.rcagent.core.Config
import com.you.rcagent.core.UiPulse
import com.you.rcagent.input.RcAccessibilityService

/**
 * Sau khi MDM cập nhật rc-agent, Android/Headwind hay mở lại chính app này lên foreground,
 * che mất browser kiosk. Không có người ở máy nên phải tự trả màn hình về kiosk.
 */
object KioskReturn {
    private const val RETRY_MS = 8_000L
    private const val MAX_TRIES = 3

    private val IGNORED = setOf(
        "android",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    fun isCandidate(pkg: String, ours: String = Config.PKG): Boolean =
        pkg.isNotBlank() && pkg != ours && pkg !in IGNORED

    /** MDM chỉ định > app kiosk đã thấy lần cuối > null (rơi về nút Home). */
    fun pickTarget(configured: String?, seen: String?, ours: String = Config.PKG): String? =
        listOf(configured, seen).firstOrNull { it != null && isCandidate(it, ours) }

    /** Chỉ kéo về khi app mình đang che (hoặc chưa biết gì sau khi process mới sinh). */
    fun needsReturn(fg: String?, ours: String = Config.PKG): Boolean = fg == null || fg == ours

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun afterUpdate(ctx: Context) {
        val app = ctx.applicationContext
        handler.removeCallbacksAndMessages(null)
        attempt(app, 0)
    }

    private fun attempt(ctx: Context, n: Int) {
        val phase = AgentState.phase
        if (phase == AgentState.Phase.ACQUIRING || phase == AgentState.Phase.STREAMING) return
        if (n > 0) {
            val fg = UiPulse.fgPkg
            // Không có sự kiện a11y thì không kiểm chứng được: dừng, đừng bấm Home mù lên kiosk đang sáng.
            if (fg == null || !needsReturn(fg)) {
                Log.i(Config.TAG, "kiosk return settled fg=$fg")
                return
            }
        }
        if (n >= MAX_TRIES) {
            Log.w(Config.TAG, "kiosk return gave up fg=${UiPulse.fgPkg}")
            return
        }
        val target = pickTarget(BackendPrefs.kioskPkg(ctx), BackendPrefs.seenKioskPkg(ctx))
        Log.i(Config.TAG, "kiosk return try=$n target=$target")
        // Lần đầu mở thẳng kiosk. Android 10+ có thể chặn startActivity từ nền mà không ném lỗi,
        // nên lần thử sau (vẫn kẹt ở rc-agent) dùng Home qua a11y thay vì lặp lại cùng một lệnh.
        val launched = n == 0 && target != null && launch(ctx, target)
        if (!launched) goHome(ctx)
        handler.postDelayed({ attempt(ctx, n + 1) }, RETRY_MS)
    }

    private fun launch(ctx: Context, pkg: String): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    private fun goHome(ctx: Context) {
        val a11y = RcAccessibilityService.instance
        if (a11y != null && runCatching { a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
        ) return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
