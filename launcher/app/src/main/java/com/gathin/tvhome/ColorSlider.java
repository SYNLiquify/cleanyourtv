package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.View;

/**
 * One channel of the Settings menu's colour picker (hue, saturation or brightness), made for the remote: a rounded
 * track painted with the channel's range at the current colour, a knob on the value, and Left/Right to move it.
 * A held arrow repeats and speeds up. Focus thickens the knob's ring.
 */
final class ColorSlider extends View {

    static final int HUE = 0, SATURATION = 1, BRIGHTNESS = 2;

    interface Listener {
        void onChanged(ColorSlider slider, float value);
    }

    private final int channel;
    private final float max;
    private final float density;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** The other two channels, so the track shows what this one would do to the current colour. */
    private final float[] hsv = {0f, 1f, 1f};
    private float value;
    private Listener listener;

    ColorSlider(Context context, int channel) {
        super(context);
        this.channel = channel;
        this.max = channel == HUE ? 360f : 100f;
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x38FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
    }

    void setListener(Listener l) {
        listener = l;
    }

    float value() {
        return value;
    }

    /** Sets the value and the colour the track is drawn around (h 0-360, s and v 0-1). */
    void set(float value, float h, float s, float v) {
        this.value = Math.max(0f, Math.min(max, value));
        hsv[0] = h;
        hsv[1] = s;
        hsv[2] = v;
        shade();
        invalidate();
    }

    private void shade() {
        if (getWidth() == 0) return;
        float l = rect.left, r = rect.right;
        int[] colors;
        if (channel == HUE) {
            colors = new int[13];
            for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(new float[]{i * 30f, 1f, 1f});
        } else if (channel == SATURATION) {
            colors = new int[]{Color.HSVToColor(new float[]{hsv[0], 0f, Math.max(hsv[2], 0.35f)}),
                    Color.HSVToColor(new float[]{hsv[0], 1f, Math.max(hsv[2], 0.35f)})};
        } else {
            colors = new int[]{0xFF000000, Color.HSVToColor(new float[]{hsv[0], hsv[1], 1f})};
        }
        track.setShader(new LinearGradient(l, 0, r, 0, colors, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float pad = h / 2f;
        float t = h * 0.32f;
        rect.set(pad, t, w - pad, h - t);
        shade();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, track);
        canvas.drawRoundRect(rect, r, r, edge);
        float x = rect.left + rect.width() * (value / max);
        float cy = getHeight() / 2f;
        float kr = getHeight() * (isFocused() ? 0.36f : 0.3f);
        float[] at = hsv.clone();
        if (channel == HUE) {
            at[0] = value;
            at[1] = 1f;
            at[2] = 1f;
        } else if (channel == SATURATION) {
            at[1] = value / 100f;
        } else {
            at[2] = value / 100f;
        }
        knob.setColor(Color.HSVToColor(at));
        canvas.drawCircle(x, cy, kr, knob);
        ring.setStrokeWidth((isFocused() ? 3f : 2f) * density);
        ring.setColor(isFocused() ? 0xFFFFFFFF : 0xCCFFFFFF);
        canvas.drawCircle(x, cy, kr, ring);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // One step a press (5 degrees of hue, 2% otherwise); a held arrow speeds up to cross the range in ~2 s.
            float step = channel == HUE ? 5f : 2f;
            int repeat = event.getRepeatCount();
            if (repeat > 6) step *= 3;
            else if (repeat > 2) step *= 2;
            float next = value + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step);
            next = Math.max(0f, Math.min(max, next));
            if (next != value) {
                value = next;
                invalidate();
                if (listener != null) listener.onChanged(this, value);
            }
            return true;   // the ends keep focus here rather than sending it to the category list
        }
        return super.onKeyDown(keyCode, event);
    }
}
