package com.vokie.phone;

final class RecordingRippleModel {
    static final int RIPPLE_COUNT = 3;
    private static final long CYCLE_MS = 3_600L;

    static float advanceTime(float currentMs, long frameDeltaMs, float level) {
        float safeLevel = Math.max(0, Math.min(1, level));
        return currentMs + frameDeltaMs * (1f + safeLevel * 0.22f);
    }

    static Wave[] waves(long elapsedMs, float level, float innerRadius, float outerRadius) {
        float safeLevel = Math.max(0, Math.min(1, level));
        Wave[] result = new Wave[RIPPLE_COUNT];
        for (int index = 0; index < RIPPLE_COUNT; index++) {
            float phase = ((elapsedMs / (float) CYCLE_MS) +
                    index / (float) RIPPLE_COUNT) % 1f;
            float radius = innerRadius + (outerRadius - innerRadius) * phase;
            float fade = (float) Math.pow(1f - phase, 1.55);
            float strength = (0.20f + safeLevel * 0.38f) * fade;
            result[index] = new Wave(radius, phase, strength);
        }
        return result;
    }

    static final class Wave {
        final float radius;
        final float phase;
        final float strength;

        Wave(float radius, float phase, float strength) {
            this.radius = radius;
            this.phase = phase;
            this.strength = strength;
        }
    }
}
