package com.officerkd6.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import com.getcapacitor.JSObject;

/**
 * Foreground service that shows the media player in the notification panel
 * (prev / play-pause / next / stop + seek bar + cover) and keeps playback alive.
 */
public class MediaPlaybackService extends Service {

    static final String ACT_START = "kd6.START";
    static final String ACT_PLAYPAUSE = "kd6.PLAYPAUSE";
    static final String ACT_NEXT = "kd6.NEXT";
    static final String ACT_PREV = "kd6.PREV";
    static final String ACT_STOP = "kd6.STOP";
    static final String ACT_DISMISS = "kd6.DISMISS";
    private static final String CHANNEL = "kd6_playback";
    private static final int NID = 4242;

    // shared state (written by MediaSessionPlugin)
    static volatile String title = "", artist = "", album = "";
    static volatile Bitmap art = null;
    static volatile long durMs = 0, posMs = 0, posStamp = 0;
    static volatile float rate = 1f;
    static volatile boolean playing = false, active = false, stopped = false;
    static volatile MediaPlaybackService instance = null;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Temporary on-screen messages to see where the panel setup stops. Set false when it works. */
    static final boolean DEBUG = true;

    static void toast(final Context c, final String msg) {
        if (!DEBUG) return;
        final Context app = c.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try { Toast.makeText(app, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
            }
        });
    }

    private MediaSession session;
    private NotificationManager nm;

    // ------------------------------------------------------------ static API
    static void sync(final Context c) {
        final Context app = c.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!active) {
                    if (instance != null) instance.hide();
                    return;
                }
                if (instance != null) {
                    instance.render();
                    return;
                }
                if (!playing) return;
                Intent i = new Intent(app, MediaPlaybackService.class).setAction(ACT_START);
                try {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static void syncSession() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                MediaPlaybackService s = instance;
                if (s != null && active) s.updateSession();
            }
        });
    }

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Media Playback Notification",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        toast(this, "Officer K: player panel service started");
        try {
            if (!nm.areNotificationsEnabled()) {
                toast(this, "Notifications are OFF for Officer K - turn them ON in Android settings");
            } else if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = nm.getNotificationChannel(CHANNEL);
                if (c != null && c.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    toast(this, "'Media Playback Notification' notifications are blocked for Officer K - turn them ON");
                }
            }
        } catch (Throwable ignored) { }
        session = new MediaSession(this, "OfficerK");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MediaSessionPlugin.emit("play", null); }
            @Override public void onPause() { MediaSessionPlugin.emit("pause", null); }
            @Override public void onSkipToNext() { MediaSessionPlugin.emit("nexttrack", null); }
            @Override public void onSkipToPrevious() { MediaSessionPlugin.emit("previoustrack", null); }
            @Override public void onStop() { doStop(); }
            @Override public void onSeekTo(long pos) {
                JSObject d = new JSObject();
                d.put("seekTime", pos / 1000.0);
                MediaSessionPlugin.emit("seekto", d);
            }
            @Override public void onFastForward() {
                JSObject d = new JSObject();
                d.put("seekOffset", 10);
                MediaSessionPlugin.emit("seekforward", d);
            }
            @Override public void onRewind() {
                JSObject d = new JSObject();
                d.put("seekOffset", 10);
                MediaSessionPlugin.emit("seekbackward", d);
            }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("stop".equals(action)) doStop();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        String a = intent == null ? null : intent.getAction();
        if (ACT_PLAYPAUSE.equals(a)) {
            MediaSessionPlugin.emit(playing ? "pause" : "play", null);
        } else if (ACT_NEXT.equals(a)) {
            MediaSessionPlugin.emit("nexttrack", null);
        } else if (ACT_PREV.equals(a)) {
            MediaSessionPlugin.emit("previoustrack", null);
        } else if (ACT_STOP.equals(a)) {
            doStop();
            return START_NOT_STICKY;
        } else if (ACT_DISMISS.equals(a)) {
            hide();
            return START_NOT_STICKY;
        }
        if (active) {
            render();
        } else {
            // started but nothing to show: satisfy startForeground, then leave
            try { startForeground(NID, buildNotification()); } catch (Throwable ignored) { }
            hide();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        hide();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try { session.setActive(false); session.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ------------------------------------------------------------ actions
    private void doStop() {
        stopped = true;
        active = false;
        MediaSessionPlugin.emit("stop", null); // web app pauses the audio
        hide();
    }

    void hide() {
        if (instance == this) instance = null;
        try { session.setActive(false); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        try { nm.cancel(NID); } catch (Throwable ignored) { }
        stopSelf();
    }

    // ------------------------------------------------------------ rendering
    private int res(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    void updateSession() {
        try {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs);
            Bitmap b = art;
            if (b != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, b);
                m.putBitmap(MediaMetadata.METADATA_KEY_ART, b);
            }
            session.setMetadata(m.build());

            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND;
            PlaybackState.Builder p = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            posMs, playing ? rate : 0f,
                            posStamp > 0 ? posStamp : SystemClock.elapsedRealtime())
                    .addCustomAction(new PlaybackState.CustomAction.Builder(
                            "stop", "Stop", res("ic_mc_stop")).build());
            session.setPlaybackState(p.build());
            session.setActive(true);
        } catch (Throwable ignored) {
        }
    }

    void render() {
        updateSession();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
        } catch (Throwable t) {
            toast(this, "Officer K panel error: " + t);
            try { nm.notify(NID, n); } catch (Throwable ignored) { }
        }
    }

    private PendingIntent pi(String action, int rc) {
        Intent i = new Intent(this, MediaPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action act(String icon, String label, String action, int rc) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, res(icon)), label, pi(action, rc)).build();
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(res("ic_mc_stat") != 0 ? res("ic_mc_stat") : android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist != null && !artist.isEmpty() ? artist : album)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(pi(ACT_DISMISS, 9));
        Bitmap big = art;
        if (big != null) b.setLargeIcon(big);

        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, li,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        b.addAction(act("ic_mc_prev", "Previous", ACT_PREV, 1));
        b.addAction(playing
                ? act("ic_mc_pause", "Pause", ACT_PLAYPAUSE, 2)
                : act("ic_mc_play", "Play", ACT_PLAYPAUSE, 2));
        b.addAction(act("ic_mc_next", "Next", ACT_NEXT, 3));
        b.addAction(act("ic_mc_stop", "Stop", ACT_STOP, 4));

        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
}
