package com.vokie.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class PhoneRecordingService extends Service {
    static final String ACTION_STOP_RECORDING =
            "com.vokie.phone.action.STOP_RECORDING";
    static final String ACTION_RECORDING_SERVICE_INTERRUPTED =
            "com.vokie.phone.action.RECORDING_SERVICE_INTERRUPTED";

    private static final String ACTION_START =
            "com.vokie.phone.action.START_RECORDING_SERVICE";
    private static final String ACTION_REQUEST_STOP =
            "com.vokie.phone.action.REQUEST_STOP_RECORDING";
    private static final String EXTRA_DEVICE_NAME = "device_name";
    private static final String EXTRA_RECORDING_MODE = "recording_mode";
    private static final String CHANNEL_ID = "vokie_phone_recording";
    private static final int NOTIFICATION_ID = 2301;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foregroundStarted;
    private boolean stopRequestedByUser;

    static void start(Context context, String deviceName, String recordingMode) {
        Intent intent = new Intent(context, PhoneRecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
                .putExtra(EXTRA_RECORDING_MODE, recordingMode);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, PhoneRecordingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_REQUEST_STOP.equals(action)) {
            stopRequestedByUser = true;
            sendAppBroadcast(ACTION_STOP_RECORDING);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        String recordingMode = intent.getStringExtra(EXTRA_RECORDING_MODE);
        Notification notification = buildNotification(deviceName, recordingMode);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
        acquireRecordingLocks();
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String deviceName, String recordingMode) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, PhoneRecordingService.class)
                .setAction(ACTION_REQUEST_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String modeLabel = "long".equals(recordingMode) ? "长录音" :
                "handsfree".equals(recordingMode) ? "免提录音" : "按住说话";
        String target = deviceName == null || deviceName.isEmpty() ?
                "Vokie" : deviceName;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Vokie 正在录音")
                .setContentText(modeLabel + " · 正在通过 WiFi 传输到 " + target)
                .setContentIntent(openPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "停止录音", stopPendingIntent);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(
                    Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "后台录音",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("录音期间保持麦克风和 WiFi 传输");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void acquireRecordingLocks() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "VokiePhone:recording");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        if (wifiLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VokiePhone:recording");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private void releaseRecordingLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void sendAppBroadcast(String action) {
        sendBroadcast(new Intent(action).setPackage(getPackageName()));
    }

    @Override
    public void onDestroy() {
        releaseRecordingLocks();
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            if (!stopRequestedByUser) {
                sendAppBroadcast(ACTION_RECORDING_SERVICE_INTERRUPTED);
            }
        }
        foregroundStarted = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
