package com.vokie.phone;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.UUID;

final class VokieBlePeripheral {
    interface Listener {
        void onState(String state, boolean connected);
    }

    static final UUID SERVICE_UUID = UUID.fromString("7b5a1000-766f-6b69-652d-70686f6e6531");
    static final UUID CONTROL_UUID = UUID.fromString("7b5a1001-766f-6b69-652d-70686f6e6531");
    static final UUID AUDIO_UUID = UUID.fromString("7b5a1002-766f-6b69-652d-70686f6e6531");
    static final UUID STATUS_UUID = UUID.fromString("7b5a1003-766f-6b69-652d-70686f6e6531");
    static final UUID CONFIG_UUID = UUID.fromString("7b5a1004-766f-6b69-652d-70686f6e6531");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice central;
    private BluetoothGattCharacteristic control;
    private BluetoothGattCharacteristic audio;
    private int mtu = 23;

    VokieBlePeripheral(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    boolean start() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onState("请先打开蓝牙", false);
            return false;
        }
        if (!adapter.isMultipleAdvertisementSupported() || adapter.getBluetoothLeAdvertiser() == null) {
            listener.onState("当前运行环境不支持 BLE 外围设备", false);
            return false;
        }
        server = manager.openGattServer(context, callback);
        if (server == null) {
            listener.onState("无法启动 GATT Server", false);
            return false;
        }
        BluetoothGattService service = new BluetoothGattService(
                SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        control = notifying(CONTROL_UUID);
        audio = notifying(AUDIO_UUID);
        BluetoothGattCharacteristic status = new BluetoothGattCharacteristic(
                STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattCharacteristic config = new BluetoothGattCharacteristic(
                CONFIG_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        config.setValue(PhoneProtocol.config(detectPlatform()));
        service.addCharacteristic(control);
        service.addCharacteristic(audio);
        service.addCharacteristic(status);
        service.addCharacteristic(config);
        server.addService(service);
        advertiser = adapter.getBluetoothLeAdvertiser();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        AdvertiseData response = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        adapter.setName("Vokie Phone");
        advertiser.startAdvertising(settings, data, response, advertiseCallback);
        listener.onState("等待 Vokie 连接", false);
        return true;
    }

    @SuppressLint("MissingPermission")
    void stop() {
        if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        if (server != null) server.close();
        advertiser = null;
        server = null;
        central = null;
        mtu = 23;
    }

    boolean isConnected() {
        return central != null;
    }

    int maxNotificationBytes() {
        return mtu - 3;
    }

    @SuppressLint("MissingPermission")
    boolean sendControl(byte[] value) {
        return notify(control, value);
    }

    @SuppressLint("MissingPermission")
    boolean sendAudio(byte[] value) {
        return notify(audio, value);
    }

    @SuppressLint("MissingPermission")
    private boolean notify(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (server == null || central == null || characteristic == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return server.notifyCharacteristicChanged(
                    central, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS;
        }
        characteristic.setValue(value);
        return server.notifyCharacteristicChanged(central, characteristic, false);
    }

    private BluetoothGattCharacteristic notifying(UUID uuid) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        characteristic.addDescriptor(descriptor);
        return characteristic;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            listener.onState("蓝牙广播启动失败：" + errorCode, false);
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                central = device;
                listener.onState("PC 已连接", true);
            } else if (central != null && central.equals(device)) {
                central = null;
                mtu = 23;
                listener.onState("等待 Vokie 连接", false);
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int value) {
            mtu = value;
            listener.onState("PC 已连接 · MTU " + value, true);
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onCharacteristicReadRequest(
                BluetoothDevice device,
                int requestId,
                int offset,
                BluetoothGattCharacteristic characteristic
        ) {
            byte[] value = characteristic.getValue();
            if (value == null || offset > value.length) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] slice = new byte[value.length - offset];
            System.arraycopy(value, offset, slice, 0, slice.length);
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattDescriptor descriptor,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            descriptor.setValue(value);
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    private static String detectPlatform() {
        String text = (Build.MANUFACTURER + " " + Build.DISPLAY + " " + Build.VERSION.INCREMENTAL)
                .toLowerCase();
        return text.contains("harmony") || text.contains("hongmeng") ? "harmonyos" : "android";
    }
}
