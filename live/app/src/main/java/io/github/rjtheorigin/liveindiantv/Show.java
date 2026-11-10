package io.github.rjtheorigin.liveindiantv;

/** One programme in a channel's schedule. Times are epoch milliseconds. */
final class Show {
    private static final String POSTERS = "https://jiotvimages.cdn.jio.com/dare_images/shows/";
    final long start, end;
    final String title, description, poster, genre, srno, category;
    /** The channel keeps it to watch again for a week. */
    final boolean catchup;

    Show(long start, long end, String title, String description, String poster, String genre, String srno,
            String category, boolean catchup) {
        this.start = start;
        this.end = end;
        this.title = title;
        this.description = description;
        this.poster = poster;
        this.genre = genre;
        this.srno = srno;
        this.category = category;
        this.catchup = catchup;
    }

    boolean isFilm() {
        return "Feature Film".equals(category) || "TV Movie".equals(category);
    }

    /** The poster at a width the image server scales it to, so the TV never downloads a 4K picture. */
    String posterUrl(int width) {
        return poster.isEmpty() ? "" : POSTERS + poster + "?width=" + width;
    }
}
