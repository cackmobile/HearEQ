package com.cackmobile.heareq

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Works out whether audio is currently going to hearing aids. */
object HearingDevices {

    /**
     * Signia IX aids stream over ASHA (TYPE_HEARING_AID) or, on newer phones, LE Audio
     * (TYPE_BLE_HEADSET). Anything with "Signia" in its name also counts.
     */
    fun isHearingAid(d: AudioDeviceInfo): Boolean {
        if (d.type == AudioDeviceInfo.TYPE_HEARING_AID) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        ) return true
        return d.productName?.toString()?.contains("signia", ignoreCase = true) == true
    }

    fun connected(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isHearingAid(it) }

    fun describe(d: AudioDeviceInfo): String {
        val kind = when {
            d.type == AudioDeviceInfo.TYPE_HEARING_AID -> "ASHA"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> "LE Audio"
            else -> "Bluetooth"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Hearing aids"
        return "$name ($kind)"
    }
}
