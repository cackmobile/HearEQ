package com.cackmobile.heareq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns the equaliser back on after a restart if you left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = EqSettings(context)
        // Don't surprise you with noise after a restart; only the equaliser comes back on.
        settings.tinnitusOn = false
        if (settings.enabled) runCatching { EqService.start(context) }
    }
}
