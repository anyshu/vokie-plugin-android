package com.vokie.phone;

final class RecordingPulseEnvelope {
    private static final float ATTACK = 0.18f;
    private static final float RELEASE = 0.045f;
    private float smoothedLevel;

    float update(short[] samples) {
        double sumSquares = 0;
        double peak = 0;
        for (short sample : samples) {
            double normalized = Math.abs(sample / 32768.0);
            sumSquares += normalized * normalized;
            peak = Math.max(peak, normalized);
        }

        double rms = Math.sqrt(sumSquares / samples.length);
        float rmsResponse = normalize(rms, 0.012, 0.11);
        float peakResponse = normalize(peak, 0.04, 0.40);
        float targetLevel = rmsResponse * 0.85f + peakResponse * 0.15f;
        if (targetLevel < 0.08f) targetLevel = 0;
        float blend = targetLevel > smoothedLevel ? ATTACK : RELEASE;
        smoothedLevel += (targetLevel - smoothedLevel) * blend;
        return smoothedLevel;
    }

    void reset() {
        smoothedLevel = 0;
    }

    private float normalize(double value, double floor, double ceiling) {
        return (float) Math.max(0, Math.min(1, (value - floor) / (ceiling - floor)));
    }

}
