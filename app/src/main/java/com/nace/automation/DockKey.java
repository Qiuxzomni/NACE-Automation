package com.nace.automation;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * Raised 3D "key" background for the WATCH / CONTROL dock buttons. Same size and position as before,
 * but filled and lifted on a darker lip so they read as action buttons and never as the BTC/ETH
 * selector toggles (purple = off, orange = selected). When pressed the key sinks onto its lip.
 */
final class DockKey {
    enum Style { GRAPHITE, PEARL, GOLD }

    /** Design shown in the app. */
    static final Style STYLE = Style.GRAPHITE;

    static final class Look {
        final int top, mid, bottom, lip, rim, text, shadow;

        Look(int top, int mid, int bottom, int lip, int rim, int text, int shadow) {
            this.top = top;
            this.mid = mid;
            this.bottom = bottom;
            this.lip = lip;
            this.rim = rim;
            this.text = text;
            this.shadow = shadow;
        }
    }

    private DockKey() {}

    static Look look(Style style) {
        switch (style) {
            case PEARL:
                // Glossy white keys with deep plum text: the only light surface on the dark dashboard.
                return new Look(0xffffffff, 0xffe9e4f2, 0xffb9b0cc, 0xff6d6380, 0xffd9d2ff, 0xff3a0f52, 0x66ffffff);
            case GOLD:
                // Solid brushed gold like the NACE logo, dark bronze text.
                return new Look(0xfffff2b0, 0xffe8b923, 0xffa87410, 0xff5e3d05, 0xffffd34d, 0xff3a2400, 0x66fff4c4);
            case GRAPHITE:
            default:
                // Brushed graphite metal with gold lettering and a gold glass rim.
                return new Look(0xff6c6f7a, 0xff3a3c45, 0xff1d1e24, 0xff0a0a0d, 0xffffc83d, 0xffffd36b, 0xcc000000);
        }
    }

    /** Neon theme: filled metal/pearl key with a glass rim around it. */
    static Drawable neon(Context context, float radiusDp) {
        return states(context, look(STYLE), radiusDp, true);
    }

    /** Terracotta theme: the same key face with a solid lip instead of the neon rim. */
    static Drawable terracotta(Context context, float radiusDp) {
        return states(context, look(STYLE), radiusDp, false);
    }

    private static Drawable states(Context context, Look look, float radiusDp, boolean neon) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, key(context, look, radiusDp, neon, true));
        states.addState(new int[]{}, key(context, look, radiusDp, neon, false));
        return states;
    }

    private static Drawable key(Context context, Look look, float radiusDp, boolean neon, boolean pressed) {
        float density = context.getResources().getDisplayMetrics().density;
        float radius = radiusDp * density;
        int lipHeight = Math.round(5 * density);
        int sink = pressed ? Math.round(4 * density) : 0;
        int bodyTop = sink;
        int bodyBottom = lipHeight - sink;

        GradientDrawable lip = new GradientDrawable();
        lip.setCornerRadius(radius);
        lip.setColor(look.lip);

        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{look.top, look.mid, look.bottom});
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{pressed ? 0x30ffffff : 0x60ffffff, 0x00ffffff});

        if (!neon) {
            body.setCornerRadius(radius);
            body.setStroke(Math.round(1.5f * density), look.lip);
            gloss.setCornerRadius(radius);
            LayerDrawable layer = new LayerDrawable(new Drawable[]{lip, body, gloss});
            layer.setLayerInset(1, 0, bodyTop, 0, bodyBottom);
            int glossInset = Math.round(3 * density);
            layer.setLayerInset(2, glossInset, bodyTop + Math.round(2 * density), glossInset, bodyBottom);
            layer.setLayerHeight(2, Math.round(20 * density));
            return layer;
        }
        // Neon: the glowing glass rim is the outer edge; the solid face sits inside it.
        int rim = Math.round(6 * density);
        float faceRadius = Math.max(radius - rim, 4 * density);
        body.setCornerRadius(faceRadius);
        gloss.setCornerRadius(faceRadius);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{
                lip, NeonUi.frame(context, look.rim, radiusDp), body, gloss});
        layer.setLayerInset(1, 0, bodyTop, 0, bodyBottom);
        layer.setLayerInset(2, rim, bodyTop + rim, rim, bodyBottom + rim);
        layer.setLayerInset(3, rim + Math.round(2 * density), bodyTop + rim + Math.round(1 * density),
                rim + Math.round(2 * density), bodyBottom + rim);
        layer.setLayerHeight(3, Math.round(18 * density));
        return layer;
    }

    static int textColor() {
        return look(STYLE).text;
    }

    static int textShadow() {
        return look(STYLE).shadow;
    }
}
