package com.vokie.phone;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class WifiDiscovery {
    private static final String TAG = "VokieDiscovery";
    static final String SERVICE_TYPE = "_vokie-phone._tcp.";

    interface Listener {
        void onDevices(List<VokieDevice> devices);
        void onError(String message);
    }

    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicastLock;
    private final Map<String, VokieDevice> devices = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Set<String> queuedServiceNames = new HashSet<>();
    private final Map<String, Integer> resolveAttempts = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener discovery;
    private Listener listener;
    private boolean resolving;
    private int resolveGeneration;
    private long resolveBlockedUntil;
    private String resolvingServiceName;
    private boolean resolveScheduled;

    WifiDiscovery(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi == null ? null : wifi.createMulticastLock("vokie-phone-discovery");
        if (multicastLock != null) multicastLock.setReferenceCounted(false);
    }

    void start(Listener nextListener) {
        boolean restarting = discovery != null;
        stop();
        listener = nextListener;
        devices.clear();
        resolveQueue.clear();
        queuedServiceNames.clear();
        resolveAttempts.clear();
        resolving = false;
        resolvingServiceName = null;
        resolveScheduled = false;
        resolveBlockedUntil = 0;
        resolveGeneration += 1;
        if (nsd == null) {
            nextListener.onError("当前系统不支持局域网发现");
            return;
        }
        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }

            @Override public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "found name=" + service.getServiceName() +
                        " type=" + service.getServiceType());
                if (!service.getServiceType().startsWith("_vokie-phone._tcp")) return;
                resolveAttempts.remove(service.getServiceName());
                enqueue(service);
                scheduleResolve();
            }

            @Override public void onServiceLost(NsdServiceInfo service) {
                String removedId = null;
                for (Map.Entry<String, VokieDevice> entry : devices.entrySet()) {
                    if (entry.getValue().service.getServiceName().equals(service.getServiceName())) {
                        removedId = entry.getKey();
                        break;
                    }
                }
                if (removedId != null) {
                    devices.remove(removedId);
                    notifyDevices();
                }
            }

            @Override public void onDiscoveryStopped(String serviceType) { }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Listener active = listener;
                if (active != null) active.onError("局域网搜索失败，请重试");
                stop();
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
        };
        int startGeneration = resolveGeneration;
        Runnable begin = () -> {
            if (discovery == null || startGeneration != resolveGeneration) return;
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        };
        if (restarting) handler.postDelayed(begin, 350);
        else begin.run();
    }

    void stop() {
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); }
            catch (IllegalArgumentException ignored) { }
        }
        discovery = null;
        listener = null;
        resolveQueue.clear();
        queuedServiceNames.clear();
        resolveAttempts.clear();
        resolving = false;
        resolvingServiceName = null;
        resolveScheduled = false;
        resolveBlockedUntil = 0;
        resolveGeneration += 1;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    private void resolveNext() {
        if (resolving || nsd == null) return;
        long retryDelay = resolveBlockedUntil - SystemClock.uptimeMillis();
        if (retryDelay > 0) {
            handler.postDelayed(this::resolveNext, retryDelay);
            return;
        }
        NsdServiceInfo service = resolveQueue.poll();
        if (service == null) return;
        queuedServiceNames.remove(service.getServiceName());
        resolving = true;
        resolvingServiceName = service.getServiceName();
        int generation = ++resolveGeneration;
        handler.postDelayed(() -> {
            if (!resolving || generation != resolveGeneration) return;
            Log.d(TAG, "resolve timeout name=" + service.getServiceName());
            resolving = false;
            resolvingServiceName = null;
            resolveGeneration += 1;
            resolveBlockedUntil = SystemClock.uptimeMillis() + 1_000;
            resolveNext();
        }, 2_500);
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                if (generation != resolveGeneration) return;
                Log.d(TAG, "resolve failed name=" + serviceInfo.getServiceName() +
                        " code=" + errorCode);
                resolving = false;
                resolvingServiceName = null;
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    String name = serviceInfo.getServiceName();
                    int attempts = resolveAttempts.getOrDefault(name, 0) + 1;
                    if (attempts < 3) {
                        resolveAttempts.put(name, attempts);
                        enqueue(serviceInfo);
                        resolveBlockedUntil = SystemClock.uptimeMillis() + 1_000;
                    } else {
                        resolveAttempts.remove(name);
                    }
                }
                resolveNext();
            }

            @Override public void onServiceResolved(NsdServiceInfo resolved) {
                if (generation != resolveGeneration) return;
                Log.d(TAG, "resolved name=" + resolved.getServiceName() +
                        " attributes=" + resolved.getAttributes().keySet());
                resolving = false;
                resolvingServiceName = null;
                resolveBlockedUntil = 0;
                resolveAttempts.remove(resolved.getServiceName());
                VokieDevice device = VokieDevice.from(resolved);
                if (device != null) {
                    devices.put(device.instanceId, device);
                    notifyDevices();
                    resolveNext();
                    return;
                }
                resolveNext();
            }
        });
    }

    private void enqueue(NsdServiceInfo service) {
        String name = service.getServiceName();
        if (name.equals(resolvingServiceName) || !queuedServiceNames.add(name)) return;
        resolveQueue.offerLast(service);
    }

    private void scheduleResolve() {
        if (resolveScheduled || resolving) return;
        resolveScheduled = true;
        handler.postDelayed(() -> {
            resolveScheduled = false;
            if (discovery != null) resolveNext();
        }, 500);
    }

    private void notifyDevices() {
        Listener active = listener;
        if (active != null) active.onDevices(new ArrayList<>(devices.values()));
    }
}
