package com.gathin.tvhome;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/**
 * A blurred drop shadow behind a rounded card (the remote's Settings panel), for windows where the system's elevation
 * shadow does not show (a see-through window on a Hisense, Android 11). One rounded rectangle drawn through a Gaussian
 * blur, so it fades all the way to transparent: the view's padding around the card must leave room for the blur.
 */
final class SoftShadow extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final int left, top, right, bottom;
    private final float radius, drop;

    /**
     * left..bottom: where the card sits inside the bounds (the view's padding); radius: the card's corners; blur: how
     * soft; drop: how far the shadow falls below the card; alpha: its darkness at the middle (0-255).
     */
    SoftShadow(int left, int top, int right, int bottom, float radius, float blur, float drop, int alpha) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.radius = radius;
        this.drop = drop;
        paint.setColor(alpha << 24);
        paint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
    }

    /** Puts the shadow behind view; before Android 9 a blur needs the view drawn in software. */
    static void apply(View view, SoftShadow shadow) {
        if (Build.VERSION.SDK_INT < 28) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        view.setBackground(shadow);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        card.set(b.left + left, b.top + top + drop, b.right - right, b.bottom - bottom + drop);
        canvas.drawRoundRect(card, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
