package com.trotabares.argentaslink;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.DhcpInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int TCP_PORT = 45678;
    private static final int DISCOVERY_PORT = 45679;
    private static final int LOCAL_BRIDGE_PORT = 45680;
    private static final int REQ_WIFI = 51;

    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ServerSocket serverSocket;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running = true;
    private volatile boolean serverMode = false;
    private ServerSocket localBridgeServer;
    private volatile Socket localBridgeSocket;
    private volatile OutputStream localBridgeOut;
    private final ExecutorService bridgeExecutor = Executors.newCachedThreadPool();
    private TextView status, role, device, stats, network, log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int sent = 0, received = 0;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final Runnable heartbeat = new Runnable() { public void run() { if (!running) return; Socket s=socket; if (s!=null && !s.isClosed()) sendExecutor.execute(() -> sendOnSocket(s,"PING",false)); main.postDelayed(this,4000); } };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        startLocalBridge();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        requestWifiPermissionIfNeeded();
        main.postDelayed(heartbeat,4000);
    }

    private TextView label(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(20, 10, 20, 10);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(12, 8, 12, 8);
        c.setBackgroundColor(Color.rgb(27,34,40));
        return c;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 18, 20, 12);
        root.setBackgroundColor(Color.rgb(12,15,18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_argentas_link);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(appIcon, new LinearLayout.LayoutParams(58,58));
        TextView title = label("ArgentasLink", 28);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(Color.rgb(255,193,7));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2,58);
        titleLp.leftMargin = 10;
        header.addView(title, titleLp);
        root.addView(header, new LinearLayout.LayoutParams(-1,72));

        status = label("● LISTO", 21);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1,70));

        role = label("Elegí el rol de este dispositivo", 17);
        role.setGravity(Gravity.CENTER);
        root.addView(role, new LinearLayout.LayoutParams(-1,58));

        Button server = new Button(this);
        server.setText("📡  TABLET · CREAR RED Y ESPERAR");
        server.setOnClickListener(v -> startServerMode());
        root.addView(server);

        Button client = new Button(this);
        client.setText("🔎  CELULAR · BUSCAR TABLET");
        client.setOnClickListener(v -> startClientMode());
        root.addView(client);

        network = label("Red: —", 15);
        device = label("Dispositivo: —", 15);
        stats = label("Enviados: 0    Recibidos: 0", 15);
        LinearLayout info = card();
        info.addView(network);
        info.addView(device);
        info.addView(stats);
        root.addView(info, new LinearLayout.LayoutParams(-1,145));

        Button ping = new Button(this);
        ping.setText("ENVIAR MENSAJE DE PRUEBA");
        ping.setOnClickListener(v -> send("TEST|Hola desde ArgentasLink"));
        root.addView(ping);

        TextView events = label("REGISTRO", 15);
        events.setTextColor(Color.rgb(255,193,7));
        root.addView(events);

        log = label("", 13);
        log.setGravity(Gravity.TOP);
        log.setBackgroundColor(Color.rgb(18,22,26));
        root.addView(log, new LinearLayout.LayoutParams(-1,0,1));

        setContentView(root);
    }

    private void requestWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_WIFI);
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_WIFI);
                return;
            }
        }
        ready();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == REQ_WIFI) ready();
    }

    private void ready() {
        append("ArgentasLink listo: Wi-Fi local + TCP.");
        setStatus("● LISTO", Color.rgb(66,217,107));
    }

    private void startServerMode() {
        if (Build.VERSION.SDK_INT < 26) {
            append("Este modo necesita Android 8 o superior.");
            return;
        }
        if (hotspotReservation != null) {
            append("La red local ya está activa.");
            return;
        }

        closeSocketOnly();
        closeServerOnly();
        serverMode = true;
        role.setText("MODO SERVIDOR · TABLET");
        setStatus("● CREANDO RED LOCAL", Color.rgb(41,182,246));
        append("Solicitando hotspot Wi-Fi local...");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    showHotspotInfo(reservation);
                    setStatus("● ESPERANDO CELULAR", Color.rgb(66,217,107));
                    append("✓ Red local creada.");
                    startTcpServer();
                    startDiscoveryResponder();
                }

                @Override public void onStopped() {
                    append("⚠ La red local fue detenida.");
                    hotspotReservation = null;
                    setStatus("● RED DETENIDA", Color.rgb(255,152,0));
                }

                @Override public void onFailed(int reason) {
                    hotspotReservation = null;
                    setStatus("● ERROR DE RED", Color.RED);
                    append("No se pudo crear la red local. Código: " + reason);
                }
            }, main);
        } catch (SecurityException e) {
            setStatus("● FALTA PERMISO WI-FI", Color.RED);
            append("Android no concedió el permiso de dispositivos cercanos.");
        } catch (Exception e) {
            setStatus("● ERROR DE RED", Color.RED);
            append("Error: " + e.getMessage());
        }
    }

    private void showHotspotInfo(WifiManager.LocalOnlyHotspotReservation reservation) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration c = reservation.getSoftApConfiguration();
                String ssid = c.getSsid();
                String pass = c.getPassphrase();
                network.setText("Red: " + ssid + "\nClave: " + (pass == null ? "(sin clave)" : pass));
            } else {
                android.net.wifi.WifiConfiguration c = reservation.getWifiConfiguration();
                String ssid = c == null ? "ArgentasLink" : c.SSID;
                String pass = c == null ? "" : c.preSharedKey;
                network.setText("Red: " + ssid + "\nClave: " + (pass == null ? "" : pass));
            }
            append("Conectá el celular a esta red Wi-Fi.");
        } catch (Exception e) {
            network.setText("Red: ArgentasLink\nConectá el celular a la red local.");
            append("No pude leer los datos de la red, pero el hotspot sigue activo.");
        }
    }

    private void startTcpServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(TCP_PORT);
                serverSocket.setReuseAddress(true);
                append("✓ TCP escuchando en puerto " + TCP_PORT);
                while (running && serverMode && !serverSocket.isClosed()) {
                    try {
                        Socket s = serverSocket.accept();
                        if (s != null) {
                            configureSocket(s);
                            attach(s);
                        }
                    } catch (Exception e) {
                        if (running && serverMode && serverSocket != null && !serverSocket.isClosed()) {
                            append("Aceptación TCP: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (running && serverMode) append("Servidor TCP: " + e.getMessage());
            }
        }, "ArgentasLink-tcp-server").start();
    }

    private void startDiscoveryResponder() {
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(DISCOVERY_PORT)) {
                ds.setBroadcast(true);
                byte[] buf = new byte[256];
                while (running && serverMode && hotspotReservation != null) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String msg = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                    if ("DISCOVER|ArgentasLink".equals(msg)) {
                        byte[] reply = ("ARGENTASLINK|" + TCP_PORT).getBytes(StandardCharsets.UTF_8);
                        DatagramPacket r = new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort());
                        ds.send(r);
                        append("→ Tablet anunciada a " + p.getAddress().getHostAddress());
                    }
                }
            } catch (Exception e) {
                if (running && serverMode) append("Descubrimiento: " + e.getMessage());
            }
        }, "ArgentasLink-discovery-server").start();
    }

    private void startClientMode() {
        closeSocketOnly();
        serverMode = false;
        role.setText("MODO CLIENTE · CELULAR");
        setStatus("● BUSCANDO TABLET", Color.rgb(41,182,246));
        append("Conectá primero el celular a la red Wi-Fi de la tablet.");
        new Thread(this::discoverTablet, "ArgentasLink-discovery-client").start();
    }

    private void discoverTablet() {
        try { InetAddress gateway=getWifiGateway(); if(gateway!=null){ append("Probando tablet por puerta de enlace: "+gateway.getHostAddress()); tryConnect(gateway,TCP_PORT,1400); if(socket!=null)return; } } catch(Exception e){ append("Prueba directa: "+e.getClass().getSimpleName()); }

        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            ds.setSoTimeout(2500);
            byte[] msg = "DISCOVER|ArgentasLink".getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(msg, msg.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);

            for (int i = 0; i < 8 && running && socket == null; i++) {
                ds.send(request);
                InetAddress sb=getSubnetBroadcast();
                if(sb!=null && !sb.getHostAddress().equals("255.255.255.255")) ds.send(new DatagramPacket(msg,msg.length,sb,DISCOVERY_PORT));
                append("Buscando ArgentasLink... intento " + (i + 1));
                try {
                    byte[] buf = new byte[256];
                    DatagramPacket response = new DatagramPacket(buf, buf.length);
                    ds.receive(response);
                    String reply = new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8);
                    if (reply.startsWith("ARGENTASLINK|")) {
                        final InetAddress host = response.getAddress();
                        append("✓ Tablet encontrada: " + host.getHostAddress());
                        int port = Integer.parseInt(reply.substring("ARGENTASLINK|".length()));
                        new Thread(() -> tryConnect(host, port), "ArgentasLink-tcp-client").start();
                        return;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                }
                Thread.sleep(700);
            }
            if (socket == null) {
                setStatus("● TABLET NO DISPONIBLE · REINTENTANDO", Color.rgb(255,152,0));
                append("No apareció ninguna tablet ArgentasLink. Voy a seguir buscando automáticamente.");
                scheduleClientReconnect(3000);
            }
        } catch (Exception e) {
            setStatus("● ERROR DE BÚSQUEDA · REINTENTANDO", Color.RED);
            append("Búsqueda: " + e.getMessage());
            scheduleClientReconnect(3000);
        }
    }

    private void scheduleClientReconnect(long delayMs) {
        main.postDelayed(() -> {
            if (running && !serverMode && socket == null) {
                startClientMode();
            }
        }, delayMs);
    }

    private InetAddress getWifiGateway() { if(wifi==null)return null; DhcpInfo d=wifi.getDhcpInfo(); if(d==null||d.gateway==0)return null; int g=d.gateway; String ip=(g&255)+"."+((g>>8)&255)+"."+((g>>16)&255)+"."+((g>>24)&255); try{return InetAddress.getByName(ip);}catch(Exception e){return null;} }

    private InetAddress getSubnetBroadcast() { if(wifi==null)return null; DhcpInfo d=wifi.getDhcpInfo(); if(d==null||d.ipAddress==0||d.netmask==0)return null; int b=d.ipAddress|~d.netmask; String ip=(b&255)+"."+((b>>8)&255)+"."+((b>>16)&255)+"."+((b>>24)&255); try{return InetAddress.getByName(ip);}catch(Exception e){return null;} }

    private void tryConnect(InetAddress host, int port) { tryConnect(host,port,5000); }

    private void tryConnect(InetAddress host, int port, int timeoutMs) {
        try {
            append("Conectando por TCP...");
            Socket s = new Socket();
            configureSocket(s);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            attach(s);
        } catch (Exception e) {
            setStatus("● TABLET NO DISPONIBLE", Color.rgb(255,152,0));
            append("No se pudo abrir TCP: " + e.getClass().getSimpleName());
        }
    }

    private void configureSocket(Socket s) throws Exception {
        s.setKeepAlive(true);
        s.setTcpNoDelay(true);
        s.setSoTimeout(0);
    }

    private synchronized void attach(Socket s) {
        try {
            if (!running || !s.isConnected() || s.isClosed()) {
                try { s.close(); } catch (Exception ignored) {}
                return;
            }

            Socket old = socket;
            if (old != null && !old.isClosed()) {
                try { old.close(); } catch (Exception ignored) {}
            }

            socket = s;
            out = s.getOutputStream();
            sendLocalBridgeState(true);
            setStatus("● CONECTADO", Color.rgb(66,217,107));
            append("✓ CONEXIÓN WI-FI/TCP ESTABLECIDA");
            device.setText("Dispositivo: " + s.getInetAddress().getHostAddress());
            new Thread(() -> readLoop(s), "ArgentasLink-reader").start();

            // El saludo no depende del botón de prueba y se envía sobre el socket recién asociado.
            sendExecutor.execute(() -> sendOnSocket(s, "HELLO|ArgentasLink", true));
        } catch (Exception e) {
            append("Error al asociar TCP: " + e.getMessage());
            closeSocketIfSame(s);
        }
    }

    private void startLocalBridge() {
        bridgeExecutor.execute(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_BRIDGE_PORT));
                localBridgeServer = ss;
                append("✓ Puente local ArgentasComandas en 127.0.0.1:" + LOCAL_BRIDGE_PORT);
                while (running && !ss.isClosed()) {
                    Socket c = ss.accept();
                    configureSocket(c);
                    synchronized (this) {
                        if (localBridgeSocket != null) {
                            try { localBridgeSocket.close(); } catch (Exception ignored) {}
                        }
                        localBridgeSocket = c;
                        localBridgeOut = c.getOutputStream();
                    }
                    sendLocalBridgeState(socket != null && !socket.isClosed());
                    bridgeExecutor.execute(() -> readLocalBridge(c));
                }
            } catch (Exception e) {
                if (running) append("Puente local: " + e.getClass().getSimpleName() +
                        (e.getMessage() == null ? "" : " · " + e.getMessage()));
            }
        });
    }

    private void readLocalBridge(Socket c) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && !c.isClosed() && (line = r.readLine()) != null) {
                if (line.startsWith("APP|")) {
                    String message = line.substring(4);
                    Socket remote = socket;
                    if (remote != null) {
                        sendExecutor.execute(() -> sendOnSocket(remote, message));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                if (localBridgeSocket == c) {
                    localBridgeSocket = null;
                    localBridgeOut = null;
                }
            }
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void sendLocalBridgeState(boolean remoteConnected) {
        OutputStream stream = localBridgeOut;
        if (stream == null) return;
        try {
            stream.write(("LINK_STATE|" + (remoteConnected ? "CONECTADO" : "DESCONECTADO") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception e) {
            try { if (localBridgeSocket != null) localBridgeSocket.close(); } catch (Exception ignored) {}
            localBridgeSocket = null;
            localBridgeOut = null;
        }
    }

    private synchronized void sendLocalBridgeMessage(String message) {
        OutputStream stream = localBridgeOut;
        if (stream == null) return;
        try {
            String clean = message.replace("\r", "").replace("\n", "");
            stream.write(("APP|" + clean + "\n").getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception e) {
            try { if (localBridgeSocket != null) localBridgeSocket.close(); } catch (Exception ignored) {}
            localBridgeSocket = null;
            localBridgeOut = null;
        }
    }

    private void readLoop(Socket s) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && !s.isClosed() && (line = r.readLine()) != null) {
                final String m = line;
                received++;
                refreshStats();
                main.post(() -> append("← " + m));
                if ("PING".equals(m)) sendExecutor.execute(() -> sendOnSocket(s, "PONG", false));
                else if (!m.equals("HELLO|ArgentasLink") && !m.equals("PONG") && !m.equals("PING")) sendLocalBridgeMessage(m);
            }
        } catch (Exception e) {
            if (running) append("TCP cerrado: " + e.getClass().getSimpleName() +
                    (e.getMessage() == null ? "" : " · " + e.getMessage()));
        } finally {
            closeSocketIfSame(s);
            if (!serverMode && running) {
                main.postDelayed(() -> {
                    if (!serverMode && socket == null && running) startClientMode();
                }, 1500);
            }
        }
    }

    private void sendOnSocket(Socket s, String message) { sendOnSocket(s,message,true); }

    private void sendOnSocket(Socket s, String message, boolean showLog) {
        try {
            OutputStream stream;
            synchronized (this) {
                if (s == null || s.isClosed() || !s.isConnected() || socket != s || out == null) {
                    append("⚠ Sin conexión para enviar.");
                    return;
                }
                stream = out;
            }
            stream.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            stream.flush();
            if (showLog) { sent++; refreshStats(); append("→ " + message); }
        } catch (Exception e) {
            append("Error enviando '" + message + "': " +
                    e.getClass().getSimpleName() +
                    (e.getMessage() == null ? "" : " · " + e.getMessage()));
            closeSocketIfSame(s);
        }
    }

    private void send(String message) {
        Socket s = socket;
        if (s == null) {
            append("⚠ Sin conexión.");
            return;
        }
        // El botón de prueba se ejecuta en el hilo de UI. Nunca hacemos I/O de red
        // directamente allí: Android lanza NetworkOnMainThreadException y termina
        // cerrando el socket. Enviamos por un único hilo dedicado para serializar
        // los writes y mantener la conexión viva.
        sendExecutor.execute(() -> sendOnSocket(s, message));
    }

    private synchronized void closeSocketIfSame(Socket expected) {
        if (socket != expected) return;
        try { if (expected != null) expected.close(); } catch (Exception ignored) {}
        sendLocalBridgeState(false);
        socket = null;
        out = null;
        if (status != null && running) setStatus("● DESCONECTADO", Color.rgb(255,82,82));
    }

    private synchronized void closeSocketOnly() {
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
        socket = null;
        out = null;
        if (status != null && running) setStatus("● DESCONECTADO", Color.rgb(255,82,82));
    }

    private synchronized void closeServerOnly() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
        serverSocket = null;
    }

    private void setStatus(String s, int color) {
        main.post(() -> {
            status.setText(s);
            status.setTextColor(color);
        });
    }

    private void refreshStats() {
        main.post(() -> stats.setText("Enviados: " + sent + "    Recibidos: " + received));
    }

    private void append(String s) {
        main.post(() -> {
            if (log != null) log.append(s + "\n");
        });
    }

    @Override protected void onDestroy() {
        running = false;
        serverMode = false;
        main.removeCallbacks(heartbeat);
        closeSocketOnly();
        closeServerOnly();
        try { if (hotspotReservation != null) hotspotReservation.close(); } catch (Exception ignored) {}
        hotspotReservation = null;
        sendExecutor.shutdownNow();
        try { if (localBridgeServer != null) localBridgeServer.close(); } catch (Exception ignored) {}
        try { if (localBridgeSocket != null) localBridgeSocket.close(); } catch (Exception ignored) {}
        localBridgeServer = null;
        localBridgeSocket = null;
        localBridgeOut = null;
        bridgeExecutor.shutdownNow();
        super.onDestroy();
    }
}