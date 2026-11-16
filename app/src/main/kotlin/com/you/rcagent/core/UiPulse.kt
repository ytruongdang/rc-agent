package com.you.rcagent.core

import android.view.accessibility.AccessibilityEvent

/**
 * Nhịp UI của máy kiosk, lấy từ sự kiện accessibility có sẵn (không thêm service/quyền):
 * app nào đang foreground, UI đổi lần cuối khi nào, và có hộp thoại ANR của hệ thống không.
 */
object UiPulse {
    private const val ANR_WINDOW_MS = 60_000L

    @Volatile var fgPkg: String? = null
        private set
    @Volatile private var lastUiMs = 0L
    @Volatile private var anrMs = 0L

    fun onEvent(e: AccessibilityEvent?) {
        e ?: return
        val now = System.currentTimeMillis()
        lastUiMs = now
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (isSystemPkg(pkg)) {
            if (isAnrText(e.text.joinToString(" "), broad = pkg == "android")) anrMs = now
        } else {
            val changed = pkg != fgPkg
            fgPkg = pkg
            // Chỉ app có icon launcher mới là ứng viên kiosk: loại bàn phím, hộp thoại quyền... cũng phát window event.
            if (changed && com.you.rcagent.boot.KioskReturn.isCandidate(pkg)) {
                runCatching {
                    val app = com.you.rcagent.RcApplication.app
                    if (app.packageManager.getLaunchIntentForPackage(pkg) != null && !isHomeApp(app, pkg)) {
                        BackendPrefs.saveSeenKioskPkg(app, pkg)
                    }
                }
            }
        }
    }

    /** Launcher/Home (Headwind, One UI Home) không phải app kiosk: Home đã tự đưa về đó. */
    private fun isHomeApp(ctx: android.content.Context, pkg: String): Boolean {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        // Chỉ loại launcher *mặc định*: kiosk browser cũng có thể khai báo HOME mà không phải home đang dùng.
        val def = ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return def?.activityInfo?.packageName == pkg
    }

    /** -1 = chưa từng thấy sự kiện UI kể từ khi service bind. */
    fun uiIdleMs(now: Long = System.currentTimeMillis()): Long =
        if (lastUiMs == 0L) -1 else now - lastUiMs

    fun anrRecent(now: Long = System.currentTimeMillis()): Boolean = now - anrMs < ANR_WINDOW_MS

    fun isSystemPkg(pkg: String): Boolean = pkg == "android" || pkg == "com.android.systemui"

    /** Nhãn hộp thoại ANR của AOSP/Samsung, tiếng Anh và tiếng Việt. */
    fun isAnrText(text: String, broad: Boolean = false): Boolean {
        val t = text.lowercase()
        // "has stopped"/"đã dừng" là lời của hộp thoại crash Android cũ (từ package android), nhưng SystemUI
        // cũng dùng ("Recording has stopped") nên chỉ nhận khi cửa sổ đến từ `android`.
        return t.contains("isn't responding") || t.contains("keeps stopping") ||
            t.contains("không phản hồi") || t.contains("liên tục dừng") ||
            (broad && (t.contains("has stopped") || t.contains("đã dừng")))
    }
}
