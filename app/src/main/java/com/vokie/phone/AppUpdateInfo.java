package com.vokie.phone;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class AppUpdateInfo {
    final long versionCode;
    final String versionName;
    final String downloadUrl;
    final String sha256;
    final boolean forceUpdate;
    final String releaseNotes;

    private AppUpdateInfo(
            long versionCode,
            String versionName,
            String downloadUrl,
            String sha256,
            boolean forceUpdate,
            String releaseNotes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.forceUpdate = forceUpdate;
        this.releaseNotes = releaseNotes;
    }

    static AppUpdateInfo fromJson(String raw) throws JSONException {
        JSONObject value = new JSONObject(raw);
        long versionCode = value.getLong("versionCode");
        String versionName = value.getString("versionName").trim();
        String downloadUrl = value.getString("downloadUrl").trim();
        String sha256 = value.getString("sha256").trim().toLowerCase(Locale.ROOT);
        if (versionCode <= 0 || versionName.isEmpty()) {
            throw new JSONException("Invalid app version");
        }
        if (!isHttpsUrl(downloadUrl)) {
            throw new JSONException("Update URL must use HTTPS");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new JSONException("Invalid APK SHA-256");
        }
        return new AppUpdateInfo(
                versionCode,
                versionName,
                downloadUrl,
                sha256,
                value.optBoolean("forceUpdate", false),
                value.optString("releaseNotes", "").trim());
    }

    boolean isNewerThan(long currentVersionCode) {
        return versionCode > currentVersionCode;
    }

    String apkFileName() {
        String safeVersion = versionName.replaceAll("[^0-9A-Za-z._-]", "_");
        return "vokiephone-v" + safeVersion + ".apk";
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
