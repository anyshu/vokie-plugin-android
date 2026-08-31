package com.vokie.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PhonePairingCryptoTest {
    @Test
    public void matchesDesktopProtocolVectors() throws Exception {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        byte[] context = "vector-context".getBytes(StandardCharsets.UTF_8);

        assertEquals("778270", PhonePairingCrypto.pairingCode(secret, context));
        byte[] token = PhonePairingCrypto.deviceToken(secret, "pairing-id", context);
        assertEquals(
                "7e4a0f487f94e24ffbefde124ecd43c78177b9ccf3b7a568451b07c6bea8baa3",
                hex(token));
        assertEquals(
                "6MhfQFvtSgOKpRTbX0j2GkXn+Ybsu1iOde/fHC3YYCM=",
                PhonePairingCrypto.authProof(token, context));
        assertEquals(
                "rxW22OHdwuCd29lK/l1Nj+jAEKG5BjrxmNDsP0+aY70=",
                PhonePairingCrypto.authOkProof(token, context));
        assertArrayEquals(token, PhonePairingCrypto.deviceToken(secret, "pairing-id", context));
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
