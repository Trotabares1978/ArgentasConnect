package com.argentas.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "ArgentasSync")
public class ArgentasSyncPlugin extends Plugin {
    private static final String SERVICE_ID = "com.argentas.app";
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;
    private static final int PERMISSION_REQUEST_CODE = 38742;

    private ConnectionsClient connections;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> discovered = new HashSet<>();
    private volatile String connectedEndpoint;
    private volatile String connectedEndpointName;
    private volatile boolean waitingForPermissions = false;

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;

            byte[] bytes = payload.asBytes();
            if (bytes == null) return;

            JSObject ret = new JSObject();
            ret.put("data", new String(bytes, StandardCharsets.UTF_8));
            notifyListeners("message", ret);
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, com.google.android.gms.nearby.connection.PayloadTransferUpdate update) {
            // Sales snapshots are small byte payloads; no progress UI is needed.
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    // Argentas is designed for two phones owned by the same operator.
                    // We accept automatically so there are no pairing codes or buttons.
                    connections.acceptConnection(endpointId, payloadCallback);
                }

                @Override
                public void onConnectionResult(String endpointId, com.google.android.gms.common.api.Status status) {
                    if (status.getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                        connectedEndpoint = endpointId;
                        connectedEndpointName = null;
                        emitStatus("Conectado");
                        connections.stopDiscovery();
                    } else {
                        if (endpointId.equals(connectedEndpoint)) {
                            connectedEndpoint = null;
                            connectedEndpointName = null;
                        }
                        emitStatus("Buscando");
                        restartDiscoverySoon();
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    if (endpointId.equals(connectedEndpoint)) {
                        connectedEndpoint = null;
                        connectedEndpointName = null;
                        emitStatus("Buscando");
                        startAdvertisingAndDiscovery();
                    }
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                    if (!running.get() || connectedEndpoint != null) return;

                    String peerName = info.getEndpointName();
                    if (peerName == null) peerName = "";

                    // Only one side requests the connection. Both sides still
                    // advertise and discover, so either phone can be A or B.
                    String localName = getLocalEndpointName();
                    if (localName.compareTo(peerName) > 0) return;

                    synchronized (discovered) {
                        if (!discovered.add(endpointId)) return;
                    }

                    connections.requestConnection(
                            localName,
                            endpointId,
                            connectionLifecycleCallback
                    ).addOnFailureListener(e -> {
                        synchronized (discovered) {
                            discovered.remove(endpointId);
                        }
                        emitStatus("Buscando");
                    });
                }

                @Override
                public void onEndpointLost(String endpointId) {
                    synchronized (discovered) {
                        discovered.remove(endpointId);
                    }
                }
            };

    @Override
    public void load() {
        super.load();
        connections = Nearby.getConnectionsClient(getContext());
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!hasRequiredPermissions()) {
            waitingForPermissions = true;
            Activity activity = getActivity();
            if (activity != null) {
                ActivityCompat.requestPermissions(
                        activity,
                        getRequiredPermissions(),
                        PERMISSION_REQUEST_CODE
                );
                emitStatus("Buscando");
            } else {
                emitStatus("Desconectado");
            }
            call.resolve();
            return;
        }

        startAdvertisingAndDiscovery();
        call.resolve();
    }

    public void onNearbyPermissionsResult(int requestCode) {
        if (requestCode != PERMISSION_REQUEST_CODE) return;

        waitingForPermissions = false;
        if (hasRequiredPermissions()) {
            startAdvertisingAndDiscovery();
        } else {
            emitStatus("Desconectado");
        }
    }

    @PluginMethod
    public void send(PluginCall call) {
        String data = call.getString("data", "");
        if (data != null && !data.isEmpty() && connectedEndpoint != null) {
            try {
                connections.sendPayload(
                        connectedEndpoint,
                        Payload.fromBytes(data.getBytes(StandardCharsets.UTF_8))
                );
            } catch (Exception ignored) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopAll();
        call.resolve();
    }

    private synchronized void startAdvertisingAndDiscovery() {
        if (running.get() || !hasRequiredPermissions()) return;

        running.set(true);
        emitStatus("Buscando");

        synchronized (discovered) {
            discovered.clear();
        }

        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder()
                        .setStrategy(STRATEGY)
                        .build();

        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder()
                        .setStrategy(STRATEGY)
                        .build();

        connections.startAdvertising(
                getLocalEndpointName(),
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
        ).addOnFailureListener(e -> emitStatus("Desconectado"));

        connections.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
        ).addOnFailureListener(e -> emitStatus("Desconectado"));
    }

    private void restartDiscoverySoon() {
        getActivity().runOnUiThread(() -> {
            if (!running.get() || connectedEndpoint != null) return;
            startAdvertisingAndDiscovery();
        });
    }

    private String getLocalEndpointName() {
        String id = android.provider.Settings.Secure.getString(
                getContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );
        return "Argentas-" + (id == null ? "device" : id);
    }

    private boolean hasRequiredPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(getContext(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] getRequiredPermissions() {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        return permissions.toArray(new String[0]);
    }

    private synchronized void stopAll() {
        running.set(false);
        connectedEndpoint = null;
        connectedEndpointName = null;

        try { connections.stopAdvertising(); } catch (Exception ignored) { }
        try { connections.stopDiscovery(); } catch (Exception ignored) { }
        try { connections.stopAllEndpoints(); } catch (Exception ignored) { }

        synchronized (discovered) {
            discovered.clear();
        }

        emitStatus("Desconectado");
    }

    private void emitStatus(String status) {
        JSObject ret = new JSObject();
        ret.put("status", status);
        notifyListeners("status", ret);
    }

    @Override
    protected void handleOnDestroy() {
        stopAll();
        super.handleOnDestroy();
    }
}
