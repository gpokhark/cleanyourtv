package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Watchmode (api.watchmode.com). Search is one request, the services for a title another. Responses are
 * cached on disk, which Watchmode's terms allow for 30 days, so each title costs quota once.
 * The free plan returns no app deep links, so only the service is used, never a link into it.
 */
final class WatchmodeProvider implements TitleSearch.Provider {
    private static final String BASE = "https://api.watchmode.com/v1/";
    private static final long SEARCH_TTL_MS = 24L * 60 * 60 * 1000;
    private static final long SOURCES_TTL_MS = 3L * 24 * 60 * 60 * 1000;

    private final String key;
    private final String region;
    private final JsonCache cache;

    WatchmodeProvider(String key, String region, JsonCache cache) {
        this.key = key;
        this.region = region;
        this.cache = cache;
    }

    @Override
    public List<TitleSearch.Title> search(String query) throws IOException {
        String body = get("search/?search_field=name&search_value=" + enc(query), SEARCH_TTL_MS);
        List<TitleSearch.Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(body).optJSONArray("title_results");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new TitleSearch.Title(String.valueOf(o.getLong("id")), o.optString("name"),
                        o.optInt("year", 0), kind(o.optString("type"))));
            }
        } catch (JSONException e) {
            throw new TitleSearch.Failure(TitleSearch.Failure.Kind.OTHER, "Unexpected answer from Watchmode");
        }
        return out;
    }

    @Override
    public List<TitleSearch.Source> sources(TitleSearch.Title title) throws IOException {
        String body = get("title/" + enc(title.id) + "/sources/?regions=" + enc(region), SOURCES_TTL_MS);
        List<TitleSearch.Source> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();   // the same service is listed once per video format
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = String.valueOf(o.optInt("source_id"));
                TitleSearch.Access access = access(o.optString("type"));
                if (!seen.add(id + "/" + access)) continue;
                out.add(new TitleSearch.Source(id, o.optString("name"), access));
            }
        } catch (JSONException e) {
            throw new TitleSearch.Failure(TitleSearch.Failure.Kind.OTHER, "Unexpected answer from Watchmode");
        }
        return out;
    }

    private static TitleSearch.Access access(String type) {
        switch (type) {
            case "sub": return TitleSearch.Access.SUB;
            case "free": return TitleSearch.Access.FREE;
            case "rent": return TitleSearch.Access.RENT;
            case "buy": return TitleSearch.Access.BUY;
            default: return TitleSearch.Access.OTHER;   // "tve" needs a cable login
        }
    }

    private static String kind(String type) {
        return type.startsWith("tv_") ? "series" : type.replace('_', ' ');
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** GET with the cache in front. The cache key leaves the API key out. */
    private String get(String pathAndQuery, long ttlMs) throws IOException {
        String cacheKey = "watchmode:" + pathAndQuery.toLowerCase(Locale.ROOT);
        String cached = cache.get(cacheKey, ttlMs);
        if (cached != null) return cached;

        HttpURLConnection c = null;
        try {
            String sep = pathAndQuery.contains("?") ? "&" : "?";
            c = (HttpURLConnection) new URL(BASE + pathAndQuery + sep + "apiKey=" + enc(key)).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                throw new TitleSearch.Failure(TitleSearch.Failure.Kind.REJECTED_KEY, "Watchmode rejected the API key");
            }
            if (code == 429) {
                throw new TitleSearch.Failure(TitleSearch.Failure.Kind.LIMIT, "Watchmode request limit reached");
            }
            if (code != 200) {
                throw new TitleSearch.Failure(TitleSearch.Failure.Kind.OTHER, "Watchmode answered " + code);
            }
            String body = read(c.getInputStream());
            cache.put(cacheKey, body);
            return body;
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException | java.net.ConnectException e) {
            throw new TitleSearch.Failure(TitleSearch.Failure.Kind.OFFLINE, "No connection");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
