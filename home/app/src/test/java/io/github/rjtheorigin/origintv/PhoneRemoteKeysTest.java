package io.github.rjtheorigin.origintv;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PhoneRemoteKeysTest {
    @Test public void directionsAndOkAreTheRemotesKeys() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, PhoneRemoteKeys.forAction("up"));
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, PhoneRemoteKeys.forAction("down"));
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, PhoneRemoteKeys.forAction("left"));
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, PhoneRemoteKeys.forAction("right"));
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, PhoneRemoteKeys.forAction("ok"));
    }

    @Test public void digitButtonsAreNumberKeys() {
        assertEquals(KeyEvent.KEYCODE_0, PhoneRemoteKeys.forAction("digit_0"));
        assertEquals(KeyEvent.KEYCODE_9, PhoneRemoteKeys.forAction("digit_9"));
    }

    @Test public void anUnknownActionIsNotAKey() {
        assertEquals(0, PhoneRemoteKeys.forAction("self_destruct"));
        assertEquals(0, PhoneRemoteKeys.forAction("digit_"));
        assertEquals(0, PhoneRemoteKeys.forAction("digit_x"));
    }

    @Test public void lettersIgnoreCaseAndTheRestOfThePunctuationHasNoKey() {
        assertEquals(KeyEvent.KEYCODE_A, PhoneRemoteKeys.forCharacter('a'));
        assertEquals(KeyEvent.KEYCODE_Z, PhoneRemoteKeys.forCharacter('Z'));
        assertEquals(KeyEvent.KEYCODE_5, PhoneRemoteKeys.forCharacter('5'));
        assertEquals(KeyEvent.KEYCODE_SPACE, PhoneRemoteKeys.forCharacter(' '));
        assertEquals(0, PhoneRemoteKeys.forCharacter('?'));
        assertEquals(0, PhoneRemoteKeys.forCharacter('क'));
    }
}
