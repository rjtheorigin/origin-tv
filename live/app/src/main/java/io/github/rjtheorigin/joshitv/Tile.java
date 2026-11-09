package io.github.rjtheorigin.joshitv;

/** One card on a Home shelf. */
final class Tile {
    /** On now on a channel; a channel logo; a catch-up programme; one to resume; a category; a way into Live TV. */
    static final int NOW = 0, LOGO = 1, CATCHUP = 2, RESUME = 3, CATEGORY = 4, MORE = 5;
    final int kind;
    final Channel channel;
    Show show;
    Resume resume;
    /** For a category or MORE tile: the category name, or "live" or "movies" for those pages. */
    final String target;
    final String label;
    final int icon, colorFrom, colorTo;

    private Tile(int kind, Channel channel, Show show, String target, String label, int icon, int from, int to) {
        this.kind = kind;
        this.channel = channel;
        this.show = show;
        this.target = target;
        this.label = label;
        this.icon = icon;
        this.colorFrom = from;
        this.colorTo = to;
    }

    static Tile channel(int kind, Channel channel, Show show) {
        return new Tile(kind, channel, show, null, null, 0, 0, 0);
    }

    static Tile resume(Channel channel, Resume resume) {
        Tile tile = new Tile(RESUME, channel, resume.show(), null, null, 0, 0, 0);
        tile.resume = resume;
        return tile;
    }

    static Tile category(String target, String label, int icon, int from, int to) {
        return new Tile(CATEGORY, null, null, target, label, icon, from, to);
    }

    static Tile more(String target, String label) {
        return new Tile(MORE, null, null, target, label, 0, 0, 0);
    }
}
