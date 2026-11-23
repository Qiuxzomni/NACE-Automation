package com.nace.automation;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small, tintable outline icons for the native dashboard. */
public final class DashboardIcons {
    private static final float VIEW_SIZE = 24f;

    private DashboardIcons() {
    }

    public enum Icon {
        HISTORY,
        SETTINGS,
        LAYERS,
        CALENDAR,
        CLOCK,
        WATCH,
        CONTROL,
        COFFEE,
        CHECK_CIRCLE,
        CROSS_CIRCLE,
        CHEVRON
    }

    public static Drawable icon(Icon icon, int color, int sizePx) {
        if (icon == null) {
            throw new IllegalArgumentException("icon must not be null");
        }
        if (sizePx <= 0) {
            throw new IllegalArgumentException("sizePx must be greater than zero");
        }
        return new OutlineIconDrawable(icon, color, sizePx);
    }

    private static final class OutlineIconDrawable extends Drawable {
        private final Icon icon;
        private final int color;
        private final int sizePx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        OutlineIconDrawable(Icon icon, int color, int sizePx) {
            this.icon = icon;
            this.color = color;
            this.sizePx = sizePx;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }
            int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / VIEW_SIZE, bounds.height() / VIEW_SIZE);
            paint.setColor(color);
            paint.setAlpha(getAlpha());
            switch (icon) {
                case HISTORY:
                    drawHistory(canvas);
                    break;
                case SETTINGS:
                    drawSettings(canvas);
                    break;
                case LAYERS:
                    drawLayers(canvas);
                    break;
                case CALENDAR:
                    drawCalendar(canvas);
                    break;
                case CLOCK:
                    drawClock(canvas);
                    break;
                case WATCH:
                    drawWatch(canvas);
                    break;
                case CONTROL:
                    drawControl(canvas);
                    break;
                case COFFEE:
                    drawCoffee(canvas);
                    break;
                case CHECK_CIRCLE:
                    drawCheckCircle(canvas);
                    break;
                case CROSS_CIRCLE:
                    canvas.drawCircle(12f, 12f, 9f, paint);
                    canvas.drawLine(8.5f, 8.5f, 15.5f, 15.5f, paint);
                    canvas.drawLine(15.5f, 8.5f, 8.5f, 15.5f, paint);
                    break;
                case CHEVRON:
                    path.reset();
                    path.moveTo(9f, 5f);
                    path.lineTo(16f, 12f);
                    path.lineTo(9f, 19f);
                    canvas.drawPath(path, paint);
                    break;
            }
            canvas.restoreToCount(save);
        }

        private void drawHistory(Canvas canvas) {
            path.reset();
            path.moveTo(6f, 3.5f);
            path.lineTo(14.5f, 3.5f);
            path.lineTo(18f, 7f);
            path.lineTo(18f, 20.5f);
            path.lineTo(6f, 20.5f);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawLine(14.5f, 3.8f, 14.5f, 7.2f, paint);
            canvas.drawLine(14.5f, 7.2f, 17.7f, 7.2f, paint);
            canvas.drawLine(9f, 11f, 15f, 11f, paint);
            canvas.drawLine(9f, 14f, 15f, 14f, paint);
            canvas.drawLine(9f, 17f, 13f, 17f, paint);
        }

        private void drawSettings(Canvas canvas) {
            // Eight rounded teeth around a clean circular hub.
            canvas.drawCircle(12f, 12f, 7.6f, paint);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4.0;
                float inner = 7.1f;
                float outer = 9.4f;
                float x1 = 12f + (float) Math.cos(angle) * inner;
                float y1 = 12f + (float) Math.sin(angle) * inner;
                float x2 = 12f + (float) Math.cos(angle) * outer;
                float y2 = 12f + (float) Math.sin(angle) * outer;
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
            canvas.drawCircle(12f, 12f, 3.1f, paint);
        }

        private void drawLayers(Canvas canvas) {
            drawDiamond(canvas, 12f, 4f, 8.5f);
            drawDiamond(canvas, 12f, 9f, 8.5f);
            drawDiamond(canvas, 12f, 14f, 8.5f);
        }

        private void drawDiamond(Canvas canvas, float cx, float cy, float halfWidth) {
            path.reset();
            path.moveTo(cx, cy - 3.8f);
            path.lineTo(cx + halfWidth, cy);
            path.lineTo(cx, cy + 3.8f);
            path.lineTo(cx - halfWidth, cy);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void drawCalendar(Canvas canvas) {
            rect.set(3.5f, 5.5f, 20.5f, 20f);
            canvas.drawRoundRect(rect, 2f, 2f, paint);
            canvas.drawLine(3.8f, 9.5f, 20.2f, 9.5f, paint);
            canvas.drawLine(8f, 3.5f, 8f, 7f, paint);
            canvas.drawLine(16f, 3.5f, 16f, 7f, paint);
            canvas.drawCircle(8f, 13f, 0.65f, paint);
            canvas.drawCircle(12f, 13f, 0.65f, paint);
            canvas.drawCircle(16f, 13f, 0.65f, paint);
            canvas.drawCircle(8f, 17f, 0.65f, paint);
            canvas.drawCircle(12f, 17f, 0.65f, paint);
        }

        private void drawClock(Canvas canvas) {
            canvas.drawCircle(12f, 12f, 8.5f, paint);
            canvas.drawLine(12f, 6.5f, 12f, 12f, paint);
            canvas.drawLine(12f, 12f, 16f, 14.5f, paint);
        }

        private void drawWatch(Canvas canvas) {
            path.reset();
            path.moveTo(2.5f, 12f);
            path.cubicTo(5f, 7.6f, 8.1f, 5.5f, 12f, 5.5f);
            path.cubicTo(15.9f, 5.5f, 19f, 7.6f, 21.5f, 12f);
            path.cubicTo(19f, 16.4f, 15.9f, 18.5f, 12f, 18.5f);
            path.cubicTo(8.1f, 18.5f, 5f, 16.4f, 2.5f, 12f);
            canvas.drawPath(path, paint);
            canvas.drawCircle(12f, 12f, 3.1f, paint);
            canvas.drawCircle(12f, 12f, 0.7f, paint);
        }

        private void drawControl(Canvas canvas) {
            canvas.drawLine(4f, 6f, 20f, 6f, paint);
            canvas.drawLine(4f, 12f, 20f, 12f, paint);
            canvas.drawLine(4f, 18f, 20f, 18f, paint);
            canvas.drawCircle(9f, 6f, 2f, paint);
            canvas.drawCircle(15f, 12f, 2f, paint);
            canvas.drawCircle(7f, 18f, 2f, paint);
        }

        private void drawCoffee(Canvas canvas) {
            path.reset();
            path.moveTo(4f, 8.5f);
            path.lineTo(16.5f, 8.5f);
            path.lineTo(16.5f, 13f);
            path.cubicTo(16.5f, 16.8f, 14.4f, 19f, 10.2f, 19f);
            path.cubicTo(6.1f, 19f, 4f, 16.8f, 4f, 13f);
            path.close();
            path.moveTo(16.5f, 9.5f);
            path.cubicTo(23f, 8.5f, 22.6f, 15.5f, 16.2f, 15.5f);
            path.moveTo(6.5f, 5.4f);
            path.cubicTo(4.9f, 3.7f, 8.6f, 3.5f, 7.4f, 1.9f);
            path.moveTo(12f, 5.4f);
            path.cubicTo(10.4f, 3.7f, 14.1f, 3.5f, 12.9f, 1.9f);
            canvas.drawPath(path, paint);
            canvas.drawLine(2.5f, 22f, 18.5f, 22f, paint);
        }

        private void drawCheckCircle(Canvas canvas) {
            canvas.drawCircle(12f, 12f, 9f, paint);
            path.reset();
            path.moveTo(7.4f, 12.3f);
            path.lineTo(10.5f, 15.4f);
            path.lineTo(16.8f, 8.9f);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }
    }
}
