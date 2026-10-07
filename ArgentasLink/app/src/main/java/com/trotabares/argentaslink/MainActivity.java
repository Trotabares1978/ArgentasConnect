package com.trotabares.argentaslink;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("7c1f3f70-6e7c-4b6f-9a1b-2d3e4f5a6b70");
    private static final int REQ_BT = 41;
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean running = true;
    private TextView status, log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> tried = new HashSet<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { setStatus("Bluetooth no disponible"); return; }
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32,32,32,32);
        status = new TextView(this); status.setTextSize(22); status.setGravity(Gravity.CENTER); status.setText("● INICIANDO");
        Button scan = new Button(this); scan.setText("Buscar ArgentasLink"); scan.setOnClickListener(v -> scanAndConnect());
        Button ping = new Button(this); ping.setText("Enviar prueba"); ping.setOnClickListener(v -> send("PING"));
        log = new TextView(this); log.setTextSize(14); log.setPadding(0,24,0,0);
        root.addView(status, new LinearLayout.LayoutParams(-1,120)); root.addView(scan); root.addView(ping); root.addView(log, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT); return;
            }
        }
        startLink();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { super.onRequestPermissionsResult(r,p,g); if (r==REQ_BT) startLink(); }

    private void startLink() {
        if (!adapter.isEnabled()) { setStatus("Bluetooth apagado"); return; }
        setStatus("● ESPERANDO / BUSCANDO");
        new Thread(this::serverLoop, "ArgentasLink-server").start();
        main.postDelayed(this::scanAndConnect, 1200);
    }

    private void serverLoop() {
        try {
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("ArgentasLink", SERVICE_UUID);
            while (running) { BluetoothSocket s = server.accept(); if (s != null) { if (socket == null || !socket.isConnected()) attach(s); else try { s.close(); } catch(Exception ignored) {} } }
            server.close();
        } catch (Exception e) { append("Servidor: " + e.getMessage()); }
    }

    private void scanAndConnect() {
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d == null || d.getAddress() == null || tried.contains(d.getAddress())) continue;
                tried.add(d.getAddress());
                new Thread(() -> tryConnect(d), "ArgentasLink-client").start();
            }
        } catch (SecurityException e) { append("Permiso Bluetooth requerido"); }
    }

    private void tryConnect(BluetoothDevice d) {
        try {
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SERVICE_UUID);
            s.connect(); attach(s); append("Conectado a servicio ArgentasLink");
        } catch (Exception e) { /* No era ArgentasLink o no está disponible. */ }
    }

    private synchronized void attach(BluetoothSocket s) {
        try {
            if (socket != null && socket.isConnected()) { s.close(); return; }
            socket = s; out = s.getOutputStream(); setStatus("● CONECTADO");
            new Thread(() -> readLoop(s), "ArgentasLink-reader").start();
            send("HELLO|ArgentasLink");
        } catch (Exception e) { closeConnection(); }
    }

    private void readLoop(BluetoothSocket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line; while (running && (line=r.readLine()) != null) { final String m=line; main.post(() -> append("← " + m)); if ("PING".equals(m)) send("PONG"); }
        } catch (Exception ignored) {} finally { closeConnection(); main.postDelayed(this::scanAndConnect, 1500); }
    }

    private synchronized void send(String message) {
        try { if (out == null || socket == null || !socket.isConnected()) return; out.write((message+"\n").getBytes(StandardCharsets.UTF_8)); out.flush(); append("→ " + message); } catch(Exception e) { closeConnection(); }
    }

    private synchronized void closeConnection() { try { if (socket != null) socket.close(); } catch(Exception ignored) {} socket=null; out=null; setStatus("● DESCONECTADO"); }
    private void setStatus(String s) { main.post(() -> status.setText(s)); }
    private void append(String s) { main.post(() -> log.append(s+"\n")); }

    @Override protected void onDestroy() { running=false; closeConnection(); super.onDestroy(); }
}
