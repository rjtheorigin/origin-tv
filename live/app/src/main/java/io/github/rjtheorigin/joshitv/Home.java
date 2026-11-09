package io.github.rjtheorigin.joshitv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home and the category pages: a side menu, a banner of what is on now, and shelves of cards. Like the guide, it
 * moves focus itself on every arrow key, so a fast press can never land in another part of the screen.
 */
final class Home {
    interface Host {
        List<Channel> channels();

        Set<String> favourites();

        List<String> recent();

        List<Resume> resumes();

        String phoneLine();

        void watchLive(Channel channel);

        void watchCatchup(Channel channel, Show show, long resumeMs);

        void openGuide(String category, Channel focus);

        void openProfile();

        void toggleFavourite(Channel channel);

        void showChannelMenu(Channel channel);
    }

    static final String HOME = "home", LIVE = "live", MOVIES = "movies", FAVOURITES = "favourites",
            PROFILE = "profile";
    private static final String MOVIE_CATEGORY = "Movies";
    private static final long SLIDE_MS = 8000, DAY = 24L * 60 * 60 * 1000;
    private static final int ZONE_NAV = 0, ZONE_CONTENT = 1;
    private static final int POSTER = 0, LOGO = 1, CATEGORY = 2;
    private static final int ROW_CHANNELS = 16, CATCHUP_MAX = 24, SLIDES = 6;
    private static final Object[][] NAV = {
            {HOME, R.string.nav_home, R.drawable.ic_home},
            {LIVE, R.string.nav_live, R.drawable.ic_live},
            {MOVIES, R.string.nav_movies, R.drawable.ic_movie},
            {"Entertainment", R.string.nav_shows, R.drawable.ic_tv},
            {"Sports", R.string.nav_sports, R.drawable.ic_sports},
            {"Kids", R.string.nav_kids, R.drawable.ic_kids},
            {"Music", R.string.nav_music, R.drawable.ic_music},
            {"News", R.string.nav_news, R.drawable.ic_news},
            {FAVOURITES, R.string.nav_favourites, R.drawable.ic_favorite},
    };
    private static final Object[][] CATEGORIES = {
            {LIVE, R.string.nav_live, R.drawable.ic_live, 0xffe5395b, 0xff9b1735},
            {MOVIES, R.string.nav_movies, R.drawable.ic_movie, 0xff8b5cf6, 0xff5b21b6},
            {"Entertainment", R.string.nav_shows, R.drawable.ic_tv, 0xff3b82f6, 0xff1d4ed8},
            {"Sports", R.string.nav_sports, R.drawable.ic_sports, 0xff22c55e, 0xff15803d},
            {"Kids", R.string.nav_kids, R.drawable.ic_kids, 0xfff59e0b, 0xffc2410c},
            {"Music", R.string.nav_music, R.drawable.ic_music, 0xffec4899, 0xffbe185d},
            {"News", R.string.nav_news, R.drawable.ic_news, 0xff6366f1, 0xff3730a3},
            {"Devotional", R.string.nav_devotional, R.drawable.ic_star, 0xffa855f7, 0xff6b21a8},
    };

    private final Activity activity;
    private final Host host;
    private final Epg epg;
    private final Logos logos, cards, heroImages;
    private final View root;
    private final LinearLayout rows;
    private final QuietScrollView scroll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final DateFormat clock;
    private final List<View> navViews = new ArrayList<>();
    private final List<String> navTargets = new ArrayList<>();
    private final List<Shelf> shelves = new ArrayList<>();
    private final List<Tile> slides = new ArrayList<>();
    private final Set<String> catchupChannels = new HashSet<>();
    private Hero hero;
    private Shelf catchupShelf;
    private String page = HOME;
    private int generation, zone = ZONE_CONTENT, navCursor, rowCursor, slide;
    private boolean built;
    /** OK went down on a tile and has not come up yet; okLong once it was held long enough for the channel menu. */
    private boolean okHeld, okLong;

