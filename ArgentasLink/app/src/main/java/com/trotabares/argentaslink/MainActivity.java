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
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("7c1f3f70-6e7c-4b6f-9a1b-2d3e4f5a6b70");
    private static final int REQ_BT = 41;
    private static final int REQ_DISCOVERABLE = 42;
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean running = true;
    private TextView status, role, log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered = false;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    append("Encontrado: " + safeName(d));
                    try {
                        if (d.fetchUuidsWithSdp()) append("Verificando servicio ArgentasLink...");
                    } catch (Exception e) { append("No se pudo verificar el servicio"); }
                }
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && hasArgentasLinkService(d)) {
                    append("✓ ArgentasLink detectado: " + safeName(d));
                    stopDiscovery();
                    new Thread(() -> tryConnect(d), "ArgentasLink-client").start();
                }
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { setStatus("Bluetooth no disponible"); return; }
        registerReceiver();
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32,32,32,32);

        TextView title = new TextView(this);
        title.setText("🍔  ArgentasLink");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1,90));

        status = new TextView(this);
        status.setTextSize(21);
        status.setGravity(Gravity.CENTER);
        status.setText("● INICIANDO");
        root.addView(status, new LinearLayout.LayoutParams(-1,100));

        role = new TextView(this);
        role.setTextSize(16);
        role.setGravity(Gravity.CENTER);
        role.setText("Elegí qué dispositivo es este");
        root.addView(role, new LinearLayout.LayoutParams(-1,70));

        Button server = new Button(this);
        server.setText("📡 SOY TABLET / SERVIDOR");
        server.setOnClickListener(v -> startServerMode());
        root.addView(server);

        Button client = new Button(this);
        client.setText("🔎 SOY CELULAR / CLIENTE");
        client.setOnClickListener(v -> startClientMode());
        root.addView(client);

        Button ping = new Button(this);
        ping.setText("Enviar prueba");
        ping.setOnClickListener(v -> send("PING"));
        root.addView(ping);

        log = new TextView(this);
        log.setTextSize(14);
        log.setPadding(0,24,0,0);
        root.addView(log, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT);
                return;
            }
        }
        startReady();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ_BT) startReady();
    }

    private void startReady() {
        if (!adapter.isEnabled()) {
            setStatus("● BLUETOOTH APAGADO");
            append("Activá Bluetooth y volvé a intentar.");
            return;
        }
        setStatus("● LISTO");
        append("ArgentasLink listo.");
    }

    private void startServerMode() {
        if (!adapter.isEnabled()) return;
        stopDiscovery();
        role.setText("MODO SERVIDOR · TABLET");
        setStatus("● ESPERANDO CELULAR");
        append("Iniciando servidor ArgentasLink...");
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(i, REQ_DISCOVERABLE);
        } catch (Exception e) { append("No se pudo activar visibilidad Bluetooth"); }
        new Thread(this::serverLoop, "ArgentasLink-server").start();
    }

    private void serverLoop() {
        try {
            BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord("ArgentasLink", SERVICE_UUID);
            append("✓ Servicio ArgentasLink publicado.");
            while (running && socket == null) {
                BluetoothSocket s = server.accept();
                if (s != null) attach(s);
            }
            try { server.close(); } catch(Exception ignored) {}
        } catch (Exception e) {
            append("Servidor: " + e.getMessage());
        }
    }

    private void startClientMode() {
        if (!adapter.isEnabled()) return;
        role.setText("MODO CLIENTE · CELULAR");
        setStatus("● BUSCANDO ARGENTASLINK");
        append("Buscando únicamente servicios ArgentasLink...");
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            adapter.startDiscovery();
        } catch (Exception e) { append("No se pudo iniciar la búsqueda Bluetooth"); }
    }

    private boolean hasArgentasLinkService(BluetoothDevice d) {
        try {
            android.os.ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) return false;
            for (android.os.ParcelUuid u : uuids) if (SERVICE_UUID.equals(u.getUuid())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void tryConnect(BluetoothDevice d) {
        try {
            append("Conectando con " + safeName(d) + "...");
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SERVICE_UUID);
            s.connect();
            attach(s);
        } catch (Exception e) {
            append("Conexión fallida: " + e.getMessage());
            setStatus("● BUSCANDO ARGENTASLINK");
            main.postDelayed(this::startClientMode, 1500);
        }
    }

    private synchronized void attach(BluetoothSocket s) {
        try {
            if (socket != null && socket.isConnected()) { s.close(); return; }
            socket = s;
            out = s.getOutputStream();
            setStatus("● CONECTADO");
            append("✓ CONEXIÓN ARGENTASLINK ESTABLECIDA");
            new Thread(() -> readLoop(s), "ArgentasLink-reader").start();
            send("HELLO|ArgentasLink");
        } catch (Exception e) { closeConnection(); }
    }

    private void readLoop(BluetoothSocket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = r.readLine()) != null) {
                final String m = line;
                main.post(() -> append("← " + m));
                if ("PING".equals(m)) send("PONG");
            }
        } catch (Exception ignored) {
        } finally {
            closeConnection();
            if (role.getText().toString().contains("CELULAR")) main.postDelayed(this::startClientMode, 1500);
        }
    }

    private synchronized void send(String message) {
        try {
            if (out == null || socket == null || !socket.isConnected()) {
                append("⚠ Sin conexión");
                return;
            }
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            append("→ " + message);
        } catch(Exception e) { closeConnection(); }
    }

    private void stopDiscovery() {
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch(Exception ignored) {}
    }

    private String safeName(BluetoothDevice d) {
        try { return d.getName() == null ? "dispositivo Bluetooth" : d.getName(); }
        catch(Exception e) { return "dispositivo Bluetooth"; }
    }

    private synchronized void closeConnection() {
        try { if (socket != null) socket.close(); } catch(Exception ignored) {}
        socket = null;
        out = null;
        setStatus("● DESCONECTADO");
    }

    private void setStatus(String s) { main.post(() -> status.setText(s)); }
    private void append(String s) { main.post(() -> log.append(s + "\n")); }

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_UUID);
        registerReceiver(discoveryReceiver, f);
        receiverRegistered = true;
    }

    @Override protected void onDestroy() {
        running = false;
        stopDiscovery();
        if (receiverRegistered) try { unregisterReceiver(discoveryReceiver); } catch(Exception ignored) {}
        closeConnection();
        super.onDestroy();
    }
}
