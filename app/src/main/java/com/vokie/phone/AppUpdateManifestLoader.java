package com.vokie.phone;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AppUpdateManifestLoader {
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private AppUpdateManifestLoader() { }

    static AppUpdateInfo load(String primaryUrl, String backupUrl) throws Exception {
        Exception lastError = null;
        String[] urls = {primaryUrl, backupUrl};
        for (String manifestUrl : urls) {
            if (manifestUrl == null || manifestUrl.trim().isEmpty()) continue;
            try {
                return request(manifestUrl.trim());
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("No update manifest URL configured");
    }

    private static AppUpdateInfo request(String manifestUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(manifestUrl).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Update manifest HTTP " + status);
            }
            return AppUpdateInfo.fromJson(readLimited(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        try (InputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_MANIFEST_BYTES) {
                    throw new IOException("Update manifest is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
