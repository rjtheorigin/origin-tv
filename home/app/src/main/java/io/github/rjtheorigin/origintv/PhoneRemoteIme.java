package io.github.rjtheorigin.origintv;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The phone remote's way into every app. Android 9 gives an accessibility service no D-pad action, and the TV's own
 * remote service refuses connections that start on the TV, but the active keyboard may send keys to whichever app has
 * focus. So this is the TV's keyboard: nothing typed is stored or logged, and the physical remote is never filtered.
 * All of it runs on the main thread. Text fields show {@link PhoneRemoteKeyboard}.
 */
public final class PhoneRemoteIme extends InputMethodService {
    private static final int SOURCE = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD;
    private static final long TAP_MS = 20;
    private static final long HOLD_MS = 750;
    /** Keys sent any faster lose letters in YouTube's search box. */
    private static final long TYPING_GAP_MS = 90;

    private static volatile PhoneRemoteIme active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneRemoteKeyboard keyboard;

    /** The running keyboard, or null while another keyboard is the TV's active one. */
    static PhoneRemoteIme active() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        keyboard = new PhoneRemoteKeyboard(this);
        return keyboard;
    }

    /** A TV is always landscape, where the default would cover the whole app with an extract view. */
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    /** Android hides soft keyboards when a remote reports itself as a hardware keyboard. */
    @Override public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    /** While the keyboard is up the physical remote moves it; otherwise keys pass to the app untouched. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return keyboardKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        return (showingKeyboard() && movesKeyboard(keyCode)) || super.onKeyUp(keyCode, event);
    }

    private boolean showingKeyboard() { return keyboard != null && isInputViewShown(); }

    private static boolean movesKeyboard(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private boolean keyboardKey(int keyCode) {
        if (!showingKeyboard() || !movesKeyboard(keyCode)) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: keyboard.move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: keyboard.move(0, 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: keyboard.move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: keyboard.move(1, 0); break;
            default: activate(keyboard.current()); break;
        }
        return true;
    }

    private void activate(String key) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        switch (key) {
            case PhoneRemoteKeyboard.SHIFT: keyboard.toggleShift(); break;
            case PhoneRemoteKeyboard.SPACE: connection.commitText(" ", 1); break;
            case PhoneRemoteKeyboard.DELETE: connection.deleteSurroundingText(1, 0); break;
            case PhoneRemoteKeyboard.DONE: finishField(); break;
            default: connection.commitText(keyboard.text(key), 1); break;
        }
    }

    private void finishField() {
        if (!sendDefaultEditorAction(true)) requestHideSelf(0);
    }

    /** True while an app has a real text field here; an app that draws its own keyboard has none. */
    private boolean hasTextField() {
        EditorInfo info = getCurrentInputEditorInfo();
        return getCurrentInputStarted() && info != null && info.inputType != InputType.TYPE_NULL;
    }

    /** One press of a remote button, as the phone sent it. */
    boolean press(int keyCode) {
        return keyboardKey(keyCode) || inject(keyCode, TAP_MS);
    }

    /** Press and hold, for what the remote does on a long press: the launcher's menus, say. */
    boolean hold(int keyCode) {
        return keyboardKey(keyCode) || inject(keyCode, HOLD_MS);
    }

    private boolean inject(int keyCode, long heldMs) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return false;
        long down = SystemClock.uptimeMillis();
        connection.sendKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, SOURCE));
        main.postDelayed(() -> {
            InputConnection now = getCurrentInputConnection();
            if (now != null) now.sendKeyEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, SOURCE));
        }, heldMs);
        return true;
    }

    /** Text from the phone: into a text field whole, or as key presses for an app with its own keyboard. */
    String type(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return "The TV is not ready";
        if (hasTextField()) {
            connection.commitText(text, 1);
            return "OK";
        }
        boolean skipped = false;
        long delay = 0;
        for (int i = 0; i < text.length(); i++) {
            int key = PhoneRemoteKeys.forCharacter(text.charAt(i));
            if (key == 0) {
                skipped = true;
            } else {
                main.postDelayed(() -> inject(key, TAP_MS), delay);
                delay += TYPING_GAP_MS;
            }
        }
        return skipped ? "This app took letters and numbers only" : "OK";
    }

    void backspace() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        if (hasTextField()) connection.deleteSurroundingText(1, 0);
        else inject(KeyEvent.KEYCODE_DEL, TAP_MS);
    }

    void enter() {
        if (hasTextField()) finishField();
        else inject(KeyEvent.KEYCODE_ENTER, TAP_MS);
    }
}
