package com.cackmobile.heareq

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * Owns one DynamicsProcessing effect per audio session.
 * Session 0 is the global output mix: it catches audio from apps that don't announce
 * their sessions, but some phones refuse it, so per-app sessions are tracked as well.
 */
class EqEngine {

    private val effects = LinkedHashMap<Int, DynamicsProcessing>()
    private var snapshot: EqSnapshot? = null
    private var active = false

    var globalAttached = false
        private set

    val sessionCount: Int get() = effects.keys.count { it != GLOBAL_SESSION }

    fun attachGlobal() {
        globalAttached = attach(GLOBAL_SESSION)
    }

    /** Returns true if the effect is (now) attached to this session. */
    fun attach(sessionId: Int): Boolean {
        if (effects.containsKey(sessionId)) return true
        return try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            snapshot?.let { applyTo(dp, it) }
            dp.setEnabled(active)
            effects[sessionId] = dp
            Log.i(TAG, "Attached to session $sessionId")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not attach to session $sessionId", e)
            false
        }
    }

    fun detach(sessionId: Int) {
        effects.remove(sessionId)?.let { runCatching { it.release() } }
        if (sessionId == GLOBAL_SESSION) globalAttached = false
    }

    fun update(s: EqSnapshot) {
        snapshot = s
        effects.values.forEach { dp -> runCatching { applyTo(dp, s) } }
    }

    fun setActive(on: Boolean) {
        active = on
        effects.values.forEach { dp -> runCatching { dp.setEnabled(on) } }
    }

    fun release() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
        globalAttached = false
    }

    private fun buildConfig(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            /* preEqInUse = */ true, /* preEqBandCount = */ Bands.COUNT,
            /* mbcInUse = */ false, /* mbcBandCount = */ 0,
            /* postEqInUse = */ false, /* postEqBandCount = */ 0,
            /* limiterInUse = */ true,
        ).setPreferredFrameDuration(10f).build()

    private fun applyTo(dp: DynamicsProcessing, s: EqSnapshot) {
        dp.setInputGainAllChannelsTo(s.preampDb)

        val eq = DynamicsProcessing.Eq(true, true, Bands.COUNT)
        for (i in 0 until Bands.COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, Bands.cutoffHz(i), s.gainsDb[i]))
        }
        dp.setPreEqAllChannelsTo(eq)

        // Brick-wall limiter so boosted bands can't clip and distort in the hearing aids.
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                /* attackTimeMs = */ 1f, /* releaseTimeMs = */ 60f,
                /* ratio = */ 10f, /* thresholdDb = */ -2f, /* postGainDb = */ 0f,
            )
        )
    }

    companion object {
        const val GLOBAL_SESSION = 0
        private const val PRIORITY = Int.MAX_VALUE
        private const val CHANNELS = 2
        private const val TAG = "HearEQ"
    }
}
