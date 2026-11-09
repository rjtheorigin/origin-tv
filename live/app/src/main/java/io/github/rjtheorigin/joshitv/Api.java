package io.github.rjtheorigin.joshitv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTP to the stream server, which listens on the TV only. */
final class Api {
    static final String BASE = "http://127.0.0.1:5001";

    private Api() { }

    static HttpURLConnection open(String urlOrPath, int timeoutMs) throws IOException {
        String address = urlOrPath.startsWith("http") ? urlOrPath : BASE + urlOrPath;
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(timeoutMs);
        return connection;
    }

    static String get(String path, int timeoutMs) throws IOException {
        HttpURLConnection connection = open(path, timeoutMs);
        try {
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    static String postJson(String path, String json) throws IOException {
        HttpURLConnection connection = open(path, 25000);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** Signs the server out of Jio. Its answer is a redirect to its web page, which is not followed. */
    static void logout() throws IOException {
        HttpURLConnection connection = open("/logout", 20000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() >= 400) throw new IOException("HTTP " + connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        try (InputStream in = code < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) bytes.write(chunk, 0, n);
            }
            // The server's reason comes with the code, so a caller can tell "signed out" from "channel down".
            if (code >= 400) {
                String reason = bytes.toString("UTF-8");
                throw new IOException("HTTP " + code + " " + reason.substring(0, Math.min(400, reason.length())));
            }
            return bytes.toString("UTF-8");
        }
    }
}
