package com.unl0cker.stickerbox;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A FrameLayout as tall as it is wide (grid tiles). */
public final class SquareFrame extends FrameLayout {
    public SquareFrame(Context c) {
        super(c);
    }

    public SquareFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, widthSpec);
    }
}
