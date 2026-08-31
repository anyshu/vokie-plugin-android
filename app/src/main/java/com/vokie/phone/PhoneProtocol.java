package com.vokie.phone;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PhoneProtocol {
    static final int AUDIO_HEADER_BYTES = 16;
    private static final short AUDIO_MAGIC = (short) 0x5041;

    static byte[] config(String platform) {
        String json = "{\"v\":1,\"device\":\"vokie-phone\",\"platform\":\"" +
                platform + "\",\"appVersion\":\"0.1.0\",\"audio\":{" +
                "\"codec\":\"pcm16\",\"sampleRate\":16000,\"channels\":1," +
                "\"frameMs\":20}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] pttDown(long sessionId, long sequence, String recordingMode) {
        return json("{\"v\":1,\"type\":\"ptt_down\",\"sessionId\":" +
                unsigned(sessionId) + ",\"seq\":" + unsigned(sequence) +
                ",\"recordingMode\":\"" + recordingMode + "\"}");
    }

    static byte[] pttUp(long sessionId, long sequence, long finalSequence) {
        return json("{\"v\":1,\"type\":\"ptt_up\",\"sessionId\":" +
                unsigned(sessionId) + ",\"seq\":" + unsigned(sequence) +
                ",\"finalSequence\":" + unsigned(finalSequence) + "}");
    }

    static byte[] sendEnter(long sequence) {
        return action("send_enter", sequence);
    }

    static byte[] undoLastOutput(long sequence) {
        return action("undo_last_output", sequence);
    }

    static List<byte[]> audioFragments(
            long sessionId,
            long sequence,
            byte[] pcm,
            int maxNotificationBytes
    ) {
        int payloadBytes = Math.max(1, maxNotificationBytes - AUDIO_HEADER_BYTES);
        int count = (pcm.length + payloadBytes - 1) / payloadBytes;
        if (count > 255) throw new IllegalArgumentException("MTU is too small");
        List<byte[]> fragments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * payloadBytes;
            int length = Math.min(payloadBytes, pcm.length - offset);
            ByteBuffer out = ByteBuffer.allocate(AUDIO_HEADER_BYTES + length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            out.putShort(AUDIO_MAGIC);
            out.put((byte) 1);
            out.put((byte) 0);
            out.putInt((int) sessionId);
            out.putInt((int) sequence);
            out.put((byte) index);
            out.put((byte) count);
            out.putShort((short) length);
            out.put(pcm, offset, length);
            fragments.add(out.array());
        }
        return fragments;
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] action(String type, long sequence) {
        return json("{\"v\":1,\"type\":\"" + type +
                "\",\"sessionId\":0,\"seq\":" + unsigned(sequence) + "}");
    }

    private static long unsigned(long value) {
        return value & 0xffffffffL;
    }

    private PhoneProtocol() {}
}
