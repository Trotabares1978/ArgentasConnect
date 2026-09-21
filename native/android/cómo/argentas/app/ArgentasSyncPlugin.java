package com.argentas.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import androidx.core.content.ContextCompat;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
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

@CapacitorPlugin(
    name = "ArgentasSync",
    permissions = {
        @Permission(
            alias = "nearby",
            strings = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            }
        )
    }
)
public class ArgentasSyncPlugin extends Plugin {
    private static final String SERVICE_ID = "com.argentas.app";
    private static final Strategy STRATEGY = Strategy.P2P_POINT_TO_POINT;

    private ConnectionsClient connections;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> discovered = new HashSet<>();
    private volatile String connectedEndpoint;
    private volatile String connectedEndpointName;

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
        public void onPayloadTransferUpdate(
                String endpointId,
                com.google.android.gms.nearby.connection.PayloadTransferUpdate update
        ) {
            // Sales data are small byte payloads; no progress UI is needed.
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(
                        String endpointId,
                        ConnectionInfo connectionInfo
                ) {
                    // No pairing code or manual button: both Argentas phones
                    // automatically accept the peer connection.
                    connections.acceptConnection(endpointId, payloadCallback);
                }

                @Override
                public void onConnectionResult(
                        String endpointId,
                        ConnectionResolution result
                ) {
                    if (result.getStatus().getStatusCode()
                            == ConnectionsStatusCodes.STATUS_OK) {

                        connectedEndpoint = endpointId;
                        connectedEndpointName = null;
                        emitStatus("Conectado");

                        // Once the two phones are connected, stop active discovery
                        // to reduce radio traffic. Advertising remains available.
                        try {
                            connections.stopDiscovery();
                        } catch (Exception ignored) { }

                    } else {
                        connectedEndpoint = null;
                        connectedEndpointName = null;
                        emitStatus("Buscando");
                        restartTransport();
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    if (endpointId.equals(connectedEndpoint)) {
                        connectedEndpoint = null;
                        connectedEndpointName = null;
                        emitStatus("Buscando");
                        restartTransport();
                    }
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(
                        String endpointId,
                        DiscoveredEndpointInfo info
                ) {
                    if (!running.get() || connectedEndpoint != null) return;

                    String peerName = info.getEndpointName();
                    if (peerName == null) peerName = "";

                    // Both phones advertise and discover, but only the one with
                    // the smaller stable name initiates. This prevents duplicate
                    // simultaneous connection attempts.
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
            requestPermissionForAlias("nearby", call, "nearbyPermissionsCallback");
            return;
        }

        startTransport();
        call.resolve();
    }

    @PermissionCallback
    private void nearbyPermissionsCallback(PluginCall call) {
        if (hasRequiredPermissions()) {
            startTransport();
            call.resolve();
        } else {
            emitStatus("Desconectado");
            call.reject("Se requieren permisos de dispositivos cercanos para sincronizar Argentas.");
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
        stopTransport();
        call.resolve();
    }

    private synchronized void startTransport() {
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
        ).addOnFailureListener(e -> {
            running.set(false);
            emitStatus("Desconectado");
        });

        connections.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
        ).addOnFailureListener(e -> {
            running.set(false);
            emitStatus("Desconectado");
        });
    }

    private synchronized void restartTransport() {
        if (!hasRequiredPermissions()) return;

        running.set(false);

        try { connections.stopDiscovery(); } catch (Exception ignored) { }
        try { connections.stopAdvertising(); } catch (Exception ignored) { }

        synchronized (discovered) {
            discovered.clear();
        }

        startTransport();
    }

    private synchronized void stopTransport() {
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

    private String getLocalEndpointName() {
        String id = android.provider.Settings.Secure.getString(
                getContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        return "Argentas-" + (id == null ? "device" : id);
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) return false;
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return false;
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) return false;
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }

        return true;
    }

    private void emitStatus(String status) {
        JSObject ret = new JSObject();
        ret.put("status", status);
        notifyListeners("status", ret);
    }

    @Override
    protected void handleOnDestroy() {
        stopTransport();
        super.handleOnDestroy();
    }
}
