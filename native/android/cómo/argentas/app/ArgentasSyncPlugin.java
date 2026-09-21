package com.argentas.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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

@CapacitorPlugin(name = "ArgentasSync")
public class ArgentasSyncPlugin extends Plugin {
    private static final String SERVICE_TYPE = "_argentas._tcp.";
    private static final int PORT = 38741;
    private static final String SERVICE_PREFIX = "Argentas-";

    private NsdManager nsd;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private ServerSocket serverSocket;
    private Socket socket;
    private PrintWriter writer;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private String serviceName;

    @Override
    public void load() {
        super.load();
        nsd = (NsdManager) getContext().getSystemService(Context.NSD_SERVICE);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!running) startLocalDiscovery();
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String data = call.getString("data", "");
        if (data != null && !data.isEmpty() && writer != null) {
            synchronized (this) {
                writer.println(data);
                writer.flush();
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
        pool.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                emitStatus("Buscando");
                pool.execute(() -> {
                    while (running && !serverSocket.isClosed()) {
                        try {
                            Socket incoming = serverSocket.accept();
                            acceptSocket(incoming);
                        } catch (Exception ignored) { }
                    }
                });
                registerService();
                discoverServices();
            } catch (Exception e) {
                emitStatus("Desconectado");
            }
        });
    }

    private void registerService() {
        String id = android.provider.Settings.Secure.getString(
                getContext().getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        serviceName = SERVICE_PREFIX + (id == null ? Integer.toHexString((int)(Math.random()*0xFFFFFF)) : id);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { }
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener); } catch (Exception ignored) { }
    }

    private void discoverServices() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (info.getServiceName() != null && info.getServiceName().equals(serviceName)) return;
                if (info.getServiceType() != null && info.getServiceType().startsWith("_argentas._tcp")) {
                    try { nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) { }
                        @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            connectTo(serviceInfo.getHost(), serviceInfo.getPort());
                        }
                    }); } catch (Exception ignored) { }
                }
            }
            @Override public void onServiceLost(NsdServiceInfo info) { if (socket == null || socket.isClosed()) emitStatus("Desconectado"); }
            @Override public void onDiscoveryStopped(String regType) { }
            @Override public void onStartDiscoveryFailed(String regType, int errorCode) { emitStatus("Desconectado"); }
            @Override public void onStopDiscoveryFailed(String regType, int errorCode) { }
        };
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener); } catch (Exception ignored) { }
    }

    private synchronized void connectTo(InetAddress host, int port) {
        if (!running || (socket != null && !socket.isClosed())) return;
        pool.execute(() -> {
            try {
                Socket s = new Socket(host, port);
                acceptSocket(s);
            } catch (Exception ignored) { emitStatus("Buscando"); }
        });
    }

    private synchronized void acceptSocket(Socket s) {
        if (!running) { try { s.close(); } catch(Exception ignored){} return; }
        try {
            if (socket != null && !socket.isClosed()) { s.close(); return; }
            socket = s;
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            emitStatus("Conectado");
            pool.execute(() -> readLoop(socket));
        } catch (Exception e) {
            closeSocket();
        }
    }

    private void readLoop(Socket s) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = reader.readLine()) != null) {
                JSObject ret = new JSObject();
                ret.put("data", line);
                notifyListeners("message", ret);
            }
        } catch (Exception ignored) { }
        finally {
            closeSocket();
            emitStatus("Buscando");
        }
    }

    private synchronized void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
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
        try { if (discoveryListener != null) nsd.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) { }
        try { if (registrationListener != null) nsd.unregisterService(registrationListener); } catch (Exception ignored) { }
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        discoveryListener = null;
        registrationListener = null;
        serverSocket = null;
    }

    @Override
    protected void handleOnDestroy() {
        stopAll();
        pool.shutdownNow();
        super.handleOnDestroy();
    }
}
