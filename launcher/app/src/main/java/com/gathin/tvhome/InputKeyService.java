package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

import java.util.HashMap;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Every other key passes through untouched.
 * Our `key_apps` config, "key|android.package|link" (or "action:name" for an app that only answers to its own action),
 * does the same with its own format. A button the system has no name for is logged ("TvHome.Keys key N").
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {
    private static final String TAG = "TvHome.Keys";
    private static final String ACTION_PREFIX = "action:";
    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();
    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;

    private static final class Target {
        final String pkg;
        final String link;

        Target(String pkg, String link) {
            this.pkg = pkg;
            this.link = link;
        }
    }

    private final Map<Integer, Target> keyApps = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        keyApps.clear();
        for (String entry : Config.load(this).keyApps) {
            Log.i(TAG, "key_apps " + entry);
            String[] p = entry.split("\\|", 3);
            if (p.length < 2) continue;
            int code = keyCode(p[0].trim());
            if (code != KeyEvent.KEYCODE_UNKNOWN && !p[1].trim().isEmpty()) {
                keyApps.put(code, new Target(p[1].trim(), p.length > 2 ? p[2].trim() : ""));
            }
        }
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        inputKeycodes = config.inputKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    private static int keyCode(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return KeyEvent.keyCodeFromString(s);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        int code = event.getKeyCode();
        Target app = keyApps.get(code);
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) open(app);
            return true;
        }
        // A button the system has no name for is logged, so its number can be found for key_apps.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !isInputKey(code)
                && !KeyEvent.keyCodeToString(code).startsWith("KEYCODE_")) {
            Log.i(TAG, "key " + code);
        }
        if (!isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    private void open(Target t) {
        Log.i(TAG, "opening " + t.pkg);
        if (!t.link.isEmpty()) {
            try {
                // "action:name" for an app whose start screen answers only to its own action; otherwise a link.
                Intent view = t.link.startsWith(ACTION_PREFIX)
                        ? new Intent(t.link.substring(ACTION_PREFIX.length())).addCategory(Intent.CATEGORY_DEFAULT)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(t.link));
                startActivity(view.setPackage(t.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // The app does not take that link: open it normally.
            }
        }
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(t.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(t.pkg);
        if (launch == null) {
            Log.w(TAG, t.pkg + " has no launcher entry");
            return;
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open " + t.pkg + ": " + e);
        }
    }


    // ---- off while an on-screen keyboard is in use ----
    //
    // A key service sits in the path of every real remote key press and answers on the launcher's main thread, so on
    // some TVs the on-screen keyboard in Search stops following the remote while it is on (the same keys sent over
    // adb are fine). The Search screen therefore switches it off while it is showing, and it comes back on leaving.
    // The enabled-services setting is changed directly (WRITE_SECURE_SETTINGS); a flag remembers that it was paused.

    private static final String PAUSE_PREFS = "keyservice";

    /** Switches the key service off, if it is on. Pair with {@link #resume}. */
    static void pause(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.contains(InputKeyService.class.getSimpleName())) return;
            StringBuilder rest = new StringBuilder();
            for (String one : list.split(":")) {
                if (one.isEmpty() || one.contains(InputKeyService.class.getSimpleName())) continue;
                if (rest.length() > 0) rest.append(':');
                rest.append(one);
            }
            ctx.getSharedPreferences(PAUSE_PREFS, Context.MODE_PRIVATE).edit().putBoolean("paused", true).commit();
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.toString());
        } catch (SecurityException e) {
            // WRITE_SECURE_SETTINGS not granted: the service stays on.
        }
    }

    /** Switches the key service back on if {@link #pause} switched it off. Cheap when it did not. */
    static void resume(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PAUSE_PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("paused", false)) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String mine = new ComponentName(ctx, InputKeyService.class).flattenToString();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.isEmpty() || "null".equals(list)) list = mine;
            else if (!list.contains(InputKeyService.class.getSimpleName())) list = list + ":" + mine;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            prefs.edit().putBoolean("paused", false).commit();
        } catch (SecurityException e) {
            // Not granted: nothing was paused either.
            prefs.edit().putBoolean("paused", false).commit();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
