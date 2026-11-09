package io.github.rjtheorigin.origintv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One-time on-TV setup; the server itself lives in the separately enabled accessibility service. */
public final class PhoneRemoteSetupActivity extends Activity {
    private TextView status;
    private TextView code;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(56, 36, 56, 24);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setBackgroundColor(Color.rgb(18, 21, 29));
        title(page, "Phone remote", 32, Color.WHITE);
        title(page, "Pair a phone on the same Wi-Fi. No notification runs in the background.", 17, 0xffb9c4d5);
        status = title(page, "Checking…", 19, Color.WHITE);
        code = title(page, "", 29, 0xff8ad2ff);
        button(page, "Enable phone remote access", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(page, "Choose the TV keyboard", v -> chooseKeyboard());
        button(page, "New pairing code", v -> newCode());
        button(page, "Revoke all paired phones", v -> {
            PhoneRemoteService service = PhoneRemoteService.connected();
            if (service != null) service.revokePhones();
            status.setText("Paired phones revoked. Make a new code to pair again.");
        });
        title(page, "The phone reaches every app through the Phone remote keyboard, so keep it as the TV's keyboard. It stores nothing you type, and physical remote keys are not captured. Turn off the service in Accessibility settings to close the phone bridge.", 15, 0xff9facbf);
        setContentView(page);
        page.getChildAt(4).requestFocus();
    }

    private TextView title(LinearLayout page, String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, 0, 0, 18);
        page.addView(view);
        return view;
    }

    private void button(LinearLayout page, String text, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(onClick);
        page.addView(button);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(this::refresh, 1500);
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        PhoneRemoteService service = PhoneRemoteService.connected();
        if (service == null) {
            status.setText("Off. Enable ‘Phone remote’ in Accessibility settings, then return here.");
            code.setText("");
        } else if (service.address() == null) {
            status.setText("Enabled. Waiting for the TV's Wi-Fi address…");
        } else if (!service.keyboardReady()) {
            status.setText("Almost: choose Phone remote as the TV's keyboard, or arrows won't reach other apps.");
        } else {
            status.setText("On. Open http://" + service.address() + ":8844 on your phone.");
        }
    }

    /** Android asks the person to allow a new keyboard in Settings before it can be picked. */
    private void chooseKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (InputMethodInfo keyboard : manager.getEnabledInputMethodList()) {
            if (getPackageName().equals(keyboard.getPackageName())) {
                manager.showInputMethodPicker();
                return;
            }
        }
        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
    }

    private void newCode() {
        PhoneRemoteService service = PhoneRemoteService.connected();
        if (service == null || service.address() == null) {
            refresh();
            return;
        }
        code.setText("Pairing code: " + service.issueCode() + "  ·  valid for 2 minutes");
    }
}
