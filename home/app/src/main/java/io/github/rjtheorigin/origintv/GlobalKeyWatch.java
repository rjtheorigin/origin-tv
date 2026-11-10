package io.github.rjtheorigin.origintv;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens an app on a remote button that only the firmware's own global-key receiver gets (Xiaomi's MI button), by
 * reading the line that receiver logs for each press. No service filters keys, so held volume keys keep repeating:
 * on this Xiaomi firmware any key-filtering accessibility service stops that. Needs READ_LOGS, which
 * build-and-install grants; without it this stays off.
 */
final class GlobalKeyWatch {
    private static final String TAG = "GlobalKeyWatch";
    private static final Pattern PRESS = Pattern.compile("receive ACTION_GLOBAL_BUTTON keyCode = (\\d+)");
    private static final Pattern EPOCH = Pattern.compile("^\\s*(\\d+)\\.(\\d{3})");
    /** The receiver logs every press twice (down and up). */
    private static final long SAME_PRESS_MS = 1000;
    /** logd cuts a reader off now and then on this TV's 64 KB buffer; reading resumes after this pause. */
    private static final long RESTART_MS = 2000;

    private final Context context;
    private final Map<Integer, String> targets = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile Process process;
    private long lastPress;
    /** Log time of the newest press line read. A restarted logcat replays its buffer, and older lines are skipped. */
    private long lastLine;

    /** Entries are "keycode:package/.Activity", e.g. "192:io.github.rjtheorigin.liveindiantv/.LiveTv". */
    GlobalKeyWatch(Context context, String[] entries) {
        this.context = context;
        for (String entry : entries) {
            int colon = entry.indexOf(':');
            if (colon < 1) continue;
            try {
                targets.put(Integer.parseInt(entry.substring(0, colon).trim()), entry.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // A malformed entry is skipped.
            }
        }
    }

    void start() {
        if (targets.isEmpty()) return;
        if (context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_LOGS not granted; the MI button watch is off");
            return;
        }
        Thread thread = new Thread(this::run, "global-key-watch");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        Process running = process;
        if (running != null) running.destroy();
    }

    private void run() {
        // logcat starts with what is already in its buffer: presses from before this watch began are skipped.
        lastLine = System.currentTimeMillis();
        while (!stopped) {
            try {
                // Only this tag, so logcat itself drops everything else; the thread sleeps in readLine between presses.
                process = new ProcessBuilder("logcat", "-b", "main", "-v", "epoch", "-s", "GlobalKey:V")
                        .redirectErrorStream(true).start();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (!stopped && (line = in.readLine()) != null) handle(line);
                }
            } catch (IOException e) {
                Log.w(TAG, "logcat: " + e.getMessage());
            }
            if (stopped) break;
            try {
                Thread.sleep(RESTART_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void handle(String line) {
        Matcher press = PRESS.matcher(line);
        if (!press.find()) return;
        Matcher time = EPOCH.matcher(line);
        if (!time.find()) return;
        long at = Long.parseLong(time.group(1)) * 1000 + Long.parseLong(time.group(2));
        if (at <= lastLine) return;
        lastLine = at;
        String target = targets.get(Integer.parseInt(press.group(1)));
        long now = System.currentTimeMillis();
        if (target == null || now - lastPress < SAME_PRESS_MS) return;
        lastPress = now;
        ComponentName component = target.contains("/") ? ComponentName.unflattenFromString(target) : null;
        main.post(() -> {
            try {
                Intent intent = component != null ? new Intent().setComponent(component)
                        : context.getPackageManager().getLaunchIntentForPackage(target);
                if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "cannot open " + target);
            }
        });
    }
}
