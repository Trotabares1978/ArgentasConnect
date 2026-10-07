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
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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
    private static final int REQ_BT = 41, REQ_DISCOVERABLE = 42;
    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean running = true;
    private TextView status, role, device, stats, log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered = false;
    private int sent = 0, received = 0;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) {
                    append("🔎 Encontrado: " + safeName(d));
                    try { d.fetchUuidsWithSdp(); } catch (Exception ignored) {}
                }
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && hasArgentasLinkService(d)) {
                    append("✓ Servicio ArgentasLink encontrado");
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
        if (adapter == null) { setStatus("BLUETOOTH NO DISPONIBLE", Color.RED); return; }
        registerReceiver();
        requestPermissionsIfNeeded();
    }

    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size); t.setTextColor(Color.WHITE); t.setPadding(20,12,20,12);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setPadding(12,10,12,10);
        c.setBackgroundColor(Color.rgb(27,34,40));
        return c;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20,18,20,12);
        root.setBackgroundColor(Color.rgb(12,15,18));

        TextView title = label("🍔  ArgentasLink", 28);
        title.setGravity(Gravity.CENTER); title.setTextColor(Color.rgb(255,193,7));
        root.addView(title, new LinearLayout.LayoutParams(-1,70));

        status = label("● INICIANDO", 21); status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1,82));

        role = label("Elegí el rol de este dispositivo", 17); role.setGravity(Gravity.CENTER);
        root.addView(role, new LinearLayout.LayoutParams(-1,60));

        Button server = new Button(this);
        server.setText("📡  TABLET · ESPERAR CONEXIÓN");
        server.setOnClickListener(v -> startServerMode());
        root.addView(server);

        Button client = new Button(this);
        client.setText("🔎  CELULAR · BUSCAR TABLET");
        client.setOnClickListener(v -> startClientMode());
        root.addView(client);

        device = label("Dispositivo: —", 16);
        stats = label("Enviados: 0    Recibidos: 0", 15);
        LinearLayout info = card(); info.addView(device); info.addView(stats);
        root.addView(info, new LinearLayout.LayoutParams(-1,110));

        Button ping = new Button(this);
        ping.setText("ENVIAR MENSAJE DE PRUEBA");
        ping.setOnClickListener(v -> send("PING"));
        root.addView(ping);

        TextView events = label("REGISTRO", 15); events.setTextColor(Color.rgb(255,193,7));
        root.addView(events);

        log = label("", 13);
        log.setGravity(Gravity.TOP); log.setBackgroundColor(Color.rgb(18,22,26));
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
        super.onRequestPermissionsResult(r,p,g); if (r == REQ_BT) startReady();
    }

    private void startReady() {
        if (!adapter.isEnabled()) { setStatus("● BLUETOOTH APAGADO", Color.rgb(255,152,0)); append("Activá Bluetooth."); return; }
        setStatus("● LISTO", Color.rgb(66,217,107)); append("ArgentasLink listo.");
    }

    private void startServerMode() {
        if (!adapter.isEnabled()) return;
        stopDiscovery();
        role.setText("MODO SERVIDOR · TABLET");
        setStatus("● ESPERANDO CELULAR", Color.rgb(66,217,107));
        append("Tablet visible durante 5 minutos.");
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
        } catch (Exception e) { append("Servidor: " + e.getMessage()); }
    }

    private void startClientMode() {
        if (!adapter.isEnabled()) return;
        role.setText("MODO CLIENTE · CELULAR");
        setStatus("● BUSCANDO ARGENTASLINK", Color.rgb(41,182,246));
        append("Buscando solamente el servicio ArgentasLink...");
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
            s.connect(); attach(s);
        } catch (Exception e) {
            append("Conexión fallida; buscando nuevamente...");
            setStatus("● BUSCANDO ARGENTASLINK", Color.rgb(41,182,246));
            main.postDelayed(this::startClientMode, 1500);
        }
    }

    private synchronized void attach(BluetoothSocket s) {
        try {
            if (socket != null && socket.isConnected()) { s.close(); return; }
            socket = s; out = s.getOutputStream();
            setStatus("● CONECTADO", Color.rgb(66,217,107));
            append("✓ CONEXIÓN ESTABLECIDA");
            try { device.setText("Dispositivo: " + safeName(s.getRemoteDevice())); } catch(Exception ignored) {}
            new Thread(() -> readLoop(s), "ArgentasLink-reader").start();
            send("HELLO|ArgentasLink");
        } catch (Exception e) { closeConnection(); }
    }

    private void readLoop(BluetoothSocket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = r.readLine()) != null) {
                final String m = line; received++; refreshStats();
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
            if (out == null || socket == null || !socket.isConnected()) { append("⚠ Sin conexión"); return; }
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            sent++; refreshStats(); append("→ " + message);
        } catch(Exception e) { closeConnection(); }
    }

    private void stopDiscovery() {
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch(Exception ignored) {}
    }

    private String safeName(BluetoothDevice d) {
        try { return d.getName() == null ? "dispositivo Bluetooth" : d.getName(); } catch(Exception e) { return "dispositivo Bluetooth"; }
    }

    private synchronized void closeConnection() {
        try { if (socket != null) socket.close(); } catch(Exception ignored) {}
        socket = null; out = null; setStatus("● DESCONECTADO", Color.rgb(255,82,82));
    }

    private void setStatus(String s, int color) { main.post(() -> { status.setText(s); status.setTextColor(color); }); }
    private void refreshStats() { main.post(() -> stats.setText("Enviados: " + sent + "    Recibidos: " + received)); }
    private void append(String s) { main.post(() -> { if (log != null) log.append(s + "\n"); }); }

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND); f.addAction(BluetoothDevice.ACTION_UUID);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(discoveryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(discoveryReceiver, f);
        receiverRegistered = true;
    }

    @Override protected void onDestroy() {
        running = false; stopDiscovery();
        if (receiverRegistered) try { unregisterReceiver(discoveryReceiver); } catch(Exception ignored) {}
        closeConnection(); super.onDestroy();
    }
}
