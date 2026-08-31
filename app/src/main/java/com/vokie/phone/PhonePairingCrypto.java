package com.vokie.phone;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class PhonePairingCrypto {
    static final class ClientKeys {
        final KeyPair keyPair;
        final String publicKeyBase64;

        ClientKeys(KeyPair keyPair) {
            this.keyPair = keyPair;
            publicKeyBase64 = Base64.getEncoder().encodeToString(
                    keyPair.getPublic().getEncoded());
        }
    }

    private PhonePairingCrypto() { }

    static ClientKeys createClientKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new ClientKeys(generator.generateKeyPair());
    }

    static byte[] deriveSharedSecret(ClientKeys keys, String serverPublicKey) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(serverPublicKey)));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.keyPair.getPrivate());
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    static byte[] context(
            String instanceId,
            String deviceId,
            String pairingId,
            String clientPublicKey,
            String serverPublicKey) {
        return String.join("\0",
                "vokie-phone-v2",
                instanceId,
                deviceId,
                pairingId,
                clientPublicKey,
                serverPublicKey).getBytes(StandardCharsets.UTF_8);
    }

    static String pairingCode(byte[] secret, byte[] context) throws Exception {
        byte[] digest = hmac(secret, "vokie-pair-code-v2\0", context);
        long value = Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt());
        return String.format(java.util.Locale.US, "%06d", value % 1_000_000L);
    }

    static String pairingReadyProof(byte[] secret, byte[] context) throws Exception {
        return Base64.getEncoder().encodeToString(
                hmac(secret, "vokie-pair-ready-v2\0", context));
    }

    static String authProof(byte[] token, byte[] context) throws Exception {
        return Base64.getEncoder().encodeToString(hmac(token, "vokie-auth-v2\0", context));
    }

    static String authOkProof(byte[] token, byte[] context) throws Exception {
        return Base64.getEncoder().encodeToString(
                hmac(token, "vokie-auth-ok-v2\0", context));
    }

    static byte[] deviceToken(byte[] secret, String pairingId, byte[] context) throws Exception {
        return hkdf(secret,
                pairingId.getBytes(StandardCharsets.UTF_8),
                concat("vokie-device-token-v2\0".getBytes(StandardCharsets.UTF_8), context),
                32);
    }

    static boolean proofMatches(String expectedBase64, String actualBase64) {
        try {
            return MessageDigest.isEqual(
                    Base64.getDecoder().decode(expectedBase64),
                    Base64.getDecoder().decode(actualBase64));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static byte[] hmac(byte[] key, String prefix, byte[] context) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(prefix.getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(context);
    }

    private static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(input);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int round = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) round++);
            previous = mac.doFinal();
            int copied = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, copied);
            offset += copied;
        }
        return output;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
