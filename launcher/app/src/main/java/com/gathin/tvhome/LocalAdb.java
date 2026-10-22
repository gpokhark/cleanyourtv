package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Closes other apps through the TV's own adb service (docs/MEMORY.md, "Closing apps through adb"), for firmware that
 * ignores the launcher's request to close them. All the protocol work, and the limits on what can be run, are in
 * {@link AdbClient}; this class keeps the app's key, filters the list and remembers whether the TV accepted the key.
 *
 * The key is made on first use and kept in the app's private folder, which is excluded from backups; it is never
 * logged. Revoke it on the TV under Developer options, "Revoke USB debugging authorizations".
 */
final class LocalAdb {
    private static final String TAG = "TvHome.Adb";
    private static final String KEY_FILE = "adb_key";
    private static final String PREFS = "adb";

    private static KeyPair cached;

    private LocalAdb() {
    }

    /** Whether the TV has accepted this app's key before, so a run will not show a prompt. */
    static boolean authorized(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("accepted", false);
    }

    /**
     * Force-stops the packages. System packages, this app and anything that is not a package name are dropped from the
     * list first. {@code allowPrompt} false never makes a key and never makes the TV ask: it is for runs nobody started,
     * which must not interrupt what is on screen. Blocks, so call it off the main thread.
     */
    static AdbClient.Result forceStop(Context ctx, Collection<String> packages, boolean allowPrompt) {
        List<String> targets = new ArrayList<>();
        for (String p : packages) {
            if (AdbClient.validPackage(p) && !AdbClient.refused(p) && !p.equals(ctx.getPackageName()) && !targets.contains(p)) targets.add(p);
        }
        if (targets.isEmpty()) return AdbClient.Result.OK;
        if (!allowPrompt && !authorized(ctx)) return AdbClient.Result.NOT_ALLOWED;
        AdbClient.Result result;
        try {
            result = stop(keys(ctx), targets, allowPrompt);
        } catch (GeneralSecurityException | IOException | RuntimeException | LinkageError e) {
            // Whatever goes wrong here, closing apps must never take the launcher down with it.
            Log.w(TAG, "failed: " + e.getClass().getSimpleName());
            return AdbClient.Result.FAILED;
        }
        SharedPreferences.Editor prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (result == AdbClient.Result.OK) prefs.putBoolean("accepted", true).apply();
        else if (result == AdbClient.Result.NOT_ALLOWED) prefs.putBoolean("accepted", false).apply();
        Log.i(TAG, "asked the TV's adb to stop " + targets.size() + " apps: " + result);
        return result;
    }

    private static AdbClient.Result stop(KeyPair keys, List<String> targets, boolean allowPrompt) {
        AdbClient.Result result = AdbClient.Result.OK;
        for (int i = 0; i < targets.size() && result == AdbClient.Result.OK; i += AdbClient.MAX_PACKAGES) {
            List<String> part = targets.subList(i, Math.min(targets.size(), i + AdbClient.MAX_PACKAGES));
            result = AdbClient.forceStop(keys, part, allowPrompt);
        }
        return result;
    }

    private static synchronized KeyPair keys(Context ctx) throws GeneralSecurityException, IOException {
        if (cached != null) return cached;
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey priv;
        try (FileInputStream in = ctx.openFileInput(KEY_FILE)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            priv = factory.generatePrivate(new PKCS8EncodedKeySpec(bytes.toByteArray()));
        } catch (java.io.FileNotFoundException | java.security.spec.InvalidKeySpecException e) {
            // First use, or an unreadable file: make a new key. The TV asks again, which is the safe outcome.
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            KeyPair made = gen.generateKeyPair();
            try (FileOutputStream out = ctx.openFileOutput(KEY_FILE, Context.MODE_PRIVATE)) {
                out.write(made.getPrivate().getEncoded());
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("accepted", false).apply();
            cached = made;
            return cached;
        }
        // Android hands the key back without its public exponent; the key was made with the standard one (65537).
        PublicKey pub = factory.generatePublic(new RSAPublicKeySpec(((RSAKey) priv).getModulus(), RSAKeyGenParameterSpec.F4));
        cached = new KeyPair(pub, priv);
        return cached;
    }
}
