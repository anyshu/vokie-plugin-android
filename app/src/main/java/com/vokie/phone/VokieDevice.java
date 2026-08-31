package com.vokie.phone;

import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VokieDevice {
    final NsdServiceInfo service;
    final String instanceId;
    final String displayName;
    final List<InetAddress> connectionAddresses;

    private VokieDevice(
            NsdServiceInfo service,
            String instanceId,
            String displayName,
            List<InetAddress> connectionAddresses) {
        this.service = service;
        this.instanceId = instanceId;
        this.displayName = displayName;
        this.connectionAddresses = connectionAddresses;
    }

    static VokieDevice from(NsdServiceInfo service) {
        Map<String, byte[]> attributes = service.getAttributes();
        String version = attribute(attributes, "v");
        String auth = attribute(attributes, "auth");
        String instance = attribute(attributes, "instance");
        String name = attribute(attributes, "name");
        if (!"2".equals(version) || !"sas-p256-v2".equals(auth) || instance.isEmpty()) {
            return null;
        }
        Set<InetAddress> addresses = new LinkedHashSet<>();
        InetAddress resolvedHost = service.getHost();
        if (resolvedHost != null) addresses.add(resolvedHost);
        for (String value : attribute(attributes, "ipv4").split(",")) {
            InetAddress address = parseIpv4(value.trim());
            if (address != null) addresses.add(address);
        }
        return new VokieDevice(
                service,
                instance,
                name.isEmpty() ? service.getServiceName() : name,
                Collections.unmodifiableList(new ArrayList<>(addresses)));
    }

    private static String attribute(Map<String, byte[]> attributes, String key) {
        byte[] value = attributes.get(key);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static InetAddress parseIpv4(String value) {
        if (value.isEmpty()) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                int part = Integer.parseInt(parts[index]);
                if (part < 0 || part > 255) return null;
                address[index] = (byte) part;
            }
            return InetAddress.getByAddress(address);
        } catch (NumberFormatException | UnknownHostException ignored) {
            return null;
        }
    }
}
