package com.nace.automation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** Native drawable palette and surfaces for the warm terracotta dashboard theme. */
final class TerracottaUi {
    static final int PAPER = Color.rgb(248, 239, 222);
    static final int INK = Color.rgb(53, 29, 20);
    static final int MUTED = Color.rgb(120, 91, 76);
    static final int CLAY = Color.rgb(184, 72, 22);
    static final int BURGUNDY = Color.rgb(119, 12, 34);
    static final int GREEN = Color.rgb(37, 99, 43);
    static final int RED = Color.rgb(176, 38, 27);
    static final int RULE = Color.rgb(166, 64, 19);
    static final int IVORY = Color.rgb(255, 248, 234);

    private TerracottaUi() {
    }

    static Drawable paper(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(PAPER);
        return drawable;
    }

    static Drawable banner(Context context, boolean online) {
        return new BannerDrawable(context, online ? CLAY : Color.rgb(151, 77, 59));
    }

    static Drawable frame(Context context, int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radius));
        drawable.setStroke(dp(context, 1.4f), stroke);
        return drawable;
    }

    static Drawable button(Context context, int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.alpha(fill) == 0 ? fill : blend(fill, IVORY, 0.08f), fill});
        drawable.setCornerRadius(dp(context, radius));
        drawable.setStroke(dp(context, 1.5f), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x22653623), drawable,
                frame(context, Color.WHITE, Color.TRANSPARENT, radius));
    }

    static Drawable segment(Context context, boolean first, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? BURGUNDY : PAPER);
        float radius = dp(context, 13f);
        if (first) {
            drawable.setCornerRadii(new float[]{radius, radius, 0f, 0f, 0f, 0f, radius, radius});
        } else {
            drawable.setCornerRadii(new float[]{0f, 0f, radius, radius, radius, radius, 0f, 0f});
        }
        drawable.setStroke(dp(context, 1.3f), selected ? BURGUNDY : INK);
        return drawable;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) * (1f - amount) + Color.red(to) * amount),
                Math.round(Color.green(from) * (1f - amount) + Color.green(to) * amount),
                Math.round(Color.blue(from) * (1f - amount) + Color.blue(to) * amount));
    }

    private static final class BannerDrawable extends Drawable {
        private final int baseColor;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint emboss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF shape = new RectF();
        private final RectF arc = new RectF();

        BannerDrawable(Context context, int baseColor) {
            this.baseColor = baseColor;
            density = context.getResources().getDisplayMetrics().density;
            emboss.setStyle(Paint.Style.STROKE);
            emboss.setStrokeWidth(1.2f * density);
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(0.8f * density);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;

            float radius = Math.min(bounds.height() * 0.09f, bounds.width() * 0.055f);
            shape.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
            fill.setShader(new LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    blend(baseColor, INK, 0.08f), blend(baseColor, IVORY, 0.05f),
                    Shader.TileMode.CLAMP));
            fill.setAlpha(getAlpha());
            fill.setColorFilter(getColorFilter());
            canvas.drawRoundRect(shape, radius, radius, fill);

            int save = canvas.save();
            clip.reset();
            clip.addRoundRect(shape, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            float cx = bounds.right + bounds.width() * 0.025f;
            float cy = bounds.top + bounds.height() * 0.82f;
            float[] radii = {bounds.height() * 0.43f, bounds.height() * 0.66f, bounds.height() * 0.89f};
            emboss.setColor(withAlpha(blend(baseColor, INK, 0.42f), Math.round(24 * getAlpha() / 255f)));
            highlight.setColor(withAlpha(blend(baseColor, IVORY, 0.45f), Math.round(18 * getAlpha() / 255f)));
            for (float r : radii) {
                arc.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(arc, 188f, 152f, false, emboss);
                arc.offset(-0.7f * density, -0.7f * density);
                canvas.drawArc(arc, 188f, 152f, false, highlight);
            }
            canvas.restoreToCount(save);
            fill.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        private static int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
