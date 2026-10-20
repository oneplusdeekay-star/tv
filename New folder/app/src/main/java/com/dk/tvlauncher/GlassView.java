package com.dk.tvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ScrollView;

/**
 * The single translucent "glass" highlight. It springs from target to target.
 * Targets in scrolling content are tracked against the live scroll position, so the
 * highlight stays glued to its tile while the page scrolls underneath it.
 */
public class GlassView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF start = new RectF();
    private final RectF target = new RectF();
    private final RectF cur = new RectF();
    private boolean targetInContent;
    private boolean shown;
    private float progress = 1f;
    private float radius = 16f;
    private ScrollView scroll;
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);

    public GlassView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x40FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0x80FFFFFF);
        anim.setDuration(380);
        anim.setInterpolator(new OvershootInterpolator(1.1f));
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    public void attachScroll(ScrollView s) {
        scroll = s;
        s.getViewTreeObserver().addOnScrollChangedListener(this::invalidate);
    }

    public void setStrokeWidth(float px) {
        stroke.setStrokeWidth(px);
    }

    private void compute(RectF out) {
        RectF end = new RectF(target);
        if (targetInContent && scroll != null) end.offset(0, -scroll.getScrollY());
        out.set(
                start.left + (end.left - start.left) * progress,
                start.top + (end.top - start.top) * progress,
                start.right + (end.right - start.right) * progress,
                start.bottom + (end.bottom - start.bottom) * progress);
    }

    /** rect is in content coordinates when inContent is true, otherwise in screen coordinates. */
    public void moveTo(RectF rect, boolean inContent, float cornerRadius, boolean animate) {
        radius = cornerRadius;
        if (!shown || !animate) {
            target.set(rect);
            targetInContent = inContent;
            start.set(rect);
            if (inContent && scroll != null) start.offset(0, -scroll.getScrollY());
            progress = 1f;
            shown = true;
            anim.cancel();
            invalidate();
            return;
        }
        compute(cur);
        start.set(cur);
        target.set(rect);
        targetInContent = inContent;
        progress = 0f;
        anim.cancel();
        anim.start();
    }

    public void hide() {
        shown = false;
        anim.cancel();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        compute(cur);
        canvas.drawRoundRect(cur, radius, radius, fill);
        canvas.drawRoundRect(cur, radius, radius, stroke);
    }
}
