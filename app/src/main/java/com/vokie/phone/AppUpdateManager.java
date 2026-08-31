package com.vokie.phone;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppUpdateManager {
    interface Listener {
        void onUpdateAvailable(AppUpdateInfo update);
        void onUpToDate();
        void onCheckFailed();
        void onDownloadStarted(AppUpdateInfo update);
        void onDownloadFailed();
    }

    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final String APK_MIME_TYPE =
            "application/vnd.android.package-archive";

    private final Activity activity;
    private final DownloadManager downloads;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private PendingDownload pendingDownload;
    private Uri pendingInstallUri;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            PendingDownload pending = pendingDownload;
            if (pending == null || pending.id != downloadId) return;
            verifyDownloadedApk(pending);
        }
    };

    AppUpdateManager(Activity activity) {
        this.activity = activity;
        downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
    }

    void check(Listener listener) {
        if (!checking.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                AppUpdateInfo update = requestManifest();
                long currentVersion = currentVersionCode(activity);
                mainHandler.post(() -> {
                    checking.set(false);
                    if (update.isNewerThan(currentVersion)) listener.onUpdateAvailable(update);
                    else listener.onUpToDate();
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    checking.set(false);
                    listener.onCheckFailed();
                });
            }
        });
    }

    void download(AppUpdateInfo update, Listener listener) {
        if (pendingDownload != null) return;
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null || downloads == null) {
            listener.onDownloadFailed();
            return;
        }
        File apk = new File(directory, update.apkFileName());
        if (apk.exists() && !apk.delete()) {
            listener.onDownloadFailed();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.downloadUrl))
                    .setTitle("Vokie Phone v" + update.versionName)
                    .setDescription("正在下载更新")
                    .setMimeType(APK_MIME_TYPE)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                            activity, Environment.DIRECTORY_DOWNLOADS, update.apkFileName());
            long id = downloads.enqueue(request);
            pendingDownload = new PendingDownload(id, update, apk, listener);
            listener.onDownloadStarted(update);
        } catch (RuntimeException error) {
            listener.onDownloadFailed();
        }
    }

    void resumePendingInstall() {
        Uri uri = pendingInstallUri;
        if (uri == null || !canInstallPackages()) return;
        pendingInstallUri = null;
        openInstaller(uri);
    }

    void destroy() {
        if (receiverRegistered) {
            activity.unregisterReceiver(downloadReceiver);
            receiverRegistered = false;
        }
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0;
        }
    }

    static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private AppUpdateInfo requestManifest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection();
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

    private String readLimited(InputStream stream) throws IOException {
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

    private void verifyDownloadedApk(PendingDownload pending) {
        executor.execute(() -> {
            try {
                Uri uri = downloads.getUriForDownloadedFile(pending.id);
                if (uri == null || !pending.apk.isFile()) {
                    throw new IOException("Downloaded APK is unavailable");
                }
                verifyHash(pending.apk, pending.update.sha256);
                verifyPackage(pending.apk, pending.update.versionCode);
                mainHandler.post(() -> {
                    pendingDownload = null;
                    requestInstall(uri);
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    pendingDownload = null;
                    pending.listener.onDownloadFailed();
                });
            }
        });
    }

    private void verifyHash(File apk, String expectedHash) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format("%02x", value));
        if (!expectedHash.contentEquals(actual)) throw new IOException("APK hash mismatch");
    }

    private void verifyPackage(File apk, long expectedVersionCode) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo downloaded = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
        if (downloaded == null || !activity.getPackageName().equals(downloaded.packageName)) {
            throw new IOException("APK package mismatch");
        }
        long downloadedVersion = Build.VERSION.SDK_INT >= 28
                ? downloaded.getLongVersionCode() : downloaded.versionCode;
        if (downloadedVersion != expectedVersionCode) {
            throw new IOException("APK version mismatch");
        }
        if (!sameSigners(signatures(installed), signatures(downloaded))) {
            throw new IOException("APK signer mismatch");
        }
    }

    private Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return new Signature[0];
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private boolean sameSigners(Signature[] left, Signature[] right) {
        if (left.length == 0 || left.length != right.length) return false;
        Signature[] leftCopy = left.clone();
        Signature[] rightCopy = right.clone();
        Arrays.sort(leftCopy, (first, second) -> first.toCharsString()
                .compareTo(second.toCharsString()));
        Arrays.sort(rightCopy, (first, second) -> first.toCharsString()
                .compareTo(second.toCharsString()));
        return Arrays.equals(leftCopy, rightCopy);
    }

    private void requestInstall(Uri uri) {
        if (!canInstallPackages()) {
            pendingInstallUri = uri;
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }
        openInstaller(uri);
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 ||
                activity.getPackageManager().canRequestPackageInstalls();
    }

    private void openInstaller(Uri uri) {
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, APK_MIME_TYPE);
        install.setClipData(ClipData.newRawUri("Vokie Phone update", uri));
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
    }

    private static final class PendingDownload {
        final long id;
        final AppUpdateInfo update;
        final File apk;
        final Listener listener;

        PendingDownload(
                long id,
                AppUpdateInfo update,
                File apk,
                Listener listener) {
            this.id = id;
            this.update = update;
            this.apk = apk;
            this.listener = listener;
        }
    }
}
