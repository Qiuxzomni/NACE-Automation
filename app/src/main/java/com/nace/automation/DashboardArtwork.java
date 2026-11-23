package com.nace.automation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

final class DashboardArtwork {
    enum Coin {
        BALANCE(.048f, .302f, .14f, .89f),
        BTC(.365f, .625f, .015f, .96f),
        ETH(.72f, .95f, .02f, .98f);

        final float left, right, top, bottom;

        Coin(float left, float right, float top, float bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private final Bitmap atlas;

    DashboardArtwork(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        atlas = BitmapFactory.decodeResource(context.getResources(), R.drawable.dashboard_finance_atlas, options);
    }

    Drawable coin(Coin coin, int size) {
        Rect source = new Rect(Math.round(coin.left * atlas.getWidth()), Math.round(coin.top * atlas.getHeight()),
                Math.round(coin.right * atlas.getWidth()), Math.round(coin.bottom * atlas.getHeight()));
        return sprite(atlas, source, size, size);
    }

    static Drawable logo(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.nace_mark, options);
        Rect source = new Rect(Math.round(.093f * logo.getWidth()), Math.round(.17f * logo.getHeight()),
                Math.round(.899f * logo.getWidth()), Math.round(.80f * logo.getHeight()));
        return sprite(logo, source, source.width(), source.height());
    }

    private static Drawable sprite(Bitmap bitmap, Rect source, int width, int height) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final RectF target = new RectF();

            @Override protected void onBoundsChange(Rect bounds) {
                float scale = Math.min((float) bounds.width() / source.width(), (float) bounds.height() / source.height());
                float halfWidth = source.width() * scale / 2f;
                float halfHeight = source.height() * scale / 2f;
                target.set(bounds.exactCenterX() - halfWidth, bounds.exactCenterY() - halfHeight,
                        bounds.exactCenterX() + halfWidth, bounds.exactCenterY() + halfHeight);
            }

            @Override public void draw(Canvas canvas) { canvas.drawBitmap(bitmap, source, target, paint); }
            @Override public int getIntrinsicWidth() { return width; }
            @Override public int getIntrinsicHeight() { return height; }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    static ImageView hero(Context context) {
        return new ImageView(context) {
            final Paint mask = new Paint();
            Shader horizontal;
            Shader vertical;

            {
                mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            }

            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                super.onSizeChanged(width, height, oldWidth, oldHeight);
                if (width == 0 || height == 0) return;
                int[] colors = {0x00ffffff, 0xffffffff, 0xffffffff, 0x00ffffff};
                horizontal = new LinearGradient(0, 0, width, 0, colors, new float[]{0, .085f, .915f, 1}, Shader.TileMode.CLAMP);
                vertical = new LinearGradient(0, 0, 0, height, colors, new float[]{0, .065f, .91f, 1}, Shader.TileMode.CLAMP);
            }

            @Override protected void onDraw(Canvas canvas) {
                if (horizontal == null || vertical == null) {
                    super.onDraw(canvas);
                    return;
                }
                int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
                super.onDraw(canvas);
                mask.setShader(horizontal);
                canvas.drawRect(0, 0, getWidth(), getHeight(), mask);
                mask.setShader(vertical);
                canvas.drawRect(0, 0, getWidth(), getHeight(), mask);
                canvas.restoreToCount(layer);
            }
        };
    }
}
