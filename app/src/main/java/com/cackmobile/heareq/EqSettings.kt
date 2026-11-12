package com.cackmobile.heareq

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.sqrt

/** The ten equaliser bands (centre frequencies in Hz). */
object Bands {
    val CENTRES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val COUNT = CENTRES.size

    const val MIN_DB = -12f
    const val MAX_DB = 12f
    const val PREAMP_MIN_DB = -12f
    const val PREAMP_MAX_DB = 6f

    /** DynamicsProcessing bands are defined by their upper edge, so sit it half an octave above the centre. */
    fun cutoffHz(index: Int): Float =
        if (index == COUNT - 1) 20000f else CENTRES[index] * sqrt(2f)
}

/** Built-in starting points. Values are dB per band, low to high. */
object Presets {
    val BUILT_IN: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Music" to floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, 1f, 2f, 2f, 1f),
        "Bass boost" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Warm" to floatArrayOf(3f, 3f, 2f, 1f, 0f, -1f, -1f, -2f, -2f, -3f),
        "Vocal clarity" to floatArrayOf(-2f, -2f, -1f, 0f, 1f, 3f, 4f, 3f, 1f, 0f),
        "Podcast / speech" to floatArrayOf(-4f, -3f, -2f, 0f, 1f, 3f, 4f, 3f, 0f, -2f),
        "Soften treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -3f, -4f, -5f),
    )
}

data class EqSnapshot(
    val enabled: Boolean,
    val onlyHearingAids: Boolean,
    val preampDb: Float,
    val gainsDb: FloatArray,
)

class EqSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("heareq", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var onlyHearingAids: Boolean
        get() = prefs.getBoolean(KEY_ONLY_HA, true)
        set(v) = prefs.edit().putBoolean(KEY_ONLY_HA, v).apply()

    var preampDb: Float
        get() = prefs.getFloat(KEY_PREAMP, -3f)
        set(v) = prefs.edit().putFloat(KEY_PREAMP, v).apply()

    var gainsDb: FloatArray
        get() = decode(prefs.getString(KEY_GAINS, null)) ?: FloatArray(Bands.COUNT)
        set(v) = prefs.edit().putString(KEY_GAINS, encode(v)).apply()

    var presetName: String
        get() = prefs.getString(KEY_PRESET, "Flat") ?: "Flat"
        set(v) = prefs.edit().putString(KEY_PRESET, v).apply()

    /** Paused from the notification. The service keeps running so the buttons stay available. */
    var paused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(v) = prefs.edit().putBoolean(KEY_PAUSED, v).apply()

    /** Switch to the next/previous saved preset (skips the unsaved "Custom" state). */
    fun cyclePreset(step: Int): String {
        val all = allPresets()
        val names = all.keys.toList()
        val cur = names.indexOf(presetName)
        val next = names[Math.floorMod(if (cur < 0) (if (step > 0) -1 else 0) + step else cur + step, names.size)]
        prefs.edit()
            .putString(KEY_GAINS, encode(all.getValue(next)))
            .putString(KEY_PRESET, next)
            .putLong(KEY_EXTERNAL_CHANGE, System.currentTimeMillis())
            .apply()
        return next
    }

    // ---- Tinnitus noise ----

    var tinnitusOn: Boolean
        get() = prefs.getBoolean(KEY_TIN_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_TIN_ON, v).apply()

    var tinnitusType: Int
        get() = prefs.getInt(KEY_TIN_TYPE, Noise.PINK).coerceIn(0, Noise.NAMES.size - 1)
        set(v) = prefs.edit().putInt(KEY_TIN_TYPE, v).apply()

    var tinnitusLeft: Boolean
        get() = prefs.getBoolean(KEY_TIN_LEFT, true)
        set(v) = prefs.edit().putBoolean(KEY_TIN_LEFT, v).apply()

    var tinnitusRight: Boolean
        get() = prefs.getBoolean(KEY_TIN_RIGHT, true)
        set(v) = prefs.edit().putBoolean(KEY_TIN_RIGHT, v).apply()

    /** 0–100 per ear. */
    var tinnitusLeftLevel: Int
        get() = prefs.getInt(KEY_TIN_LEFT_LEVEL, 50)
        set(v) = prefs.edit().putInt(KEY_TIN_LEFT_LEVEL, v).apply()

    var tinnitusRightLevel: Int
        get() = prefs.getInt(KEY_TIN_RIGHT_LEVEL, 50)
        set(v) = prefs.edit().putInt(KEY_TIN_RIGHT_LEVEL, v).apply()

    fun earsLabel(): String = when {
        tinnitusLeft && tinnitusRight -> "left + right"
        tinnitusLeft -> "left only"
        tinnitusRight -> "right only"
        else -> "no ear selected"
    }

    /** Lets the app screen know something outside it (the notification) changed the settings. */
    fun markExternalChange() = prefs.edit().putLong(KEY_EXTERNAL_CHANGE, System.currentTimeMillis()).apply()

    fun snapshot() = EqSnapshot(enabled, onlyHearingAids, preampDb, gainsDb)

    /** Built-in presets followed by the ones you've saved. */
    fun allPresets(): LinkedHashMap<String, FloatArray> {
        val out = LinkedHashMap(Presets.BUILT_IN)
        prefs.all.keys.filter { it.startsWith(CUSTOM_PREFIX) }.sorted().forEach { key ->
            decode(prefs.getString(key, null))?.let { out[key.removePrefix(CUSTOM_PREFIX)] = it }
        }
        return out
    }

    fun isCustom(name: String) = prefs.contains(CUSTOM_PREFIX + name)

    fun saveCustom(name: String, gains: FloatArray) =
        prefs.edit().putString(CUSTOM_PREFIX + name, encode(gains)).apply()

    fun deleteCustom(name: String) = prefs.edit().remove(CUSTOM_PREFIX + name).apply()

    private fun encode(a: FloatArray) = a.joinToString(",")

    private fun decode(s: String?): FloatArray? =
        s?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == Bands.COUNT }?.toFloatArray()

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_ONLY_HA = "only_hearing_aids"
        const val KEY_PREAMP = "preamp_db"
        const val KEY_GAINS = "gains_db"
        const val KEY_PRESET = "preset_name"
        const val KEY_PAUSED = "paused"
        const val KEY_TIN_ON = "tinnitus_on"
        const val KEY_TIN_TYPE = "tinnitus_type"
        const val KEY_TIN_LEFT = "tinnitus_left"
        const val KEY_TIN_RIGHT = "tinnitus_right"
        const val KEY_TIN_LEFT_LEVEL = "tinnitus_left_level"
        const val KEY_TIN_RIGHT_LEVEL = "tinnitus_right_level"
        const val KEY_EXTERNAL_CHANGE = "external_change"
        private const val CUSTOM_PREFIX = "custom_preset_"
    }
}
