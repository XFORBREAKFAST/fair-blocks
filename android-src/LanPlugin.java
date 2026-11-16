package com.dzaky.fairblocks;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LAN 1v1 untuk Fair Blocks.
 *  - host():     buka server TCP di port 47800 + umumkan room lewat NSD (mDNS) di Wi-Fi/hotspot.
 *  - discover(): cari room lain di jaringan yang sama (event "peers").
 *  - connect():  sambung ke host (IP manual juga bisa).
 *  - send():     kirim 1 pesan JSON (1 baris). Pesan masuk -> event "message".
 * Tanpa internet. Tanpa library tambahan.
 */
@CapacitorPlugin(name = "Lan")
public class LanPlugin extends Plugin {
    static final String TYPE = "_fairblocks._tcp.";
    static final int PORT = 47800;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JSObject> peers = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private ServerSocket server;
    private Socket sock;
    private BufferedWriter out;
    private NsdManager nsd;
    private NsdManager.RegistrationListener regListener;
    private NsdManager.DiscoveryListener discListener;
    private WifiManager.MulticastLock lock;

    @Override
    public void load() {
        nsd = (NsdManager) getContext().getSystemService(Context.NSD_SERVICE);
    }

    private void acquireLock() {
        try {
            if (lock == null) {
                WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("fairblocks");
                    lock.setReferenceCounted(false);
                }
            }
            if (lock != null && !lock.isHeld()) lock.acquire();
        } catch (Exception ignored) { }
    }

    // ---------------- host ----------------
    @PluginMethod
    public void host(PluginCall call) {
        final String name = call.getString("name", "Room");
        closeAll();
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            call.reject("Port " + PORT + " tidak bisa dibuka: " + e.getMessage());
            return;
        }
        final ServerSocket srv = server;
        io.execute(() -> {
            while (!srv.isClosed()) {
                try {
                    Socket s = srv.accept();
                    if (sock != null) { try { sock.close(); } catch (Exception ignored) { } }
                    attach(s);
                } catch (Exception e) {
                    break;
                }
            }
        });
        registerNsd(name);
        JSObject r = new JSObject();
        r.put("ips", localIps());
        r.put("port", PORT);
        call.resolve(r);
    }

    private void registerNsd(String name) {
        if (nsd == null) return;
        acquireLock();
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(TYPE);
        info.setPort(PORT);
        regListener = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { }
            public void onRegistrationFailed(NsdServiceInfo i, int code) { }
            public void onServiceUnregistered(NsdServiceInfo i) { }
            public void onUnregistrationFailed(NsdServiceInfo i, int code) { }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Exception e) {
            regListener = null;
        }
    }

    // ---------------- discover ----------------
    @PluginMethod
    public void discover(PluginCall call) {
        if (nsd == null) { call.reject("NSD tidak tersedia"); return; }
        stopDiscovery();
        acquireLock();
        peers.clear();
        discListener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onStartDiscoveryFailed(String t, int c) { }
            public void onStopDiscoveryFailed(String t, int c) { }
            public void onServiceFound(NsdServiceInfo si) {
                if (si.getServiceType() == null || !si.getServiceType().contains("_fairblocks")) return;
                resolve(si, 0);
            }
            public void onServiceLost(NsdServiceInfo si) {
                peers.remove(si.getServiceName());
                emitPeers();
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discListener);
            call.resolve();
        } catch (Exception e) {
            discListener = null;
            call.reject(e.getMessage());
        }
    }

    private void resolve(final NsdServiceInfo si, final int attempt) {
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int code) {
                    // Android lama hanya bisa resolve satu per satu -> coba lagi sebentar lagi
                    if (attempt < 5) main.postDelayed(() -> resolve(si, attempt + 1), 400);
                }
                public void onServiceResolved(NsdServiceInfo s) {
                    if (s.getHost() == null) return;
                    JSObject p = new JSObject();
                    p.put("name", s.getServiceName());
                    p.put("host", s.getHost().getHostAddress());
                    p.put("port", s.getPort());
                    peers.put(s.getServiceName(), p);
                    emitPeers();
                }
            });
        } catch (Exception ignored) { }
    }

    private void emitPeers() {
        JSArray a = new JSArray();
        for (JSObject p : peers.values()) a.put(p);
        JSObject r = new JSObject();
        r.put("peers", a);
        notifyListeners("peers", r);
    }

    // ---------------- connect / messaging ----------------
    @PluginMethod
    public void connect(final PluginCall call) {
        final String host = call.getString("host");
        final int port = call.getInt("port", PORT);
        if (host == null || host.isEmpty()) { call.reject("IP kosong"); return; }
        stopDiscovery();
        io.execute(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                attach(s);
                call.resolve();
            } catch (Exception e) {
                call.reject("Gagal konek ke " + host + ": " + e.getMessage());
            }
        });
    }

    private void attach(final Socket s) throws Exception {
        sock = s;
        s.setTcpNoDelay(true);
        synchronized (writeLock) {
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        notifyListeners("connected", new JSObject());
        io.execute(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    JSObject m = new JSObject();
                    m.put("data", line);
                    notifyListeners("message", m);
                }
            } catch (Exception ignored) { }
            if (sock == s) {
                sock = null;
                notifyListeners("disconnected", new JSObject());
            }
        });
    }

    @PluginMethod
    public void send(PluginCall call) {
        final String data = call.getString("data", "");
        io.execute(() -> {
            synchronized (writeLock) {
                try {
                    if (out != null) {
                        out.write(data.replace('\n', ' '));
                        out.write('\n');
                        out.flush();
                    }
                } catch (Exception ignored) { }
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        closeAll();
        call.resolve();
    }

    private void stopDiscovery() {
        try { if (discListener != null && nsd != null) nsd.stopServiceDiscovery(discListener); } catch (Exception ignored) { }
        discListener = null;
    }

    private void closeAll() {
        stopDiscovery();
        try { if (regListener != null && nsd != null) nsd.unregisterService(regListener); } catch (Exception ignored) { }
        regListener = null;
        Socket s = sock; sock = null;
        try { if (s != null) s.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        synchronized (writeLock) { out = null; }
    }

    private JSArray localIps() {
        JSArray a = new JSArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address) a.put(ad.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return a;
    }

    @Override
    protected void handleOnDestroy() {
        closeAll();
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        io.shutdownNow();
    }
}
