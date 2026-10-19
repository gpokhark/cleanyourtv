package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Tiny on-disk cache of response bodies, one file per request, so repeating a search or reopening a title
 * costs no quota. The cache key is the request without its API key, and nothing secret is ever written.
 */
final class JsonCache {
    private final File dir;

    JsonCache(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** The cached body, or null when absent or older than maxAgeMs. */
    String get(String key, long maxAgeMs) {
        File f = file(key);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    void put(String key, String body) {
        try (FileOutputStream out = new FileOutputStream(file(key))) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A cache that cannot be written just costs a request next time.
        }
    }

    /** Removes entries older than maxAgeMs. */
    void prune(long maxAgeMs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (now - f.lastModified() > maxAgeMs) f.delete();
        }
    }

    private File file(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(dir, sb + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
