package io.github.rjtheorigin.origintv;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A ScrollView that never scrolls by itself when focus moves: the PatchWall-style Home scrolls it row by row. */
public class PatchScrollView extends ScrollView {
    public PatchScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }
}