    /** Resting on a menu item opens its page, so moving through the menu needs no OK. */
    private final Runnable openFocusedNav = () -> {
        String target = navTargets.get(navCursor);
        boolean action = LIVE.equals(target) || PROFILE.equals(target);
        if (zone == ZONE_NAV && isShowing() && !action && !target.equals(page)) showPage(target);
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            boolean onHero = zone == ZONE_CONTENT && currentRow() == hero;
            if (hero != null && slides.size() > 1 && !onHero) showSlide((slide + 1) % slides.size());
            main.postDelayed(this, SLIDE_MS);
        }
    };

    Home(Activity activity, Host host, Epg epg, Logos logos, Logos cards, Logos heroImages) {
        this.activity = activity;
        this.host = host;
        this.epg = epg;
        this.logos = logos;
        this.cards = cards;
        this.heroImages = heroImages;
        this.clock = android.text.format.DateFormat.getTimeFormat(activity);
        root = activity.findViewById(R.id.home);
        rows = activity.findViewById(R.id.home_rows);
        scroll = activity.findViewById(R.id.home_scroll);
        LinearLayout navItems = activity.findViewById(R.id.nav_items);
        for (Object[] spec : NAV) addNav(navItems, (String) spec[0], (int) spec[1], (int) spec[2]);
        addNav(activity.findViewById(R.id.nav_bottom), PROFILE, R.string.nav_profile, R.drawable.ic_person);
    }

    // ---- Showing

    boolean isShowing() {
        return root.getVisibility() == View.VISIBLE;
    }

    /** Shows the page that was open last, rebuilt so Continue watching and the banner are current. */
    void show() {
        okHeld = false;
        root.setVisibility(View.VISIBLE);
        showPage(built ? page : HOME);
        // Again after layout: a focus request on a view not laid out yet is lost, and Android then focuses the
        // first menu item while the keys still act on the banner.
        root.post(this::restoreFocus);
        main.removeCallbacks(rotate);
        main.postDelayed(rotate, SLIDE_MS);
    }

    void hide() {
        root.setVisibility(View.GONE);
        main.removeCallbacks(rotate);
        main.removeCallbacks(openFocusedNav);
    }

    void shutdown() {
        main.removeCallbacksAndMessages(null);
        pool.shutdownNow();
    }

    /** Back on Home: a category page returns to Home; Home itself says no, so the activity can exit. */
    boolean onBack() {
        if (HOME.equals(page)) return false;
        zone = ZONE_CONTENT;
        showPage(HOME);
        return true;
    }

    void restoreFocus() {
        if (zone == ZONE_NAV) focusNav(navCursor);
        else focusRow(rowCursor);
    }

    private void open(String target) {
        if (LIVE.equals(target)) host.openGuide(null, null);
        else if (PROFILE.equals(target)) host.openProfile();
        else showPage(target);
    }

    private void showPage(String target) {
        built = true;
        page = target;
        generation++;
        rows.removeAllViews();
        shelves.clear();
        slides.clear();
        catchupChannels.clear();
        hero = null;
        catchupShelf = null;
        slide = 0;
        rowCursor = 0;

        View header = inflate(R.layout.home_header, rows);
        ((TextView) header.findViewById(R.id.page_title)).setText(pageTitle(target));
        ((TextView) header.findViewById(R.id.header_phone)).setText(
                activity.getString(R.string.header_phone, host.phoneLine()));
        rows.addView(header);
        if (!FAVOURITES.equals(target)) {
            hero = new Hero(inflate(R.layout.home_hero, rows));
            rows.addView(hero.view);
        }

        List<Channel> movieChannels = pick(MOVIE_CATEGORY, ROW_CHANNELS);
        if (HOME.equals(target)) {
            addContinueWatching();
            addLogos(activity.getString(R.string.shelf_for_you), forYou(), Tile.more(LIVE,
                    activity.getString(R.string.tile_all_channels)));
            addNow(activity.getString(R.string.shelf_movies_now), movieChannels);
            addCatchup(movieChannels);
            addCategories();
        } else if (MOVIES.equals(target)) {
            addNow(activity.getString(R.string.shelf_movies_now), movieChannels);
            addCatchup(movieChannels);
            addLogos(activity.getString(R.string.shelf_all, pageTitle(target)), pick(MOVIE_CATEGORY, 200),
                    Tile.more(MOVIE_CATEGORY, activity.getString(R.string.tile_open_live)));
        } else if (FAVOURITES.equals(target)) {
            List<Channel> favourites = new ArrayList<>();
            for (Channel channel : host.channels()) {
                if (host.favourites().contains(channel.id)) favourites.add(channel);
            }
            if (favourites.isEmpty()) {
                addLogos(activity.getString(R.string.favourites_empty), favourites,
                        Tile.more(LIVE, activity.getString(R.string.tile_open_live)));
            } else {
                addNow(activity.getString(R.string.shelf_favourites_now), favourites);
                addLogos(activity.getString(R.string.shelf_favourite_channels), favourites, null);
            }
        } else {
            addNow(activity.getString(R.string.shelf_on_now), pick(target, ROW_CHANNELS));
            addLogos(activity.getString(R.string.shelf_all, pageTitle(target)), pick(target, 200),
                    Tile.more(target, activity.getString(R.string.tile_open_live)));
        }
        for (Shelf shelf : shelves) rows.addView(shelf.view);
        for (int i = 0; i < navViews.size(); i++) navViews.get(i).setActivated(navTargets.get(i).equals(target));
        showSlide(0);
        scroll.scrollTo(0, 0);
        if (zone == ZONE_NAV) focusNav(navCursor);
        else focusRow(0);
        loadSchedules();
    }

    private String pageTitle(String target) {
        for (int i = 0; i < navTargets.size(); i++) {
            if (navTargets.get(i).equals(target)) {
                return ((TextView) navViews.get(i).findViewById(R.id.label)).getText().toString();
            }
        }
        return target;
    }

    // ---- Shelves

    private void addContinueWatching() {
        List<Tile> tiles = new ArrayList<>();
        for (Resume resume : host.resumes()) {
            Channel channel = find(resume.channelId);
            if (channel != null) tiles.add(Tile.resume(channel, resume));
        }
        for (String id : host.recent()) {
            Channel channel = find(id);
            if (channel != null && tiles.size() < 12) tiles.add(Tile.channel(Tile.NOW, channel, epg.now(id)));
        }
        if (!tiles.isEmpty()) shelves.add(new Shelf(activity.getString(R.string.shelf_continue), POSTER, tiles));
    }

    private void addNow(String title, List<Channel> channels) {
        List<Tile> tiles = new ArrayList<>();
        for (Channel channel : channels) tiles.add(Tile.channel(Tile.NOW, channel, epg.now(channel.id)));
        if (!tiles.isEmpty()) shelves.add(new Shelf(title, POSTER, tiles));
    }

    private void addLogos(String title, List<Channel> channels, Tile more) {
        List<Tile> tiles = new ArrayList<>();
        for (Channel channel : channels) tiles.add(Tile.channel(Tile.LOGO, channel, null));
        if (more != null) tiles.add(more);
        if (!tiles.isEmpty()) shelves.add(new Shelf(title, LOGO, tiles));
    }

    /** Films from the last day on the movie channels, filled in as their schedules arrive. */
    private void addCatchup(List<Channel> channels) {
        catchupShelf = new Shelf(activity.getString(R.string.shelf_catchup), POSTER, new ArrayList<>());
        shelves.add(catchupShelf);
        for (Channel channel : channels) catchupChannels.add(channel.id);
        catchupShelf.view.setVisibility(View.GONE);
    }

    private void addCategories() {
        List<Tile> tiles = new ArrayList<>();
        for (Object[] spec : CATEGORIES) {
            tiles.add(Tile.category((String) spec[0], activity.getString((int) spec[1]), (int) spec[2],
                    (int) spec[3], (int) spec[4]));
        }
        shelves.add(new Shelf(activity.getString(R.string.shelf_categories), CATEGORY, tiles));
    }

    /** Recent and favourite channels first, then the household's languages. */
    private List<Channel> forYou() {
        Set<Channel> chosen = new LinkedHashSet<>();
        for (String id : host.recent()) {
            Channel channel = find(id);
            if (channel != null) chosen.add(channel);
        }
        for (Channel channel : host.channels()) {
            if (host.favourites().contains(channel.id)) chosen.add(channel);
        }
        for (Channel channel : pick(null, 40)) {
            if (chosen.size() >= 14) break;
            chosen.add(channel);
        }
        return new ArrayList<>(chosen);
    }

    /** A category's channels (all, when null), in the household's order: recent, favourite, language, name. */
    private List<Channel> pick(String category, int limit) {
        List<String> recent = host.recent();
        Set<String> favourites = host.favourites();
        List<String> languages = Languages.preferred(activity, host.channels(), favourites, recent);
        List<Channel> out = new ArrayList<>();
        for (Channel channel : host.channels()) {
            if (category == null || category.equals(channel.category)) out.add(channel);
        }
        out.sort(Comparator.<Channel>comparingInt(c -> {
            int index = recent.indexOf(c.id);
            return index < 0 ? 100 : index;
        }).thenComparingInt(c -> favourites.contains(c.id) ? 0 : 1)
                .thenComparingInt(c -> {
                    int index = languages.indexOf(c.language);
                    return index < 0 ? languages.size() : index;
                })
                .thenComparing(c -> c.name.toLowerCase()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private Channel find(String id) {
        for (Channel channel : host.channels()) {
            if (channel.id.equals(id)) return channel;
        }
        return null;
    }

    // ---- Schedules

    /** Today's schedule for every channel on the page, and yesterday's for the catch-up shelf, three at a time. */
    private void loadSchedules() {
        int current = generation;
        Set<String> ids = new LinkedHashSet<>();
        for (Shelf shelf : shelves) {
            for (Tile tile : shelf.tiles) {
                if (tile.kind == Tile.NOW && tile.channel != null) ids.add(tile.channel.id);
            }
        }
        ids.addAll(catchupChannels);
        for (String id : ids) {
            pool.execute(() -> {
                if (current != generation) return;
                epg.load(id, 0);
                if (catchupChannels.contains(id)) epg.load(id, -1);
                main.post(() -> {
                    if (current == generation) scheduleLoaded(id);
                });
            });
        }
    }

    private void scheduleLoaded(String id) {
        for (Shelf shelf : shelves) {
            for (int i = 0; i < shelf.tiles.size(); i++) {
                Tile tile = shelf.tiles.get(i);
                if (tile.kind == Tile.NOW && tile.channel.id.equals(id)) {
                    tile.show = epg.now(id);
                    shelf.adapter.notifyItemChanged(i, "show");
                }
            }
        }
        if (catchupShelf != null && catchupChannels.contains(id)) addCatchupFilms(id);
        refreshSlides();
    }

    private void addCatchupFilms(String id) {
        Channel channel = find(id);
        if (channel == null) return;
        long now = System.currentTimeMillis();
        Set<String> have = new HashSet<>();
        for (Tile tile : catchupShelf.tiles) have.add(tile.show.srno);
        for (int offset = -1; offset <= 0; offset++) {
            List<Show> shows = epg.cached(id, offset);
            if (shows == null) continue;
            for (Show show : shows) {
                boolean recent = show.end < now && show.end > now - DAY;
                if (recent && show.catchup && show.isFilm() && !show.poster.isEmpty() && have.add(show.srno)) {
                    catchupShelf.tiles.add(Tile.channel(Tile.CATCHUP, channel, show));
                }
            }
        }
        catchupShelf.tiles.sort((a, b) -> Long.compare(b.show.end, a.show.end));
        while (catchupShelf.tiles.size() > CATCHUP_MAX) catchupShelf.tiles.remove(catchupShelf.tiles.size() - 1);
        catchupShelf.cursor = Math.min(catchupShelf.cursor, Math.max(0, catchupShelf.tiles.size() - 1));
        catchupShelf.adapter.notifyDataSetChanged();
        catchupShelf.view.setVisibility(catchupShelf.tiles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- Banner

    /** What is on now with a poster, films first, one per channel. */
    private void refreshSlides() {
        if (hero == null) return;
        List<Tile> films = new ArrayList<>(), others = new ArrayList<>();
        Set<String> channels = new HashSet<>();
        for (Shelf shelf : shelves) {
            for (Tile tile : shelf.tiles) {
                if (tile.kind != Tile.NOW || tile.show == null || tile.show.poster.isEmpty()) continue;
                if (!channels.add(tile.channel.id)) continue;
                (tile.show.isFilm() ? films : others).add(tile);
            }
        }
        films.addAll(others);
        List<Tile> next = films.size() > SLIDES ? films.subList(0, SLIDES) : films;
        if (sameChannels(next, slides)) return;
        boolean wasEmpty = slides.isEmpty();
        slides.clear();
        slides.addAll(next);
        if (wasEmpty || slide >= slides.size()) showSlide(0);
        else showDots();
    }

    private static boolean sameChannels(List<Tile> a, List<Tile> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).channel.id.equals(b.get(i).channel.id)) return false;
        }
        return true;
    }

    private void showSlide(int index) {
        if (hero == null) return;
        slide = index;
        if (slides.isEmpty()) {
            hero.tag.setText(R.string.app_name);
            hero.title.setText(pageTitle(page));
            hero.meta.setText(R.string.hero_loading);
            hero.image.setImageDrawable(null);
            hero.logo.setVisibility(View.INVISIBLE);
        } else {
            Tile tile = slides.get(index);
            Show show = tile.show;
            hero.tag.setText(R.string.hero_now_on);
            hero.title.setText(show.title);
            List<String> parts = new ArrayList<>();
            if (!show.genre.isEmpty()) parts.add(show.genre);
            if (!tile.channel.language.isEmpty()) parts.add(tile.channel.language);
            parts.add(tile.channel.name);
            parts.add(activity.getString(R.string.until, clock.format(new Date(show.end))));
            hero.meta.setText(String.join("  •  ", parts));
            heroImages.load(show.posterUrl(960), hero.image);
            hero.logo.setVisibility(View.VISIBLE);
            logos.load(tile.channel.logo, hero.logo);
        }
        showDots();
    }

    private void showDots() {
        hero.dots.removeAllViews();
        if (slides.size() < 2) return;
        for (int i = 0; i < slides.size(); i++) {
            View dot = new View(activity);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dot.setActivated(i == slide);
            // A plain View given WRAP_CONTENT takes all the room it is offered: without a size, the first dot
            // grew into a white box over the whole banner.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(i == slide ? 18 : 5), dp(5));
            params.setMarginStart(dp(5));
            hero.dots.addView(dot, params);
        }
    }

    // ---- Keys

    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!vertical && !sideways && !ok && code != KeyEvent.KEYCODE_MENU) return false;
        if (ok && zone == ZONE_CONTENT) return contentOk(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean fresh = event.getRepeatCount() == 0;
        if (zone == ZONE_NAV) navKey(code, ok, fresh);
        else contentKey(code, ok, fresh);
        return true;
    }

    private void navKey(int code, boolean ok, boolean fresh) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = navCursor + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (next < 0 || next >= navViews.size()) return;
            focusNav(next);
            // The page follows the menu at once; the previous page's schedule loads are dropped by generation.
            main.removeCallbacks(openFocusedNav);
            openFocusedNav.run();
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && fresh) {
            main.removeCallbacks(openFocusedNav);
            openFocusedNav.run();
            focusRow(rowCursor);
        } else if (ok && fresh) {
            String target = navTargets.get(navCursor);
            if (!target.equals(page) || LIVE.equals(target) || PROFILE.equals(target)) open(target);
            else focusRow(0);
        }
    }

    private void contentKey(int code, boolean ok, boolean fresh) {
        List<Row> list = visibleRows();
        if (list.isEmpty()) {
            focusNav(navCursor);
            return;
        }
        rowCursor = Math.min(rowCursor, list.size() - 1);
        Row row = list.get(rowCursor);
        if (code == KeyEvent.KEYCODE_DPAD_UP && rowCursor > 0) {
            focusRow(rowCursor - 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && rowCursor < list.size() - 1) {
            focusRow(rowCursor + 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (row.cursor > 0) {
                row.cursor--;
                row.focus(row.cursor);
            } else if (fresh) {
                focusNav(navCursor);
            }
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (row.cursor < row.count() - 1) {
                row.cursor++;
                row.focus(row.cursor);
            } else if (row == hero && fresh && slides.size() > 1) {
                showSlide((slide + 1) % slides.size());
            }
        } else if (ok && fresh) {
            activate(row, row.cursor);
        } else if (code == KeyEvent.KEYCODE_MENU && fresh && row instanceof Shelf) {
            Tile tile = ((Shelf) row).tiles.get(row.cursor);
            if (tile.channel != null) host.toggleFavourite(tile.channel);
        }
    }

    /** OK on a tile acts when released. Held on a channel, it opens that channel's menu (Add to Favourites). */
    private boolean contentOk(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                okHeld = true;
                okLong = false;
            } else if (okHeld && !okLong && MainActivity.isLongPress(event)) {
                Channel channel = focusedChannel();
                if (channel != null) {
                    okLong = true;
                    host.showChannelMenu(channel);
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // An OK pressed on another screen and released here does nothing.
            boolean press = okHeld && !okLong;
            okHeld = false;
            if (press) contentKey(KeyEvent.KEYCODE_DPAD_CENTER, true, true);
        }
        return true;
    }

    /** The channel behind the focused live tile or banner slide; programmes and categories have none. */
    private Channel focusedChannel() {
        Row row = currentRow();
        if (row == null) return null;
        if (row == hero) return slides.isEmpty() ? null : slides.get(slide).channel;
        Tile tile = ((Shelf) row).tiles.get(row.cursor);
        return tile.kind == Tile.NOW || tile.kind == Tile.LOGO ? tile.channel : null;
    }

    /** After the channel menu changed a favourite: the Favourites page is rebuilt to match. */
    void favouritesChanged() {
        if (!FAVOURITES.equals(page) || !isShowing()) return;
        showPage(FAVOURITES);
        root.post(this::restoreFocus);
    }

    private void activate(Row row, int index) {
        if (row == hero) {
            Channel channel = slides.isEmpty() ? null : slides.get(slide).channel;
            if (index == 0 && channel != null) host.watchLive(channel);
            else host.openGuide(null, channel);
            return;
        }
        Tile tile = ((Shelf) row).tiles.get(index);
        switch (tile.kind) {
            case Tile.NOW:
            case Tile.LOGO:
                host.watchLive(tile.channel);
                break;
            case Tile.CATCHUP:
                host.watchCatchup(tile.channel, tile.show, resumeFor(tile.show.srno));
                break;
            case Tile.RESUME:
                host.watchCatchup(tile.channel, tile.show, tile.resume.position);
                break;
            case Tile.CATEGORY:
                open(tile.target);
                break;
            case Tile.MORE:
                host.openGuide(LIVE.equals(tile.target) ? null : tile.target, null);
                break;
            default:
                break;
        }
    }

    private long resumeFor(String srno) {
        for (Resume resume : host.resumes()) {
            if (resume.srno.equals(srno)) return resume.position;
        }
        return 0;
    }

    private List<Row> visibleRows() {
        List<Row> list = new ArrayList<>();
        if (hero != null) list.add(hero);
        for (Shelf shelf : shelves) {
            if (shelf.view.getVisibility() == View.VISIBLE && !shelf.tiles.isEmpty()) list.add(shelf);
        }
        return list;
    }

    private Row currentRow() {
        List<Row> list = visibleRows();
        return list.isEmpty() ? null : list.get(Math.min(rowCursor, list.size() - 1));
    }

    private void focusNav(int index) {
        zone = ZONE_NAV;
        navCursor = index;
        navViews.get(index).requestFocus();
    }

    private void focusRow(int index) {
        List<Row> list = visibleRows();
        if (list.isEmpty()) {
            focusNav(navCursor);
            return;
        }
        zone = ZONE_CONTENT;
        rowCursor = Math.max(0, Math.min(index, list.size() - 1));
        Row row = list.get(rowCursor);
        row.cursor = Math.min(row.cursor, row.count() - 1);
        row.focus(row.cursor);
        int y = rowCursor == 0 ? 0 : row.view.getTop() - dp(6);
        scroll.smoothScrollTo(0, Math.max(0, y));
    }

    // ---- Views

    private void addNav(LinearLayout parent, String target, int label, int icon) {
        View item = inflate(R.layout.nav_item, parent);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        parent.addView(item);
        navViews.add(item);
        navTargets.add(target);
    }

    private View inflate(int layout, ViewGroup parent) {
        return LayoutInflater.from(activity).inflate(layout, parent, false);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private String aired(long time) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean today = then.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        return activity.getString(today ? R.string.aired_today : R.string.aired_yesterday, clock.format(new Date(time)));
    }

    private abstract static class Row {
        final View view;
        int cursor;

        Row(View view) {
            this.view = view;
        }

        abstract int count();

        abstract void focus(int index);
    }

    private final class Hero extends Row {
        final ImageView image, logo;
        final TextView tag, title, meta, watch, info;
        final LinearLayout dots;

        Hero(View view) {
            super(view);
            image = view.findViewById(R.id.hero_image);
            logo = view.findViewById(R.id.hero_logo);
            tag = view.findViewById(R.id.hero_tag);
            title = view.findViewById(R.id.hero_title);
            meta = view.findViewById(R.id.hero_meta);
            watch = view.findViewById(R.id.hero_watch);
            info = view.findViewById(R.id.hero_info);
            dots = view.findViewById(R.id.hero_dots);
            title.setFontVariationSettings("'wght' 800");
            watch.setText(R.string.hero_watch);
            info.setText(R.string.hero_info);
        }

        @Override
        int count() {
            return 2;
        }

        @Override
        void focus(int index) {
            (index == 0 ? watch : info).requestFocus();
        }
    }

    private final class Shelf extends Row {
        final List<Tile> tiles;
        final int kind;
        final RecyclerView list;
        final ShelfAdapter adapter;

        Shelf(String title, int kind, List<Tile> tiles) {
            super(inflate(R.layout.shelf, rows));
            this.kind = kind;
            this.tiles = tiles;
            ((TextView) view.findViewById(R.id.shelf_title)).setText(title);
            list = view.findViewById(R.id.shelf_list);
            list.setLayoutManager(new RowManager(activity, RecyclerView.HORIZONTAL));
            list.setItemAnimator(null);
            adapter = new ShelfAdapter(this);
            list.setAdapter(adapter);
        }

        @Override
        int count() {
            return tiles.size();
        }

        /** A card already fully on screen just takes focus; one off screen scrolls to the left edge first. */
        @Override
        void focus(int index) {
            RowManager manager = (RowManager) list.getLayoutManager();
            View card = manager.findViewByPosition(index);
            boolean visible = card != null && card.getLeft() >= 0 && card.getRight() <= list.getWidth();
            if (visible) card.requestFocus();
            else manager.focusRow(index, 0);
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }

    private final class ShelfAdapter extends RecyclerView.Adapter<Holder> {
        private final Shelf shelf;

        ShelfAdapter(Shelf shelf) {
            this.shelf = shelf;
        }

        @Override
        public int getItemCount() {
            return shelf.tiles.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            int layout = shelf.kind == POSTER ? R.layout.card_poster
                    : shelf.kind == LOGO ? R.layout.card_logo : R.layout.card_category;
            View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            view.setOnFocusChangeListener((card, focused) -> card.animate()
                    .scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(120).start());
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Tile tile = shelf.tiles.get(position);
            if (shelf.kind == POSTER) bindPoster(holder.itemView, tile);
            else if (shelf.kind == LOGO) bindLogo(holder.itemView, tile);
            else bindCategory(holder.itemView, tile);
        }

        private void bindPoster(View view, Tile tile) {
            ImageView poster = view.findViewById(R.id.poster);
            TextView tag = view.findViewById(R.id.tag), title = view.findViewById(R.id.title),
                    sub = view.findViewById(R.id.sub);
            ProgressBar progress = view.findViewById(R.id.progress);
            Show show = tile.show;
            Channel channel = tile.channel;
            cards.load(show == null ? "" : show.posterUrl(360), poster);
            logos.load(channel.logo, view.findViewById(R.id.logo));
            long now = System.currentTimeMillis();
            tag.setVisibility(tile.kind == Tile.NOW ? View.VISIBLE : View.GONE);
            tag.setText(R.string.tag_live);
            progress.setVisibility(View.VISIBLE);
            if (tile.kind == Tile.RESUME) {
                Resume resume = tile.resume;
                title.setText(resume.title);
                long left = Math.max(1, (resume.duration() - resume.position) / 60000);
                sub.setText(channel.name + "  •  " + activity.getString(R.string.minutes_left, left));
                progress.setProgress((int) (1000 * resume.position / resume.duration()));
            } else if (tile.kind == Tile.CATCHUP) {
                title.setText(show.title);
                sub.setText(channel.name + "  •  " + aired(show.start));
                long resumed = resumeFor(show.srno);
                progress.setVisibility(resumed > 0 ? View.VISIBLE : View.GONE);
                progress.setProgress((int) (1000 * resumed / Math.max(1, show.end - show.start)));
            } else if (show != null) {
                title.setText(show.title);
                sub.setText(channel.name + "  •  " + activity.getString(R.string.until,
                        clock.format(new Date(show.end))));
                progress.setProgress((int) (1000 * (now - show.start) / Math.max(1, show.end - show.start)));
            } else {
                title.setText(channel.name);
                sub.setText(channel.language + "  •  " + channel.category);
                progress.setVisibility(View.INVISIBLE);
            }
        }

        private void bindLogo(View view, Tile tile) {
            ImageView logo = view.findViewById(R.id.logo);
            TextView label = view.findViewById(R.id.label);
            boolean more = tile.kind == Tile.MORE;
            logo.setVisibility(more ? View.GONE : View.VISIBLE);
            label.setVisibility(more ? View.VISIBLE : View.GONE);
            if (more) label.setText(tile.label);
            else logos.load(tile.channel.logo, logo);
        }

        private void bindCategory(View view, Tile tile) {
            TextView label = view.findViewById(R.id.label);
            label.setText(tile.label);
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(0, tile.icon, 0, 0);
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{tile.colorFrom, tile.colorTo});
            fill.setCornerRadius(dp(14));
            label.setBackground(fill);
        }
    }
}
