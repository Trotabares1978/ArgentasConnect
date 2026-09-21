package com.argentas.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "ArgentasSync")
public class ArgentasSyncPlugin extends Plugin {
    private static final String SERVICE_TYPE = "_argentas._tcp.";
    private static final int PORT = 38741;
    private static final String SERVICE_PREFIX = "Argentas-";
    private static final long RECONNECT_DELAY_SECONDS = 3;

    private NsdManager nsd;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private ServerSocket serverSocket;
    private Socket socket;
    private PrintWriter writer;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private String serviceName;
    private volatile InetAddress lastPeerHost;
    private volatile int lastPeerPort = PORT;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void load() {
        super.load();
        Context context = getContext();
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("ArgentasConnect");
            multicastLock.setReferenceCounted(false);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!running) startLocalDiscovery();
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String data = call.getString("data", "");
        if (data != null && !data.isEmpty()) {
            synchronized (this) {
                if (writer != null) {
                    writer.println(data);
                    writer.flush();
                }
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopAll();
        call.resolve();
    }

    private void startLocalDiscovery() {
        running = true;
        emitStatus("Buscando");

        try {
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Exception ignored) { }

        pool.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                pool.execute(this::acceptLoop);
                registerService();
                discoverServices();
            } catch (Exception e) {
                emitStatus("Desconectado");
            }
        });
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket incoming = serverSocket.accept();
                acceptIncoming(incoming);
            } catch (Exception ignored) {
                if (!running) return;
            }
        }
    }

    private void registerService() {
        String id = android.provider.Settings.Secure.getString(
                getContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);

        serviceName = SERVICE_PREFIX +
                (id == null
                        ? Integer.toHexString((int) (Math.random() * 0xFFFFFF))
                        : id);

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                emitStatus("Desconectado");
            }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { }
        };

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception ignored) {
            emitStatus("Desconectado");
        }
    }

    private void discoverServices() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                String peerName = info.getServiceName();
                if (peerName == null || peerName.equals(serviceName)) return;
                if (info.getServiceType() == null ||
                        !info.getServiceType().startsWith("_argentas._tcp")) return;

                // Only the lexicographically smaller service name initiates the
                // connection. This prevents both phones from opening two
                // simultaneous connections to each other.
                if (serviceName != null && serviceName.compareTo(peerName) > 0) return;

                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            emitStatus("Buscando");
                        }

                        @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            lastPeerHost = serviceInfo.getHost();
                            lastPeerPort = serviceInfo.getPort();
                            connectTo(lastPeerHost, lastPeerPort);
                        }
                    });
                } catch (Exception ignored) { }
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                if (socket == null || socket.isClosed()) emitStatus("Buscando");
            }

            @Override public void onDiscoveryStopped(String regType) { }

            @Override public void onStartDiscoveryFailed(String regType, int errorCode) {
                emitStatus("Desconectado");
                scheduler.schedule(() -> {
                    if (running) discoverServices();
                }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }

            @Override public void onStopDiscoveryFailed(String regType, int errorCode) { }
        };

        try {
            nsd.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
            );
        } catch (Exception ignored) {
            emitStatus("Desconectado");
        }
    }

    private synchronized void connectTo(InetAddress host, int port) {
        if (!running || host == null || (socket != null && !socket.isClosed())) return;

        pool.execute(() -> {
            try {
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(host, port), 2500);
                acceptSocket(s);
            } catch (Exception ignored) {
                emitStatus("Buscando");
                scheduleReconnect();
            }
        });
    }

    private synchronized void acceptIncoming(Socket incoming) {
        if (!running) {
            try { incoming.close(); } catch (Exception ignored) { }
            return;
        }

        // The smaller service name is the only side that initiates. The other
        // side simply accepts the incoming connection.
        acceptSocket(incoming);
    }

    private synchronized void acceptSocket(Socket s) {
        if (!running) {
            try { s.close(); } catch (Exception ignored) { }
            return;
        }

        try {
            if (socket != null && !socket.isClosed()) {
                s.close();
                return;
            }

            socket = s;
            writer = new PrintWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8
                    ),
                    true
            );

            emitStatus("Conectado");
            pool.execute(() -> readLoop(s));
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            closeSocket();
            scheduleReconnect();
        }
    }

    private void readLoop(Socket s) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            s.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            String line;
            while (running && (line = reader.readLine()) != null) {
                JSObject ret = new JSObject();
                ret.put("data", line);
                notifyListeners("message", ret);
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                if (socket == s) {
                    closeSocket();
                }
            }
            emitStatus("Buscando");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        scheduler.schedule(() -> {
            if (!running || lastPeerHost == null ||
                    (socket != null && !socket.isClosed())) return;
            connectTo(lastPeerHost, lastPeerPort);
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) { }
        socket = null;
        writer = null;
    }

    private void emitStatus(String status) {
        main.post(() -> {
            JSObject ret = new JSObject();
            ret.put("status", status);
            notifyListeners("status", ret);
        });
    }

    private synchronized void stopAll() {
        running = false;
        closeSocket();

        try {
            if (discoveryListener != null) nsd.stopServiceDiscovery(discoveryListener);
        } catch (Exception ignored) { }

        try {
            if (registrationListener != null) nsd.unregisterService(registrationListener);
        } catch (Exception ignored) { }

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }

        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) { }

        discoveryListener = null;
        registrationListener = null;
        serverSocket = null;
        lastPeerHost = null;
    }

    @Override
    protected void handleOnDestroy() {
        stopAll();
        pool.shutdownNow();
        scheduler.shutdownNow();
        super.handleOnDestroy();
    }
}
