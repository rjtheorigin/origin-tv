package io.github.rjtheorigin.origintv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * The on-screen keyboard, for typing with the physical remote. {@link PhoneRemoteIme} is the TV's keyboard so that the
 * phone can reach every app, so it has to offer one of its own: a plain grid moved with the arrows, drawn in one view.
 */
@SuppressLint("ViewConstructor")
final class PhoneRemoteKeyboard extends View {
    static final String SHIFT = "shift";
    static final String SPACE = "space";
    static final String DELETE = "delete";
    static final String DONE = "done";

    private static final String[][] ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", "@"},
            {"z", "x", "c", "v", "b", "n", "m", ".", "-", "_"},
            {SHIFT, SPACE, DELETE, DONE},
    };
    private static final int BACKGROUND = Color.rgb(18, 21, 29);
    private static final int KEY = Color.rgb(42, 48, 64);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float keyHeight;
    private final float gap;
    private int row = 1;
    private int column;
    private boolean shifted;

    PhoneRemoteKeyboard(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        keyHeight = 44 * density;
        gap = 6 * density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(20 * density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                (int) (ROWS.length * (keyHeight + gap) + gap));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(BACKGROUND);
        float rowTop = gap;
        for (int r = 0; r < ROWS.length; r++) {
            float width = (getWidth() - gap) / ROWS[r].length;
            for (int c = 0; c < ROWS[r].length; c++) {
                boolean current = r == row && c == column;
                float left = gap + c * width;
                box.set(left, rowTop, left + width - gap, rowTop + keyHeight);
                paint.setColor(current ? Color.WHITE : KEY);
                canvas.drawRoundRect(box, gap, gap, paint);
                paint.setColor(current ? Color.BLACK : Color.WHITE);
                canvas.drawText(label(ROWS[r][c]), box.centerX(),
                        box.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
            }
            rowTop += keyHeight + gap;
        }
    }

    private String label(String key) {
        switch (key) {
            case SHIFT: return shifted ? "abc" : "ABC";
            case SPACE: return "Space";
            case DELETE: return "Delete";
            case DONE: return "Done";
            default: return text(key);
        }
    }

    /** Stays on the same part of the screen when moving between rows of different lengths. */
    void move(int across, int down) {
        if (down != 0) {
            int next = Math.max(0, Math.min(ROWS.length - 1, row + down));
            column = (int) ((column + 0.5f) / ROWS[row].length * ROWS[next].length);
            row = next;
        } else {
            column = Math.max(0, Math.min(ROWS[row].length - 1, column + across));
        }
        invalidate();
    }

    String current() { return ROWS[row][column]; }

    void toggleShift() {
        shifted = !shifted;
        invalidate();
    }

    String text(String key) { return shifted ? key.toUpperCase(Locale.ROOT) : key; }
}
