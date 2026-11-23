package com.nace.automation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Keeps the rendered bevel and highlights intact when panels change size. */
final class GlassFrame extends Drawable {
    private static final int SAMPLE = 2;
    private static Bitmap atlas;

    private final Bitmap texture;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private final int[] sourceX = new int[4];
    private final int[] sourceY = new int[4];
    private final float[] targetX = new float[4];
    private final float[] targetY = new float[4];
    private final float radius;
    private final float density;
    private final boolean pill;
    private int rows;

    static GlassFrame create(Context context, int accent, float radius) {
        if (atlas == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = SAMPLE;
            atlas = BitmapFactory.decodeResource(context.getResources(), R.drawable.neon_glass_atlas, options);
        }
        return new GlassFrame(atlas, accent, radius, context.getResources().getDisplayMetrics().density);
    }

    private GlassFrame(Bitmap texture, int accent, float radius, float density) {
        this.texture = texture;
        this.radius = radius;
        this.density = density;
        pill = radius >= 20;
        if (accent != NeonUi.PURPLE) paint.setColorFilter(similarGlass(accent));
    }

    @Override protected void onBoundsChange(Rect bounds) {
        // Coordinates refer to the transparent crop around each shape in the artwork.
        int left = (pill ? 33 : 171) / SAMPLE;
        int right = (pill ? 1500 : 1361) / SAMPLE;
        int top = (pill ? 67 : 545) / SAMPLE;
        int bottom = (pill ? 507 : 978) / SAMPLE;
        int cap = (pill ? 266 : 227) / SAMPLE;
        sourceX[0] = left;
        sourceX[1] = left + cap;
        sourceX[2] = right - cap;
        sourceX[3] = right;
        float scale = bounds.height() / (float) (bottom - top);
        boolean tall = bounds.height() > 76f * density;
        if (tall) scale = radius * density / (116f / SAMPLE);
        int width = Math.round(Math.min(cap * scale, bounds.width() * .465f));
        targetX[0] = bounds.left;
        targetX[1] = bounds.left + width;
        targetX[2] = bounds.right - width;
        targetX[3] = bounds.right;
        sourceY[0] = top;
        targetY[0] = bounds.top;
        if (tall) {
            int verticalCap = 147 / SAMPLE;
            int height = Math.round(Math.min(verticalCap * scale, bounds.height() * .43f));
            sourceY[1] = top + verticalCap;
            sourceY[2] = bottom - verticalCap;
            sourceY[3] = bottom;
            targetY[1] = bounds.top + height;
            targetY[2] = bounds.bottom - height;
            targetY[3] = bounds.bottom;
            rows = 3;
        } else {
            sourceY[1] = bottom;
            targetY[1] = bounds.bottom;
            rows = 1;
        }
    }

    @Override public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < 3; x++) {
                source.set(sourceX[x], sourceY[y], sourceX[x + 1], sourceY[y + 1]);
                target.set(targetX[x], targetY[y], targetX[x + 1], targetY[y + 1]);
                canvas.drawBitmap(texture, source, target, paint);
            }
        }
    }

    private static ColorMatrixColorFilter similarGlass(int accent) {
        // The original has a magenta cast. Use its green channel as the neutral
        // component so the white hotspots stay white when the rim changes color.
        float red = Color.red(accent) / 255f;
        float green = Color.green(accent) / 255f;
        float blue = Color.blue(accent) / 255f;
        return new ColorMatrixColorFilter(new float[]{
                .52f * red, 1f - red, .48f * red, 0, 0,
                .52f * green, 1f - green, .48f * green, 0, 0,
                .52f * blue, 1f - blue, .48f * blue, 0, 0,
                0, 0, 0, 1, 0
        });
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
