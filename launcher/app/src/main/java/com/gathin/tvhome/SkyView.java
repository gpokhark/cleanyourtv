package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * A full-screen gradient that follows the clock: night blues, a warm horizon at sunset, a soft
 * glow that travels with the sun. Off by default (config.xml, sky_intensity = 0); a theme turns
 * it on with {@code "sky_intensity": 60}.
 *
 * Like the ambient glow, the backdrop is one small bitmap (480x270) stretched over the screen:
 * a gradient repainted at full resolution every minute is exactly the CPU cost the ambient glow
 * was rewritten to avoid on weak GPUs. It is redrawn once a minute and never animated, so the
 * cost is a single full-screen draw per minute.
 */
public class SkyView extends View {

    /** Full-screen and faded with setAlpha: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static final int TEX_W = 480;
    private static final int TEX_H = 270;

    /** Opacity of the whole backdrop, 0 to 255, from sky_intensity. */
    private int intensity;
    private int lastMinute = -1;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private Bitmap texture;
    private BitmapDrawable drawable;

    public SkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** The place to compute sunrise and sunset for (sky_latitude, sky_longitude); NaN for either leaves it to the TV's own location. */
    public void setLocation(double lat, double lon) {
        latitude = lat;
        longitude = lon;
    }

    /** {@code percent} of sky_intensity: 0 hides the backdrop, 100 covers the wallpaper. */
    public void setIntensity(int percent) {
        intensity = Math.max(0, Math.min(100, percent));
        setAlpha(intensity / 100f);
        setVisibility(intensity == 0 ? GONE : VISIBLE);
        if (intensity > 0) render(true);
    }

    /** Picks up a new time of day; cheap and safe to call every minute. */
    public void update() {
        if (intensity == 0 || getVisibility() != VISIBLE) return;
        render(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (intensity > 0) render(true);
    }

    private void render(boolean force) {
        Calendar now = Calendar.getInstance();
        int minute = Sky.minuteOfDay(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        if (!force && minute == lastMinute) return;
        lastMinute = minute;

        Sky.Palette sky = palette(now, minute);
        if (texture == null) texture = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(texture);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0f, 0f, 0f, TEX_H,
                new int[]{sky.top, sky.mid, sky.bottom},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);

        if (sky.sun > 0) {
            // A warm core low on the horizon, where the sun (or its afterglow) sits.
            int warm = Sky.lerpColor(sky.bottom, 0xFFFFD9A0, 0.65f);
            paint.setShader(new RadialGradient(
                    sky.sunX * TEX_W, TEX_H * 0.94f, TEX_W * 0.42f,
                    new int[]{(sky.sun << 24) | (warm & 0x00FFFFFF), warm & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);
        }

        if (drawable == null) {
            drawable = new BitmapDrawable(getResources(), texture);
            drawable.setGravity(Gravity.FILL);
            setBackground(drawable);
        }
        invalidate();
    }

    /**
     * The palette for {@code minute}, anchored to the real sunrise and sunset for the configured
     * place, or else when the system already holds a last known fix (one binder call, no listener, no updates requested).
     * Without the permission or a fix this is the fixed-anchor palette.
     */
    private Sky.Palette palette(Calendar now, int minute) {
        try {
            if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
                int[] sun = Sky.sunTimes(latitude, longitude, now.get(Calendar.DAY_OF_YEAR), utcOffset(now));
                if (sun != null) return Sky.at(minute, sun[0], sun[1]);
                return Sky.at(minute);
            }
            if (getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
                Location fix = lm == null ? null : lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (fix == null && lm != null) fix = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (fix != null) {
                    int[] sun = Sky.sunTimes(fix.getLatitude(), fix.getLongitude(),
                            now.get(Calendar.DAY_OF_YEAR), utcOffset(now));
                    if (sun != null) return Sky.at(minute, sun[0], sun[1]);
                }
            }
        } catch (RuntimeException ignored) {
            // No location service on this TV: fall through to the fixed anchors.
        }
        return Sky.at(minute);
    }

    private static int utcOffset(Calendar now) {
        return (now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / 60000;
    }
}
