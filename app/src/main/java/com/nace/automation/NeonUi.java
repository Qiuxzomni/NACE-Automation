package com.nace.automation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

final class NeonUi {
    static final int WHITE = 0xfffaf2ff;
    static final int MUTED = 0xffd6b9e2;
    static final int GOLD = 0xffffe078;
    static final int ORANGE = 0xffff9c09;
    static final int PURPLE = 0xffdb20ff;
    static final int GREEN = 0xff9cff99;
    static final int RED = 0xffff748d;
    static final int DARK = 0xff0b0311;

    private NeonUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String content, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static Button button(Context context, String caption) {
        Button view = new Button(context);
        view.setText(caption);
        view.setAllCaps(false);
        view.setTextColor(WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(context, 42));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(dp(context, 5), dp(context, 3), dp(context, 5), dp(context, 3));
        return view;
    }

    static void setButtonAccent(Button button, int accent, float radius) {
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x42ffffff),
                frame(button.getContext(), accent, radius), null));
    }

    static Drawable frame(Context context, int accent, float radius) {
        return GlassFrame.create(context, accent, radius);
    }

    static Drawable addTimeFrame(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF box = new RectF();
            final DashPathEffect dashes = new DashPathEffect(new float[]{6f * density, 4f * density}, 0);

            @Override public void draw(Canvas canvas) {
                box.set(getBounds());
                box.inset(3f * density, 3f * density);
                paint.setStyle(Paint.Style.FILL);
                paint.setPathEffect(null);
                paint.setColor(0x66280842);
                canvas.drawRoundRect(box, 17f * density, 17f * density, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xfff190ff);
                paint.setStrokeWidth(.95f * density);
                paint.setPathEffect(dashes);
                canvas.drawRoundRect(box, 17f * density, 17f * density, paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    static Drawable backdrop() {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Shader haze;

            @Override protected void onBoundsChange(Rect bounds) {
                float width = bounds.width();
                float height = bounds.height();
                haze = new RadialGradient(width * .49f, Math.min(height * .40f, width * .88f),
                        Math.max(width * .87f, 1f), new int[]{0xff22052e, 0xff12051a, DARK},
                        new float[]{0, .57f, 1f}, Shader.TileMode.CLAMP);
            }

            @Override
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                if (bounds.isEmpty()) return;
                canvas.drawColor(DARK);
                paint.setShader(haze);
                canvas.drawRect(bounds, paint);
                paint.setShader(null);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

}
