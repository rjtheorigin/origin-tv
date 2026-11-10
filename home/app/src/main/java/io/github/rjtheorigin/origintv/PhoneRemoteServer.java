package io.github.rjtheorigin.origintv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, local-only web bridge hosted by the system-bound accessibility service, with no foreground notification. */
final class PhoneRemoteServer {
    private static final String TAG = "PhoneRemote";
    private static final int PORT = 8844;
    private static final int MAX_BODY = 2048;
    private static final int MAX_LINE = 8192;
    private static final String PREFS = "phone_remote";
    private static final String TOKENS = "token_hashes";
    private static final String ICON_PATH = "/api/icon/";
    private static final String PACKAGE_NAME = "[a-zA-Z0-9_.]{2,100}";
    /** App art is the one answer a phone may keep: the page reloads it on every visit otherwise. */
    private static final String KEEP_A_WEEK = "private, max-age=604800";
    private final PhoneRemoteService service;
    private final SecureRandom random = new SecureRandom();
    // Eight, as a phone's Live TV tab loads several schedules while video segments are passing through.
    private final ExecutorService clients = Executors.newFixedThreadPool(8);
    private final SharedPreferences prefs;
    private final PhoneRemoteLive live;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private volatile ServerSocket listener;
    private volatile String address;
    private volatile int subnetPrefix;
    private String pairCode;
    private long pairExpires;
    private int attempts;

    PhoneRemoteServer(PhoneRemoteService service) {
        this.service = service;
        prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        live = new PhoneRemoteLive(service);
    }

