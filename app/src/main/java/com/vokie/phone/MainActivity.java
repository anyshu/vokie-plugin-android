package com.vokie.phone;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String AUDIO_TAG = "VokiePhoneAudio";
    private static final int REQUEST_PERMISSIONS = 20;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 21;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320;
    private static final int BLUE = Color.rgb(22, 119, 255);
    private static final int BLUE_DARK = Color.rgb(10, 101, 216);
    private static final int BLUE_PALE = Color.rgb(235, 244, 255);
    private static final int INK = Color.rgb(29, 33, 41);
    private static final int MUTED = Color.rgb(105, 115, 134);
    private static final int SURFACE = Color.rgb(249, 250, 252);
    private static final int ACTION_SURFACE = Color.rgb(238, 240, 244);
    private static final int ACTION_SURFACE_STRONG = Color.rgb(224, 229, 237);
    private static final int GREEN = Color.rgb(24, 160, 88);
    private static final String UPDATE_READY_LABEL = "\u2193  更新";
    private static final String MODE_PTT = "ptt";
    private static final String MODE_HANDSFREE = "handsfree";
    private static final String MODE_LONG = "long";
    private static final PathInterpolator PULSE_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    private TextView statusText;
    private TextView deviceText;
    private TextView recordTitle;
    private TextView recordHint;
    private ImageView microphoneIcon;
    private FrameLayout recordControl;
    private View recordOuterRing;
    private View recordInner;
    private RecordingRippleView recordingRipple;
    private Button openButton;
    private Button retryButton;
    private Button moreButton;
    private Button updateButton;
    private Button sendButton;
    private Button undoButton;
    private WifiDiscovery discovery;
    private WifiPhoneTransport transport;
    private PhoneCredentialStore credentials;
    private AppUpdateManager updateManager;
    private AppUpdateInfo availableUpdate;
    private final List<VokieDevice> devices = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ReconnectBackoff reconnectBackoff = new ReconnectBackoff();
    private final Runnable reconnectRunnable = this::connectSelected;
    private final Runnable rediscoveryRunnable = () -> startDiscovery(false);
    private final Runnable idleDiscoveryRefreshRunnable = this::refreshIdleDiscovery;
    private final Runnable connectedStatusResetRunnable = this::restoreConnectedStatus;
    private VokieDevice selectedDevice;
    private Dialog pairingDialog;
    private AudioRecord audioRecord;
    private Thread recordThread;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private long sessionId;
    private long sequence;
    private long controlSequence;
    private boolean manualDisconnect;
    private String recordingMode = MODE_PTT;
    private long lastPulseAt;
    private final RecordingPulseEnvelope recordingPulseEnvelope =
            new RecordingPulseEnvelope();
    private boolean recordingReceiverRegistered;
    private final BroadcastReceiver recordingActionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PhoneRecordingService.ACTION_STOP_RECORDING.equals(action)) {
                stopRecording(true);
            } else if (PhoneRecordingService.ACTION_RECORDING_SERVICE_INTERRUPTED.equals(action)) {
                stopRecording(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        credentials = new PhoneCredentialStore(this);
        recordingMode = credentials.getRecordingMode();
        registerRecordingActionReceiver();
        buildUi();
        updateManager = new AppUpdateManager(this);
        transport = new WifiPhoneTransport(new WifiPhoneTransport.Listener() {
            @Override public void onState(
                    long connectionId, String message, boolean connected, String pcName) {
                runOnUiThread(() -> {
                    if (!transport.isCurrentConnection(connectionId)) return;
                    if ("电脑已断开连接".equals(message)) manualDisconnect = true;
                    if (!connected && recording.get()) stopRecording(false);
                    dismissPairingDialog();
                    showStatus(message, connected, pcName);
                    setConnected(connected);
                    if (connected) resetReconnectBackoff();
                    else if ("连接已断开".equals(message)) scheduleReconnect();
                });
            }

            @Override public void onPairingCode(
                    long connectionId, String pairingCode, String pcName) {
                runOnUiThread(() -> {
                    if (!transport.isCurrentConnection(connectionId)) return;
                    showPairingDialog(connectionId, pairingCode, pcName);
                });
            }

            @Override public void onCredentialRejected(long connectionId, String instanceId) {
                runOnUiThread(() -> {
                    if (!transport.isCurrentConnection(connectionId)) return;
                    resetReconnectBackoff();
                    mainHandler.postDelayed(reconnectRunnable, 900);
                });
            }

            @Override public void onDeviceForgotten(
                    long connectionId, String instanceId, String pcName) {
                runOnUiThread(() -> {
                    if (!transport.isCurrentConnection(connectionId)) return;
                    manualDisconnect = true;
                    setConnected(false);
                    showStatus("已解除验证", false, pcName);
                });
            }

            @Override public void onRecordingStopped(
                    long connectionId, long stoppedSessionId, String reason) {
                runOnUiThread(() -> {
                    if (!transport.isCurrentConnection(connectionId) ||
                            !recording.get() || stoppedSessionId != sessionId) return;
                    Log.i(AUDIO_TAG, "PC stopped recording session=" + stoppedSessionId);
                    stopRecording(false);
                });
            }
        }, credentials);
        handlePairingIntent(getIntent());
        if (hasPermissions()) {
            requestNotificationPermissionIfNeeded();
        }
        else requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
        mainHandler.postDelayed(() -> checkForUpdates(false), 1_200);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.setBackgroundColor(SURFACE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandLogo = new ImageView(this);
        brandLogo.setImageResource(R.drawable.vokie_logo);
        brandLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams brandLogoParams = new LinearLayout.LayoutParams(
                dp(30), dp(30));
        brandLogoParams.rightMargin = dp(8);
        brand.addView(brandLogo, brandLogoParams);
        TextView title = text("Vokie", 26, INK, Typeface.BOLD);
        brand.addView(title, new LinearLayout.LayoutParams(-2, -2));
        identity.addView(brand, matchWrap());
        statusText = text("●  等待扫码连接", 14, MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(4);
        identity.addView(statusText, statusParams);
        deviceText = text("在电脑 Vokie 设置中打开二维码", 14, MUTED, Typeface.NORMAL);
        deviceText.setSingleLine(true);
        deviceText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams deviceParams = matchWrap();
        deviceParams.topMargin = dp(3);
        identity.addView(deviceText, deviceParams);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));

        updateButton = topUpdateButton();
        updateButton.setVisibility(View.GONE);
        updateButton.setOnClickListener((view) -> downloadAvailableUpdate());
        header.addView(updateButton, new LinearLayout.LayoutParams(dp(82), dp(44)));

        moreButton = overflowButton();
        moreButton.setOnClickListener((view) -> {
            showMoreMenu(view);
        });
        header.addView(moreButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header, matchWrap());

        LinearLayout voiceArea = new LinearLayout(this);
        voiceArea.setOrientation(LinearLayout.VERTICAL);
        voiceArea.setGravity(Gravity.CENTER);
        voiceArea.setClipChildren(false);
        voiceArea.setClipToPadding(false);
        root.addView(voiceArea, new LinearLayout.LayoutParams(-1, 0, 1));

        recordControl = new FrameLayout(this);
        recordControl.setClipChildren(false);
        recordControl.setClipToPadding(false);
        recordingRipple = new RecordingRippleView(this);
        FrameLayout.LayoutParams rippleParams = new FrameLayout.LayoutParams(
                dp(280), dp(280), Gravity.CENTER);
        recordControl.addView(recordingRipple, rippleParams);
        recordOuterRing = new View(this);
        recordOuterRing.setBackground(
                circleStroke(Color.TRANSPARENT, Color.rgb(209, 228, 253), 1));
        recordControl.addView(recordOuterRing, new FrameLayout.LayoutParams(-1, -1));
        recordInner = new View(this);
        recordInner.setBackground(circleStroke(Color.WHITE, BLUE, 3));
        FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(
                -1, -1, Gravity.CENTER);
        innerParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        recordControl.addView(recordInner, innerParams);

        LinearLayout recordContent = new LinearLayout(this);
        recordContent.setOrientation(LinearLayout.VERTICAL);
        recordContent.setGravity(Gravity.CENTER);
        microphoneIcon = new ImageView(this);
        microphoneIcon.setImageResource(android.R.drawable.ic_btn_speak_now);
        microphoneIcon.setImageTintList(ColorStateList.valueOf(BLUE));
        recordContent.addView(microphoneIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        recordTitle = text("按住说话", 20, BLUE, Typeface.BOLD);
        recordTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams recordTitleParams = matchWrap();
        recordTitleParams.topMargin = dp(8);
        recordContent.addView(recordTitle, recordTitleParams);
        recordHint = text("松开发送 · 不在手机保存", 12, MUTED, Typeface.NORMAL);
        recordHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams recordHintParams = matchWrap();
        recordHintParams.topMargin = dp(6);
        recordContent.addView(recordHint, recordHintParams);
        recordControl.addView(recordContent, new FrameLayout.LayoutParams(-1, -1));
        recordControl.setEnabled(false);
        recordControl.setOnTouchListener((view, event) -> {
            if (!transport.isConnected()) return true;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start();
                if (MODE_PTT.equals(recordingMode)) startRecording();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                if (MODE_PTT.equals(recordingMode)) stopRecording(true);
                else toggleRecording();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                if (MODE_PTT.equals(recordingMode)) stopRecording(true);
                return true;
            }
            return true;
        });
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(dp(220), dp(220));
        voiceArea.addView(recordControl, recordParams);

        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);

        undoButton = actionButton("撤回", false, android.R.drawable.ic_menu_revert);
        undoButton.setEnabled(false);
        undoButton.setOnClickListener((view) -> {
            transport.sendControl(PhoneProtocol.undoLastOutput(controlSequence++));
            showTransientConnectedStatus("已撤回");
        });
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        undoParams.rightMargin = dp(6);
        commandRow.addView(undoButton, undoParams);

        sendButton = actionButton("发送", true, android.R.drawable.ic_menu_send);
        sendButton.setEnabled(false);
        sendButton.setOnClickListener((view) -> {
            transport.sendControl(PhoneProtocol.sendEnter(controlSequence++));
            showTransientConnectedStatus("已发送");
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        sendParams.leftMargin = dp(6);
        commandRow.addView(sendButton, sendParams);
        root.addView(commandRow, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);

        openButton = actionButton("唤起 Vokie", false, android.R.drawable.ic_menu_view);
        openButton.setEnabled(false);
        openButton.setOnClickListener((view) -> {
            transport.openVokie();
            showTransientConnectedStatus("已请求唤起 Vokie");
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        openParams.rightMargin = dp(6);
        utilityRow.addView(openButton, openParams);

        retryButton = actionButton("扫描二维码", false, android.R.drawable.ic_menu_camera);
        retryButton.setOnClickListener((view) -> launchQrScanner());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        retryParams.leftMargin = dp(6);
        utilityRow.addView(retryButton, retryParams);
        LinearLayout.LayoutParams utilityParams = new LinearLayout.LayoutParams(-1, dp(44));
        utilityParams.topMargin = dp(8);
        root.addView(utilityRow, utilityParams);

        TextView footer = text(
                "v" + AppUpdateManager.currentVersionName(this),
                12, MUTED, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.topMargin = dp(8);
        root.addView(footer, footerParams);

        setContentView(root);
        setConnected(false);
    }

    private void launchQrScanner() {
        new IntentIntegrator(this)
                .setPrompt("扫描电脑 Vokie 设置中的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    private void handlePairingIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            handlePairingUri(intent.getData().toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            handlePairingUri(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handlePairingUri(String raw) {
        try {
            URI uri = new URI(raw);
            if (!"vokie".equalsIgnoreCase(uri.getScheme()) ||
                    !"pair".equalsIgnoreCase(uri.getHost())) {
                throw new URISyntaxException(raw, "不是 Vokie 配对二维码");
            }
            String instanceId = queryValue(uri.getRawQuery(), "instance_id");
            String host = queryValue(uri.getRawQuery(), "host");
            String hosts = queryValueOrDefault(uri.getRawQuery(), "hosts", host);
            int port = Integer.parseInt(queryValue(uri.getRawQuery(), "port"));
            String name = queryValue(uri.getRawQuery(), "name");
            Log.i("VokiePhonePair", "invite targets=" + hosts + ":" + port + " name=" + name);
            VokieDevice device = VokieDevice.fromInvite(instanceId, hosts, port, name);
            if (device == null) throw new IllegalArgumentException("二维码内容无效");
            devices.clear();
            devices.add(device);
            selectDevice(device);
        } catch (Exception error) {
            Log.e("VokiePhonePair", "invalid pairing QR", error);
            Toast.makeText(this, "二维码无效，请重新扫描", Toast.LENGTH_LONG).show();
        }
    }

    private String queryValue(String query, String key) throws Exception {
        if (query == null) throw new IllegalArgumentException("缺少二维码参数");
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return java.net.URLDecoder.decode(pair[1], "UTF-8");
            }
        }
        throw new IllegalArgumentException("缺少二维码参数");
    }

    private String queryValueOrDefault(String query, String key, String fallback) {
        try {
            return queryValue(query, key);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void startDiscovery() {
        startDiscovery(true);
    }

    private void startDiscovery(boolean resetBackoff) {
        if (!hasPermissions()) return;
        stopRecording(false);
        dismissPairingDialog();
        transport.close();
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(rediscoveryRunnable);
        mainHandler.removeCallbacks(idleDiscoveryRefreshRunnable);
        if (resetBackoff) reconnectBackoff.reset();
        selectedDevice = null;
        devices.clear();
        manualDisconnect = false;
        setConnected(false);
        showStatus("等待扫码连接", false, "在电脑 Vokie 设置中打开二维码");
        scheduleIdleDiscoveryRefresh();
        discovery.start(new WifiDiscovery.Listener() {
            @Override public void onDevices(List<VokieDevice> discovered) {
                runOnUiThread(() -> handleDevices(discovered));
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    showStatus(message, false, "");
                    retryButton.setEnabled(true);
                });
            }
        });
    }

    private void handleDevices(List<VokieDevice> discovered) {
        devices.clear();
        devices.addAll(discovered);
        moreButton.setEnabled(true);
        updateConnectionAction();
        if (devices.isEmpty()) scheduleIdleDiscoveryRefresh();
        else mainHandler.removeCallbacks(idleDiscoveryRefreshRunnable);
        if (manualDisconnect) return;
        if (devices.isEmpty()) {
            mainHandler.removeCallbacks(reconnectRunnable);
            return;
        }
        if (selectedDevice != null && transport.isActive()) return;

        String preferredId = credentials.getSelectedInstanceId();
        for (VokieDevice device : devices) {
            if (device.instanceId.equals(preferredId)) {
                selectDevice(device);
                return;
            }
        }
        List<VokieDevice> trusted = new ArrayList<>();
        for (VokieDevice device : devices) {
            if (credentials.hasToken(device.instanceId)) trusted.add(device);
        }
        if (trusted.size() == 1) {
            selectDevice(trusted.get(0));
        } else if (devices.size() == 1) {
            selectDevice(devices.get(0));
        } else if (devices.size() > 1) {
            showStatus("找到 " + devices.size() + " 台 Vokie", false, "请选择要连接的电脑");
        }
    }

    private void selectDevice(VokieDevice device) {
        manualDisconnect = false;
        selectedDevice = device;
        credentials.setSelectedInstanceId(device.instanceId);
        connectSelected();
    }

    private void connectSelected() {
        VokieDevice device = selectedDevice;
        if (device == null || transport.isActive()) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        showStatus("正在连接", false, device.displayName);
        transport.connect(
                device,
                Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID),
                Build.MANUFACTURER + " " + Build.MODEL);
        updateConnectionAction();
    }

    private void reconnectOrSearch() {
        if (transport.isActive()) return;
        VokieDevice target = findDiscoveredDevice(
                selectedDevice == null ? "" : selectedDevice.instanceId);
        if (target != null) {
            resetReconnectBackoff();
            selectDevice(target);
            return;
        }
        if (devices.size() == 1) {
            resetReconnectBackoff();
            selectDevice(devices.get(0));
            return;
        }
        if (devices.size() > 1) {
            showDevicePicker();
            return;
        }
        launchQrScanner();
    }

    private VokieDevice findDiscoveredDevice(String instanceId) {
        for (VokieDevice device : devices) {
            if (device.instanceId.equals(instanceId)) return device;
        }
        return null;
    }

    private void updateConnectionAction() {
        if (retryButton == null) return;
        boolean connected = transport != null && transport.isConnected();
        boolean connecting = transport != null && transport.isActive();
        retryButton.setText("扫描二维码");
        retryButton.setEnabled(!connected && !connecting);
    }

    private void scheduleReconnect() {
        if (manualDisconnect || selectedDevice == null || devices.isEmpty()) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(rediscoveryRunnable);
        long delayMs = reconnectBackoff.nextDelayMs();
        showStatus("连接失败，稍后重试", false, selectedDevice.displayName);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private void scheduleRediscovery() {
        if (manualDisconnect) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(rediscoveryRunnable);
        long delayMs = reconnectBackoff.nextDelayMs();
        showStatus("连接失败，稍后重新搜索", false,
                selectedDevice == null ? "" : selectedDevice.displayName);
        mainHandler.postDelayed(rediscoveryRunnable, delayMs);
    }

    private void resetReconnectBackoff() {
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(rediscoveryRunnable);
        reconnectBackoff.reset();
    }

    private void scheduleIdleDiscoveryRefresh() {
        mainHandler.removeCallbacks(idleDiscoveryRefreshRunnable);
        if (!manualDisconnect && devices.isEmpty()) {
            mainHandler.postDelayed(idleDiscoveryRefreshRunnable, 30_000);
        }
    }

    private void refreshIdleDiscovery() {
        if (!manualDisconnect && !transport.isConnected() && devices.isEmpty()) {
            startDiscovery(false);
        }
    }

    private void showMoreMenu(View anchor) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setBackground(roundRect(Color.WHITE, dp(8), 0, Color.TRANSPARENT));

        TextView heading = text("更多功能", 13, MUTED, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(36)));

        List<View> menuRows = new ArrayList<>();
        menuRows.add(addMenuAction(panel, dialog,
                android.R.drawable.ic_btn_speak_now,
                "录音模式", "当前：" + recordingModeLabel(),
                !recording.get(), false, this::showRecordingModePicker));
        menuRows.add(addMenuAction(panel, dialog,
                android.R.drawable.ic_popup_sync,
                "检查更新",
                "当前 v" + AppUpdateManager.currentVersionName(this),
                true, false, () -> checkForUpdates(true)));
        panel.addView(menuDivider(), new LinearLayout.LayoutParams(-1, dp(1)));
        menuRows.add(addMenuAction(panel, dialog,
                android.R.drawable.ic_menu_close_clear_cancel,
                "断开当前设备", "保留验证，下次可直接连接",
                transport.isActive(), false, this::disconnectCurrentDevice));
        menuRows.add(addMenuAction(panel, dialog,
                android.R.drawable.ic_menu_delete,
                "解除设备验证", "下次连接需要重新确认",
                transport.isConnected() && selectedDevice != null &&
                        credentials.hasToken(selectedDevice.instanceId),
                true, this::confirmForgetCurrentDevice));

        dialog.setContentView(panel);
        dialog.setOnShowListener(ignored -> {
            for (int index = 0; index < menuRows.size(); index++) {
                animateMenuRow(menuRows.get(index), index * 35L);
            }
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.12f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.getDecorView().setElevation(dp(14));
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.TOP | Gravity.END;
        attributes.width = Math.min(
                dp(320), getResources().getDisplayMetrics().widthPixels - dp(24));
        attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
        attributes.x = dp(12);
        attributes.y = dp(88);
        window.setAttributes(attributes);
    }

    private View addMenuAction(
            LinearLayout panel,
            Dialog dialog,
            int icon,
            String title,
            String subtitle,
            boolean enabled,
            boolean destructive,
            Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setFocusable(enabled);
        row.setAlpha(enabled ? 1f : 0.38f);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(icon);
        iconView.setImageTintList(ColorStateList.valueOf(
                destructive ? Color.rgb(132, 90, 22) : INK));
        iconView.setPadding(dp(9), dp(9), dp(9), dp(9));
        iconView.setBackground(roundRect(
                destructive ? Color.rgb(255, 246, 224) : ACTION_SURFACE,
                dp(8), 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.rightMargin = dp(12);
        row.addView(iconView, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(
                title, 15, destructive ? Color.rgb(132, 90, 22) : INK, Typeface.BOLD);
        titleView.setSingleLine(true);
        labels.addView(titleView, matchWrap());
        TextView subtitleView = text(subtitle, 12, MUTED, Typeface.NORMAL);
        subtitleView.setSingleLine(true);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(2);
        labels.addView(subtitleView, subtitleParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if (enabled) {
            addPressFeedback(row);
            row.setOnClickListener(ignored -> {
                dialog.dismiss();
                action.run();
            });
        }
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(64)));
        return row;
    }

    private View menuDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(235, 237, 241));
        divider.setPadding(dp(58), 0, dp(8), 0);
        return divider;
    }

    private String recordingModeLabel() {
        if (MODE_HANDSFREE.equals(recordingMode)) return "免提模式";
        if (MODE_LONG.equals(recordingMode)) return "长录音模式";
        return "按住说话";
    }

    private void animateMenuRow(View row, long delayMs) {
        float restingAlpha = row.isEnabled() ? 1f : 0.38f;
        row.setAlpha(0f);
        row.setTranslationY(-dp(4));
        row.animate()
                .alpha(restingAlpha)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(170)
                .setInterpolator(PULSE_INTERPOLATOR)
                .start();
    }

    private void showRecordingModePicker() {
        String[] labels = {"按住说话", "免提模式", "长录音模式"};
        String[] modes = {MODE_PTT, MODE_HANDSFREE, MODE_LONG};
        int current = MODE_HANDSFREE.equals(recordingMode)
                ? 1 : MODE_LONG.equals(recordingMode) ? 2 : 0;
        int[] selection = {current};
        new VokieDialog.Builder(this)
                .setTitle("录音模式")
                .setMessage("选择手机端录音按钮的操作方式")
                .setChoices(labels, current, which -> selection[0] = which)
                .setPositiveButton("确定", () -> {
                    recordingMode = modes[selection[0]];
                    credentials.setRecordingMode(recordingMode);
                    updateRecordControl(false);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDevicePicker() {
        if (devices.isEmpty()) {
            new VokieDialog.Builder(this)
                    .setTitle("Vokie 列表")
                    .setMessage("当前 WiFi 下未发现可用的 Vokie。")
                    .setPositiveButton("重新搜索", this::startDiscovery)
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String[] labels = new String[devices.size()];
        int initialSelection = -1;
        for (int index = 0; index < devices.size(); index++) {
            VokieDevice device = devices.get(index);
            boolean current = selectedDevice != null &&
                    selectedDevice.instanceId.equals(device.instanceId);
            if (current) initialSelection = index;
            labels[index] = device.displayName;
        }
        int[] selection = {initialSelection};
        new VokieDialog.Builder(this)
                .setTitle("Vokie 列表")
                .setMessage("选择一台同一 WiFi 下的电脑")
                .setChoices(labels, initialSelection, which -> selection[0] = which)
                .setPositiveButton("确认加入", () -> {
                    if (selection[0] < 0) return;
                    VokieDevice chosen = devices.get(selection[0]);
                    if (transport.isConnected() && selectedDevice != null &&
                            selectedDevice.instanceId.equals(chosen.instanceId)) return;
                    resetReconnectBackoff();
                    transport.close();
                    selectDevice(chosen);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void disconnectCurrentDevice() {
        stopRecording(false);
        transport.close();
        resetReconnectBackoff();
        manualDisconnect = true;
        setConnected(false);
        showStatus("已断开", false,
                selectedDevice == null ? "" : selectedDevice.displayName);
    }

    private void confirmForgetCurrentDevice() {
        VokieDevice device = selectedDevice;
        if (device == null || !transport.isConnected()) return;
        new VokieDialog.Builder(this)
                .setTitle("解除设备验证")
                .setMessage("解除后，下次加入 " + device.displayName + " 时需要重新核对验证码。")
                .setPositiveButton(
                        "解除验证",
                        VokieDialog.ActionTone.CAUTION,
                        transport::forgetCurrentDevice)
                .setNegativeButton("取消", null)
                .show();
    }

    private void checkForUpdates(boolean userInitiated) {
        if (updateManager == null) return;
        if (userInitiated) {
            Toast.makeText(this, "正在检查更新", Toast.LENGTH_SHORT).show();
        }
        AppUpdateManager.Listener listener = new AppUpdateManager.Listener() {
            @Override public void onUpdateAvailable(AppUpdateInfo update) {
                availableUpdate = update;
                showTopUpdateAction();
                if (userInitiated) {
                    Toast.makeText(
                            MainActivity.this,
                            "发现新版本 v" + update.versionName,
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onUpToDate() {
                if (userInitiated) {
                    Toast.makeText(
                            MainActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onCheckFailed() {
                if (userInitiated) {
                    Toast.makeText(
                            MainActivity.this,
                            "暂时无法检查更新，请稍后重试",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onDownloadStarted(AppUpdateInfo update) { }
            @Override public void onDownloadFailed() { }
        };
        updateManager.check(listener);
    }

    private void showTopUpdateAction() {
        if (updateButton.getVisibility() == View.VISIBLE) return;
        updateButton.setAlpha(0f);
        updateButton.setScaleX(0.9f);
        updateButton.setScaleY(0.9f);
        updateButton.setVisibility(View.VISIBLE);
        updateButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(PULSE_INTERPOLATOR)
                .start();
    }

    private void downloadAvailableUpdate() {
        AppUpdateInfo update = availableUpdate;
        if (update == null || updateManager == null) return;
        updateManager.download(update, createDownloadListener());
    }

    private AppUpdateManager.Listener createDownloadListener() {
        return new AppUpdateManager.Listener() {
            @Override public void onUpdateAvailable(AppUpdateInfo update) { }
            @Override public void onUpToDate() { }
            @Override public void onCheckFailed() { }

            @Override public void onDownloadStarted(AppUpdateInfo update) {
                updateButton.setText("下载中");
                updateButton.setEnabled(false);
                Toast.makeText(
                        MainActivity.this,
                        "正在下载 Vokie Phone v" + update.versionName,
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void onDownloadFailed() {
                updateButton.setText(UPDATE_READY_LABEL);
                updateButton.setEnabled(true);
                Toast.makeText(
                        MainActivity.this,
                        "更新包下载或校验失败",
                        Toast.LENGTH_LONG).show();
            }
        };
    }

    private void showPairingDialog(long connectionId, String code, String pcName) {
        dismissPairingDialog();
        String readableCode = code.substring(0, 3) + "  " + code.substring(3);
        pairingDialog = new VokieDialog.Builder(this)
                .setTitle("确认连接")
                .setMessage(pcName)
                .setVerificationCode(
                        readableCode,
                        "请确认电脑上显示相同验证码，然后在电脑允许连接。")
                .setNegativeButton("取消连接", () -> {
                    if (transport.isCurrentConnection(connectionId)) transport.close();
                    setConnected(false);
                    showStatus("已取消连接", false, pcName);
                })
                .setCancelable(false)
                .show();
        showStatus("等待电脑确认", false, pcName);
    }

    private void dismissPairingDialog() {
        if (pairingDialog != null) pairingDialog.dismiss();
        pairingDialog = null;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (isHarmonyAndroidCompatibilityLayer()) {
                return new String[]{Manifest.permission.RECORD_AUDIO};
            }
            return new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.RECORD_AUDIO};
    }

    private boolean isHarmonyAndroidCompatibilityLayer() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        return manufacturer.toLowerCase(Locale.ROOT).contains("huawei") ||
                brand.toLowerCase(Locale.ROOT).contains("huawei") ||
                brand.toLowerCase(Locale.ROOT).contains("honor");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    private void registerRecordingActionReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(PhoneRecordingService.ACTION_STOP_RECORDING);
        filter.addAction(PhoneRecordingService.ACTION_RECORDING_SERVICE_INTERRUPTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recordingActionReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingActionReceiver, filter);
        }
        recordingReceiverRegistered = true;
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS && hasPermissions()) {
            requestNotificationPermissionIfNeeded();
        }
        else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) return;
        else showStatus("需要麦克风和附近设备权限", false, "请在系统设置中允许");
    }

    private void startRecording() {
        if (recording.get() || !transport.isConnected()) return;
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, FRAME_SAMPLES * 8));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            showStatus("无法打开手机麦克风", true, deviceText.getText().toString());
            return;
        }
        try {
            PhoneRecordingService.start(
                    this,
                    selectedDevice == null ? "Vokie" : selectedDevice.displayName,
                    recordingMode);
            recorder.startRecording();
        } catch (IllegalStateException | SecurityException error) {
            PhoneRecordingService.stop(this);
            recorder.release();
            showStatus("无法打开手机麦克风", true, deviceText.getText().toString());
            Log.e(AUDIO_TAG, "start failed", error);
            return;
        }
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            PhoneRecordingService.stop(this);
            recorder.release();
            showStatus("无法打开手机麦克风", true, deviceText.getText().toString());
            Log.e(AUDIO_TAG, "recorder did not enter recording state");
            return;
        }
        audioRecord = recorder;
        sessionId = (System.currentTimeMillis() / 1000L) & 0xffffffffL;
        sequence = 0;
        recording.set(true);
        transport.sendControl(
                PhoneProtocol.pttDown(sessionId, controlSequence++, recordingMode));
        recordThread = new Thread(() -> recordLoop(recorder, sessionId), "vokie-wifi-audio");
        recordThread.start();
        updateRecordControl(true);
        showStatus("正在录音", true, selectedDevice.displayName);
    }

    private void toggleRecording() {
        if (recording.get()) stopRecording(true);
        else startRecording();
    }

    private void recordLoop(AudioRecord recorder, long activeSession) {
        short[] samples = new short[FRAME_SAMPLES];
        capture: while (recording.get()) {
            int offset = 0;
            while (recording.get() && offset < FRAME_SAMPLES) {
                int count = recorder.read(
                        samples, offset, FRAME_SAMPLES - offset, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    Log.e(AUDIO_TAG, "read failed code=" + count);
                    break capture;
                }
                if (count == 0) continue;
                offset += count;
            }
            if (!recording.get() || offset != FRAME_SAMPLES) continue;
            sendAudioFrame(activeSession, samples);
        }
        if (recording.get()) mainHandler.post(() -> stopRecording(false));
    }

    private void sendAudioFrame(long activeSession, short[] samples) {
        byte[] pcm = new byte[FRAME_SAMPLES * 2];
        ByteBuffer out = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) out.putShort(sample);
        for (byte[] frame : PhoneProtocol.audioFragments(
                activeSession, sequence, pcm, 64 * 1024)) {
            transport.sendAudio(frame);
        }
        if (sequence == 0) Log.i(AUDIO_TAG, "first PCM frame queued");
        sequence += 1;
        updateRecordingPulse(samples);
    }

    private void updateRecordingPulse(short[] samples) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPulseAt < 60) return;
        lastPulseAt = now;
        float audioLevel = recordingPulseEnvelope.update(samples);
        recordingRipple.post(() -> {
            if (!recording.get()) return;
            recordingRipple.setAudioLevel(audioLevel);
        });
    }

    private void animateRing(View ring, float scale) {
        ring.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(1f)
                .setDuration(120)
                .setInterpolator(PULSE_INTERPOLATOR)
                .start();
    }

    private void resetRecordingPulse() {
        lastPulseAt = 0;
        recordingPulseEnvelope.reset();
        if (recordingRipple != null) recordingRipple.setRecording(false);
        if (recordOuterRing == null || recordInner == null) return;
        animateRing(recordOuterRing, 1f);
        animateRing(recordInner, 1f);
    }

    private void stopRecording(boolean submit) {
        if (!recording.getAndSet(false)) {
            PhoneRecordingService.stop(this);
            return;
        }
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        Thread thread = recordThread;
        recordThread = null;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }
        if (thread != null) {
            try { thread.join(800); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (recorder != null) recorder.release();
        if (submit && transport.isConnected()) {
            if (sequence == 0) sendAudioFrame(sessionId, new short[FRAME_SAMPLES]);
            long finalSequence = sequence - 1;
            Log.i(AUDIO_TAG, "recording finished frames=" + sequence);
            transport.sendControl(
                    PhoneProtocol.pttUp(sessionId, controlSequence++, finalSequence));
            showTransientConnectedStatus(
                    MODE_LONG.equals(recordingMode) ? "正在处理录音" : "正在发送");
        } else if (transport.isConnected()) {
            showStatus("已连接", true, selectedDevice.displayName);
        }
        resetRecordingPulse();
        updateRecordControl(false);
        PhoneRecordingService.stop(this);
    }

    private void setConnected(boolean connected) {
        recordControl.setEnabled(connected);
        sendButton.setEnabled(connected);
        undoButton.setEnabled(connected);
        openButton.setEnabled(connected);
        updateConnectionAction();
        updateRecordControl(recording.get());
    }

    private void showStatus(String value, boolean connected, String pcName) {
        mainHandler.removeCallbacks(connectedStatusResetRunnable);
        statusText.setText((connected ? "●  " : "○  ") + value);
        statusText.setTextColor(connected ? GREEN : MUTED);
        deviceText.setText(pcName == null || pcName.isEmpty() ?
                "在电脑 Vokie 设置中打开二维码" : pcName);
    }

    private void showTransientConnectedStatus(String value) {
        showStatus(value, true,
                selectedDevice == null ? "" : selectedDevice.displayName);
        mainHandler.postDelayed(connectedStatusResetRunnable, 1600);
    }

    private void restoreConnectedStatus() {
        if (!transport.isConnected() || recording.get()) return;
        showStatus("已连接", true,
                selectedDevice == null ? "" : selectedDevice.displayName);
    }

    private void updateRecordControl(boolean activeRecording) {
        if (recordInner == null) return;
        boolean enabled = recordControl.isEnabled();
        recordingRipple.setRecording(activeRecording);
        recordOuterRing.animate()
                .alpha(activeRecording ? 0f : 1f)
                .setDuration(activeRecording ? 160 : 220)
                .setInterpolator(PULSE_INTERPOLATOR)
                .start();
        if (activeRecording) {
            recordInner.setBackground(circleStroke(BLUE_PALE, BLUE_DARK, 3));
            recordTitle.setText(MODE_PTT.equals(recordingMode)
                    ? "松开发送" : MODE_HANDSFREE.equals(recordingMode)
                    ? "点击停止" : "结束录音");
            recordTitle.setTextColor(BLUE_DARK);
            recordHint.setText(MODE_LONG.equals(recordingMode)
                    ? "正在 Vokie 中录制" : "正在实时传输到 Vokie");
            microphoneIcon.setImageTintList(ColorStateList.valueOf(BLUE_DARK));
        } else {
            int color = enabled ? BLUE : Color.rgb(170, 177, 189);
            recordOuterRing.setBackground(
                    circleStroke(Color.TRANSPARENT, Color.rgb(209, 228, 253), 1));
            recordInner.setBackground(circleStroke(Color.WHITE, color, 3));
            recordTitle.setText(MODE_PTT.equals(recordingMode)
                    ? "按住说话" : MODE_HANDSFREE.equals(recordingMode)
                    ? "点击开始" : "长录音");
            recordTitle.setTextColor(color);
            recordHint.setText(!enabled
                    ? "连接 Vokie 后开始说话"
                    : MODE_PTT.equals(recordingMode)
                    ? "松开发送 · 不在手机保存"
                    : MODE_HANDSFREE.equals(recordingMode)
                    ? "再次点击停止"
                    : "点击开始 Vokie 录制");
            microphoneIcon.setImageTintList(ColorStateList.valueOf(color));
        }
        recordControl.setAlpha(enabled ? 1f : 0.64f);
    }

    private Button overflowButton() {
        Button button = new Button(this);
        button.setText("\u2022\u2022\u2022");
        button.setTextSize(18);
        button.setTextColor(INK);
        button.setContentDescription("更多功能");
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setStateListAnimator(null);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        addPressFeedback(button);
        return button;
    }

    private Button topUpdateButton() {
        Button button = new Button(this);
        button.setText(UPDATE_READY_LABEL);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(18, 139, 83));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setStateListAnimator(null);
        button.setGravity(Gravity.CENTER);
        button.setBackground(new InsetDrawable(
                roundRect(Color.rgb(221, 247, 232), dp(16), 0, Color.TRANSPARENT),
                0, dp(6), 0, dp(6)));
        button.setPadding(dp(6), 0, dp(8), 0);
        addPressFeedback(button);
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateManager != null) updateManager.resumePendingInstall();
    }

    private Button actionButton(String label, boolean primary, int icon) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(INK);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setGravity(Gravity.CENTER);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(8));
        button.setBackground(roundRect(
                primary ? ACTION_SURFACE_STRONG : ACTION_SURFACE,
                dp(12), 0, Color.TRANSPARENT));
        addPressFeedback(button);
        return button;
    }

    private void addPressFeedback(View view) {
        view.setOnTouchListener((pressedView, event) -> {
            if (!pressedView.isEnabled()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .alpha(0.84f)
                        .setDuration(80)
                        .setInterpolator(PULSE_INTERPOLATOR)
                        .start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(140)
                        .setInterpolator(PULSE_INTERPOLATOR)
                        .start();
            }
            return false;
        });
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable circleStroke(int fill, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeWidth, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        stopRecording(false);
        dismissPairingDialog();
        if (discovery != null) discovery.stop();
        if (transport != null) transport.destroy();
        if (updateManager != null) updateManager.destroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (recordingReceiverRegistered) {
            unregisterReceiver(recordingActionReceiver);
            recordingReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
