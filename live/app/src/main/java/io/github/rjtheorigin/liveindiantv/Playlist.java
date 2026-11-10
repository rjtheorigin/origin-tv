package io.github.rjtheorigin.liveindiantv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the server's M3U into channels, sorted by name and numbered in that order. */
final class Playlist {
    private static final String LICENSE = "#KODIPROP:inputstream.adaptive.license_key=";

    private Playlist() { }

    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        String info = null, license = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                info = line;
                license = null;
            } else if (line.startsWith(LICENSE)) {
                license = line.substring(LICENSE.length());
            } else if (info != null && line.startsWith("http")) {
                String name = attr(info, "tvg-name");
                if (!name.isEmpty() && !isTest(name)) {
                    out.add(new Channel(attr(info, "tvg-id"), name, attr(info, "tvg-logo"),
                            attr(info, "tvg-language"), attr(info, "tvg-type"), line, license));
                }
                info = null;
                license = null;
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (int i = 0; i < out.size(); i++) out.get(i).number = i + 1;
        return out;
    }

    /** Jio lists placeholder channels named TEST1 HD, TEST14 HD and so on. */
    private static boolean isTest(String name) {
        return name.matches("(?i)test\\s*\\w?\\d*(\\s*hd)?");
    }

    private static String attr(String line, String key) {
        String start = key + "=\"";
        int from = line.indexOf(start);
        if (from < 0) return "";
        from += start.length();
        int to = line.indexOf('"', from);
        return to < 0 ? "" : line.substring(from, to);
    }
}
