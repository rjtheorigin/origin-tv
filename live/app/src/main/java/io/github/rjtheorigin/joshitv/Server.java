package io.github.rjtheorigin.joshitv;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Runs the stream server from the app's own library folder, on 127.0.0.1 only, with its login tokens in the
 * app's private storage. Its log goes to /dev/null because it writes request headers, tokens included, when
 * Jio refuses a channel.
 */
final class Server {
    private static Process process;
    /** Bound by the launcher's phone page. */
    private static boolean held;
    /** Joshi TV is on screen. */
    private static boolean viewing;
    /** Live Channels sessions and guide copies using the server. */
    private static int liveUsers;

    private Server() { }

    static synchronized void ensure(Context context) throws IOException {
        if (portOpen()) return;
        File home = home(context);
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("Cannot create " + home);
        ProcessBuilder builder = new ProcessBuilder(
                context.getApplicationInfo().nativeLibraryDir + "/libjiotvgo.so",
                "--skip-update-check", "serve", "--host", "127.0.0.1", "--port", "5001");
        Map<String, String> env = builder.environment();
        env.put("HOME", context.getFilesDir().getPath());
        env.put("JIOTV_PATH_PREFIX", home.getPath());
        env.put("JIOTV_LOG_PATH", "/dev/null");
        builder.directory(context.getFilesDir());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File("/dev/null"));
        process = builder.start();
        for (int i = 0; i < 80; i++) {
            if (portOpen()) return;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("The server did not start");
    }

    /** True only for a server this app started, as one started another way holds its own login. */
    static synchronized boolean needsLogin(Context context) {
        if (process == null) return false;
        File store = new File(home(context), "store_v4.toml");
        try {
            return !store.exists()
                    || !new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8).contains("ssoToken");
        } catch (IOException e) {
            return true;
        }
    }

    static synchronized void setHeld(boolean value) {
        held = value;
    }

    static synchronized void setViewing(boolean value) {
        viewing = value;
    }

    static synchronized void addLiveUser() {
        liveUsers++;
    }

    static synchronized void removeLiveUser() {
        if (liveUsers > 0) liveUsers--;
    }

    /**
     * Stops the server only when nothing is using it, so the phone page, the TV app and Live Channels cannot pull it
     * from each other.
     */
    static synchronized void stopIfIdle() {
        if (process != null && !held && !viewing && liveUsers == 0) {
            process.destroy();
            process = null;
        }
    }

    private static File home(Context context) {
        return new File(context.getFilesDir(), "jiotv");
    }

    private static boolean portOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5001), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
