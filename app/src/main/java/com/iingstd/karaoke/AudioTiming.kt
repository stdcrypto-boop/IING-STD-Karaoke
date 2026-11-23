package com.iingstd.karaoke

/** Pure frame calculations used by the offline audio mixer. */
internal object AudioTiming {
    fun backingOffsetFrames(startMs: Long, sampleRate: Int, totalFrames: Long): Long {
        require(sampleRate > 0 && totalFrames > 0) { "Sample rate atau durasi musik tidak valid" }
        val ms = startMs.coerceAtLeast(0L)
        // Comparing quotient/remainder avoids overflow even for Long.MAX_VALUE.
        val seconds = ms / 1000L
        val milliseconds = ms % 1000L
        require(seconds <= totalFrames / sampleRate) {
            "Posisi awal melewati durasi musik"
        }
        val wholeFrames = seconds * sampleRate
        val fractionalFrames = milliseconds * sampleRate.toLong() / 1000L
        // Check before addition: a near-Long.MAX_VALUE offset must not wrap negative.
        require(wholeFrames < totalFrames && fractionalFrames < totalFrames - wholeFrames) {
            "Posisi awal melewati durasi musik"
        }
        val offset = wholeFrames + fractionalFrames
        return offset
    }

    fun requiredBackingFrames(voiceFrames: Long, voiceRate: Int, backingRate: Int): Long {
        require(voiceFrames >= 0 && voiceRate > 0 && backingRate > 0) {
            "Durasi atau sample rate audio tidak valid"
        }
        val wholeSeconds = voiceFrames / voiceRate
        val remainder = voiceFrames % voiceRate
        // The remainder product fits Long (both rates are positive Ints).
        // Round up without adding voiceRate - 1, which could overflow at bounds.
        val numerator = remainder * backingRate.toLong()
        val fractional = numerator / voiceRate +
            if (numerator % voiceRate != 0L) 1L else 0L
        require(wholeSeconds <= (Long.MAX_VALUE - fractional) / backingRate) {
            "Durasi audio terlalu panjang"
        }
        return wholeSeconds * backingRate + fractional
    }
}
