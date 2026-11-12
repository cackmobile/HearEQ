package com.cackmobile.heareq

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The noise colours on offer, in the order they appear in the app. */
object Noise {
    const val WHITE = 0
    const val PINK = 1
    const val BROWN = 2
    const val BLUE = 3
    const val VIOLET = 4
    const val GREY = 5
    const val GREEN = 6
    const val OCEAN = 7

    val NAMES = listOf("White", "Pink", "Brown / red", "Blue", "Violet", "Grey", "Green", "Ocean waves")

    val DESCRIPTIONS = listOf(
        "Equal energy at every frequency. Bright and hissy, like a detuned radio.",
        "Softer than white, with less hiss. Like steady rain. A common choice for tinnitus.",
        "Deep and rumbly, like a waterfall or distant thunder. (Red and brown noise are the same thing.)",
        "The opposite of pink: weighted towards high frequencies. Can mask high-pitched ringing.",
        "Even brighter than blue. Mostly very high frequencies, for high-pitched tinnitus.",
        "Shaped to sound equally loud across all pitches to the ear. Balanced and gentle.",
        "Focused on the middle frequencies, like wind through trees or a running stream.",
        "Gentle noise that swells and fades like waves on a beach.",
    )
}

/**
 * Plays generated noise as a stereo stream. Android sends the left channel to the left hearing aid
 * and the right channel to the right, which is how each ear is switched on and off.
 */
class NoiseGenerator {

    @Volatile var type = Noise.PINK
    @Volatile var leftGain = 0f
    @Volatile var rightGain = 0f

    @Volatile private var running = false
    private var thread: Thread? = null

    val isPlaying: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "HearEQ-noise").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun run() {
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(
                    max(
                        AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
                        FRAMES * 2 * 4 * 4,
                    )
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            Log.e("HearEQ", "Could not open audio output for noise", e)
            running = false
            return
        }

        // Separate generators per ear so the two sides aren't identical (sounds more natural).
        val left = Channel(0x9E3779B97F4A7C15uL.toLong())
        val right = Channel(0x6A09E667F3BCC909L)
        val buf = FloatArray(FRAMES * 2)
        var gl = 0f
        var gr = 0f

        try {
            track.play()
            while (running) {
                val t = type
                val tl = leftGain
                val tr = rightGain
                for (i in 0 until FRAMES) {
                    // Glide towards the target level so changes never click.
                    gl += (tl - gl) * SMOOTH
                    gr += (tr - gr) * SMOOTH
                    buf[2 * i] = (left.next(t) * gl).coerceIn(-1f, 1f)
                    buf[2 * i + 1] = (right.next(t) * gr).coerceIn(-1f, 1f)
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
            // Fade out instead of cutting off.
            val t = type
            for (block in 0 until 8) {
                for (i in 0 until FRAMES) {
                    gl *= 0.999f
                    gr *= 0.999f
                    buf[2 * i] = left.next(t) * gl
                    buf[2 * i + 1] = right.next(t) * gr
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Throwable) {
            Log.e("HearEQ", "Noise playback stopped", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            running = false
        }
    }

    /** One ear's worth of noise generators, each normalised to the same loudness. */
    private class Channel(seed: Long) {
        private var s = if (seed == 0L) 1L else seed

        // pink (Paul Kellet's filter)
        private var p0 = 0f; private var p1 = 0f; private var p2 = 0f; private var p3 = 0f
        private var p4 = 0f; private var p5 = 0f; private var p6 = 0f
        private var brown = 0f
        private var lastPink = 0f
        private var lastWhite = 0f

        private val greyLow = Biquad.lowShelf(150.0, 10.0)
        private val greyHigh = Biquad.highShelf(7000.0, 6.0)
        private val greenBand = Biquad.bandPass(500.0, 0.5)

        private var swellPhase = 0.0
        private val swellStep = 2 * PI / (RATE * (8.0 + (seed and 0xF) / 8.0)) // ~8–10 s per wave

        private val norm = FloatArray(Noise.NAMES.size) { 1f }

        init {
            // Measure each colour for half a second and scale it to a common loudness.
            for (t in norm.indices) {
                var sum = 0.0
                val n = RATE / 2
                repeat(n) { val v = raw(t); sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                norm[t] = if (rms > 1e-6f) TARGET_RMS / rms else 1f
            }
        }

        fun next(type: Int): Float {
            val t = type.coerceIn(0, norm.size - 1)
            var v = raw(t) * norm[t]
            if (t == Noise.OCEAN) {
                swellPhase += swellStep
                if (swellPhase > 2 * PI) swellPhase -= 2 * PI
                val swell = 0.5 - 0.5 * cos(swellPhase)
                v *= (0.15 + 0.85 * swell.pow(1.5)).toFloat() * 1.6f
            }
            return v
        }

        private fun raw(type: Int): Float = when (type) {
            Noise.WHITE -> white()
            Noise.PINK -> pink()
            Noise.BROWN -> brownNoise()
            Noise.BLUE -> { val p = pink(); val d = p - lastPink; lastPink = p; d }
            Noise.VIOLET -> { val w = white(); val d = w - lastWhite; lastWhite = w; d }
            Noise.GREY -> greyHigh.process(greyLow.process(pink()))
            Noise.GREEN -> greenBand.process(pink())
            Noise.OCEAN -> 0.6f * pink() + 0.4f * brownNoise()
            else -> pink()
        }

        /** Uniform white noise in [-1, 1] (xorshift: fast and good enough for audio). */
        private fun white(): Float {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            return ((s ushr 40).toFloat() / 16777216f) * 2f - 1f
        }

        private fun pink(): Float {
            val w = white()
            p0 = 0.99886f * p0 + w * 0.0555179f
            p1 = 0.99332f * p1 + w * 0.0750759f
            p2 = 0.96900f * p2 + w * 0.1538520f
            p3 = 0.86650f * p3 + w * 0.3104856f
            p4 = 0.55000f * p4 + w * 0.5329522f
            p5 = -0.7616f * p5 - w * 0.0168980f
            val out = p0 + p1 + p2 + p3 + p4 + p5 + p6 + w * 0.5362f
            p6 = w * 0.115926f
            return out
        }

        private fun brownNoise(): Float {
            brown = (brown + 0.02f * white()) / 1.02f
            return brown
        }
    }

    /** Standard "Audio EQ Cookbook" filters, used to shape grey and green noise. */
    private class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun lowShelf(f0: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / RATE
                val c = cos(w); val alpha = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                    (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
                )
            }

            fun highShelf(f0: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / RATE
                val c = cos(w); val alpha = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                    (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
                )
            }

            fun bandPass(f0: Double, q: Double): Biquad {
                val w = 2 * PI * f0 / RATE; val alpha = sin(w) / (2 * q)
                return make(alpha, 0.0, -alpha, 1 + alpha, -2 * cos(w), 1 - alpha)
            }
        }
    }

    companion object {
        const val RATE = 48000
        private const val FRAMES = 1024
        private const val SMOOTH = 0.0005f       // ~40 ms glide
        private const val TARGET_RMS = 0.2f

        /** Slider 0–100 → loudness. 0 is silent; 100 is full scale; each step is 0.6 dB. */
        fun levelToGain(level: Int): Float =
            if (level <= 0) 0f else 10f.pow((level.coerceAtMost(100) - 100) * 0.6f / 20f)
    }
}
