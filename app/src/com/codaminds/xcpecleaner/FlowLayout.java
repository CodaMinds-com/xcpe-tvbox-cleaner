package com.codaminds.xcpecleaner;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays children out left to right and wraps to a new row when the next one would not fit, honouring each
 * child's end margin as the horizontal gap. Used for the button bar so that long translated labels never get
 * clipped; D-pad focus moves across rows with the platform's normal nearest-neighbour rules.
 */
public class FlowLayout extends ViewGroup {
    private final int rowGap;

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
        rowGap = Math.round(10 * c.getResources().getDisplayMetrics().density);
    }

    @Override public LayoutParams generateLayoutParams(AttributeSet attrs) { return new MarginLayoutParams(getContext(), attrs); }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    @Override protected LayoutParams generateLayoutParams(LayoutParams p) { return new MarginLayoutParams(p); }
    @Override protected boolean checkLayoutParams(LayoutParams p) { return p instanceof MarginLayoutParams; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0, usedW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
            int w = v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin, h = v.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxW) { y += rowH + rowGap; x = 0; rowH = 0; }
            x += w; rowH = Math.max(rowH, h); usedW = Math.max(usedW, x);
        }
        int totalH = y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), wSpec), resolveSize(totalH, hSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = getPaddingTop(), rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
            int w = v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin, h = v.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + w > maxW) { y += rowH + rowGap; x = 0; rowH = 0; }
            int left = rtl ? (r - l - getPaddingRight() - x - w + lp.leftMargin) : (getPaddingLeft() + x + lp.leftMargin);
            v.layout(left, y + lp.topMargin, left + v.getMeasuredWidth(), y + lp.topMargin + v.getMeasuredHeight());
            x += w; rowH = Math.max(rowH, h);
        }
    }
}