    void start() {
        connectivity = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { bind(connectivity.getLinkProperties(network)); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { bind(properties); }
            @Override public void onLost(Network network) { closeListener(); }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        connectivity.registerNetworkCallback(request, callback);
    }

    void stop() {
        if (connectivity != null && callback != null) connectivity.unregisterNetworkCallback(callback);
        callback = null;
        closeListener();
        live.stop();
        clients.shutdownNow();
    }

    String address() { return address; }

    synchronized String issueCode() {
        pairCode = String.valueOf(100000 + random.nextInt(900000));
        pairExpires = System.currentTimeMillis() + 120_000;
        attempts = 0;
        return pairCode;
    }

    void revokePhones() { prefs.edit().remove(TOKENS).apply(); }

    private synchronized void bind(LinkProperties properties) {
        if (properties == null || clients.isShutdown()) return;
        Inet4Address ip = null;
        for (android.net.LinkAddress link : properties.getLinkAddresses()) {
            InetAddress candidate = link.getAddress();
            if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) {
                ip = (Inet4Address) candidate;
                subnetPrefix = link.getPrefixLength();
                break;
            }
        }
        if (ip == null) return;
        if (listener != null && !listener.isClosed() && ip.getHostAddress().equals(address)) return;
        closeListener();
        try {
            ServerSocket socket = new ServerSocket(PORT, 4, ip);
            listener = socket;
            address = ip.getHostAddress();
            Thread thread = new Thread(() -> accept(socket), "phone-remote-listener");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "Listening on Wi-Fi only");
        } catch (IOException e) { Log.w(TAG, "Wi-Fi listener unavailable: " + e.getClass().getSimpleName()); }
    }

    private synchronized void closeListener() {
        address = null;
        ServerSocket socket = listener;
        listener = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private void accept(ServerSocket socket) {
        while (!socket.isClosed() && !clients.isShutdown()) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(5000);
                clients.execute(() -> handle(client));
            } catch (IOException e) {
                if (!socket.isClosed()) Log.w(TAG, "Accept stopped: " + e.getClass().getSimpleName());
                break;
            } catch (RuntimeException e) { break; }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            if (!sameWifiSubnet(client.getInetAddress())) return;
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            String first = readLine(in);
            if (first == null) return;
            String[] request = first.split(" ");
            if (request.length != 3 || !"HTTP/1.1".equals(request[2])) return;
            String method = request[0], path = request[1];
            // Only a video path may be long or carry a query: PhoneRemoteLive checks it against its own list.
            boolean video = path.startsWith(PhoneRemoteLive.VIDEO_PATH);
            if (!path.startsWith("/") || path.contains("..")
                    || (!video && (path.contains("?") || path.length() > 70))) return;
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 32; i++) {
                String line = readLine(in);
                if (line == null) return;
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon < 1) return;
                headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            String expectedHost = address + ":" + PORT;
            String origin = "http://" + expectedHost;
            if (!expectedHost.equals(headers.get("host"))) { respond(client, 403, "application/json", error("Invalid host")); return; }
            if (headers.containsKey("origin") && !origin.equals(headers.get("origin"))) {
                respond(client, 403, "application/json", error("Invalid origin")); return;
            }
            int length = 0;
            try { if (headers.containsKey("content-length")) length = Integer.parseInt(headers.get("content-length")); }
            catch (NumberFormatException e) { respond(client, 400, "application/json", error("Invalid length")); return; }
            if (length < 0 || length > MAX_BODY || ("POST".equals(method) && length == 0)) {
                respond(client, 400, "application/json", error("Invalid body")); return;
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) return;
                read += n;
            }
            route(client, method, path, headers, new String(body, StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) { }
    }

    /** Check the actual Wi-Fi prefix, not merely whether the client has a private IP address. */
    private boolean sameWifiSubnet(InetAddress peer) {
        String local = address;
        if (local == null || !(peer instanceof Inet4Address)) return false;
        try {
            byte[] a = InetAddress.getByName(local).getAddress();
            byte[] b = peer.getAddress();
            int bits = subnetPrefix;
            if (bits < 8 || bits > 32) return false;
            for (int i = 0; i < 4; i++) {
                int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : (255 << (8 - bits)) & 255;
                if ((a[i] & mask) != (b[i] & mask)) return false;
                bits -= 8;
            }
            return true;
        } catch (IOException e) { return false; }
    }

    private void route(Socket socket, String method, String path, Map<String, String> headers, String body)
            throws IOException, JSONException {
        if ("GET".equals(method) && "/".equals(path)) {
            try (InputStream asset = service.getAssets().open("phone-remote.html")) {
                respond(socket, 200, "text/html; charset=utf-8", readAsset(asset));
            }
            return;
        }
        if ("GET".equals(method) && "/icon.png".equals(path)) {
            respond(socket, 200, "image/png", PhoneRemoteApps.pageIcon(service), KEEP_A_WEEK);
            return;
        }
        if ("GET".equals(method) && "/api/status".equals(path)) {
            boolean paired = authorised(headers);
            JSONObject status = new JSONObject().put("paired", paired).put("connected", true);
            // The app on screen says what the family is watching, so only a paired phone hears it.
            if (paired) status.put("keyboard", service.keyboardReady()).put("app", service.nowOnTv());
            respond(socket, 200, "application/json", status.toString());
            return;
        }
        if ("POST".equals(method) && "/api/pair".equals(path)) {
            JSONObject json = new JSONObject(body);
            String code = json.optString("code", "");
            synchronized (this) {
                if (pairCode == null || System.currentTimeMillis() > pairExpires || attempts >= 5) {
                    respond(socket, 403, "application/json", error("Open Phone remote on the TV for a new code"));
                    return;
                }
                attempts++;
                if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                        pairCode.getBytes(StandardCharsets.UTF_8))) {
                    respond(socket, 403, "application/json", error("Incorrect code"));
                    return;
                }
                pairCode = null;
            }
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            String token = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            Set<String> stored = new HashSet<>(prefs.getStringSet(TOKENS, Collections.emptySet()));
            if (stored.size() >= 8) stored.clear();
            stored.add(hash(token));
            prefs.edit().putStringSet(TOKENS, stored).apply();
            respond(socket, 200, "application/json", new JSONObject().put("token", token).toString());
            return;
        }
        // A video element cannot send the token header, so these carry a short-lived key in the path instead.
        if ("GET".equals(method) && path.startsWith(PhoneRemoteLive.VIDEO_PATH)) { live.video(socket, path); return; }
        if (!authorised(headers)) { respond(socket, 401, "application/json", error("Pair this device on the TV")); return; }
        if ("POST".equals(method) && "/api/unpair".equals(path)) {
            Set<String> stored = new HashSet<>(prefs.getStringSet(TOKENS, Collections.emptySet()));
            stored.remove(hash(headers.get("x-remote-token")));
            prefs.edit().putStringSet(TOKENS, stored).apply();
            respond(socket, 200, "application/json", "{\"message\":\"OK\"}");
            return;
        }
        if ("POST".equals(method) && "/api/action".equals(path)) {
            JSONObject json = new JSONObject(body);
            String action = json.optString("action", "");
            String value = json.optString("value", "");
            String result = service.perform(action, value);
            int status = "OK".equals(result) ? 200 : 422;
            respond(socket, status, "application/json", new JSONObject().put("message", result).toString());
            return;
        }
        if ("GET".equals(method) && "/api/apps".equals(path)) {
            respond(socket, 200, "application/json", PhoneRemoteApps.list(service).toString());
            return;
        }
        if ("GET".equals(method) && path.startsWith(ICON_PATH)) {
            String pkg = path.substring(ICON_PATH.length());
            byte[] art = pkg.matches(PACKAGE_NAME) ? PhoneRemoteApps.art(service, pkg) : null;
            if (art == null) respond(socket, 404, "application/json", error("App unavailable"));
            else respond(socket, 200, "image/webp", art, KEEP_A_WEEK);
            return;
        }
        if ("GET".equals(method) && "/api/live/playlist".equals(path)) { live.playlist(socket); return; }
        if ("GET".equals(method) && path.startsWith(PhoneRemoteLive.EPG_PATH)) { live.schedule(socket, path); return; }
        if ("POST".equals(method) && "/api/live/play".equals(path)) {
            boolean started = live.playOnTv(new JSONObject(body).optString("id", ""));
            respond(socket, started ? 200 : 404, "application/json",
                    started ? "{\"message\":\"OK\"}" : error("Live Indian TV is not available"));
            return;
        }
        if ("POST".equals(method) && "/api/live/watch".equals(path)) {
            respond(socket, 200, "application/json", new JSONObject().put("base", live.watchOnPhone()).toString());
            return;
        }
        if ("GET".equals(method) && "/api/cast".equals(path)) {
            respond(socket, 200, "application/json", PhoneRemoteApps.cast(service).toString());
            return;
        }
        if ("POST".equals(method) && "/api/app".equals(path)) {
            String pkg = new JSONObject(body).optString("package", "");
            if (!pkg.matches(PACKAGE_NAME)) {
                respond(socket, 400, "application/json", error("Invalid app")); return;
            }
            Intent i = PhoneRemoteApps.launchIntent(service.getPackageManager(), pkg);
            if (i == null) { respond(socket, 404, "application/json", error("App unavailable")); return; }
            service.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            respond(socket, 200, "application/json", "{\"message\":\"OK\"}");
            return;
        }
        if ("GET".equals(method) && "/api/watch".equals(path)) {
            JSONArray rows = new JSONArray();
            List<ContinueWatching.Item> items = ContinueWatching.load(service);
            for (int i = 0; i < items.size(); i++) {
                ContinueWatching.Item item = items.get(i);
                rows.put(new JSONObject().put("index", i).put("app", item.pkg)
                        .put("title", item.title).put("subtitle", item.subtitle));
            }
            respond(socket, 200, "application/json", rows.toString());
            return;
        }
        if ("POST".equals(method) && "/api/watch/open".equals(path)) {
            int index = new JSONObject(body).optInt("index", -1);
            List<ContinueWatching.Item> items = ContinueWatching.load(service);
            if (index < 0 || index >= items.size()) {
                respond(socket, 404, "application/json", error("Title is no longer available")); return;
            }
            Intent i = ContinueWatching.intentFor(service, items.get(index));
            if (i == null) { respond(socket, 404, "application/json", error("App unavailable")); return; }
            service.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            respond(socket, 200, "application/json", "{\"message\":\"OK\"}");
            return;
        }
        respond(socket, 404, "application/json", error("Unknown command"));
    }

    private boolean authorised(Map<String, String> headers) {
        String token = headers.get("x-remote-token");
        if (token == null || token.length() > 128) return false;
        byte[] candidate = hash(token).getBytes(StandardCharsets.UTF_8);
        for (String stored : prefs.getStringSet(TOKENS, Collections.emptySet())) {
            if (MessageDigest.isEqual(candidate, stored.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String error(String message) {
        return new JSONObject(Collections.singletonMap("error", message)).toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < MAX_LINE; i++) {
            int b = in.read();
            if (b < 0) return null;
            if (b == '\n') return bytes.toString("UTF-8").replace("\r", "");
            bytes.write(b);
        }
        return null;
    }

    private static byte[] readAsset(InputStream asset) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = asset.read(buffer)) != -1) bytes.write(buffer, 0, n);
        return bytes.toByteArray();
    }

    static void respond(Socket socket, int status, String type, String body) throws IOException {
        respond(socket, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static void respond(Socket socket, int status, String type, byte[] body) throws IOException {
        respond(socket, status, type, body, "no-store");
    }

    private static void respond(Socket socket, int status, String type, byte[] body, String cache) throws IOException {
        String label = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 502 ? "Bad Gateway" : "Unprocessable Entity";
        String headers = "HTTP/1.1 " + status + " " + label + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cache + "\r\nX-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' blob: https://jiotvimages.cdn.jio.com; media-src 'self' blob:; "
                + "script-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
