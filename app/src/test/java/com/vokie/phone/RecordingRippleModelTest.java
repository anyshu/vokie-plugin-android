package com.vokie.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecordingRippleModelTest {
    @Test
    public void createsThreeStaggeredWaterWaves() {
        RecordingRippleModel.Wave[] waves = RecordingRippleModel.waves(
                500, 0.5f, 100, 140);

        assertEquals(3, waves.length);
        assertTrue(waves[0].radius != waves[1].radius);
        assertTrue(waves[1].radius != waves[2].radius);
        for (RecordingRippleModel.Wave wave : waves) {
            assertTrue(wave.radius >= 100);
            assertTrue(wave.radius <= 140);
            assertTrue(wave.strength >= 0);
        }
    }

    @Test
    public void audioChangesStrengthWithoutChangingTheExpansionPath() {
        RecordingRippleModel.Wave quiet = RecordingRippleModel.waves(
                350, 0, 100, 140)[0];
        RecordingRippleModel.Wave loud = RecordingRippleModel.waves(
                350, 1, 100, 140)[0];

        assertEquals(quiet.radius, loud.radius, 0.001f);
        assertTrue(loud.strength > quiet.strength);
    }

    @Test
    public void advancesAtASlowStableRate() {
        RecordingRippleModel.Wave start = RecordingRippleModel.waves(
                0, 0.5f, 100, 140)[0];
        RecordingRippleModel.Wave afterOneSecond = RecordingRippleModel.waves(
                1_000, 0.5f, 100, 140)[0];

        assertEquals(100, start.radius, 0.001f);
        assertTrue(afterOneSecond.radius > start.radius);
        assertTrue(afterOneSecond.radius < 112);
    }

    @Test
    public void louderAudioIncreasesSpeedWithoutJumpingTheTimeline() {
        float quietTime = RecordingRippleModel.advanceTime(800, 1_000, 0);
        float loudTime = RecordingRippleModel.advanceTime(800, 1_000, 1);

        assertEquals(1_800, quietTime, 0.001f);
        assertEquals(2_020, loudTime, 0.001f);
        assertTrue(loudTime > quietTime);
    }
}
