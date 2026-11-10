package io.github.rjtheorigin.liveindiantv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole app: a channel guide with a live preview, full-screen viewing, and Jio sign-in on a number pad.
 * Start with an "id" extra to play that channel full screen; with none it opens the guide.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity implements Home.Host {
    private static final String TAG = "Channels";
    private static final String DEFAULT_ID = "202"; // DD National
    private static final long SERVER_IDLE_MS = 120_000, BANNER_MS = 4_000, MESSAGE_MS = 3_000, SETTLE_MS = 200;
    private static final List<String> CATEGORIES = Arrays.asList("Entertainment", "Movies", "News", "Sports", "Kids",
            "Music", "Infotainment", "Lifestyle", "Devotional", "Business", "Educational");
    private static final Object VISIBLE_ROWS = new Object();
    private static final int ZONE_RAIL = 0, ZONE_LIST = 1, ZONE_SCHEDULE = 2, ZONE_PREVIEW = 3;
    private static final int RETURN_HOME = 0, RETURN_GUIDE = 1;
    private static final long SEEK_BACK_MS = 10_000, SEEK_FORWARD_MS = 30_000, RESUME_SAVE_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Epg epg = new Epg();
    private final List<Channel> all = new ArrayList<>();
    private final List<Channel> shown = new ArrayList<>();
    private final List<RailItem> railItems = new ArrayList<>();
    private final List<ShowRow> showRows = new ArrayList<>();

    private HandlerThread epgThread;
    private Handler epgHandler;
    private Logos logos;
    private SharedPreferences prefs;
    private Set<String> favourites;
    private DateFormat clock;

    private FrameLayout root;
    private LinearLayout guide, banner, login, profile;
    private TextView profileTitle, profileNumber, profileNote, profilePrimary, profileSecondary;
    private boolean confirmingSignOut;
    /** Jio ended this TV's sign-in, usually because the account was signed in on another device. */
    private boolean sessionEnded;
    /** The OK that opened the profile card is still held: its release must not press the card's button. */
    private boolean swallowOkRelease;
    /** The card shows this channel's menu (long press OK) instead of the profile. */
    private Channel menuChannel;
    /** OK went down on a channel row and has not come up yet; okLong once it was held long enough for the menu. */
    private boolean okHeld, okLong;
    private TextView previewHint;
    private GradientDrawable previewRing;
    private PlayerView playerView;
    private RecyclerView rail, list, schedule;
    private TextView nowTitle, nowMeta, nowDesc, bannerName, bannerShow, message, loginTitle, loginHint, loginDigits;
    private ProgressBar nowProgress;
    private ImageView bannerLogo;
    private ProgressBar bannerProgress;
    private View homeView;
    private Home home;
    private Logos posterImages, heroImages;
    /** Where Back from full screen goes. */
    private int returnTo = RETURN_HOME;
    /** A catch-up programme is playing, with its own seek keys and banner. */
    private boolean vod;
    private Channel vodChannel;
    private Show vodShow;
    private final RailAdapter railAdapter = new RailAdapter();
    private final ChannelAdapter channelAdapter = new ChannelAdapter();
    private final ShowAdapter showAdapter = new ShowAdapter();

    private ExoPlayer player;
    private Channel playing, focused;
    private RailItem filter, pendingFilter;
    private int retries;
    /** Which panel has the remote. Only Left and Right change it, so Up and Down can never leave a panel. */
    private int zone = ZONE_LIST;
    private int railCursor, listCursor, showCursor;
    private boolean ready, startFailed, exitArmed, busy, enteringOtp;
    private String phone = "";
    private CharSequence starting;
    private final StringBuilder typed = new StringBuilder();

    private final Runnable stopServer = Server::stopIfIdle;
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);
    private final Runnable disarmExit = () -> exitArmed = false;
    private final Runnable loadDetail = this::loadDetailNow;
    private final Runnable saveResumeTick = new Runnable() {
        @Override
        public void run() {
            saveResume();
            if (vod) main.postDelayed(this, RESUME_SAVE_MS);
        }
    };
    private final Runnable bannerTick = new Runnable() {
        @Override
        public void run() {
            if (vod && banner.getVisibility() == View.VISIBLE) {
                updateBanner();
                main.postDelayed(this, 1000);
            }
        }
    };
    private final Runnable applyPendingFilter = () -> {
        if (pendingFilter != null) applyFilter(pendingFilter);
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onRenderedFirstFrame() {
            retries = 0;
            Log.i(TAG, "first frame " + (playing == null ? "" : playing.id));
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            playerView.setKeepScreenOn(isPlaying);
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.w(TAG, "playback error " + error.getErrorCodeName());
            if (vod) {
                say(getString(R.string.catchup_failed));
                exitVod();
                return;
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                player.seekToDefaultPosition();
                player.prepare();
            } else if (playing != null && retries < 1) {
                retries++;
                Channel channel = playing;
                io.execute(() -> {
                    try {
                        Server.ensure(MainActivity.this);
                    } catch (IOException ignored) {
                        // The retry below reports it.
                    }
                    main.postDelayed(() -> {
                        if (channel == playing) startPlayback();
                    }, 1500);
                });
            } else if (playing != null) {
                say(getString(R.string.cannot_play, playing.name));
                checkSession();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);
        favourites = new HashSet<>(prefs.getStringSet("favourites", Collections.emptySet()));
        if (LiveSync.isSetUp(this)) LiveSync.schedulePeriodic(this);
        clock = android.text.format.DateFormat.getTimeFormat(this);
        logos = new Logos(getCacheDir());
        // Posters and banners stay in memory only: nothing piles up in the TV's storage between restarts.
        posterImages = Logos.inMemory(360, 8);
        heroImages = Logos.inMemory(960, 4);
        io.execute(() -> {
            Logos.forget(getCacheDir(), "posters");
            Logos.forget(getCacheDir(), "hero");
            // Channel logos are small and on every screen, so they stay on disk, capped at 8 MB.
            long logoBytes = Logos.trim(getCacheDir(), "logos", 8L * 1024 * 1024);
            Log.i(TAG, "cache: logos " + logoBytes / 1024 + " KB, posters and banners in memory only");
        });
        epgThread = new HandlerThread("schedule");
        epgThread.start();
        epgHandler = new Handler(epgThread.getLooper());
        bindViews();
        home = new Home(this, this, epg, logos, posterImages, heroImages);
        buildKeys();
        bootstrap();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        guide = findViewById(R.id.guide);
        banner = findViewById(R.id.banner);
        login = findViewById(R.id.login);
        playerView = findViewById(R.id.player);
        rail = findViewById(R.id.rail);
        list = findViewById(R.id.list);
        schedule = findViewById(R.id.schedule);
        nowTitle = findViewById(R.id.now_title);
        nowMeta = findViewById(R.id.now_meta);
        nowDesc = findViewById(R.id.now_desc);
        nowProgress = findViewById(R.id.now_progress);
        bannerName = findViewById(R.id.banner_name);
        bannerShow = findViewById(R.id.banner_show);
        bannerLogo = findViewById(R.id.banner_logo);
        bannerProgress = findViewById(R.id.banner_progress);
        homeView = findViewById(R.id.home);
        message = findViewById(R.id.message);
        loginTitle = findViewById(R.id.login_title);
        loginHint = findViewById(R.id.login_hint);
        loginDigits = findViewById(R.id.login_digits);
        profile = findViewById(R.id.profile);
        profileTitle = findViewById(R.id.profile_title);
        profileNumber = findViewById(R.id.profile_number);
        profileNote = findViewById(R.id.profile_note);
        profilePrimary = findViewById(R.id.profile_primary);
        profileSecondary = findViewById(R.id.profile_secondary);
        profilePrimary.setOnClickListener(view -> {
            if (menuChannel != null) {
                Channel channel = menuChannel;
                closeProfile();
                toggleFavourite(channel);
            } else if (confirmingSignOut) {
                signOut();
            } else {
                confirmSignOut();
            }
        });
        profileSecondary.setOnClickListener(view -> {
            if (confirmingSignOut) showProfile();
            else closeProfile();
        });
        // The preview takes the remote with Right from the list: a ring and a hint say that OK opens full screen.
        previewRing = new GradientDrawable();
        previewRing.setColor(0);
        previewRing.setStroke(dp(3), 0xffc4b0ff);
        // Focused, the preview shows only this ring: Android's own focus tint would dim the picture.
        playerView.setDefaultFocusHighlightEnabled(false);
        previewHint = new TextView(this);
        previewHint.setText(R.string.preview_hint);
        previewHint.setTextColor(0xffffffff);
        previewHint.setTextSize(13);
        previewHint.setTypeface(getResources().getFont(R.font.outfit_medium));
        previewHint.setBackgroundResource(R.drawable.bg_card);
        previewHint.setPadding(dp(12), dp(5), dp(12), dp(5));
        previewHint.setVisibility(View.GONE);
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay != null) {
            FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            hint.bottomMargin = dp(10);
            overlay.addView(previewHint, hint);
        }
        for (RecyclerView view : new RecyclerView[]{rail, list, schedule}) {
            view.setLayoutManager(new RowManager(this));
            view.setItemAnimator(null);
        }
        rail.setAdapter(railAdapter);
        list.setAdapter(channelAdapter);
        schedule.setAdapter(showAdapter);
    }

    // ---- Start-up

    private void bootstrap() {
        ready = false;
        startFailed = false;
        say(startingText(), 0);
        io.execute(() -> {
            try {
                Server.ensure(this);
                List<Channel> channels = Playlist.parse(Api.get("/playlist.m3u?q=high", 30000));
                if (channels.isEmpty()) throw new IOException("empty playlist");
                main.post(() -> onChannels(channels));
            } catch (IOException e) {
                Log.w(TAG, "start failed: " + e.getMessage());
                main.post(() -> {
                    startFailed = true;
                    say(getString(R.string.start_failed), 0);
                });
            }
        });
    }

    private void onChannels(List<Channel> channels) {
        message.setVisibility(View.GONE);
        all.clear();
        all.addAll(channels);
        buildRail();
        if (Server.needsLogin(this)) {
            showLogin();
            return;
        }
        ready = true;
        // Signed in with channels: start the copy into Android's TV listings, which the Origin TV home screen and
        // Live Channels read, the same as finishing Live Channels' setup screen. It then refreshes twice a day.
        if (!LiveSync.isSetUp(this)) LiveSync.markSetUp(this);
        applyFilter(railItems.get(1)); // 0 is the profile row, 1 is All channels.
        ensurePlayer();
        String wanted = getIntent().getStringExtra("id");
        Channel requested = find(wanted);
        Channel last = find(prefs.getString("last", DEFAULT_ID));
        checkSession();
        if (requested != null) {
            play(requested);
            fullScreen();
        } else if (isLiveTv(getIntent())) {
            openGuide(null, null);
        } else {
            // Home shows posters, not video: the last channel waits until Watch now or Live TV.
            playing = last != null ? last : all.get(0);
            showHome();
        }
    }

    /** The MI button opens Live Indian TV through this alias, straight to the channel guide. */
    private static boolean isLiveTv(Intent intent) {
        return intent.getComponent() != null && intent.getComponent().getClassName().endsWith(".LiveTv");
    }

    private Channel find(String id) {
        if (id == null) return null;
        for (Channel channel : all) {
            if (channel.id.equals(id)) return channel;
        }
        return null;
    }

    private void buildRail() {
        railItems.clear();
        railItems.add(new RailItem(RailItem.PROFILE, getString(R.string.profile_label), ""));
        railItems.add(new RailItem(RailItem.ALL, getString(R.string.all_channels), ""));
        railItems.add(new RailItem(RailItem.FAVOURITE, getString(R.string.favourites), ""));
        Map<String, Integer> categories = new LinkedHashMap<>(), languages = new LinkedHashMap<>();
        for (Channel channel : all) {
            categories.merge(channel.category, 1, Integer::sum);
            languages.merge(channel.language, 1, Integer::sum);
        }
        List<String> categoryNames = new ArrayList<>(CATEGORIES);
        for (String name : categories.keySet()) {
            if (!categoryNames.contains(name)) categoryNames.add(name);
        }
        for (String name : categoryNames) {
            if (categories.containsKey(name)) railItems.add(new RailItem(RailItem.CATEGORY, name, name));
        }
        railItems.add(new RailItem(RailItem.HEADING, getString(R.string.languages), ""));
        List<String> firstLanguages = Languages.preferred(this, channels(), favourites, recent());
        List<String> languageNames = new ArrayList<>(firstLanguages);
        List<String> others = new ArrayList<>(languages.keySet());
        others.removeAll(firstLanguages);
        others.sort((a, b) -> languages.get(b) - languages.get(a));
        languageNames.addAll(others);
        for (String name : languageNames) {
            if (languages.containsKey(name)) railItems.add(new RailItem(RailItem.LANGUAGE, name, name));
        }
        for (RailItem item : railItems) item.count = count(item);
        railAdapter.notifyDataSetChanged();
    }

    private int count(RailItem item) {
        int total = 0;
        for (Channel channel : all) {
            if (item.matches(channel, favourites)) total++;
        }
        return total;
    }

    // ---- Lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        Server.setViewing(true);
        main.removeCallbacks(stopServer);
        if (ready && vod) {
            ensurePlayer();
            startVod(vodChannel, vodShow, resumeFor(vodShow.srno));
        } else if (ready && homeView.getVisibility() == View.VISIBLE) {
            home.show();
        } else if (ready) {
            ensurePlayer();
            if (playing != null) {
                Channel channel = playing;
                io.execute(() -> {
                    try {
                        Server.ensure(this);
                    } catch (IOException e) {
                        Log.w(TAG, "server: " + e.getMessage());
                    }
                    main.post(() -> {
                        if (channel == playing && player != null) startPlayback();
                    });
                });
            }
        }
    }

    @Override
    protected void onStop() {
        if (vod) saveResume();
        home.hide();
        releasePlayer();
        Server.setViewing(false);
        main.postDelayed(stopServer, SERVER_IDLE_MS);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        epgThread.quit();
        logos.shutdown();
        posterImages.shutdown();
        heroImages.shutdown();
        home.shutdown();
        io.shutdown();
        if (isFinishing()) Server.stopIfIdle();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!ready) return;
        if (vod) stopVod();
        Channel requested = find(intent.getStringExtra("id"));
        if (requested != null) {
            play(requested);
            fullScreen();
        } else if (isLiveTv(intent)) {
            openGuide(null, null);
        } else {
            showHome();
        }
    }

    // ---- Playback

    private void ensurePlayer() {
        if (player != null) return;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000, 24_000, 1_500, 3_000)
                .setTargetBufferBytes(20 * 1024 * 1024)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(load).build();
        player.addListener(playerListener);
        player.setPlayWhenReady(true);
        playerView.setPlayer(player);
    }

    private void releasePlayer() {
        if (player == null) return;
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void play(Channel channel) {
        Channel old = playing;
        playing = channel;
        retries = 0;
        List<String> recent = new ArrayList<>(recent());
        // A channel new to the recent list joins Live Channels; one pushed off the end leaves it.
        boolean newlyRecent = !recent.contains(channel.id);
        recent.remove(channel.id);
        recent.add(0, channel.id);
        while (recent.size() > 12) recent.remove(recent.size() - 1);
        prefs.edit().putString("last", channel.id).putString("recent", TextUtils.join(",", recent)).apply();
        if (newlyRecent) LiveSync.syncSoon(this);
        refreshRow(old);
        refreshRow(channel);
        startPlayback();
        updateBanner();
    }

    private void startPlayback() {
        if (player == null || playing == null || vod) return;
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(playing.id).setUri(Uri.parse(playing.url));
        if (playing.license != null) {
            item.setMimeType(MimeTypes.APPLICATION_MPD).setDrmConfiguration(
                    new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(playing.license).build());
        } else {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        player.setMediaItem(item.build());
        player.prepare();
    }

    private void step(int direction) {
        List<Channel> source = shown.contains(playing) ? shown : all;
        if (source.isEmpty()) return;
        int index = Math.max(0, source.indexOf(playing));
        int size = source.size();
        play(source.get((index + direction + size) % size));
        showBanner();
    }

    // ---- Screens

    private void showGuide() {
        hideNow(banner, hideBanner);
        homeView.setVisibility(View.GONE);
        home.hide();
        playerView.setVisibility(View.VISIBLE);
        guide.setVisibility(View.VISIBLE);
        int edge = getResources().getDimensionPixelSize(R.dimen.edge);
        FrameLayout.LayoutParams preview = new FrameLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.preview_w),
                getResources().getDimensionPixelSize(R.dimen.preview_h), Gravity.TOP | Gravity.END);
        preview.setMargins(0, edge, edge, 0);
        playerView.setLayoutParams(preview);
        zone = ZONE_LIST;
        okHeld = false;
        setPreviewFocus(false);
        listCursor = Math.max(shown.indexOf(playing), 0);
        if (!shown.isEmpty()) {
            moveFocus(list, listCursor);
            onChannelFocused(shown.get(listCursor));
        }
    }

    private void fullScreen() {
        returnTo = guide.getVisibility() == View.VISIBLE ? RETURN_GUIDE : RETURN_HOME;
        setPreviewFocus(false);
        homeView.setVisibility(View.GONE);
        home.hide();
        playerView.setVisibility(View.VISIBLE);
        guide.setVisibility(View.GONE);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.requestFocus();
        showBanner();
    }

    private void showBanner() {
        updateBanner();
        banner.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner);
        // A paused programme keeps its banner, so it is clear why the picture stopped.
        boolean paused = vod && player != null && !player.getPlayWhenReady();
        if (!paused) main.postDelayed(hideBanner, BANNER_MS);
        main.removeCallbacks(bannerTick);
        if (vod) main.postDelayed(bannerTick, 1000);
    }

    private void updateBanner() {
        bannerProgress.setVisibility(vod ? View.VISIBLE : View.GONE);
        if (vod) {
            long position = player == null ? 0 : player.getCurrentPosition();
            long length = Math.max(1, vodShow.end - vodShow.start);
            bannerName.setText(vodShow.title);
            String state = player != null && !player.getPlayWhenReady() ? "  •  " + getString(R.string.paused) : "";
            bannerShow.setText(vodChannel.name + "  •  " + duration(position) + " / " + duration(length) + state);
            bannerProgress.setProgress((int) (1000 * position / length));
            logos.load(vodChannel.logo, bannerLogo);
            return;
        }
        if (playing == null) return;
        Channel channel = playing;
        bannerName.setText(channel.number + "  " + channel.name);
        logos.load(channel.logo, bannerLogo);
        Show show = epg.now(channel.id);
        bannerShow.setText(show != null ? show.title : channel.language + " · " + channel.category);
        if (show == null && !epg.hasToday(channel.id)) {
            epgHandler.post(() -> {
                epg.load(channel.id, 0);
                main.post(() -> {
                    Show loaded = epg.now(channel.id);
                    if (channel == playing && loaded != null) bannerShow.setText(loaded.title);
                });
            });
        }
    }

    private void hideNow(View view, Runnable pending) {
        main.removeCallbacks(pending);
        view.setVisibility(View.GONE);
    }

    private CharSequence startingText() {
        if (starting == null) {
            SpannableString text = new SpannableString(getString(R.string.starting));
            text.setSpan(new RelativeSizeSpan(1.5f), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            starting = text;
        }
        return starting;
    }

    private void say(CharSequence text) {
        say(text, MESSAGE_MS);
    }

    private void say(CharSequence text, long forMs) {
        // Outfit is a variable font, so bold is a weight setting: a bold style alone leaves it thin.
        message.setFontVariationSettings(text == starting ? "'wght' 800" : "'wght' 500");
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideMessage);
        if (forMs > 0) main.postDelayed(hideMessage, forMs);
    }

    // ---- Guide

    private void applyFilter(RailItem item) {
        main.removeCallbacks(applyPendingFilter);
        pendingFilter = null;
        RailItem old = filter;
        filter = item;
        shown.clear();
        for (Channel channel : all) {
            if (item.matches(channel, favourites)) shown.add(channel);
        }
        listCursor = 0;
        channelAdapter.notifyDataSetChanged();
        list.scrollToPosition(0);
        if (old != null) railAdapter.notifyItemChanged(railItems.indexOf(old), "state");
        railAdapter.notifyItemChanged(railItems.indexOf(item), "state");
        if (!shown.isEmpty()) {
            onChannelFocused(shown.get(0));
        }
    }

    /** Keeps the row near the middle of its panel, so holding a key scrolls the list and not the highlight. */
    private void moveFocus(RecyclerView view, int position) {
        View row = view.getChildAt(0);
        int rowHeight = row == null ? dp(60) : row.getHeight();
        // A panel that was hidden has no height yet, and the row would land above the top edge.
        int height = view.getHeight() > 0 ? view.getHeight()
                : root.getHeight() - 2 * getResources().getDimensionPixelSize(R.dimen.edge);
        RowManager manager = (RowManager) view.getLayoutManager();
        manager.focusRow(position, Math.max(0, (height - rowHeight) / 2));
        // A row already on screen takes focus now, so focus never falls to another panel while the layout runs.
        View onScreen = manager.findViewByPosition(position);
        if (onScreen != null) onScreen.requestFocus();
    }

    private void onChannelFocused(Channel channel) {
        focused = channel;
        main.removeCallbacks(loadDetail);
        main.postDelayed(loadDetail, SETTLE_MS);
    }

    private void loadDetailNow() {
        Channel channel = focused;
        if (channel == null) return;
        fillDetail(channel, null, null);
        epgHandler.post(() -> {
            List<Show> today = epg.load(channel.id, 0);
            List<Show> tomorrow = epg.load(channel.id, 1);
            main.post(() -> {
                if (channel == focused) {
                    fillDetail(channel, today, tomorrow);
                    refreshRow(channel);
                    loadVisibleRows();
                }
            });
        });
    }

    private void fillDetail(Channel channel, List<Show> today, List<Show> tomorrow) {
        showRows.clear();
        long time = System.currentTimeMillis();
        Show current = null;
        if (today != null) {
            for (Show show : today) {
                if (show.end <= time) continue;
                boolean now = show.start <= time;
                if (now) current = show;
                showRows.add(new ShowRow(clock.format(new Date(show.start)), show.title, now));
            }
        }
        if (tomorrow != null && !tomorrow.isEmpty()) {
            showRows.add(new ShowRow("", getString(R.string.tomorrow), false));
            for (Show show : tomorrow) {
                showRows.add(new ShowRow(clock.format(new Date(show.start)), show.title, false));
            }
        }
        showAdapter.notifyDataSetChanged();
        schedule.scrollToPosition(0);
        if (current != null) {
            nowTitle.setText(current.title);
            nowMeta.setText(getString(R.string.now_time, clock.format(new Date(current.start)),
                    clock.format(new Date(current.end))));
            nowDesc.setText(current.description);
            long span = Math.max(1, current.end - current.start);
            nowProgress.setProgress((int) (1000 * (time - current.start) / span));
            nowProgress.setVisibility(View.VISIBLE);
        } else {
            nowTitle.setText(channel.name);
            nowMeta.setText(channel.language + " · " + channel.category);
            nowDesc.setText(today == null ? "" : getString(R.string.no_schedule));
            nowProgress.setVisibility(View.INVISIBLE);
        }
    }

    /** Fills in "what's on" for the rows on screen, a few at a time, newest request first. */
    private void loadVisibleRows() {
        LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
        int first = manager.findFirstVisibleItemPosition(), last = manager.findLastVisibleItemPosition();
        epgHandler.removeCallbacksAndMessages(VISIBLE_ROWS);
        if (first < 0) return;
        for (int position = first; position <= last && position < shown.size(); position++) {
            Channel channel = shown.get(position);
            if (epg.hasToday(channel.id)) continue;
            epgHandler.postAtTime(() -> {
                epg.load(channel.id, 0);
                main.post(() -> refreshRow(channel));
            }, VISIBLE_ROWS, SystemClock.uptimeMillis());
        }
    }

    private void refreshRow(Channel channel) {
        int position = channel == null ? -1 : shown.indexOf(channel);
        if (position >= 0) channelAdapter.notifyItemChanged(position, "state");
    }

    @Override
    public void toggleFavourite(Channel channel) {
        boolean added = favourites.add(channel.id);
        if (!added) favourites.remove(channel.id);
        prefs.edit().putStringSet("favourites", new HashSet<>(favourites)).apply();
        LiveSync.syncSoon(this);
        say(getString(added ? R.string.favourite_added : R.string.favourite_removed));
        if (filter != null && filter.kind == RailItem.FAVOURITE) {
            applyFilter(filter);
            if (!shown.isEmpty()) moveFocus(list, 0);
            else enterRail();
        }
        refreshRow(channel);
        if (homeView.getVisibility() == View.VISIBLE) home.favouritesChanged();
        for (int i = 0; i < railItems.size(); i++) {
            RailItem item = railItems.get(i);
            if (item.kind == RailItem.FAVOURITE) {
                item.count = favourites.size();
                railAdapter.notifyItemChanged(i, "state");
            }
        }
    }

    // ---- Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (startFailed) {
            if (isOk(code) && up) bootstrap();
            if (code == KeyEvent.KEYCODE_BACK && up) finish();
            return isOk(code) || code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event);
        }
        if (profile.getVisibility() == View.VISIBLE) {
            if (swallowOkRelease && isOk(code)) {
                if (up) swallowOkRelease = false;
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    if (confirmingSignOut) showProfile();
                    else closeProfile();
                }
                return true;
            }
            // Arrows move between the card's two buttons only. Left to Android, they reached the rows behind
            // the card first, so Sign out could never be selected.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) profilePrimary.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) profileSecondary.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(event);
        }
        if (login.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) loginBack();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (!ready) {
            if (code == KeyEvent.KEYCODE_BACK && up) finish();
            return code == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event);
        }
        if (homeView.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up && !home.onBack()) armExit();
                return true;
            }
            return home.onKey(event) || super.dispatchKeyEvent(event);
        }
        boolean guideOpen = guide.getVisibility() == View.VISIBLE;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (up) onBackKey(guideOpen);
            return true;
        }
        if (guideOpen) return onGuideKey(event) || super.dispatchKeyEvent(event);
        if (vod) return onVodKey(event) || super.dispatchKeyEvent(event);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (down) showBanner();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) showGuide();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** Every direction key in the guide stops here, so Android's focus search never picks the next row. */
    private boolean onGuideKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean sideways = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!vertical && !sideways && !isOk(code) && code != KeyEvent.KEYCODE_MENU) return false;
        if (isOk(code) && zone == ZONE_LIST) return onListOk(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean fresh = event.getRepeatCount() == 0;
        int direction = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
        if (vertical) {
            if (zone == ZONE_RAIL) moveRail(direction, fresh);
            else if (zone == ZONE_LIST) moveList(direction, fresh);
            else if (zone == ZONE_PREVIEW) {
                if (direction > 0 && fresh && !showRows.isEmpty()) enterSchedule();
            } else if (direction < 0 && showCursor == 0) {
                if (fresh) enterPreview();
            } else {
                moveSchedule(direction);
            }
            return true;
        }
        if (!fresh) return true;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (zone == ZONE_LIST && !shown.isEmpty()) toggleFavourite(shown.get(listCursor));
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (zone == ZONE_LIST) enterRail();
            else if (zone == ZONE_SCHEDULE || zone == ZONE_PREVIEW) enterList();
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (zone == ZONE_RAIL) enterList();
            else if (zone == ZONE_LIST) enterPreview();
        } else if (zone == ZONE_RAIL) {
            if (railItems.get(railCursor).kind == RailItem.PROFILE) {
                swallowOkRelease = true;
                showProfile();
            } else {
                enterList();
            }
        } else if (zone == ZONE_PREVIEW) {
            fullScreen();
        }
        return true;
    }

    /**
     * OK on a channel acts when it is released, and only plays the channel in the preview: full screen is Right,
     * then OK. Held for a long press, it opens the channel's menu instead, since the remote has no Menu key.
     */
    private boolean onListOk(KeyEvent event) {
        if (shown.isEmpty()) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                okHeld = true;
                okLong = false;
            } else if (okHeld && !okLong && isLongPress(event)) {
                okLong = true;
                showChannelMenu(shown.get(listCursor));
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // An OK pressed somewhere else (full screen, Home) and released here does nothing.
            boolean press = okHeld && !okLong;
            okHeld = false;
            if (press) {
                Channel channel = shown.get(listCursor);
                if (channel != playing) play(channel);
                else say(getString(R.string.preview_already));
            }
        }
        return true;
    }

    /**
     * Android marks the first repeat of a held key as a long press, about half a second in; a remote that sends
     * its own repeats gets there by the time held instead.
     */
    static boolean isLongPress(KeyEvent event) {
        return event.isLongPress()
                || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
    }

    private void enterPreview() {
        if (playing == null) {
            if (shown.isEmpty()) return;
            play(shown.get(listCursor));
        }
        zone = ZONE_PREVIEW;
        setPreviewFocus(true);
    }

    /**
     * The preview holds Android focus while it has the remote: without a focused view there, Android hands focus to
     * the first row of the guide, which then looks selected.
     */
    private void setPreviewFocus(boolean on) {
        playerView.setForeground(on ? previewRing : null);
        previewHint.setVisibility(on ? View.VISIBLE : View.GONE);
        playerView.setFocusable(on);
        if (on) playerView.requestFocus();
    }

    private void enterRail() {
        zone = ZONE_RAIL;
        setPreviewFocus(false);
        railCursor = railItems.indexOf(filter);
        moveFocus(rail, railCursor);
    }

    private void enterList() {
        if (pendingFilter != null) applyFilter(pendingFilter);
        if (shown.isEmpty()) return;
        zone = ZONE_LIST;
        setPreviewFocus(false);
        moveFocus(list, listCursor);
        onChannelFocused(shown.get(listCursor));
    }

    private void enterSchedule() {
        zone = ZONE_SCHEDULE;
        setPreviewFocus(false);
        showCursor = 0;
        moveFocus(schedule, 0);
    }

    private void moveRail(int direction, boolean fresh) {
        int size = railItems.size(), next = railCursor;
        do {
            next += direction;
            if (next < 0 || next >= size) {
                if (!fresh) return;
                next = (next + size) % size;
            }
        } while (railItems.get(next).kind == RailItem.HEADING);
        railCursor = next;
        moveFocus(rail, next);
        if (railItems.get(next).kind == RailItem.PROFILE) {
            // The profile row opens a card on OK; resting on it changes no filter.
            pendingFilter = null;
            main.removeCallbacks(applyPendingFilter);
            return;
        }
        pendingFilter = railItems.get(next);
        main.removeCallbacks(applyPendingFilter);
        if (pendingFilter == filter) pendingFilter = null;
        else main.postDelayed(applyPendingFilter, SETTLE_MS);
    }

    /** A held key stops at the end of the list. A fresh press there carries on round to the other end. */
    private void moveList(int direction, boolean fresh) {
        int size = shown.size();
        if (size == 0) return;
        int next = listCursor + direction;
        if (next < 0 || next >= size) {
            if (!fresh) return;
            next = (next + size) % size;
        }
        listCursor = next;
        moveFocus(list, next);
        onChannelFocused(shown.get(next));
    }

    private void moveSchedule(int direction) {
        int next = showCursor + direction;
        if (next < 0 || next >= showRows.size()) return;
        showCursor = next;
        moveFocus(schedule, next);
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Back steps out one screen: catch-up or full screen to where it came from, the guide to Home. */
    private void onBackKey(boolean guideOpen) {
        if (vod) {
            exitVod();
        } else if (guideOpen && (zone == ZONE_PREVIEW || zone == ZONE_SCHEDULE)) {
            enterList();
        } else if (guideOpen || returnTo == RETURN_HOME) {
            showHome();
        } else {
            showGuide();
        }
    }

    private void armExit() {
        if (exitArmed) {
            finish();
        } else {
            exitArmed = true;
            say(getString(R.string.exit_again), 2500);
            main.postDelayed(disarmExit, 2500);
        }
    }

    // ---- Sign-in

    private void buildKeys() {
        GridLayout grid = findViewById(R.id.login_keys);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (String label : labels) {
            TextView key = new TextView(this);
            key.setText(label);
            key.setGravity(Gravity.CENTER);
            key.setTextSize(20);
            key.setTextColor(getColorStateList(R.color.row_text));
            key.setBackgroundResource(R.drawable.bg_key);
            key.setFocusable(true);
            key.setClickable(true);
            key.setOnClickListener(view -> onPad(label));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(84);
            params.height = dp(44);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(key, params);
        }
    }

    private void showLogin() {
        hideNow(banner, hideBanner);
        guide.setVisibility(View.GONE);
        homeView.setVisibility(View.GONE);
        home.hide();
        profile.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        login.setVisibility(View.VISIBLE);
        enteringOtp = false;
        phone = "";
        typed.setLength(0);
        loginTitle.setText(R.string.login_title);
        loginHint.setText(sessionEnded ? R.string.login_session_ended : R.string.login_phone_hint);
        loginDigits.setText("");
        ((GridLayout) findViewById(R.id.login_keys)).getChildAt(0).requestFocus();
    }

    private void loginBack() {
        if (enteringOtp) showLogin();
        else finish();
    }

    private void onPad(String key) {
        if (busy) return;
        if ("⌫".equals(key)) {
            if (typed.length() > 0) typed.setLength(typed.length() - 1);
        } else if ("OK".equals(key)) {
            submit();
            return;
        } else if (typed.length() < (enteringOtp ? 6 : 10)) {
            typed.append(key);
        }
        loginDigits.setText(typed);
    }

    private void submit() {
        String digits = typed.toString();
        if (digits.length() != (enteringOtp ? 6 : 10)) return;
        busy = true;
        boolean otp = enteringOtp;
        String number = otp ? phone : digits;
        String body = otp
                ? "{\"number\":\"+91" + number + "\",\"otp\":\"" + digits + "\"}"
                : "{\"number\":\"+91" + number + "\"}";
        say(getString(R.string.please_wait), 0);
        io.execute(() -> {
            boolean worked;
            try {
                Api.postJson(otp ? "/login/verifyOTP" : "/login/sendOTP", body);
                worked = !otp || !Server.needsLogin(this);
            } catch (IOException e) {
                worked = false;
            }
            boolean success = worked;
            main.post(() -> {
                busy = false;
                message.setVisibility(View.GONE);
                if (!success) {
                    say(getString(otp ? R.string.login_otp_failed : R.string.login_send_failed));
                } else if (otp) {
                    // Kept for the profile card: the server stores Jio's account IDs, not the number.
                    prefs.edit().putString("phone", number).apply();
                    sessionEnded = false;
                    login.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    phone = "";
                    typed.setLength(0);
                    bootstrap();
                } else {
                    phone = number;
                    enteringOtp = true;
                    typed.setLength(0);
                    loginDigits.setText("");
                    loginHint.setText(R.string.login_otp_hint);
                }
            });
        });
    }

    // ---- Jio sign-in still valid?

    /**
     * Asks Jio for DD National once. If Jio says the sign-in is no longer valid (another device took this TV's
     * place), the sign-in pad comes up with the reason instead of every channel failing quietly.
     */
    private void checkSession() {
        io.execute(() -> {
            String problem = null;
            try {
                Api.get("/live/mpd/" + DEFAULT_ID + "?q=high", 20000);
            } catch (IOException e) {
                problem = e.getMessage();
            }
            if (problem == null || !looksSignedOut(problem)) return;
            Log.i(TAG, "Jio sign-in ended");
            main.post(() -> {
                if (!ready || vod || login.getVisibility() == View.VISIBLE) return;
                sessionEnded = true;
                ready = false;
                if (player != null) player.stop();
                showLogin();
            });
        });
    }

    /** A plan refusal (code 3012) is not a sign-out; an expired or rejected token is. */
    static boolean looksSignedOut(String problem) {
        String text = problem.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("3012") || text.contains("eligible")) return false;
        return text.contains("status code: 401") || text.contains("status code: 419")
                || text.contains("unauthori") || text.contains("expired")
                || (text.contains("token") && (text.contains("invalid") || text.contains("refresh")));
    }

    // ---- Home

    private void showHome() {
        hideNow(banner, hideBanner);
        setPreviewFocus(false);
        guide.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        // Home shows posters only: stopping the player frees the video decoder and its memory.
        if (player != null) player.stop();
        homeView.setVisibility(View.VISIBLE);
        home.show();
    }

    @Override
    public List<Channel> channels() {
        return all;
    }

    @Override
    public Set<String> favourites() {
        return favourites;
    }

    @Override
    public List<String> recent() {
        String saved = prefs.getString("recent", "");
        List<String> ids = new ArrayList<>();
        for (String id : saved.split(",")) {
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    @Override
    public List<Resume> resumes() {
        return Resume.read(prefs.getString("resumes", null));
    }

    @Override
    public void watchLive(Channel channel) {
        ensurePlayer();
        play(channel);
        fullScreen();
    }

    @Override
    public void openGuide(String category, Channel focus) {
        RailItem item = railItems.get(1);
        for (RailItem candidate : railItems) {
            if (candidate.kind == RailItem.CATEGORY && candidate.value.equals(category)) item = candidate;
        }
        applyFilter(item);
        ensurePlayer();
        if (focus != null) {
            play(focus);
        } else if (playing != null) {
            startPlayback();
        } else {
            play(all.get(0));
        }
        showGuide();
    }

    @Override
    public void openProfile() {
        swallowOkRelease = true;
        showProfile();
    }

    // ---- Catch-up

    @Override
    public void watchCatchup(Channel channel, Show show, long resumeMs) {
        returnTo = guide.getVisibility() == View.VISIBLE ? RETURN_GUIDE : RETURN_HOME;
        startVod(channel, show, resumeMs);
    }

    private void startVod(Channel channel, Show show, long resumeMs) {
        vod = true;
        vodChannel = channel;
        vodShow = show;
        hideNow(banner, hideBanner);
        setPreviewFocus(false);
        homeView.setVisibility(View.GONE);
        home.hide();
        guide.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ensurePlayer();
        String address = Api.BASE + "/catchup/stream/" + channel.id + ".m3u8?start=" + show.start
                + "&end=" + show.end + "&srno=" + show.srno;
        player.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(address))
                .setMimeType(MimeTypes.APPLICATION_M3U8).build(), Math.max(0, resumeMs));
        player.setPlayWhenReady(true);
        player.prepare();
        root.requestFocus();
        showBanner();
        main.removeCallbacks(saveResumeTick);
        main.postDelayed(saveResumeTick, RESUME_SAVE_MS);
    }

    /** Leaves catch-up where it came from, keeping the place for Continue watching. */
    private void exitVod() {
        stopVod();
        if (returnTo == RETURN_GUIDE) {
            startPlayback();
            showGuide();
        } else {
            showHome();
        }
    }

    private void stopVod() {
        saveResume();
        vod = false;
        main.removeCallbacks(saveResumeTick);
        main.removeCallbacks(bannerTick);
        if (player != null) player.stop();
        bannerProgress.setVisibility(View.GONE);
    }

    private void saveResume() {
        if (!vod || player == null || vodShow == null) return;
        long position = player.getCurrentPosition();
        if (position < 60_000) return;
        Resume latest = new Resume(vodShow.srno, vodChannel.id, vodShow.title, vodShow.poster, vodShow.start,
                vodShow.end, position);
        prefs.edit().putString("resumes", Resume.save(resumes(), latest)).apply();
    }

    private long resumeFor(String srno) {
        for (Resume resume : resumes()) {
            if (resume.srno.equals(srno)) return resume.position;
        }
        return 0;
    }

    /** Left rewinds 10 s, Right skips 30 s, OK pauses; held arrows keep seeking. */
    private boolean onVodKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seekBy(-SEEK_BACK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seekBy(SEEK_FORWARD_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && event.getRepeatCount() == 0 && player != null) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                    showBanner();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (down) showBanner();
                return true;
            default:
                return false;
        }
    }

    private void seekBy(long delta) {
        if (player == null) return;
        long length = Math.max(1, vodShow.end - vodShow.start);
        player.seekTo(Math.max(0, Math.min(length - 5_000, player.getCurrentPosition() + delta)));
        showBanner();
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---- Profile

    /** The number as the rail shows it: the rail is narrow, so without +91. */
    @Override
    public String phoneLine() {
        String number = prefs.getString("phone", "");
        if (number.length() != 10) return getString(R.string.profile_number_short);
        return number.substring(0, 5) + " " + number.substring(5);
    }

    private void showProfile() {
        confirmingSignOut = false;
        menuChannel = null;
        boolean known = prefs.getString("phone", "").length() == 10;
        profileTitle.setText(R.string.profile_label);
        profileNumber.setText("+91 " + phoneLine());
        profileNumber.setVisibility(known ? View.VISIBLE : View.GONE);
        profileNote.setText(known ? R.string.profile_signed_in : R.string.profile_number_unknown);
        profilePrimary.setText(R.string.sign_out);
        profileSecondary.setText(R.string.close);
        profile.setVisibility(View.VISIBLE);
        profileSecondary.requestFocus();
    }

    /** Focus starts on Cancel, so a stray OK cannot sign the family out. */
    private void confirmSignOut() {
        confirmingSignOut = true;
        profileTitle.setText(R.string.sign_out_title);
        profileNote.setText(R.string.sign_out_note);
        profilePrimary.setText(R.string.sign_out);
        profileSecondary.setText(R.string.cancel);
        profileSecondary.requestFocus();
    }

    private void closeProfile() {
        profile.setVisibility(View.GONE);
        confirmingSignOut = false;
        boolean fromMenu = menuChannel != null;
        menuChannel = null;
        if (homeView.getVisibility() == View.VISIBLE) home.restoreFocus();
        else if (guide.getVisibility() == View.VISIBLE) moveFocus(fromMenu ? list : rail, fromMenu ? listCursor : railCursor);
    }

    /**
     * A long press of OK on a channel, in the guide or on Home: the card offers Add to (or Remove from) Favourites.
     * The OK that opened it is still held, so its repeats and release are swallowed.
     */
    @Override
    public void showChannelMenu(Channel channel) {
        confirmingSignOut = false;
        menuChannel = channel;
        swallowOkRelease = true;
        okHeld = false;
        boolean favourite = favourites.contains(channel.id);
        profileTitle.setText(channel.name);
        profileNumber.setVisibility(View.GONE);
        profileNote.setText(favourite ? R.string.channel_menu_note_favourite : R.string.channel_menu_note);
        profilePrimary.setText(favourite ? R.string.favourite_remove : R.string.favourite_add);
        profileSecondary.setText(R.string.cancel);
        profile.setVisibility(View.VISIBLE);
        profilePrimary.requestFocus();
    }

    private void signOut() {
        if (busy) return;
        busy = true;
        say(getString(R.string.signing_out), 0);
        io.execute(() -> {
            boolean done;
            try {
                Server.ensure(this);
                Api.logout();
                done = Server.needsLogin(this);
            } catch (IOException e) {
                done = false;
            }
            boolean signedOut = done;
            main.post(() -> {
                busy = false;
                message.setVisibility(View.GONE);
                if (!signedOut) {
                    say(getString(R.string.sign_out_failed));
                    return;
                }
                prefs.edit().remove("phone").apply();
                if (player != null) player.stop();
                Channel old = playing;
                playing = null;
                refreshRow(old);
                ready = false;
                confirmingSignOut = false;
                showLogin();
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- Lists

    private static final class RailItem {
        static final int ALL = 0, FAVOURITE = 1, CATEGORY = 2, LANGUAGE = 3, HEADING = 4, PROFILE = 5;
        final int kind;
        final String label, value;
        int count;

        RailItem(int kind, String label, String value) {
            this.kind = kind;
            this.label = label;
            this.value = value;
        }

        boolean matches(Channel channel, Set<String> favourites) {
            switch (kind) {
                case ALL:
                    return true;
                case FAVOURITE:
                    return favourites.contains(channel.id);
                case CATEGORY:
                    return value.equals(channel.category);
                case LANGUAGE:
                    return value.equals(channel.language);
                default:
                    return false;
            }
        }
    }

    private static final class ShowRow {
        final String time, title;
        final boolean now;

        ShowRow(String time, String title, boolean now) {
            this.time = time;
            this.title = title;
            this.now = now;
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View view) {
            super(view);
        }
    }

    private final class RailAdapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemViewType(int position) {
            int kind = railItems.get(position).kind;
            return kind == RailItem.HEADING ? 1 : kind == RailItem.PROFILE ? 2 : 0;
        }

        @Override
        public int getItemCount() {
            return railItems.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(type == 1 ? R.layout.row_heading : type == 2 ? R.layout.row_profile : R.layout.row_rail,
                            parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            RailItem item = railItems.get(position);
            ((TextView) holder.itemView.findViewById(R.id.label)).setText(item.label);
            if (item.kind == RailItem.HEADING) return;
            ((TextView) holder.itemView.findViewById(R.id.count)).setText(
                    item.kind == RailItem.PROFILE ? phoneLine() : String.valueOf(item.count));
            holder.itemView.setActivated(item == filter);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
            onBindViewHolder(holder, position);
        }
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemCount() {
            return shown.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_channel, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Channel channel = shown.get(position);
            View view = holder.itemView;
            ((TextView) view.findViewById(R.id.number)).setText(String.valueOf(channel.number));
            ((TextView) view.findViewById(R.id.name)).setText(channel.name);
            logos.load(channel.logo, view.findViewById(R.id.logo));
            bindState(view, channel);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position);
            else bindState(holder.itemView, shown.get(position));
        }

        private void bindState(View view, Channel channel) {
            Show show = epg.now(channel.id);
            ((TextView) view.findViewById(R.id.sub)).setText(
                    show != null ? show.title : channel.language + " · " + channel.category);
            String mark = (favourites.contains(channel.id) ? "★" : "") + (channel == playing ? " ▶" : "");
            ((TextView) view.findViewById(R.id.mark)).setText(mark.trim());
            view.setActivated(channel == playing);
        }
    }

    private final class ShowAdapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemCount() {
            return showRows.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_show, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ShowRow row = showRows.get(position);
            ((TextView) holder.itemView.findViewById(R.id.time)).setText(row.time);
            TextView title = holder.itemView.findViewById(R.id.title);
            title.setText(row.title);
            title.setAlpha(row.time.isEmpty() ? 0.6f : 1f); // The "Tomorrow" heading.
            holder.itemView.setActivated(row.now);
        }
    }
}
