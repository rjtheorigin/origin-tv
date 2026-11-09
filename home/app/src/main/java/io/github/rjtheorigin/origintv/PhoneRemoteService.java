package io.github.rjtheorigin.origintv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * User-enabled, system-bound bridge. It never filters keys from the physical remote. Android keeps it running without
 * a notification; it serves the phone page and does what needs the system (Home, Back, volume), while
 * {@link PhoneRemoteIme} delivers keys and text to the app on screen.
 */
public final class PhoneRemoteService extends AccessibilityService {
    private static final String KEYBOARD_NEEDED = "Choose Phone remote as the TV's keyboard";

    private static volatile PhoneRemoteService connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneRemoteServer server;
    private volatile String foreground;
    private GlobalKeyWatch keyWatch;

    static PhoneRemoteService connected() { return connected; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        stopBridge();
        // An earlier build kept a client key for the TV's remote service here; nothing can use it.
        deleteSharedPreferences("tv_wide_remote");
        connected = this;
        server = new PhoneRemoteServer(this);
        server.start();
        // This service is always bound and filters no keys, so it also watches for the MI button (global_key_watch).
        keyWatch = new GlobalKeyWatch(this, getResources().getStringArray(R.array.global_key_watch));
        keyWatch.start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!"com.android.systemui".contentEquals(pkg)) foreground = pkg.toString();
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        stopBridge();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    private void stopBridge() {
        if (server != null) { server.stop(); server = null; }
        if (keyWatch != null) { keyWatch.stop(); keyWatch = null; }
        if (connected == this) connected = null;
    }

    String address() { return server == null ? null : server.address(); }
    String issueCode() { return server == null ? null : server.issueCode(); }
    void revokePhones() { if (server != null) server.revokePhones(); }
    boolean keyboardReady() { return PhoneRemoteIme.active() != null; }

    /** The name of the app on screen, or null before the first window change since the service started. */
    String nowOnTv() {
        String pkg = foreground;
        if (pkg == null) return null;
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) { return null; }
    }

    /** Called from the local server thread; Android UI/accessibility work stays on the main thread. */
    String perform(String action, String value) {
        final String[] result = {"Unavailable"};
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try { result[0] = performOnMain(action, value); }
            catch (RuntimeException e) { result[0] = "This app did not accept that action"; }
            finally { done.countDown(); }
        });
        try {
            return done.await(3, TimeUnit.SECONDS) ? result[0] : "TV did not respond";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        }
    }

    private String performOnMain(String action, String value) {
        PhoneRemoteIme ime = PhoneRemoteIme.active();
        switch (action) {
            case "home": return done(performGlobalAction(GLOBAL_ACTION_HOME));
            case "back": return done(performGlobalAction(GLOBAL_ACTION_BACK));
            case "power": return done(performGlobalAction(GLOBAL_ACTION_POWER_DIALOG));
            case "volume_up": return volume(AudioManager.ADJUST_RAISE);
            case "volume_down": return volume(AudioManager.ADJUST_LOWER);
            case "mute": return volume(AudioManager.ADJUST_TOGGLE_MUTE);
            case "settings": return open(new Intent(Settings.ACTION_SETTINGS));
            case "inputs": return open(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS));
            case "miracast":
                Intent miracast = PhoneRemoteApps.launchIntent(getPackageManager(), PhoneRemoteApps.MIRACAST);
                return miracast == null ? "Miracast is not on this TV" : open(miracast);
            case "ok_hold":
                return ime == null ? KEYBOARD_NEEDED : done(ime.hold(KeyEvent.KEYCODE_DPAD_CENTER));
            case "text":
                if (value == null || value.isEmpty() || value.length() > 500) return "Text must be 1–500 characters";
                return ime == null ? setText(value) : ime.type(value);
            case "backspace":
                if (ime == null) return KEYBOARD_NEEDED;
                ime.backspace();
                return "OK";
            case "enter":
                if (ime == null) return KEYBOARD_NEEDED;
                ime.enter();
                return "OK";
            default:
                int key = PhoneRemoteKeys.forAction(action);
                if (key == 0) return "Unknown action";
                if (ime != null) return done(ime.press(key));
                return PhoneRemoteKeys.isMedia(key) ? media(key) : KEYBOARD_NEEDED;
        }
    }

    private String open(Intent intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "OK";
    }

    private String media(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return "OK";
    }

    private String volume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
        return "OK";
    }

    private static String done(boolean result) { return result ? "OK" : "Not available in this app"; }

    /** The route for text while another keyboard is active: only reaches fields that expose themselves. */
    private String setText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "This app does not expose a text field";
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null || !focused.isEditable()) return "Focus a text field on the TV first";
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
            return done(ok);
        } finally { root.recycle(); }
    }
}
