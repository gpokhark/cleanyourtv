package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds the where-to-watch provider named by `search_provider`. To add a source: implement
 * {@link TitleSearch.Provider}, add a case below, and push its key with tools/push-key.sh.
 * The key is read from the launcher's private folder on the TV, never from the app or the repo.
 */
final class SearchProviders {
    private SearchProviders() {}

    static final String KEY_DIR = "keys";

    /** Where the key for a provider lives: <external files>/keys/<provider>.key. */
    static File keyFile(Context ctx, String provider) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(new File(dir, KEY_DIR), provider.toLowerCase(Locale.ROOT) + ".key");
    }

    /** The provider, or a Failure saying why there isn't one (no key pushed, unknown name). */
    static TitleSearch.Provider create(Context ctx, String name, String region) throws TitleSearch.Failure {
        String provider = name.toLowerCase(Locale.ROOT);
        String key = readKey(keyFile(ctx, provider));
        if (key.isEmpty()) {
            throw new TitleSearch.Failure(TitleSearch.Failure.Kind.NO_KEY, "No " + provider + " key on this TV");
        }
        JsonCache cache = new JsonCache(new File(ctx.getCacheDir(), "search-" + provider));
        cache.prune(30L * 24 * 60 * 60 * 1000);
        switch (provider) {
            case "watchmode":
                return new WatchmodeProvider(key, region, cache);
            default:
                throw new TitleSearch.Failure(TitleSearch.Failure.Kind.OTHER, "Unknown search provider: " + name);
        }
    }

    private static String readKey(File f) {
        if (f == null || !f.isFile() || f.length() > 512) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
