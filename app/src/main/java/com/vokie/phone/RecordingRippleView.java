package com.vokie.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

final class RecordingRippleView extends View {
    private static final int INNER_COLOR = Color.rgb(68, 137, 236);
    private static final int OUTER_COLOR = Color.rgb(174, 211, 255);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean recording;
    private float targetAudioLevel;
    private float displayedAudioLevel;
    private float rippleTimeMs;
    private long lastFrameAt;

    RecordingRippleView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setRecording(boolean recording) {
        if (this.recording == recording) return;
        this.recording = recording;
        targetAudioLevel = 0;
        displayedAudioLevel = 0;
        rippleTimeMs = 0;
        lastFrameAt = SystemClock.uptimeMillis();
        setVisibility(recording ? VISIBLE : INVISIBLE);
        if (recording) postInvalidateOnAnimation();
    }

    void setAudioLevel(float audioLevel) {
        targetAudioLevel = Math.max(0, Math.min(1, audioLevel));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!recording) return;

        long now = SystemClock.uptimeMillis();
        long frameDeltaMs = Math.min(50, Math.max(0, now - lastFrameAt));
        updateDisplayedAudioLevel(frameDeltaMs);
        rippleTimeMs = RecordingRippleModel.advanceTime(
                rippleTimeMs, frameDeltaMs, displayedAudioLevel);
        lastFrameAt = now;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float innerRadius = Math.min(getWidth(), getHeight()) * 0.35f;
        float outerRadius = Math.min(getWidth(), getHeight()) / 2f - dp(10);
        RecordingRippleModel.Wave[] waves = RecordingRippleModel.waves(
                Math.round(rippleTimeMs),
                displayedAudioLevel,
                innerRadius,
                outerRadius);
        for (RecordingRippleModel.Wave wave : waves) drawWave(canvas, centerX, centerY, wave);
        postInvalidateOnAnimation();
    }

    private void updateDisplayedAudioLevel(long frameDeltaMs) {
        float elapsedSeconds = frameDeltaMs / 1_000f;
        float rate = targetAudioLevel > displayedAudioLevel ? 1.8f : 0.55f;
        float blend = 1f - (float) Math.exp(-rate * elapsedSeconds);
        displayedAudioLevel += (targetAudioLevel - displayedAudioLevel) * blend;
    }

    private void drawWave(
            Canvas canvas,
            float centerX,
            float centerY,
            RecordingRippleModel.Wave wave) {
        int color = blendColor(INNER_COLOR, OUTER_COLOR, wave.phase);
        drawStroke(canvas, centerX, centerY, wave.radius, color,
                wave.strength * (0.13f + wave.phase * 0.18f), dp(11));
        drawStroke(canvas, centerX, centerY, wave.radius, color,
                wave.strength * 0.34f, dp(5));
        drawStroke(canvas, centerX, centerY, wave.radius, color,
                wave.strength * (0.92f - wave.phase * 0.54f), dp(1.4f));
    }

    private void drawStroke(
            Canvas canvas,
            float centerX,
            float centerY,
            float radius,
            int color,
            float alpha,
            float width) {
        paint.setColor(color);
        paint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
        paint.setStrokeWidth(width);
        canvas.drawCircle(centerX, centerY, radius, paint);
    }

    private int blendColor(int from, int to, float amount) {
        int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int green = Math.round(
                Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int blue = Math.round(
                Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.rgb(red, green, blue);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
