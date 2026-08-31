package com.vokie.phone;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class WifiPhoneTransport {
    interface Listener {
        void onState(long connectionId, String message, boolean connected, String pcName);
        void onPairingCode(long connectionId, String pairingCode, String pcName);
        void onCredentialRejected(long connectionId, String instanceId);
        void onDeviceForgotten(long connectionId, String instanceId, String pcName);
        void onRecordingStopped(long connectionId, long sessionId, String reason);
    }

    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private final Listener listener;
    private final PhoneCredentialStore credentials;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile String targetInstanceId = "";
    private volatile String targetName = "";

    WifiPhoneTransport(Listener listener, PhoneCredentialStore credentials) {
        this.listener = listener;
        this.credentials = credentials;
    }

    void connect(VokieDevice device, String deviceId, String deviceName) {
        if (active.get() && device.instanceId.equals(targetInstanceId)) return;
        close();
        long connectionId = connectionGeneration.incrementAndGet();
        targetInstanceId = device.instanceId;
        targetName = device.displayName;
        active.set(true);
        reader.execute(() -> connectAndRead(connectionId, device, deviceId, deviceName));
    }

    boolean isConnected() { return connected.get(); }
    boolean isActive() { return active.get(); }
    String targetInstanceId() { return targetInstanceId; }
    boolean isCurrentConnection(long connectionId) {
        return connectionGeneration.get() == connectionId;
    }

    void sendControl(byte[] value) { enqueue(value); }
    void sendAudio(byte[] value) { enqueue(value); }

    void openVokie() {
        enqueue("{\"v\":2,\"type\":\"open_vokie\"}"
                .getBytes(StandardCharsets.UTF_8));
    }

    void forgetCurrentDevice() {
        enqueue("{\"v\":2,\"type\":\"forget_device\"}"
                .getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        connectionGeneration.incrementAndGet();
        closeCurrentConnection();
    }

    private void closeCurrentConnection() {
        active.set(false);
        connected.set(false);
        Socket current = socket;
        socket = null;
        output = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
    }

    void destroy() {
        close();
        reader.shutdownNow();
        writer.shutdownNow();
    }

    private void connectAndRead(
            long connectionId,
            VokieDevice device,
            String deviceId,
            String deviceName) {
        try {
            List<InetAddress> hosts = device.connectionAddresses;
            if (hosts.isEmpty()) throw new ConnectionFailure("Vokie 地址不可用，请重新搜索");
            PhonePairingCrypto.ClientKeys clientKeys = PhonePairingCrypto.createClientKeys();
            byte[] storedToken = credentials.getToken(device.instanceId);
            Socket next = connectFirstAvailable(hosts, device.service.getPort());
            next.setKeepAlive(true);
            next.setTcpNoDelay(true);
            next.setSoTimeout(30_000);
            if (!isCurrentConnection(connectionId) ||
                    !active.get() || !device.instanceId.equals(targetInstanceId)) {
                next.close();
                return;
            }
            socket = next;
            output = new DataOutputStream(next.getOutputStream());
            sendFrame(hello(device, deviceId, deviceName, clientKeys, storedToken != null));
            listener.onState(connectionId, "正在验证连接", false, device.displayName);
            readResponses(
                    connectionId,
                    new DataInputStream(next.getInputStream()),
                    device,
                    deviceId,
                    clientKeys,
                    storedToken);
        } catch (ConnectionFailure error) {
            if (!isCurrentConnection(connectionId)) return;
            closeCurrentConnection();
            listener.onState(connectionId, error.getMessage(), false, targetName);
        } catch (Exception error) {
            if (!isCurrentConnection(connectionId)) return;
            closeCurrentConnection();
            listener.onState(connectionId, "连接失败，正在重新搜索", false, targetName);
        }
    }

    private Socket connectFirstAvailable(List<InetAddress> hosts, int port) throws IOException {
        IOException lastError = null;
        for (InetAddress host : hosts) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(host, port), 4_000);
                return candidate;
            } catch (IOException error) {
                lastError = error;
                try { candidate.close(); } catch (IOException ignored) { }
            }
        }
        throw lastError == null ? new IOException("No Vokie address available") : lastError;
    }

    private void readResponses(
            long connectionId,
            DataInputStream input,
            VokieDevice device,
            String deviceId,
            PhonePairingCrypto.ClientKeys clientKeys,
            byte[] storedToken) throws Exception {
        byte[] candidateToken = storedToken;
        byte[] context = null;
        String pcName = device.displayName;
        while (isCurrentConnection(connectionId) && socket != null && !socket.isClosed()) {
            JSONObject message = new JSONObject(new String(readFrame(input), StandardCharsets.UTF_8));
            String type = message.optString("type");
            if ("server_hello".equals(type)) {
                String instanceId = message.getString("instanceId");
                String pairingId = message.getString("pairingId");
                String serverPublicKey = message.getString("serverPublicKey");
                pcName = message.optString("pcName", device.displayName);
                if (!device.instanceId.equals(instanceId)) {
                    throw new ConnectionFailure("连接的 Vokie 身份不匹配");
                }
                byte[] sharedSecret = PhonePairingCrypto.deriveSharedSecret(
                        clientKeys, serverPublicKey);
                context = PhonePairingCrypto.context(
                        instanceId,
                        deviceId,
                        pairingId,
                        clientKeys.publicKeyBase64,
                        serverPublicKey);
                String mode = message.optString("mode");
                if ("trusted".equals(mode)) {
                    if (candidateToken == null) {
                        throw new ConnectionFailure("配对信息已失效，请重新连接");
                    }
                    sendJson(new JSONObject()
                            .put("v", 2)
                            .put("type", "auth_proof")
                            .put("proof", PhonePairingCrypto.authProof(candidateToken, context)));
                } else if ("pairing".equals(mode)) {
                    socket.setSoTimeout(0);
                    candidateToken = PhonePairingCrypto.deviceToken(
                            sharedSecret, pairingId, context);
                    String code = PhonePairingCrypto.pairingCode(sharedSecret, context);
                    listener.onPairingCode(connectionId, code, pcName);
                    sendJson(new JSONObject()
                            .put("v", 2)
                            .put("type", "pairing_ready")
                            .put("proof", PhonePairingCrypto.pairingReadyProof(
                                    sharedSecret, context)));
                } else {
                    throw new ConnectionFailure("Vokie 验证协议不兼容");
                }
                continue;
            }
            if ("auth_ok".equals(type)) {
                if (candidateToken == null || context == null ||
                        !PhonePairingCrypto.proofMatches(
                                PhonePairingCrypto.authOkProof(candidateToken, context),
                                message.optString("proof"))) {
                    throw new ConnectionFailure("Vokie 身份验证失败");
                }
                if ("paired".equals(message.optString("mode"))) {
                    credentials.saveToken(device.instanceId, pcName, candidateToken);
                }
                credentials.setSelectedInstanceId(device.instanceId);
                connected.set(true);
                socket.setSoTimeout(0);
                listener.onState(connectionId, "已连接", true, pcName);
                continue;
            }
            if ("auth_error".equals(type)) {
                String reason = message.optString("reason");
                if ("credential_rejected".equals(reason)) {
                    credentials.removeToken(device.instanceId);
                    listener.onCredentialRejected(connectionId, device.instanceId);
                    throw new ConnectionFailure("配对信息已更新，正在重新验证");
                }
                if ("pairing_rejected".equals(reason)) {
                    throw new ConnectionFailure("电脑拒绝了连接请求");
                }
                if ("approval_busy".equals(reason)) {
                    throw new ConnectionFailure("另一台手机正在等待电脑确认");
                }
                throw new ConnectionFailure("连接验证失败，请重试");
            }
            if ("server_disconnect".equals(type)) {
                closeCurrentConnection();
                listener.onState(connectionId, "电脑已断开连接", false, pcName);
                return;
            }
            if ("recording_stopped".equals(type)) {
                listener.onRecordingStopped(
                        connectionId,
                        message.optLong("sessionId", -1L),
                        message.optString("reason", "pc_stopped"));
                continue;
            }
            if ("forget_device_ok".equals(type)) {
                credentials.removeToken(device.instanceId);
                credentials.clearSelectedInstanceId();
                closeCurrentConnection();
                listener.onDeviceForgotten(connectionId, device.instanceId, pcName);
                return;
            }
        }
    }

    private void enqueue(byte[] value) {
        if (!connected.get()) return;
        long connectionId = connectionGeneration.get();
        writer.execute(() -> {
            if (!isCurrentConnection(connectionId) || !connected.get()) return;
            try {
                sendFrame(value);
            } catch (IOException error) {
                if (!isCurrentConnection(connectionId)) return;
                closeCurrentConnection();
                listener.onState(connectionId, "连接已断开", false, targetName);
            }
        });
    }

    private synchronized void sendJson(JSONObject value) throws IOException {
        sendFrame(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(byte[] value) throws IOException {
        DataOutputStream stream = output;
        Socket current = socket;
        if (current == null || current.isClosed() || stream == null) {
            throw new IOException("not connected");
        }
        stream.writeInt(value.length);
        stream.write(value);
        stream.flush();
    }

    private byte[] readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("invalid response");
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }

    private static byte[] hello(
            VokieDevice device,
            String deviceId,
            String deviceName,
            PhonePairingCrypto.ClientKeys clientKeys,
            boolean hasCredential) throws Exception {
        return new JSONObject()
                .put("v", 2)
                .put("type", "hello")
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("platform", "android")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("targetInstanceId", device.instanceId)
                .put("clientPublicKey", clientKeys.publicKeyBase64)
                .put("hasCredential", hasCredential)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class ConnectionFailure extends IOException {
        ConnectionFailure(String message) { super(message); }
    }
}
