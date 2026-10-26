package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The cameras in ha_cameras, by place. A plain list is one place without a name: Cameras opens its grid straight away.
 * "Home: camera.a, camera.b; KW Roseville: camera.c, …" makes one place each, and Cameras first asks which one
 * (CameraSitesActivity).
 */
final class CameraSites {
    private CameraSites() {}

    static final class Site {
        final String name;
        final List<String> ids;

        Site(String name, List<String> ids) {
            this.name = name;
            this.ids = Collections.unmodifiableList(ids);
        }
    }

    static final List<Site> ALL = parse(Config.HA_CAMERAS);

    private static List<Site> parse(String config) {
        List<Site> sites = new ArrayList<>();
        for (String part : config.split(";")) {
            int colon = part.indexOf(':');
            String name = colon < 0 ? "" : part.substring(0, colon).trim();
            List<String> ids = new ArrayList<>();
            for (String id : part.substring(colon + 1).split(",")) if (!id.trim().isEmpty()) ids.add(id.trim());
            if (!ids.isEmpty()) sites.add(new Site(name, ids));
        }
        return Collections.unmodifiableList(sites);
    }

    /** More than one place: Cameras asks which. */
    static boolean several() {
        return ALL.size() > 1;
    }

    /** Every camera, place after place (the home screen's camera row). */
    static List<String> ids() {
        List<String> all = new ArrayList<>();
        for (Site s : ALL) all.addAll(s.ids);
        return all;
    }
}
