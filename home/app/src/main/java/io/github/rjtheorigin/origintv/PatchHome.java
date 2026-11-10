package io.github.rjtheorigin.origintv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A PatchWall-style Home laid over the classic one, inside the same Home activity, so the Home key, boot, the phone
 * remote and the Inputs menu keep their tested paths. A tab bar (Home, Live TV, Movies, Shows, Sports, Kids, Music,
 * News, then Inputs and Settings), a hero with the focused programme's picture and details, and rows of cards:
 * Live Indian TV's channels from Android's TV database, the app row, and Watch Next.
 *
 * Like Live Indian TV's own Home it moves focus itself on every arrow key, so a fast press never lands elsewhere.
 * Launcher settings' "Home style" (or patch_home in patch_home.xml) switches it off; the classic Home is untouched.
 */
final class PatchHome {
    interface Host {
        void patchOpenInputs(View anchor);

        void patchOpenMenu(View anchor);

        /** "All apps": the classic Home, until Back or Home. */
        void patchShowClassic();

        /** The new Home is on screen again (the classic one's wallpaper can stop). */
        void patchShown();
    }

    static final String PREF_KEY = "home_style";
    static final String STYLE_PATCH = "patchwall";
    static final String STYLE_CLASSIC = "classic";

    static boolean enabled(Context context) {
        String saved = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY, null);
        if (saved != null) return STYLE_PATCH.equals(saved);
        return context.getResources().getBoolean(R.bool.patch_home);
    }

    private static final String TAG = PatchData.TAG;
    private static final int PAGE_HOME = 0, PAGE_LIVE = 1, PAGE_MOVIES = 2, PAGE_SHOWS = 3, PAGE_SPORTS = 4,
            PAGE_KIDS = 5, PAGE_MUSIC = 6, PAGE_NEWS = 7;
    private static final int[] TAB_LABELS = {R.string.patch_tab_home, R.string.patch_tab_live,
            R.string.patch_tab_movies, R.string.patch_tab_shows, R.string.patch_tab_sports, R.string.patch_tab_kids,
            R.string.patch_tab_music, R.string.patch_tab_news};
    private static final Set<String> MOVIES = genres(TvContract.Programs.Genres.MOVIES);
    private static final Set<String> SHOWS = genres(TvContract.Programs.Genres.ENTERTAINMENT,
            TvContract.Programs.Genres.DRAMA, TvContract.Programs.Genres.COMEDY, TvContract.Programs.Genres.LIFE_STYLE,
            TvContract.Programs.Genres.TRAVEL, TvContract.Programs.Genres.EDUCATION,
            TvContract.Programs.Genres.ANIMAL_WILDLIFE, TvContract.Programs.Genres.SHOPPING);
    private static final Set<String> SPORTS = genres(TvContract.Programs.Genres.SPORTS);
    private static final Set<String> KIDS = genres(TvContract.Programs.Genres.FAMILY_KIDS);
    private static final Set<String> MUSIC = genres(TvContract.Programs.Genres.MUSIC);
    private static final Set<String> NEWS = genres(TvContract.Programs.Genres.NEWS);
    /** The Home tab's "Live now": films first, then shows, sport, kids, music and news last. */
    private static final List<Set<String>> LIVE_ORDER = Arrays.asList(MOVIES, SHOWS, SPORTS, KIDS, MUSIC, NEWS);

    private static final long RELOAD_MS = 5 * 60_000L;
    private static final long TAB_REST_MS = 300;
    private static final long HERO_REST_MS = 140;
    private static final int ZONE_TABS = 0, ZONE_ROWS = 1;

    private static final int KIND_PROGRAM = 0, KIND_APP = 1, KIND_INPUTS = 2, KIND_ALL_APPS = 3, KIND_CONTINUE = 4,
            KIND_GUIDE = 5;

    /** One card. */
    private static final class Item {
        final int kind;
        PatchData.Program program;
        PatchData.App app;
        ContinueWatching.Item watch;

        Item(int kind) {
            this.kind = kind;
        }
    }

    private final Activity activity;
    private final Host host;
    private final FrameLayout parent;
    private final View view;
    private final PatchScrollView scroll;
    private final LinearLayout rowsBox;
    private final View heroBlock;
    private final ImageView heroImage;
    private final View heroFadeLeft, heroFadeBottom, topBar;
    private final TextView heroTag, heroTitle, heroMeta, heroDesc, clock;
    private final List<View> tabs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Map<View, Integer> hiddenSiblings = new IdentityHashMap<>();
    /** The classic views' own focus rule, kept while they are blocked: a visible view inside an invisible parent
     *  could still take focus otherwise (Home's resume restores the grid's focus). */
    private final Map<ViewGroup, Integer> blockedFocus = new IdentityHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final PatchImages posters, logos, heroImages;
    private final List<String> appOrder;
    private final java.text.DateFormat time;
    private final SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
    private final Map<String, String> appLabels = new HashMap<>();
    private final float density;

    private PatchData data;
    private boolean loading;
    private boolean resumed;
    private boolean okHeld;
    private int zone = ZONE_ROWS;
    private int tabCursor;
    private int page = PAGE_HOME;
    private int rowCursor;
    private Item heroItem;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (data != null && System.currentTimeMillis() - data.loadedAt > RELOAD_MS) reload();
            main.postDelayed(this, 60_000L);
        }
    };
    private final Runnable openFocusedTab = () -> {
        if (zone == ZONE_TABS && tabCursor < TAB_LABELS.length && tabCursor != page) showPage(tabCursor);
    };
    private final Runnable showHeroForFocus = this::showHeroForFocus;

    PatchHome(Activity activity, FrameLayout parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        view = LayoutInflater.from(activity).inflate(R.layout.patch_home, parent, false);
        scroll = view.findViewById(R.id.patch_scroll);
        rowsBox = view.findViewById(R.id.patch_rows);
        heroBlock = view.findViewById(R.id.patch_hero);
        heroImage = view.findViewById(R.id.patch_hero_image);
        heroFadeLeft = view.findViewById(R.id.patch_hero_fade_left);
        heroFadeBottom = view.findViewById(R.id.patch_hero_fade_bottom);
        topBar = view.findViewById(R.id.patch_top);
        heroTag = view.findViewById(R.id.patch_hero_tag);
        heroTitle = view.findViewById(R.id.patch_hero_title);
        heroMeta = view.findViewById(R.id.patch_hero_meta);
        heroDesc = view.findViewById(R.id.patch_hero_desc);
        clock = view.findViewById(R.id.patch_clock);
        heroTitle.setFontVariationSettings("'wght' 700");
        time = DateFormat.getTimeFormat(activity);
        android.content.ContentResolver resolver = activity.getContentResolver();
        // Card posters at about 260 px, the hero at about 850 px: opaque, so RGB_565 at half the memory.
        posters = new PatchImages(resolver, 280, 12, true, 2);
        logos = new PatchImages(resolver, 80, 2, false, 1);
        heroImages = new PatchImages(resolver, 900, 4, true, 1);
        appOrder = Arrays.asList(activity.getResources().getStringArray(R.array.patch_home_apps));

        LinearLayout tabBox = view.findViewById(R.id.patch_tabs);
        for (int label : TAB_LABELS) {
            TextView tab = (TextView) LayoutInflater.from(activity).inflate(R.layout.patch_tab, tabBox, false);
            tab.setText(label);
            tabBox.addView(tab);
            tabs.add(tab);
        }
        tabs.add(view.findViewById(R.id.patch_btn_inputs));
        tabs.add(view.findViewById(R.id.patch_btn_settings));
        markTab();
        showHero(null);
    }

    /** Puts the new Home over the classic one and hides the classic views (their visibility is kept to restore). */
    void attach() {
        if (view.getParent() == null) parent.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        show();
        reload();
    }

    boolean isShowing() {
        return view.getVisibility() == View.VISIBLE;
    }

    /** Back from "All apps", or Home: the new Home again, the classic views hidden so they neither draw nor take focus. */
    void show() {
        if (!isShowing() || hiddenSiblings.isEmpty()) {
            hiddenSiblings.clear();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child == view) continue;
                hiddenSiblings.put(child, child.getVisibility());
                child.setVisibility(View.INVISIBLE);
                if (child instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) child;
                    blockedFocus.put(group, group.getDescendantFocusability());
                    group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
            }
        }
        view.setVisibility(View.VISIBLE);
        view.bringToFront();
        host.patchShown();
        main.post(this::restoreFocus);
    }

    /** "All apps": the classic Home as it was. */
    private void showClassic() {
        view.setVisibility(View.GONE);
        for (Map.Entry<ViewGroup, Integer> entry : blockedFocus.entrySet()) {
            entry.getKey().setDescendantFocusability(entry.getValue());
        }
        blockedFocus.clear();
        for (Map.Entry<View, Integer> entry : hiddenSiblings.entrySet()) entry.getKey().setVisibility(entry.getValue());
        hiddenSiblings.clear();
        host.patchShowClassic();
    }

    void onResume() {
        resumed = true;
        main.removeCallbacks(tick);
        tick.run();
        // Watch Next and what is on now change while another app is in front: read them again on the way back.
        if (data == null || System.currentTimeMillis() - data.loadedAt > 60_000L) reload();
        else if (isShowing()) main.post(this::restoreFocus);
    }

    void onPause() {
        resumed = false;
        okHeld = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(openFocusedTab);
        main.removeCallbacks(showHeroForFocus);
    }

    /** The Home key: the Home tab, first row, scrolled to the top. */
    void onHome() {
        if (!isShowing()) show();
        if (page != PAGE_HOME) showPage(PAGE_HOME);
        for (Row row : rows) row.cursor = 0;
        focusRow(0);
    }

    /** Back: a category page goes to Home; on Home, back to the first row. Never leaves (this is Home). */
    void onBack() {
        if (page != PAGE_HOME) {
            showPage(PAGE_HOME);
            focusTab(PAGE_HOME);
        } else {
            focusRow(0);
        }
    }

    void trimMemory() {
        posters.clear();
        logos.clear();
        heroImages.clear();
    }

    void destroy() {
        main.removeCallbacksAndMessages(null);
        loader.shutdownNow();
        posters.shutdown();
        logos.shutdown();
        heroImages.shutdown();
    }

    private void reload() {
        if (loading) return;
        loading = true;
        Context app = activity.getApplicationContext();
        loader.execute(() -> {
            PatchData loaded = PatchData.load(app, appOrder);
            main.post(() -> {
                loading = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (data == null) {
                    // First load: focus starts on the first card, as on PatchWall.
                    zone = ZONE_ROWS;
                    rowCursor = 0;
                }
                data = loaded;
                showPage(page);
                if (resumed && isShowing()) restoreFocus();
            });
        });
    }

    // ---- pages -----------------------------------------------------------------------------------

    private void showPage(int target) {
        page = target;
        markTab();
        rowsBox.removeViews(1, rowsBox.getChildCount() - 1);   // the hero block stays
        List<Row> old = new ArrayList<>(rows);
        rows.clear();
        long now = System.currentTimeMillis();
        switch (target) {
            case PAGE_HOME:
                addRow(R.string.patch_row_live_now, programs(liveNow(now, 24)), old);
                addRow(R.string.patch_row_apps, appItems(), old);
                addRow(R.string.patch_row_continue, continueItems(), old);
                addRow(R.string.patch_row_movies_now, programs(onNow(MOVIES, now, 20)), old);
                addRow(R.string.patch_row_movies_soon, programs(soon(MOVIES, now, 3, 20)), old);
                addRow(R.string.patch_row_shows_now, programs(onNow(SHOWS, now, 20)), old);
                addRow(R.string.patch_tab_sports, programs(onNow(SPORTS, now, 20)), old);
                addRow(R.string.patch_tab_kids, programs(onNow(KIDS, now, 20)), old);
                addRow(R.string.patch_tab_music, programs(onNow(MUSIC, now, 20)), old);
                addRow(R.string.patch_tab_news, programs(onNow(NEWS, now, 20)), old);
                break;
            case PAGE_LIVE: {
                List<Item> all = new ArrayList<>();
                all.add(new Item(KIND_GUIDE));
                all.addAll(programs(onNow(null, now, 120)));
                addRow(R.string.patch_row_all_channels, all, old);
                addRow(R.string.patch_row_movies_now, programs(onNow(MOVIES, now, 30)), old);
                addRow(R.string.patch_row_shows_now, programs(onNow(SHOWS, now, 30)), old);
                addRow(R.string.patch_tab_sports, programs(onNow(SPORTS, now, 30)), old);
                addRow(R.string.patch_tab_kids, programs(onNow(KIDS, now, 30)), old);
                addRow(R.string.patch_tab_music, programs(onNow(MUSIC, now, 30)), old);
                addRow(R.string.patch_tab_news, programs(onNow(NEWS, now, 30)), old);
                break;
            }
            case PAGE_MOVIES:
                addRow(R.string.patch_row_movies_now, programs(onNow(MOVIES, now, 40)), old);
                addRow(R.string.patch_row_movies_soon, programs(soon(MOVIES, now, 6, 40)), old);
                break;
            case PAGE_SHOWS:
                addRow(R.string.patch_row_shows_now, programs(onNow(SHOWS, now, 40)), old);
                addRow(R.string.patch_row_up_next, programs(soon(SHOWS, now, 2, 40)), old);
                break;
            default: {
                Set<String> genre = target == PAGE_SPORTS ? SPORTS : target == PAGE_KIDS ? KIDS
                        : target == PAGE_MUSIC ? MUSIC : NEWS;
                addRow(R.string.patch_row_on_now, programs(onNow(genre, now, 40)), old);
                addRow(R.string.patch_row_up_next, programs(soon(genre, now, 3, 40)), old);
                break;
            }
        }
        rowCursor = Math.min(rowCursor, Math.max(0, rows.size() - 1));
        showHero(firstProgram());
        scroll.scrollTo(0, 0);
        dimHero(false);
    }

    private void addRow(int title, List<Item> items, List<Row> old) {
        if (items.isEmpty()) return;
        String name = activity.getString(title);
        Row row = new Row(name, items);
        // A reload keeps each row's place, so a refresh never throws focus back to the first card.
        for (Row before : old) {
            if (before.title.equals(name)) row.cursor = Math.min(before.cursor, items.size() - 1);
        }
        rows.add(row);
        rowsBox.addView(row.view);
    }

    private List<Item> programs(List<PatchData.Program> list) {
        List<Item> items = new ArrayList<>(list.size());
        for (PatchData.Program program : list) {
            Item item = new Item(KIND_PROGRAM);
            item.program = program;
            items.add(item);
        }
        return items;
    }

    private List<Item> appItems() {
        List<Item> items = new ArrayList<>();
        items.add(new Item(KIND_INPUTS));
        if (data != null) {
            for (PatchData.App app : data.apps) {
                Item item = new Item(KIND_APP);
                item.app = app;
                items.add(item);
            }
        }
        items.add(new Item(KIND_ALL_APPS));
        return items;
    }

    private List<Item> continueItems() {
        List<Item> items = new ArrayList<>();
        if (data == null) return items;
        for (ContinueWatching.Item watch : data.continueItems) {
            Item item = new Item(KIND_CONTINUE);
            item.watch = watch;
            items.add(item);
        }
        return items;
    }

    /** What is on now, with a poster, one programme per channel, in LIVE_ORDER's genre order. */
    private List<PatchData.Program> liveNow(long now, int limit) {
        List<PatchData.Program> out = new ArrayList<>();
        if (data == null) return out;
        Set<Long> seen = new HashSet<>();
        for (Set<String> genre : LIVE_ORDER) {
            for (PatchData.Program p : data.programs) {
                if (out.size() >= limit) return out;
                if (p.onNow(now) && !p.poster.isEmpty() && genre.contains(p.genre) && seen.add(p.channelRow)) out.add(p);
            }
        }
        return out;
    }

    /** On now in those genres (all, when null): with a poster first, then by channel number; one per channel. */
    private List<PatchData.Program> onNow(Set<String> genre, long now, int limit) {
        List<PatchData.Program> withPoster = new ArrayList<>(), without = new ArrayList<>();
        if (data == null) return withPoster;
        Set<Long> seen = new HashSet<>();
        Map<Long, Integer> order = channelOrder();
        List<PatchData.Program> list = new ArrayList<>(data.programs);
        Collections.sort(list, (a, b) -> Integer.compare(order.getOrDefault(a.channelRow, 0),
                order.getOrDefault(b.channelRow, 0)));
        for (PatchData.Program p : list) {
            if (!p.onNow(now) || (genre != null && !genre.contains(p.genre)) || !seen.add(p.channelRow)) continue;
            // The full channel list stays in channel order; genre rows show the ones with a picture first.
            (genre == null || !p.poster.isEmpty() ? withPoster : without).add(p);
        }
        withPoster.addAll(without);
        return withPoster.subList(0, Math.min(limit, withPoster.size()));
    }

    /** Starting within that many hours, soonest first, one per channel. */
    private List<PatchData.Program> soon(Set<String> genre, long now, int hours, int limit) {
        List<PatchData.Program> out = new ArrayList<>();
        if (data == null) return out;
        Set<Long> seen = new HashSet<>();
        long until = now + hours * 3_600_000L;
        for (PatchData.Program p : data.programs) {   // sorted by start
            if (out.size() >= limit) break;
            if (p.start > now && p.start < until && genre.contains(p.genre) && seen.add(p.channelRow)) out.add(p);
        }
        return out;
    }

    private Map<Long, Integer> channelOrder() {
        Map<Long, Integer> order = new HashMap<>();
        if (data == null) return order;
        for (int i = 0; i < data.channels.size(); i++) order.put(data.channels.get(i).row, i);
        return order;
    }

    private Item firstProgram() {
        for (Row row : rows) {
            for (Item item : row.items) if (item.kind == KIND_PROGRAM) return item;
        }
        return null;
    }

    // ---- keys and focus --------------------------------------------------------------------------

    /** Arrows and OK while the new Home is on screen. Other keys (Back, Home, volume, app keys) go their usual way. */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ok && !arrow) return false;
        if (ok) {
            // OK acts when released, and only when it also went down here: an OK pressed in another app and
            // released on Home does nothing.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) okHeld = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean press = okHeld && !event.isCanceled();
                okHeld = false;
                if (press) activateFocused();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean fresh = event.getRepeatCount() == 0;
        if (zone == ZONE_TABS) tabKey(code, fresh);
        else rowKey(code, fresh);
        return true;
    }

    private void tabKey(int code, boolean fresh) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && tabCursor > 0) {
            focusTab(tabCursor - 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && tabCursor < tabs.size() - 1) {
            focusTab(tabCursor + 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && fresh) {
            main.removeCallbacks(openFocusedTab);
            openFocusedTab.run();
            focusRow(0);
        }
    }

    private void rowKey(int code, boolean fresh) {
        if (rows.isEmpty()) {
            focusTab(page);
            return;
        }
        rowCursor = Math.min(rowCursor, rows.size() - 1);
        Row row = rows.get(rowCursor);
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (rowCursor > 0) focusRow(rowCursor - 1);
            else if (fresh) focusTab(page);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (rowCursor < rows.size() - 1) focusRow(rowCursor + 1);
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (row.cursor > 0) row.focus(row.cursor - 1, false);
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (row.cursor < row.items.size() - 1) row.focus(row.cursor + 1, true);
        }
        heroSoon();
    }

    private void focusTab(int index) {
        zone = ZONE_TABS;
        tabCursor = Math.max(0, Math.min(index, tabs.size() - 1));
        tabs.get(tabCursor).requestFocus();
        scroll.smoothScrollTo(0, 0);
        dimHero(false);
        // Resting on a tab opens its page, as on PatchWall; moving through the tabs does not rebuild each one.
        main.removeCallbacks(openFocusedTab);
        if (tabCursor < TAB_LABELS.length && tabCursor != page) main.postDelayed(openFocusedTab, TAB_REST_MS);
    }

    private void focusRow(int index) {
        if (rows.isEmpty()) {
            focusTab(page);
            return;
        }
        zone = ZONE_ROWS;
        rowCursor = Math.max(0, Math.min(index, rows.size() - 1));
        Row row = rows.get(rowCursor);
        row.cursor = Math.max(0, Math.min(row.cursor, row.items.size() - 1));
        row.focus(row.cursor, false);
        // The first rows stay under the hero while they fit on screen (the Home tab's Apps row just does, as on
        // PatchWall); further down, the row moves up under the tabs.
        int y = 0;
        if (row.view.getBottom() > scroll.getHeight()) y = Math.max(0, row.view.getTop() - dp(64));
        scroll.smoothScrollTo(0, y);
        dimHero(y > 0);
        heroSoon();
    }

    /** Back on Home: focus where it was, in the tabs or a row. */
    void restoreFocus() {
        if (!isShowing()) return;
        if (zone == ZONE_TABS || rows.isEmpty()) focusTab(rows.isEmpty() ? page : tabCursor);
        else focusRow(rowCursor);
    }

    private void markTab() {
        for (int i = 0; i < TAB_LABELS.length; i++) tabs.get(i).setActivated(i == page);
    }

    private Item focusedItem() {
        if (zone != ZONE_ROWS || rows.isEmpty()) return null;
        Row row = rows.get(Math.min(rowCursor, rows.size() - 1));
        return row.items.isEmpty() ? null : row.items.get(Math.min(row.cursor, row.items.size() - 1));
    }

    private void activateFocused() {
        if (zone == ZONE_TABS) {
            if (tabCursor < TAB_LABELS.length) {
                main.removeCallbacks(openFocusedTab);
                if (tabCursor != page) showPage(tabCursor);
                focusRow(0);
            } else if (tabs.get(tabCursor).getId() == R.id.patch_btn_inputs) {
                host.patchOpenInputs(tabs.get(tabCursor));
            } else {
                host.patchOpenMenu(tabs.get(tabCursor));
            }
            return;
        }
        Item item = focusedItem();
        if (item == null) return;
        Row row = rows.get(rowCursor);
        switch (item.kind) {
            case KIND_PROGRAM:
                watch(item.program);
                break;
            case KIND_APP:
                start(item.app.launch);
                break;
            case KIND_INPUTS:
                host.patchOpenInputs(row.focusedCard());
                break;
            case KIND_ALL_APPS:
                showClassic();
                break;
            case KIND_GUIDE:
                start(new Intent().setComponent(new ComponentName(PatchData.LIVE_APP, PatchData.LIVE_APP + ".LiveTv"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KIND_CONTINUE:
                start(ContinueWatching.intentFor(activity, item.watch));
                break;
            default:
                break;
        }
    }

    /** Live Indian TV's own link for the channel (written by its LiveSync), which plays it full screen. */
    private void watch(PatchData.Program program) {
        Intent intent = null;
        if (program.appLink != null) {
            try {
                intent = Intent.parseUri(program.appLink, Intent.URI_INTENT_SCHEME);
                String target = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
                if (!PatchData.LIVE_APP.equals(target)) intent = null;   // only Live Indian TV's own links are followed
            } catch (URISyntaxException e) {
                Log.w(TAG, "bad channel link: " + e);
            }
        }
        if (intent == null) {
            PackageManager pm = activity.getPackageManager();
            intent = pm.getLeanbackLaunchIntentForPackage(PatchData.LIVE_APP);
        }
        if (intent == null) return;
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void start(Intent intent) {
        if (intent == null) return;
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- hero ------------------------------------------------------------------------------------

    private void heroSoon() {
        main.removeCallbacks(showHeroForFocus);
        main.postDelayed(showHeroForFocus, HERO_REST_MS);
    }

    private void showHeroForFocus() {
        Item item = focusedItem();
        if (item != null && (item.kind == KIND_PROGRAM || item.kind == KIND_CONTINUE) && item != heroItem) {
            showHero(item);
        }
    }

    /** The hero shows a programme (or a Watch Next row); with nothing to show, a short note about Live Indian TV. */
    private void showHero(Item item) {
        heroItem = item;
        if (item == null) {
            heroTag.setVisibility(View.GONE);
            heroTitle.setText(R.string.patch_empty_title);
            boolean noChannels = data != null && data.channels.isEmpty();
            heroMeta.setText("");
            heroDesc.setText(noChannels ? activity.getString(R.string.patch_empty_body) : "");
            heroImages.load("", heroImage);
            return;
        }
        if (item.kind == KIND_CONTINUE) {
            ContinueWatching.Item watch = item.watch;
            heroTag.setVisibility(View.GONE);
            heroTitle.setText(watch.title);
            String label = appLabel(watch.pkg);
            heroMeta.setText(watch.subtitle == null || watch.subtitle.isEmpty() ? label : label + "  •  " + watch.subtitle);
            heroDesc.setText("");
            heroImages.load(watch.webPoster == null ? "" : watch.webPoster, heroImage);
            return;
        }
        PatchData.Program p = item.program;
        long now = System.currentTimeMillis();
        heroTag.setVisibility(View.VISIBLE);
        if (p.onNow(now)) {
            heroTag.setText(R.string.patch_tag_live);
            heroTag.setBackgroundResource(R.drawable.patch_tag_live);
        } else {
            heroTag.setText(activity.getString(R.string.patch_at, time.format(new Date(p.start))).toUpperCase(Locale.getDefault()));
            heroTag.setBackgroundResource(R.drawable.patch_tag_soon);
        }
        heroTitle.setText(p.title);
        heroMeta.setText(activity.getString(R.string.patch_meta, p.channelName, time.format(new Date(p.start)),
                time.format(new Date(p.end))));
        heroDesc.setText(p.description);
        heroImages.load(p.posterAt(960), heroImage);
    }

    /** Further down the page the hero picture steps back, so the rows read on a dark page. */
    private void dimHero(boolean dim) {
        float alpha = dim ? 0.15f : 1f;
        heroImage.animate().alpha(alpha).setDuration(180).start();
        heroBlock.animate().alpha(dim ? 0f : 1f).setDuration(180).start();
        // Scrolled, the rows pass under the tabs: the bar turns solid so they do not show through it.
        topBar.setBackgroundResource(dim ? R.drawable.patch_top_solid : R.drawable.patch_fade_top);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(time.format(now));
        clock.setContentDescription(time.format(now) + ", " + day.format(now));
    }

    private String appLabel(String pkg) {
        String label = appLabels.get(pkg);
        if (label != null) return label;
        PackageManager pm = activity.getPackageManager();
        try {
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;
        }
        appLabels.put(pkg, label);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static Set<String> genres(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    // ---- rows ------------------------------------------------------------------------------------

    private final class Row {
        final String title;
        final List<Item> items;
        final View view;
        final RecyclerView list;
        final PatchRowManager manager;
        final CardAdapter adapter;
        int cursor;

        Row(String title, List<Item> items) {
            this.title = title;
            this.items = items;
            view = LayoutInflater.from(activity).inflate(R.layout.patch_row, rowsBox, false);
            ((TextView) view.findViewById(R.id.patch_row_title)).setText(title);
            list = view.findViewById(R.id.patch_row_list);
            manager = new PatchRowManager(activity);
            list.setLayoutManager(manager);
            list.setItemAnimator(null);
            list.setFocusable(false);
            adapter = new CardAdapter(this);
            list.setAdapter(adapter);
        }

        /** A card on screen just takes focus; one off screen scrolls in, at the right edge when moving right. */
        void focus(int index, boolean movingRight) {
            cursor = index;
            View card = manager.findViewByPosition(index);
            int start = list.getPaddingLeft(), end = list.getWidth() - list.getPaddingRight();
            if (card != null && card.getLeft() >= start - dp(2) && card.getRight() <= end + dp(2)) {
                card.requestFocus();
                return;
            }
            int width = card != null ? card.getWidth() : firstCardWidth();
            int offset = movingRight && width > 0 ? Math.max(0, end - start - width - dp(4)) : 0;
            manager.focusAt(index, offset);
        }

        View focusedCard() {
            View card = manager.findViewByPosition(cursor);
            return card != null ? card : list;
        }

        private int firstCardWidth() {
            View any = list.getChildAt(0);
            return any == null ? 0 : any.getWidth() + dp(4);
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }

    private static final int TYPE_PROGRAM = 0, TYPE_APP = 1;

    private final class CardAdapter extends RecyclerView.Adapter<Holder> {
        private final Row row;

        CardAdapter(Row row) {
            this.row = row;
        }

        @Override
        public int getItemCount() {
            return row.items.size();
        }

        @Override
        public int getItemViewType(int position) {
            int kind = row.items.get(position).kind;
            return kind == KIND_PROGRAM || kind == KIND_CONTINUE ? TYPE_PROGRAM : TYPE_APP;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup group, int type) {
            View card = LayoutInflater.from(group.getContext()).inflate(
                    type == TYPE_PROGRAM ? R.layout.patch_card_program : R.layout.patch_card_app, group, false);
            card.setOnFocusChangeListener((v, focused) -> v.animate()
                    .scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(120).start());
            return new Holder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Item item = row.items.get(position);
            if (item.kind == KIND_PROGRAM) bindProgram(holder.itemView, item.program);
            else if (item.kind == KIND_CONTINUE) bindContinue(holder.itemView, item.watch);
            else bindApp(holder.itemView, item);
        }

        private void bindProgram(View card, PatchData.Program p) {
            long now = System.currentTimeMillis();
            posters.load(p.posterAt(320), card.findViewById(R.id.patch_poster));
            logos.load(p.logo(), card.findViewById(R.id.patch_logo), true);
            TextView tag = card.findViewById(R.id.patch_tag);
            ProgressBar progress = card.findViewById(R.id.patch_progress);
            tag.setVisibility(View.VISIBLE);
            if (p.onNow(now)) {
                tag.setText(R.string.patch_tag_live);
                tag.setBackgroundResource(R.drawable.patch_tag_live);
                progress.setVisibility(View.VISIBLE);
                progress.setProgress((int) (1000 * (now - p.start) / Math.max(1, p.end - p.start)));
            } else {
                tag.setText(time.format(new Date(p.start)));
                tag.setBackgroundResource(R.drawable.patch_tag_soon);
                progress.setVisibility(View.GONE);
            }
            ((TextView) card.findViewById(R.id.patch_title)).setText(p.title);
            String when = activity.getString(p.onNow(now) ? R.string.patch_until : R.string.patch_at,
                    time.format(new Date(p.onNow(now) ? p.end : p.start)));
            ((TextView) card.findViewById(R.id.patch_sub)).setText(p.channelName + "  •  " + when);
        }

        private void bindContinue(View card, ContinueWatching.Item watch) {
            ImageView poster = card.findViewById(R.id.patch_poster);
            if (watch.webPoster != null) {
                posters.load(watch.webPoster, poster);
            } else {
                posters.load("", poster);
                if (watch.poster != null) poster.setImageBitmap(watch.poster);
            }
            ImageView logo = card.findViewById(R.id.patch_logo);
            logos.load("", logo, true);
            card.findViewById(R.id.patch_tag).setVisibility(View.GONE);
            ProgressBar progress = card.findViewById(R.id.patch_progress);
            progress.setVisibility(watch.progress >= 0 ? View.VISIBLE : View.GONE);
            progress.setProgress((int) (1000 * Math.max(0f, watch.progress)));
            ((TextView) card.findViewById(R.id.patch_title)).setText(watch.title);
            ((TextView) card.findViewById(R.id.patch_sub)).setText(appLabel(watch.pkg));
        }

        private void bindApp(View card, Item item) {
            ImageView banner = card.findViewById(R.id.patch_banner);
            View box = card.findViewById(R.id.patch_app_box);
            ImageView icon = card.findViewById(R.id.patch_app_icon);
            TextView label = card.findViewById(R.id.patch_app_label);
            if (item.kind == KIND_APP && item.app.banner != null) {
                banner.setImageDrawable(item.app.banner);
                banner.setVisibility(View.VISIBLE);
                box.setVisibility(View.GONE);
                card.setContentDescription(item.app.label);
                return;
            }
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            box.setVisibility(View.VISIBLE);
            switch (item.kind) {
                case KIND_INPUTS:
                    icon.setImageResource(R.drawable.ic_input);
                    icon.setColorFilter(0xFF26262E);
                    label.setText(R.string.patch_inputs);
                    break;
                case KIND_ALL_APPS:
                    icon.setImageResource(R.drawable.ic_apps);
                    icon.setColorFilter(0xFF26262E);
                    label.setText(R.string.patch_all_apps);
                    break;
                case KIND_GUIDE:
                    icon.setImageResource(R.drawable.ic_apps);
                    icon.setColorFilter(0xFF26262E);
                    label.setText(R.string.patch_guide);
                    break;
                default:
                    icon.clearColorFilter();
                    icon.setImageDrawable(item.app.icon);
                    label.setText(item.app.label);
                    break;
            }
            card.setContentDescription(label.getText());
        }
    }
}
