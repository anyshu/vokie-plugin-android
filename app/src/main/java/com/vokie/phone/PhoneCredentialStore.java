package com.vokie.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PhoneCredentialStore {
    private static final String KEY_ALIAS = "vokie_phone_pairing_v2";
    private static final String PREFS = "vokie_phone_credentials_v2";
    private static final String SELECTED_INSTANCE = "selected_instance";
    private static final String RECORDING_MODE = "recording_mode";
    private static final String LAST_INSTANCE = "last_instance";
    private static final String LAST_HOSTS = "last_hosts";
    private static final String LAST_PORT = "last_port";
    private static final String LAST_NAME = "last_name";
    private final SharedPreferences preferences;

    PhoneCredentialStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    byte[] getToken(String instanceId) {
        String encoded = preferences.getString(tokenKey(instanceId), null);
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("invalid token");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(instanceId.getBytes(StandardCharsets.UTF_8));
            byte[] token = cipher.doFinal(ciphertext);
            return token.length == 32 ? token : null;
        } catch (Exception error) {
            removeToken(instanceId);
            return null;
        }
    }

    boolean hasToken(String instanceId) {
        return getToken(instanceId) != null;
    }

    void saveToken(String instanceId, String pcName, byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Android Keystore requires it to generate the GCM nonce on affected devices.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(instanceId.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(token);
        byte[] iv = cipher.getIV();
        preferences.edit()
                .putString(tokenKey(instanceId), Base64.getEncoder().encodeToString(iv) + "." +
                        Base64.getEncoder().encodeToString(ciphertext))
                .putString(nameKey(instanceId), pcName)
                .putString(SELECTED_INSTANCE, instanceId)
                .apply();
    }

    void removeToken(String instanceId) {
        preferences.edit().remove(tokenKey(instanceId)).remove(nameKey(instanceId)).apply();
    }

    String getSelectedInstanceId() {
        return preferences.getString(SELECTED_INSTANCE, "");
    }

    void setSelectedInstanceId(String instanceId) {
        preferences.edit().putString(SELECTED_INSTANCE, instanceId).apply();
    }

    void clearSelectedInstanceId() {
        preferences.edit().remove(SELECTED_INSTANCE).apply();
    }

    // 记录最近一次成功连接的电脑（instanceId + 地址 + 端口 + 名称），
    // 用于冷启动（进程被杀后重新打开）时免扫码自动重连。
    void saveLastDevice(String instanceId, String hosts, int port, String name) {
        preferences.edit()
                .putString(LAST_INSTANCE, instanceId)
                .putString(LAST_HOSTS, hosts)
                .putInt(LAST_PORT, port)
                .putString(LAST_NAME, name == null ? "" : name)
                .apply();
    }

    void clearLastDevice(String instanceId) {
        if (!instanceId.equals(preferences.getString(LAST_INSTANCE, ""))) return;
        preferences.edit()
                .remove(LAST_INSTANCE)
                .remove(LAST_HOSTS)
                .remove(LAST_PORT)
                .remove(LAST_NAME)
                .apply();
    }

    LastDevice getLastDevice() {
        String instanceId = preferences.getString(LAST_INSTANCE, "");
        String hosts = preferences.getString(LAST_HOSTS, "");
        int port = preferences.getInt(LAST_PORT, 0);
        if (instanceId.isEmpty() || hosts.isEmpty() || port <= 0) return null;
        return new LastDevice(instanceId, hosts, port,
                preferences.getString(LAST_NAME, ""));
    }

    static final class LastDevice {
        final String instanceId;
        final String hosts;
        final int port;
        final String name;

        LastDevice(String instanceId, String hosts, int port, String name) {
            this.instanceId = instanceId;
            this.hosts = hosts;
            this.port = port;
            this.name = name;
        }
    }

    String getRecordingMode() {
        String mode = preferences.getString(RECORDING_MODE, "ptt");
        return "handsfree".equals(mode) || "long".equals(mode) ? mode : "ptt";
    }

    void setRecordingMode(String mode) {
        preferences.edit().putString(RECORDING_MODE, mode).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private String tokenKey(String instanceId) { return "token_" + instanceId; }
    private String nameKey(String instanceId) { return "name_" + instanceId; }
}
