package com.cackmobile.heareq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns the equaliser back on after a restart if you left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && EqSettings(context).enabled) {
            runCatching { EqService.start(context) }
        }
    }
}
