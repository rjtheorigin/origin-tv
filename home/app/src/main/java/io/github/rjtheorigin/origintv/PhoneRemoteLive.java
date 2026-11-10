package io.github.rjtheorigin.origintv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The phone page's Live TV tab. The stream server listens on the TV only and has no password, so the phone never
 * reaches it: this passes on a short list of paths and nothing else. Sign-in, logout and DRM licences stay out of
 * reach of the Wi-Fi, and so does anything else the server adds later.
 */
final class PhoneRemoteLive {
    static final String APP = "io.github.rjtheorigin.liveindiantv";
    static final String VIDEO_PATH = "/v/";
    static final String EPG_PATH = "/api/live/epg/";
    private static final String SERVER = "http://127.0.0.1:5001";
    private static final int PORT = 5001;
    /** The server stops this long after the last request, so an idle phone page holds no memory on the TV. */
    private static final long IDLE_MS = 180_000;
    private static final long VIDEO_KEY_MS = 6 * 60 * 60 * 1000;
    private static final Pattern CHANNEL = Pattern.compile("[0-9]{1,6}");
    private static final Pattern SCHEDULE = Pattern.compile("[0-9]{1,6}/-?[0-9]{1,2}");
    private static final Pattern STREAM = Pattern.compile(
            "/(live/high/[0-9]{1,6}\\.m3u8|render\\.(m3u8|ts|key)\\?[A-Za-z0-9_=&.%+/-]+)");

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> videoKeys = new ConcurrentHashMap<>();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };
    private final Runnable release = this::unbind;
    private boolean bound;

    PhoneRemoteLive(Context context) {
        this.context = context;
    }

    void stop() {
        main.post(this::unbind);
    }

    void playlist(java.net.Socket socket) throws IOException {
        pass(socket, "/playlist.m3u?q=high", "audio/x-mpegurl", 30000);
    }

    void schedule(java.net.Socket socket, String path) throws IOException {
        String tail = path.substring(EPG_PATH.length());
        if (!SCHEDULE.matcher(tail).matches()) {
            PhoneRemoteServer.respond(socket, 400, "application/json", PhoneRemoteServer.error("Invalid channel"));
            return;
        }
        pass(socket, "/epg/" + tail, "application/json", 15000);
    }

    /** Opens Live Indian TV full screen on that channel. It starts its own server. */
    boolean playOnTv(String id) {
        if (!CHANNEL.matcher(id).matches()) return false;
        try {
            context.startActivity(new Intent().setClassName(APP, APP + ".MainActivity").putExtra("id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /** Where this phone's video lives, with a key that keeps the long-term token out of every URL. */
    String watchOnPhone() {
        long now = System.currentTimeMillis();
        videoKeys.values().removeIf(expires -> expires < now);
        byte[] secret = new byte[18];
        random.nextBytes(secret);
        String key = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        videoKeys.put(key, now + VIDEO_KEY_MS);
        return VIDEO_PATH + key + "/live/high/";
    }

    void video(java.net.Socket socket, String path) throws IOException {
        int slash = path.indexOf('/', VIDEO_PATH.length());
        if (slash < 0) { refuse(socket, 404); return; }
        String key = path.substring(VIDEO_PATH.length(), slash), target = path.substring(slash);
        Long expires = videoKeys.get(key);
        if (expires == null || expires < System.currentTimeMillis()) { refuse(socket, 403); return; }
        if (!STREAM.matcher(target).matches()) { refuse(socket, 404); return; }
        HttpURLConnection connection = null;
        try {
            hold();
            connection = open(target, 25000);
            if (connection.getResponseCode() != 200) { refuse(socket, 502); return; }
            if (target.contains(".m3u8")) {
                // Playlists name the server's own paths: point them back through here, key included.
                String prefix = VIDEO_PATH + key + "/render.";
                String text = new String(read(connection), StandardCharsets.UTF_8)
                        .replace("\n/render.", "\n" + prefix).replace("URI=\"/render.", "URI=\"" + prefix);
                PhoneRemoteServer.respond(socket, 200, "application/vnd.apple.mpegurl", text);
            } else {
                copy(connection, socket);
            }
        } catch (IOException e) {
            refuse(socket, 502);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void pass(java.net.Socket socket, String path, String type, int timeoutMs) throws IOException {
        try {
            hold();
            HttpURLConnection connection = open(path, timeoutMs);
            try {
                if (connection.getResponseCode() != 200) { refuse(socket, 502); return; }
                PhoneRemoteServer.respond(socket, 200, type, read(connection));
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            refuse(socket, 502);
        }
    }

    private static void refuse(java.net.Socket socket, int status) throws IOException {
        PhoneRemoteServer.respond(socket, status, "application/json", PhoneRemoteServer.error("Live TV is not available"));
    }

    private static HttpURLConnection open(String path, int timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(SERVER + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(timeoutMs);
        return connection;
    }

    private static byte[] read(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) bytes.write(chunk, 0, n);
            return bytes.toByteArray();
        }
    }

    /** Video goes straight through in small pieces, so a segment never sits in the launcher's memory. */
    private static void copy(HttpURLConnection connection, java.net.Socket socket) throws IOException {
        long length = connection.getContentLengthLong();
        String type = connection.getContentType();
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + (type == null ? "video/mp2t" : type) + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = connection.getInputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        }
        out.flush();
    }

    /** Keeps the server up, starting it through Live Indian TV when it is closed, and waits for it to answer. */
    private void hold() throws IOException {
        main.post(() -> {
            main.removeCallbacks(release);
            if (!bound) {
                bound = context.bindService(new Intent().setClassName(APP, APP + ".ServerService"), connection,
                        Context.BIND_AUTO_CREATE);
            }
            main.postDelayed(release, IDLE_MS);
        });
        for (int i = 0; i < 48; i++) {
            if (serverUp()) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("server not up");
    }

    private static boolean serverUp() {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void unbind() {
        main.removeCallbacks(release);
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Android already dropped the binding.
        }
    }
}
