package io.github.rjtheorigin.liveindiantv;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Moves a list by position instead of leaving it to Android's focus search, which looks outside the list for
 * the nearest row when the next one is not laid out yet. That is what sent a fast Down to the category panel.
 */
final class RowManager extends LinearLayoutManager {
    private int wanted = RecyclerView.NO_POSITION;

    RowManager(Context context) {
        super(context);
    }

    RowManager(Context context, int orientation) {
        super(context, orientation, false);
    }

    /** Puts the row that far from the top, and gives it focus as soon as it is on screen. */
    void focusRow(int position, int offset) {
        wanted = position;
        scrollToPositionWithOffset(position, offset);
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (wanted == RecyclerView.NO_POSITION) return;
        View row = findViewByPosition(wanted);
        if (row != null) {
            wanted = RecyclerView.NO_POSITION;
            row.requestFocus();
        }
    }

    /** focusRow does all the scrolling, so focus must not start a second, slower scroll. */
    @Override
    public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate,
            boolean focusedChildVisible) {
        return false;
    }
}
