package com.vokie.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class PhoneProtocolTest {
    @Test
    public void createsSendAndUndoControls() {
        assertEquals(
                "{\"v\":1,\"type\":\"send_enter\",\"sessionId\":0,\"seq\":7}",
                new String(PhoneProtocol.sendEnter(7), StandardCharsets.UTF_8));
        assertEquals(
                "{\"v\":1,\"type\":\"undo_last_output\",\"sessionId\":0,\"seq\":8}",
                new String(PhoneProtocol.undoLastOutput(8), StandardCharsets.UTF_8));
    }

    @Test
    public void includesRecordingModeWhenStartingAudio() {
        assertEquals(
                "{\"v\":1,\"type\":\"ptt_down\",\"sessionId\":12,\"seq\":3," +
                        "\"recordingMode\":\"handsfree\"}",
                new String(PhoneProtocol.pttDown(12, 3, "handsfree"),
                        StandardCharsets.UTF_8));
    }
}
