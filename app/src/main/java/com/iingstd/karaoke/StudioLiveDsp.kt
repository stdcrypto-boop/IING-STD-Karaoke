package com.iingstd.karaoke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign

/**
 * Stateful mono PCM processor for the upcoming Studio LIVE capture pipeline.
 * No Android dependencies, allocations or blocking calls in process().
 * IMPORTANT: the same processed samples must be sent to AudioTrack and WAV.
 * Do not run this on top of the F999 NEO direct-monitor path.
 */
class StudioLiveDsp(private val sampleRate: Int = 48000) {
    @Volatile var bassDb = 0f
    @Volatile var midDb = 0f
    @Volatile var trebleDb = 0f
    @Volatile var reverbMix = 0f
    @Volatile var compressorAmount = 0f
    @Volatile var outputGain = 1f

    private val bassPole = exp(-2.0 * PI * 220.0 / sampleRate).toFloat()
    private val treblePole = exp(-2.0 * PI * 3200.0 / sampleRate).toFloat()
    private var bassState = 0f
    private var trebleState = 0f
    // Short, bounded ambience. Allocated once, never inside the audio loop.
    private val delay = FloatArray((sampleRate * 0.085f).toInt().coerceAtLeast(1))
    private var delayIndex = 0
    private var envelope = 0f

    fun reset() {
        bassState = 0f
        trebleState = 0f
        envelope = 0f
        delay.fill(0f)
        delayIndex = 0
    }

    /**
     * Process input in place. Setters may be called from Compose/UI thread.
     * Keep all parameters within safe bounds; a soft limiter avoids hard clipping.
     */
    fun process(samples: ShortArray, length: Int) {
        val lowGain = 10.0.pow((bassDb.coerceIn(-12f, 12f) / 20.0).toDouble()).toFloat()
        val middleGain = 10.0.pow((midDb.coerceIn(-12f, 12f) / 20.0).toDouble()).toFloat()
        val highGain = 10.0.pow((trebleDb.coerceIn(-12f, 12f) / 20.0).toDouble()).toFloat()
        val wet = reverbMix.coerceIn(0f, 0.35f)
        val compression = compressorAmount.coerceIn(0f, 1f)
        val gain = outputGain.coerceIn(0f, 2f)
        for (i in 0 until length.coerceAtMost(samples.size)) {
            val input = samples[i] / 32768f
            bassState = bassPole * bassState + (1f - bassPole) * input
            trebleState = treblePole * trebleState + (1f - treblePole) * input
            val low = bassState
            val high = input - trebleState
            val mid = input - low - high
            var value = (low * lowGain + mid * middleGain + high * highGain) * gain
            // Envelope follower, mild static compression above -15 dBFS.
            envelope += (abs(value) - envelope) * if (abs(value) > envelope) 0.08f else 0.002f
            if (envelope > 0.18f && compression > 0f) {
                val reduction = 1f / (1f + (envelope - 0.18f) * compression * 2.5f)
                value *= reduction
            }
            val delayed = delay[delayIndex]
            delay[delayIndex] = (value + delayed * 0.18f).coerceIn(-1f, 1f)
            delayIndex = (delayIndex + 1) % delay.size
            value = value * (1f - wet) + delayed * wet
            // Smooth saturation rather than integer wraparound.
            value /= 1f + abs(value) * 0.12f
            samples[i] = (value.coerceIn(-0.999f, 0.999f) * 32767f).toInt().toShort()
        }
    }
}
