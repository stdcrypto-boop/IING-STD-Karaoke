package com.iingstd.karaoke

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimingTest {
    @Test fun offsetAtZero() {
        assertEquals(0L, AudioTiming.backingOffsetFrames(0L, 48000, 480000L))
    }

    @Test fun negativeOffsetClampsToZero() {
        assertEquals(0L, AudioTiming.backingOffsetFrames(-100L, 48000, 480000L))
    }

    @Test fun millisecondOffset() {
        assertEquals(24000L, AudioTiming.backingOffsetFrames(500L, 48000, 480000L))
    }

    @Test fun tinyAudioAllowsSubMillisecondStart() {
        assertEquals(0L, AudioTiming.backingOffsetFrames(0L, 48000, 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun offsetBeyondBackingRejected() {
        AudioTiming.backingOffsetFrames(1000L, 48000, 48000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hugeOffsetRejectedWithoutOverflow() {
        AudioTiming.backingOffsetFrames(Long.MAX_VALUE, 48000, 48000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nearLongMaxOffsetCannotWrapIntoValidRange() {
        AudioTiming.backingOffsetFrames(Long.MAX_VALUE / 1000L * 1000L + 999L, 1000, Long.MAX_VALUE)
    }

    @Test fun offsetNearLongMaxWithoutOverflow() {
        assertEquals(Long.MAX_VALUE - 2L,
            AudioTiming.backingOffsetFrames(Long.MAX_VALUE - 2L, 1000, Long.MAX_VALUE))
    }

    @Test fun sameRateNeedsSameNumberOfFrames() {
        assertEquals(48000L, AudioTiming.requiredBackingFrames(48000L, 48000, 48000))
    }

    @Test fun resamplingRoundsUpRequiredFrames() {
        assertEquals(2L, AudioTiming.requiredBackingFrames(1L, 44100, 48000))
    }

    @Test fun downsamplingRoundsUpRequiredFrames() {
        assertEquals(1L, AudioTiming.requiredBackingFrames(1L, 48000, 44100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSampleRateRejected() {
        AudioTiming.requiredBackingFrames(100L, 0, 48000)
    }
}
