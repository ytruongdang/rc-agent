package com.you.rcagent.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.you.rcagent.capture.ScreenWake
import com.you.rcagent.core.Config

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        ScreenWake.acquire(ctx, Config.WAKE_PUSH_MS)
        MqttClient.connectNow()
    }
}
