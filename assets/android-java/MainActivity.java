package com.officerkd6.player;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int REQ_NOTIF = 1001;
    private boolean warned = false;
    private boolean permsDone = false;
    private boolean askedAll = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaSessionPlugin.class);
        registerPlugin(LocalMusicPlugin.class);
        super.onCreate(savedInstanceState);
        // Ask for notifications + photos / audio / video access
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            String[] all = {"android.permission.POST_NOTIFICATIONS",
                    "android.permission.READ_MEDIA_AUDIO",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_IMAGES"};
            for (String p : all) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            String p = "android.permission.READ_EXTERNAL_STORAGE";
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_NOTIF);
        } else {
            permsDone = true;
        }
        // SELF-TEST: show the panel a few seconds after launch (proves the notification part works)
        if (MediaPlaybackService.DEBUG) {
            final Context app = getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (MediaPlaybackService.active) return; // real music already showing
                    MediaPlaybackService.title = "Officer K - panel test";
                    MediaPlaybackService.artist = "If you see this, the panel works. Press Stop.";
                    MediaPlaybackService.album = "";
                    MediaPlaybackService.durMs = 0;
                    MediaPlaybackService.posMs = 0;
                    MediaPlaybackService.playing = true;
                    MediaPlaybackService.active = true;
                    MediaPlaybackService.stopped = false;
                    MediaPlaybackService.toast(app, "Officer K: showing TEST panel - pull the screen down");
                    MediaPlaybackService.sync(app);
                }
            }, 3000);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permsDone = true;
        if (requestCode == REQ_NOTIF) {
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if ("android.permission.POST_NOTIFICATIONS".equals(permissions[i])
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    openNotificationSettings();
                }
            }
        }
    }

    /** Opens the Android "All files access" screen for this app. */
    static void openAllFilesSettings(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    private void askAllFiles() {
        if (askedAll || Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return;
        if (isFinishing() || isDestroyed()) return;
        askedAll = true;
        try {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Please Allow")
                    .setMessage("For better use of Officer K, please allow the following permission:\n\n"
                            + "All files access\nIt is used to access music and media files on your device, not your private data.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Allow", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            openAllFilesSettings(MainActivity.this);
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (permsDone) askAllFiles();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (!warned && Build.VERSION.SDK_INT < 33 && !nm.areNotificationsEnabled()) {
                warned = true;
                openNotificationSettings();
            }
        } catch (Throwable ignored) {
        }
    }

    private void openNotificationSettings() {
        try {
            Toast.makeText(this, "Turn ON notifications for Officer K to see the player panel",
                    Toast.LENGTH_LONG).show();
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // keep the WebView (and its audio) alive while the screen is off / app in background
        try {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        } catch (Throwable ignored) {
        }
    }
}
