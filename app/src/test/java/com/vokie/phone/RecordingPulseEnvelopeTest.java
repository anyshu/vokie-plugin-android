package com.vokie.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecordingPulseEnvelopeTest {
    @Test
    public void keepsAVisibleBreathWhenInputIsSilent() {
        RecordingPulseEnvelope envelope = new RecordingPulseEnvelope();

        float level = envelope.update(new short[320]);

        assertEquals(0f, level, 0.001f);
    }

    @Test
    public void reactsStronglyToVoiceAndReleasesSmoothly() {
        RecordingPulseEnvelope envelope = new RecordingPulseEnvelope();
        short[] voice = alternatingSamples((short) 20_000);

        float firstVoiceFrame = envelope.update(voice);
        float secondVoiceFrame = envelope.update(voice);
        float releaseFrame = envelope.update(new short[320]);

        assertTrue(firstVoiceFrame > 0.15f);
        assertTrue(secondVoiceFrame > firstVoiceFrame);
        assertTrue(releaseFrame > firstVoiceFrame);
        assertTrue(secondVoiceFrame - releaseFrame < 0.02f);
    }

    @Test
    public void resetReturnsTheEnvelopeToItsRestingSize() {
        RecordingPulseEnvelope envelope = new RecordingPulseEnvelope();
        envelope.update(alternatingSamples((short) 20_000));

        envelope.reset();
        float level = envelope.update(new short[320]);

        assertEquals(0f, level, 0.001f);
    }

    private short[] alternatingSamples(short amplitude) {
        short[] samples = new short[320];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = index % 2 == 0 ? amplitude : (short) -amplitude;
        }
        return samples;
    }
}
