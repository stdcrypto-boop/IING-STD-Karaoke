package com.iingstd.karaoke

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First building block for Studio LIVE. Stateful, allocation-free PCM processor:
 * one instance per audio stream, never shared between microphone sessions.
 * Parameters can change between buffers while recording.
 *
 * Important: monitoring and WAV must receive the SAME processed buffer when this
 * processor is enabled. Do not combine with direct hardware monitoring.
 */
class LiveVocalEq(private val sampleRate: Int = 48000) {
    data class Settings(val bassDb: Float = 0f, val midDb: Float = 0f, val trebleDb: Float = 0f)
    @Volatile var settings = Settings()
        set(value) {
            field = Settings(value.bassDb.coerceIn(-12f, 12f),
                value.midDb.coerceIn(-12f, 12f), value.trebleDb.coerceIn(-12f, 12f))
        }

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0
        fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0
            this.a1 = a1 / a0; this.a2 = a2 / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        fun reset() { z1 = 0.0; z2 = 0.0 }
    }
    private val bass = Biquad()
    private val mid = Biquad()
    private val treble = Biquad()
    private var applied = Settings(Float.NaN, Float.NaN, Float.NaN)

    private fun coefficients(frequency: Double, db: Float, kind: Int, filter: Biquad) {
        val a = 10.0.pow(db.toDouble() / 40.0)
        val w = 2.0 * PI * frequency / sampleRate
        val cw = cos(w); val sw = sin(w)
        if (kind == 1) {
            val alpha = sw / 2.0 // peak Q = 1
            filter.set(1 + alpha * a, -2 * cw, 1 - alpha * a,
                1 + alpha / a, -2 * cw, 1 - alpha / a)
        } else {
            val alpha = sw / 2.0 * sqrt(2.0) // shelf slope = 1
            val root = 2.0 * sqrt(a) * alpha
            if (kind == 0) {
                filter.set(a * ((a + 1) - (a - 1) * cw + root),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - root),
                    (a + 1) + (a - 1) * cw + root,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - root)
            } else {
                filter.set(a * ((a + 1) + (a - 1) * cw + root),
                    -2 * a * ((a - 1) + (a + 1) * cw),
                    a * ((a + 1) + (a - 1) * cw - root),
                    (a + 1) - (a - 1) * cw + root,
                    2 * ((a - 1) - (a + 1) * cw),
                    (a + 1) - (a - 1) * cw - root)
            }
        }
    }

    fun process(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        val next = settings
        if (next != applied) {
            coefficients(160.0, next.bassDb, 0, bass)
            coefficients(1100.0, next.midDb, 1, mid)
            coefficients(4500.0, next.trebleDb, 2, treble)
            applied = next
        }
        for (i in 0 until count) {
            var x = samples[i].toDouble() / 32768.0
            x = treble.process(mid.process(bass.process(x)))
            // Headroom and soft limiting to prevent EQ boosts wrapping/clipping.
            x *= 0.72
            x = x / (1.0 + kotlin.math.abs(x) * 0.18)
            samples[i] = (x * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun reset() { bass.reset(); mid.reset(); treble.reset() }
}
