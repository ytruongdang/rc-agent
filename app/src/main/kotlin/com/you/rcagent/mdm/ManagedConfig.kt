package com.you.rcagent.mdm

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log
import com.you.rcagent.core.BackendPrefs
import com.you.rcagent.core.Config

/** Headwind Application Settings + Android managed restrictions → BackendPrefs. */
object ManagedConfig {
    const val HOST = "host"
    const val MQTT = "mqtt"
    const val MQTT_PASSWORD = "mqtt_password"
    const val DEVICE_ID = "device_id"
    const val RELAY_PORT = "relay_port"
    const val KIOSK_PKG = "kiosk_pkg"
    const val DIM_AFTER_MIN = "dim_after_min"
    const val DIM_LEVEL = "dim_level"

    @Volatile var lastApplied: String = ""
        private set

    fun flatten(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun take(canon: String, vararg names: String) {
            names.firstNotNullOfOrNull { raw[it]?.trim()?.takeIf { s -> s.isNotEmpty() && s != "0" } }
                ?.let { out[canon] = it }
        }
        take(HOST, HOST, "backend", "relay_host")
        take(MQTT, MQTT, "mqtt_broker")
        take(MQTT_PASSWORD, MQTT_PASSWORD, "mqtt_pass")
        take(DEVICE_ID, DEVICE_ID, "deviceId")
        take(RELAY_PORT, RELAY_PORT, "ws_port")
        take(KIOSK_PKG, KIOSK_PKG, "kiosk_package", "kiosk")
        take(DIM_AFTER_MIN, DIM_AFTER_MIN)
        take(DIM_LEVEL, DIM_LEVEL)
        return out
    }

    fun fromBundle(b: Bundle): Map<String, String> {
        val raw = LinkedHashMap<String, String>()
        for (k in b.keySet()) {
            val v = when (val x = b.get(k)) {
                is String -> x
                is Number -> x.toString()
                is Boolean -> x.toString()
                else -> continue
            }
            if (v.isNotBlank()) raw[k] = v
        }
        return flatten(raw)
    }

    fun apply(ctx: Context, raw: Map<String, String>): Boolean {
        // Chỉ bản debug: test với máy đang được MDM quản lý thì Headwind đẩy cấu hình production đè lên cấu hình local.
        // `pin_config` ghi bằng `adb shell run-as`; bản release không bao giờ đọc cờ này.
        if (com.you.rcagent.BuildConfig.DEBUG && ctx.getSharedPreferences("rc", 0).getBoolean("pin_config", false)) {
            Log.i(Config.TAG, "mdm apply ignored (pin_config, debug build)")
            return false
        }
        val entries = flatten(raw)
        if (entries.isEmpty()) return false
        // So giá trị kết nối trước/sau: Headwind đẩy lại cùng một cấu hình mỗi lần đồng bộ, và trước đây mỗi lần như
        // vậy đều ép MQTT ngắt rồi nối lại dù không có gì đổi.
        val before = BackendPrefs.connFingerprint(ctx)
        entries[HOST]?.let { BackendPrefs.saveHost(ctx, it) }
        entries[MQTT]?.let { BackendPrefs.saveMqtt(ctx, it) }
        entries[MQTT_PASSWORD]?.let { BackendPrefs.saveMqttPassword(ctx, it) }
        entries[DEVICE_ID]?.let { BackendPrefs.saveDeviceId(ctx, it) }
        entries[RELAY_PORT]?.let { BackendPrefs.saveRelayPort(ctx, it) }
        val changed = BackendPrefs.connFingerprint(ctx) != before
        // Không đụng `changed`: kiosk_pkg không liên quan broker, đừng ép MQTT reconnect.
        entries[KIOSK_PKG]?.let { BackendPrefs.saveKioskPkg(ctx, it) }
        // Không đụng `changed`: làm mờ không liên quan broker.
        entries[DIM_AFTER_MIN]?.let { BackendPrefs.saveDimAfterMin(ctx, it) }
        entries[DIM_LEVEL]?.let { BackendPrefs.saveDimLevel(ctx, it) }
        lastApplied = entries.keys.joinToString(",")
        Log.i(Config.TAG, "mdm apply $lastApplied changed=$changed")
        return changed
    }

    fun applyFromRestrictions(ctx: Context): Boolean {
        val rm = ctx.getSystemService(RestrictionsManager::class.java) ?: return false
        val b = rm.applicationRestrictions ?: return false
        if (b.isEmpty) return false
        return apply(ctx, fromBundle(b))
    }
}
