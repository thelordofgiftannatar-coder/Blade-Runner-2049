package com.officerkd6.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Bridges the web app's media controls to a native notification-panel player.
 * Two routes are offered: a direct JavaScript interface "KD6Native" (used by the app)
 * and the normal Capacitor plugin methods (fallback).
 */
@CapacitorPlugin(name = "MediaSession")
public class MediaSessionPlugin extends Plugin {

    private static boolean seenPlay = false;
    private static volatile WebView web = null;
    private static volatile boolean viaJs = false;
    private static final Map<String, PluginCall> handlers = new ConcurrentHashMap<>();

    /** Runs while the Bridge is being built, i.e. BEFORE the page loads, so the interface is always there. */
    @Override
    public void load() {
        try {
            web = getBridge().getWebView();
            web.addJavascriptInterface(new JsApi(getContext()), "KD6Native");
        } catch (Throwable ignored) {
        }
    }

    /** Called when a button is pressed in the notification panel. */
    static void emit(final String action, JSObject data) {
        final WebView w = web;
        if (viaJs && w != null) {
            final String js = "window.__kd6act&&window.__kd6act('" + action + "',"
                    + (data == null ? "{}" : data.toString()) + ")";
            w.post(new Runnable() {
                @Override
                public void run() {
                    try { w.evaluateJavascript(js, null); } catch (Throwable ignored) { }
                }
            });
            return;
        }
        PluginCall c = handlers.get(action);
        if (c == null) return;
        try {
            c.resolve(data == null ? new JSObject() : data);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ shared logic
    static void applyMeta(Context ctx, String t, String ar, String al, String artSrc) {
        MediaPlaybackService.title = t == null ? "" : t;
        MediaPlaybackService.artist = ar == null ? "" : ar;
        MediaPlaybackService.album = al == null ? "" : al;
        Bitmap bmp = null;
        try {
            int comma = artSrc == null ? -1 : artSrc.indexOf(',');
            if (artSrc != null && artSrc.startsWith("data:") && comma > 0) {
                byte[] b = Base64.decode(artSrc.substring(comma + 1), Base64.DEFAULT);
                bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            }
        } catch (Throwable ignored) {
        }
        MediaPlaybackService.art = bmp;
        MediaPlaybackService.sync(ctx);
    }

    static void applyState(Context ctx, String st) {
        boolean play = "playing".equals(st);
        if (play && !seenPlay) {
            seenPlay = true;
            MediaPlaybackService.toast(ctx, "Officer K: sending 'playing' to the player panel");
        }
        if (play) MediaPlaybackService.stopped = false;
        MediaPlaybackService.playing = play;
        MediaPlaybackService.active = !"none".equals(st) && !MediaPlaybackService.stopped;
        MediaPlaybackService.sync(ctx);
    }

    static void applyPos(double dur, double pos, double rate) {
        MediaPlaybackService.durMs = (long) (dur * 1000);
        MediaPlaybackService.posMs = (long) (pos * 1000);
        MediaPlaybackService.rate = (float) rate;
        MediaPlaybackService.posStamp = SystemClock.elapsedRealtime();
        MediaPlaybackService.syncSession();
    }

    // ------------------------------------------------------------ direct JS interface
    static class JsApi {
        private final Context ctx;

        JsApi(Context c) { ctx = c.getApplicationContext(); }

        @JavascriptInterface
        public void hello() {
            viaJs = true;
            MediaPlaybackService.toast(ctx, "Officer K: app connected to the player panel");
        }

        @JavascriptInterface
        public void meta(String title, String artist, String album, String art) {
            applyMeta(ctx, title, artist, album, art);
        }

        @JavascriptInterface
        public void state(String st) { applyState(ctx, st); }

        @JavascriptInterface
        public void pos(double dur, double pos, double rate) { applyPos(dur, pos, rate); }
    }

    // ------------------------------------------------------------ Capacitor plugin methods (fallback)
    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void setActionHandler(PluginCall call) {
        String action = call.getString("action");
        if (action == null) {
            call.reject("action required");
            return;
        }
        call.setKeepAlive(true);
        try {
            getBridge().saveCall(call);
        } catch (Throwable ignored) {
        }
        handlers.put(action, call);
    }

    @PluginMethod
    public void setMetadata(PluginCall call) {
        String src = "";
        try {
            JSArray arr = call.getArray("artwork");
            if (arr != null && arr.length() > 0) {
                JSONObject o = arr.getJSONObject(0);
                src = o.optString("src", "");
            }
        } catch (Throwable ignored) {
        }
        applyMeta(getContext(), call.getString("title", ""), call.getString("artist", ""),
                call.getString("album", ""), src);
        call.resolve();
    }

    @PluginMethod
    public void setPlaybackState(PluginCall call) {
        applyState(getContext(), call.getString("playbackState", "none"));
        call.resolve();
    }

    @PluginMethod
    public void setPositionState(PluginCall call) {
        applyPos(call.getDouble("duration", 0.0), call.getDouble("position", 0.0),
                call.getDouble("playbackRate", 1.0));
        call.resolve();
    }
}
