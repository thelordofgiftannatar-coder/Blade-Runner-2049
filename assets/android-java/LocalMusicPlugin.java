package com.officerkd6.player;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.media.MediaScannerConnection;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/** Phone-wide music scan + "All files access" helpers used by the web app. */
@CapacitorPlugin(name = "LocalMusic")
public class LocalMusicPlugin extends Plugin {

    @PluginMethod
    public void hasAllFiles(PluginCall call) {
        JSObject o = new JSObject();
        o.put("value", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager());
        call.resolve(o);
    }

    @PluginMethod
    public void requestAllFiles(PluginCall call) {
        MainActivity.openAllFilesSettings(getContext());
        call.resolve();
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Throwable ignored) {
        }
        call.resolve();
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url", "")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Throwable t) {
            call.reject("Could not open link");
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        Integer min = call.getInt("minSeconds", 30);
        JSArray arr = new JSArray();
        String[] proj = {MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME};
        String sel = MediaStore.Audio.Media.DURATION + ">=? AND " + MediaStore.Audio.Media.DATA + " IS NOT NULL";
        ContentResolver cr = getContext().getContentResolver();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel,
                new String[]{String.valueOf(min * 1000L)}, MediaStore.Audio.Media.TITLE + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String p = c.getString(0);
                    if (p == null) continue;
                    JSObject o = new JSObject();
                    o.put("path", p);
                    o.put("title", c.getString(1));
                    String ar = c.getString(2), al = c.getString(3);
                    if (ar != null && !ar.startsWith("<unknown")) o.put("artist", ar);
                    if (al != null && !al.startsWith("<unknown")) o.put("album", al);
                    o.put("duration", c.getLong(4) / 1000.0);
                    o.put("name", c.getString(5));
                    File par = new File(p).getParentFile();
                    o.put("folder", par != null ? par.getName() : "");
                    arr.put(o);
                }
            }
        } catch (SecurityException e) {
            call.reject("Permission denied");
            return;
        } catch (Exception e) {
            call.reject("Scan failed: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("songs", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String path = call.getString("path"), data = call.getString("data");
        Boolean keep = call.getBoolean("keepTime", false);
        if (path == null || data == null) {
            call.reject("path and data required");
            return;
        }
        File f = new File(path);
        long lm = f.exists() ? f.lastModified() : 0;
        try {
            File par = f.getParentFile();
            if (par != null) par.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(Base64.decode(data, Base64.DEFAULT));
            }
            if (keep != null && keep && lm > 0) f.setLastModified(lm);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not write file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void listDir(PluginCall call) {
        String dir = call.getString("dir", ""), ext = call.getString("ext", "");
        JSArray names = new JSArray();
        File[] fs = new File(dir).listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File f : fs) if (f.isFile() && f.getName().endsWith(ext)) names.put(f.getName());
        }
        JSObject r = new JSObject();
        r.put("names", names);
        call.resolve(r);
    }

    /** Lists real files and directories in shared internal storage; nothing is copied into the app. */
    @PluginMethod
    public void listStorage(PluginCall call) {
        String path = call.getString("path");
        if (path == null || path.trim().isEmpty()) path = Environment.getExternalStorageDirectory().getAbsolutePath();
        File dir = new File(path);
        if (!dir.isDirectory() || !dir.canRead()) { call.reject("Folder cannot be read"); return; }
        File[] entries = dir.listFiles();
        JSArray arr = new JSArray();
        if (entries != null) {
            Arrays.sort(entries, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            int count = 0;
            for (File f : entries) {
                if (count++ >= 300) break;
                JSObject o = new JSObject();
                o.put("name", f.getName()); o.put("path", f.getAbsolutePath());
                o.put("directory", f.isDirectory()); o.put("size", f.isFile() ? f.length() : 0L);
                o.put("modified", f.lastModified()); arr.put(o);
            }
        }
        JSObject result = new JSObject(); result.put("path", dir.getAbsolutePath());
        result.put("entries", arr); result.put("truncated", entries != null && entries.length > 300);
        call.resolve(result);
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String p = call.getString("path");
        if (p == null || p.trim().isEmpty()) { call.reject("File path required"); return; }
        try {
            File f = new File(p).getCanonicalFile();
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!f.getPath().equals(root) && !f.getPath().startsWith(root + File.separator)) {
                call.reject("Only shared internal storage files can be deleted"); return;
            }
            if (f.isDirectory()) { call.reject("Folder deletion is disabled to protect its contents"); return; }
            if (!f.exists()) { JSObject gone = new JSObject(); gone.put("deleted", true); call.resolve(gone); return; }
            boolean deleted = f.delete();
            if (!deleted) { call.reject("Android could not delete this file. Check All files access permission."); return; }
            MediaScannerConnection.scanFile(getContext(), new String[]{f.getAbsolutePath()}, null, null);
            JSObject result = new JSObject(); result.put("deleted", true); call.resolve(result);
        } catch (Exception e) { call.reject("Delete failed: " + e.getMessage()); }
    }
}
