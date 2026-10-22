package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The streaming services the viewer can use without paying anything more, from the `my_services` config.
 * Each entry is "Label|android.package|id,id" where the ids are the provider's own ids for that service
 * (Peacock has two). A title counts as watchable when one of these services carries it as included in the
 * subscription or free; rent, buy and cable-login offers never count.
 */
final class MyServices {
    static final class Service {
        final String label;
        final String pkg;
        final Set<String> ids;

        Service(String label, String pkg, Set<String> ids) {
            this.label = label;
            this.pkg = pkg;
            this.ids = ids;
        }
    }

    /** One of the viewer's services that carries a title at no extra cost. */
    static final class Hit {
        final Service service;
        final boolean free;   // free with ads rather than part of a subscription

        Hit(Service service, boolean free) {
            this.service = service;
            this.free = free;
        }
    }

    private final List<Service> services;

    private MyServices(List<Service> services) {
        this.services = Collections.unmodifiableList(services);
    }

    static MyServices parse(List<String> entries) {
        List<Service> out = new ArrayList<>();
        for (String e : entries) {
            String[] p = e.split("\\|");
            if (p.length < 3) continue;
            Set<String> ids = new HashSet<>();
            for (String id : p[2].split(",")) {
                if (!id.trim().isEmpty()) ids.add(id.trim());
            }
            if (!ids.isEmpty()) out.add(new Service(p[0].trim(), p[1].trim(), ids));
        }
        return new MyServices(out);
    }

    /** The viewer's services among these sources, in config order. Empty means a payment is needed. */
    List<Hit> match(List<TitleSearch.Source> sources) {
        List<Hit> hits = new ArrayList<>();
        for (Service s : services) {
            Boolean free = null;
            for (TitleSearch.Source src : sources) {
                if (!s.ids.contains(src.serviceId)) continue;
                if (src.access == TitleSearch.Access.SUB) free = free == null ? Boolean.FALSE : free;
                else if (src.access == TitleSearch.Access.FREE) free = free == null ? Boolean.TRUE : free;
            }
            if (free != null) hits.add(new Hit(s, free));
        }
        return hits;
    }
}
