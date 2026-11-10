package io.github.rjtheorigin.liveindiantv;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live Indian TV as a channel source for the Live Channels app. Live Channels owns the screen, the guide and the remote;
 * this plays the channel it tunes to into the picture area it hands over, through the same stream server and player
 * settings as Live Indian TV. Live Channels' picture area is always marked secure, so Widevine channels can play in it.
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveInput extends TvInputService {
    private static final String TAG = "LiveInput";
    private static final int RETRIES = 2;

    @Override
    public Session onCreateSession(@NonNull String inputId) {
        return new LiveSession(this);
    }

    /** The stream address and licence address, kept with the channel row: a tune needs no playlist download. */
    static byte[] encodeStream(String url, String license) {
        return (url + "\n" + (license == null ? "" : license)).getBytes(StandardCharsets.UTF_8);
    }

    private static final class LiveSession extends Session implements Player.Listener {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private ExoPlayer player;
        private Surface surface;
        private float volume = 1f;
        private MediaItem current;
        /** Bumped by each tune, so a slow lookup for an old channel cannot start playing over the new one. */
        private int tune;
        private int retries;
        private boolean showing, usingServer;

        LiveSession(Context context) {
            super(context);
            this.context = context;
        }

        @Override
        public boolean onSetSurface(Surface value) {
            surface = value;
            if (player != null) player.setVideoSurface(value);
            return true;
        }

        @Override
        public void onSetStreamVolume(float value) {
            volume = value;
            if (player != null) player.setVolume(value);
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // Jio's streams carry no captions.
        }

        @Override
        public boolean onTune(Uri channelUri) {
            int generation = ++tune;
            showing = false;
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            if (player != null) player.stop();
            if (!usingServer) {
                Server.addLiveUser();
                usingServer = true;
            }
            io.execute(() -> {
                String[] stream = lookup(channelUri);
                if (stream != null) {
                    try {
                        Server.ensure(context);
                    } catch (IOException e) {
                        Log.w(TAG, "server: " + e.getMessage());
                        stream = null;
                    }
                }
                String[] found = stream;
                main.post(() -> {
                    if (generation != tune) return;
                    if (found == null) notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                    else play(found[0], found[1]);
                });
            });
            return true;
        }

        private String[] lookup(Uri channelUri) {
            try (Cursor cursor = context.getContentResolver().query(channelUri,
                    new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) return null;
                String[] parts = new String(cursor.getBlob(0), StandardCharsets.UTF_8).split("\n", -1);
                return parts.length == 2 && parts[0].startsWith("http") ? parts : null;
            } catch (RuntimeException e) {
                Log.w(TAG, "channel " + channelUri + ": " + e.getMessage());
                return null;
            }
        }

        private void play(String url, String license) {
            if (player == null) {
                DefaultLoadControl load = new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(12_000, 24_000, 1_500, 3_000)
                        .setTargetBufferBytes(20 * 1024 * 1024)
                        .build();
                player = new ExoPlayer.Builder(context).setLoadControl(load).build();
                player.addListener(this);
                player.setVideoSurface(surface);
                player.setVolume(volume);
                player.setPlayWhenReady(true);
            }
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
            if (!license.isEmpty()) {
                item.setMimeType(MimeTypes.APPLICATION_MPD).setDrmConfiguration(
                        new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(license).build());
            } else {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            current = item.build();
            retries = 0;
            player.setMediaItem(current);
            player.prepare();
        }

        @Override
        public void onRenderedFirstFrame() {
            showing = true;
            notifyVideoAvailable();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (!showing) return;
            if (state == Player.STATE_BUFFERING) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
            } else if (state == Player.STATE_READY) {
                notifyVideoAvailable();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.w(TAG, "playback: " + error.getErrorCodeName());
            if (current != null && retries++ < RETRIES) {
                int generation = tune;
                main.postDelayed(() -> {
                    if (generation != tune || player == null) return;
                    player.setMediaItem(current);
                    player.prepare();
                }, 1500);
            } else {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            }
        }

        @Override
        public void onRelease() {
            tune++;
            main.removeCallbacksAndMessages(null);
            io.shutdownNow();
            if (player != null) {
                player.release();
                player = null;
            }
            if (usingServer) {
                usingServer = false;
                Server.removeLiveUser();
                Server.stopIfIdle();
            }
        }
    }
}
