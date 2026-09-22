package com.you.rcagent.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.you.rcagent.core.AgentState
import com.you.rcagent.core.Capabilities
import com.you.rcagent.core.Config
import com.you.rcagent.transport.MqttClient

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        Log.i(Config.TAG, "boot ${intent?.action}")
        val phase = AgentState.phase
        if (phase == AgentState.Phase.ACQUIRING || phase == AgentState.Phase.STREAMING) {
            Log.i(Config.TAG, "boot ignored, phase=$phase")
            return
        }
        AgentState.set(AgentState.Phase.HEALING)
        SelfHeal.run(ctx.applicationContext)
        Log.i(Config.TAG, "caps ${Capabilities.probe(ctx)}")
        AgentState.set(AgentState.Phase.IDLE)
        MqttClient.connectNow()
    }
}
