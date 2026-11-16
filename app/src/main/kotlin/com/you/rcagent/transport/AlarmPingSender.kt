package com.you.rcagent.transport

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.you.rcagent.core.Config
import org.eclipse.paho.mqttv5.client.MqttPingSender
import org.eclipse.paho.mqttv5.client.internal.ClientComms

/**
 * Paho mặc định ping bằng java.util.Timer — dừng khi CPU ngủ nên broker cắt `keepalive_timeout(141)` sau 1,5×keepalive.
 * Ping bằng AlarmManager (đánh thức được CPU) + wakelock ngắn quanh lần ping; WifiLock giữ radio khỏi power-save.
 */
class AlarmPingSender(private val ctx: Context) : MqttPingSender {
    private var comms: ClientComms? = null
    private var receiver: BroadcastReceiver? = null
    private var pending: android.app.PendingIntent? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val action = "${Config.PKG}.MQTT_PING"

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    @Synchronized
    override fun start() {
        val c = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rcagent:mqttping")
                runCatching { wl.acquire(15_000L) }
                // checkForActivity gửi PINGREQ và tự gọi lại schedule() cho lần sau.
                val ok = runCatching { comms?.checkForActivity() }.onFailure {
                    Log.w(Config.TAG, "mqtt ping failed: ${it.message}")
                }.isSuccess
                if (!ok) runCatching { if (wl.isHeld) wl.release() }
                else Thread { Thread.sleep(3_000); runCatching { if (wl.isHeld) wl.release() } }.start()
            }
        }
        receiver = r
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else c.registerReceiver(r, f)
        pending = android.app.PendingIntent.getBroadcast(
            c, 0, Intent(action).setPackage(c.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rcagent:mqtt").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        schedule(comms?.keepAlive ?: (Config.MQTT_KEEPALIVE_S * 1000L))
    }

    @Synchronized
    override fun stop() {
        val c = ctx.applicationContext
        runCatching { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending ?: return@runCatching) }
        receiver?.let { runCatching { c.unregisterReceiver(it) } }
        receiver = null
        pending = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    @Synchronized
    override fun schedule(delayInMilliseconds: Long) {
        val pi = pending ?: return
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + delayInMilliseconds
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) } }
    }
}
