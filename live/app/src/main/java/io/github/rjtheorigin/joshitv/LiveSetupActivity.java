package io.github.rjtheorigin.joshitv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/**
 * Opened by Live Channels when Joshi TV is picked under "Set up your sources". It copies the channel list, which
 * takes a few seconds, and leaves logos and the guide to the background job.
 */
public class LiveSetupActivity extends Activity {
    private static final long CLOSE_MS = 3_500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.bg_app);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(26);
        card.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.live_setup_title);
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(24);
        title.setTypeface(getResources().getFont(R.font.outfit_medium));
        status = new TextView(this);
        status.setText(R.string.live_setup_working);
        status.setTextColor(getColor(R.color.muted));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        card.addView(title);
        card.addView(status);
        root.addView(card, new FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(root);
        setResult(RESULT_CANCELED);
        new Thread(this::copy, "live-setup").start();
    }

    private void copy() {
        int count;
        String error = null;
        try {
            count = LiveSync.syncChannels(this);
        } catch (IOException | RuntimeException e) {
            Log.w("LiveSync", "setup: " + e.getMessage());
            count = 0;
            error = e.getMessage();
        }
        int channels = count;
        boolean signedOut = error != null && error.contains("signed out");
        main.post(() -> {
            if (isFinishing()) return;
            done = true;
            if (channels > 0) {
                LiveSync.markSetUp(this);
                setResult(RESULT_OK);
                status.setText(getString(R.string.live_setup_done, channels));
            } else {
                status.setText(signedOut ? R.string.live_setup_signed_out : R.string.live_setup_failed);
            }
            main.postDelayed(this::finish, CLOSE_MS);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // OK or Back closes the screen once the copy is done; before that the copy is left to finish.
        boolean ok = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_BACK;
        if (ok && event.getAction() == KeyEvent.ACTION_UP && done) finish();
        return ok || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
