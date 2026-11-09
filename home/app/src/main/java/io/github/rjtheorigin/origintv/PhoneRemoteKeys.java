package io.github.rjtheorigin.origintv;

import android.view.KeyEvent;

/** Which key each button on the phone page sends. No Android state, so the mapping is unit tested. */
final class PhoneRemoteKeys {
    private PhoneRemoteKeys() { }

    /** The key for a phone button, or 0 when the action is not a plain key press. */
    static int forAction(String action) {
        switch (action) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "play_pause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "fast_forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case "previous": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "channel_up": return KeyEvent.KEYCODE_CHANNEL_UP;
            case "channel_down": return KeyEvent.KEYCODE_CHANNEL_DOWN;
            case "menu": return KeyEvent.KEYCODE_MENU;
            default:
                if (action.length() != 7 || !action.startsWith("digit_")) return 0;
                char digit = action.charAt(6);
                return digit >= '0' && digit <= '9' ? KeyEvent.KEYCODE_0 + (digit - '0') : 0;
        }
    }

    /**
     * The key that types a character into an app that draws its own keyboard, such as YouTube, which has no
     * text field to commit to. Letters are sent unshifted, since those keyboards ignore case. 0 when no key types it.
     */
    static int forCharacter(char c) {
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        return c == ' ' ? KeyEvent.KEYCODE_SPACE : 0;
    }

    /** Media keys still have a route (the active media session) while this app is not the TV's keyboard. */
    static boolean isMedia(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return true;
            default:
                return false;
        }
    }
}
