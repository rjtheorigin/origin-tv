package io.github.rjtheorigin.origintv;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A row of the PatchWall-style Home moved by position instead of Android's focus search, which looks outside the
 * row for the nearest card when the next one is not laid out yet (from Joshi TV's RowManager, tested on this TV).
 */
final class PatchRowManager extends LinearLayoutManager {
    private int wanted = RecyclerView.NO_POSITION;

    PatchRowManager(Context context) {
        super(context, RecyclerView.HORIZONTAL, false);
    }

    /** Puts that card the given distance from the row's start, and gives it focus as soon as it is laid out. */
    void focusAt(int position, int offset) {
        wanted = position;
        scrollToPositionWithOffset(position, offset);
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (wanted == RecyclerView.NO_POSITION) return;
        View card = findViewByPosition(wanted);
        if (card != null) {
            wanted = RecyclerView.NO_POSITION;
            card.requestFocus();
        }
    }

    /** focusAt does all the scrolling, so focus must not start a second, slower scroll. */
    @Override
    public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate,
            boolean focusedChildVisible) {
        return false;
    }
}
