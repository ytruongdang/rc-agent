package com.you.rcagent

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.you.rcagent.boot.SelfHeal
import com.you.rcagent.capture.CapCrash
import com.you.rcagent.capture.CaptureService
import com.you.rcagent.core.AgentState
import com.you.rcagent.core.Capabilities
import com.you.rcagent.core.Config
import com.you.rcagent.core.FaultLog
import com.you.rcagent.transport.MqttClient
import java.io.File
import java.io.FileOutputStream

class RcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        dropStaleCrash()
        FaultLog.noteProcessStart()
        installCrashLog()
        AgentState.set(AgentState.Phase.HEALING)
        SelfHeal.run(this)
        val caps = Capabilities.probe(this)
        Log.i(Config.TAG, "caps $caps")
        AgentState.set(AgentState.Phase.IDLE)
        com.you.rcagent.mdm.ManagedConfig.applyFromRestrictions(this)
        com.you.rcagent.mdm.HeadwindBridge.start(this)
        MqttClient.start(this)
    }

    private fun dropStaleCrash() {
        val stamp = File(filesDir, "rc_ver")
        val last = stamp.takeIf { it.exists() }?.readText()
        if (last != BuildConfig.VERSION_NAME) {
            File(filesDir, CRASH_FILE).delete()
            FaultLog.clear()
            stamp.writeText(BuildConfig.VERSION_NAME)
        }
    }

    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = "${BuildConfig.VERSION_NAME} ${System.currentTimeMillis()} ${t.name}\n${FaultLog.format("CRASH", t.name, e)}"
            runCatching {
                FileOutputStream(File(filesDir, CRASH_FILE)).use { out ->
                    out.write(text.toByteArray())
                    out.flush()
                    out.fd.sync()
                }
            }
            FaultLog.error("CRASH", e, t.name)
            if (CapCrash.isCaptureThread(t.name)) {
                CapCrash.markAbandon()
                Handler(Looper.getMainLooper()).post {
                    CaptureService.recoverAfterCapCrash()
                }
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val CRASH_FILE = "rc_crash.txt"
        lateinit var app: RcApplication
            private set

        fun crashText(): String =
            runCatching { File(app.filesDir, CRASH_FILE).readText() }.getOrDefault("")
    }
}
