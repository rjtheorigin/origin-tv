package io.github.rjtheorigin.joshitv;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A ScrollView that never scrolls by itself when focus moves: Home scrolls it row by row instead. */
public class QuietScrollView extends ScrollView {
    public QuietScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}
