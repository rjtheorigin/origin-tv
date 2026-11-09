package io.github.rjtheorigin.joshitv;

/** One row of the server's playlist. */
final class Channel {
    final String id, name, logo, language, category, url;
    /** The Widevine licence address, or null for a stream with no encryption. */
    final String license;
    int number;

    Channel(String id, String name, String logo, String language, String category, String url, String license) {
        this.id = id;
        this.name = name;
        this.logo = logo;
        this.language = language;
        this.category = category;
        this.url = url;
        this.license = license;
    }
}
