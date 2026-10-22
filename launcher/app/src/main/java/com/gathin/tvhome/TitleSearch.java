package com.gathin.tvhome;

import java.io.IOException;
import java.util.List;

/**
 * Where-to-watch lookups, independent of the data source. A source (Watchmode today) implements
 * {@link Provider}; {@link SearchProviders} picks one by the `search_provider` config value, so another
 * can be added with one class and one line there. Sources report services by their own ids, which the
 * `my_services` config lists next to each service (see {@link MyServices}).
 */
final class TitleSearch {
    private TitleSearch() {}

    /** What a service does with a title. Only SUB and FREE cost nothing extra. */
    enum Access { SUB, FREE, RENT, BUY, OTHER }

    static final class Title {
        final String id;
        final String name;
        final int year;       // 0 when unknown
        final String kind;    // "movie", "series", ...

        Title(String id, String name, int year, String kind) {
            this.id = id;
            this.name = name;
            this.year = year;
            this.kind = kind;
        }
    }

    static final class Source {
        final String serviceId;
        final String serviceName;
        final Access access;

        Source(String serviceId, String serviceName, Access access) {
            this.serviceId = serviceId;
            this.serviceName = serviceName;
            this.access = access;
        }
    }

    interface Provider {
        /** Matching titles, best first. One request. */
        List<Title> search(String query) throws IOException;

        /** Every service that offers the title in the configured region. One request. */
        List<Source> sources(Title title) throws IOException;
    }

    /** A failed lookup, with enough of a reason to tell the viewer something useful. */
    static final class Failure extends IOException {
        enum Kind { NO_KEY, REJECTED_KEY, LIMIT, OFFLINE, OTHER }

        final Kind kind;

        Failure(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}
