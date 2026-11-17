package com.miaoxiang.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后恢复后台轮询。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Prefs(context)
            if (prefs.pairCode.isNotEmpty()) {
                PollingWorker.schedule(context, prefs.pollMinutes)
            }
        }
    }
}
