import sys, re
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
perms = ["android.permission.FOREGROUND_SERVICE",
         "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
         "android.permission.POST_NOTIFICATIONS",
         "android.permission.WAKE_LOCK",
         "android.permission.READ_MEDIA_IMAGES",
         "android.permission.READ_MEDIA_AUDIO",
         "android.permission.READ_MEDIA_VIDEO",
         "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
         "android.permission.READ_EXTERNAL_STORAGE",
         "android.permission.MANAGE_EXTERNAL_STORAGE"]
add = "".join(f'    <uses-permission android:name="{x}" />\n' for x in perms if x not in s)
if add:
    s = s.replace("</manifest>", add + "</manifest>")
if "MediaPlaybackService" not in s:
    svc = '        <service android:name=".MediaPlaybackService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n'
    s = s.replace("</application>", svc + "    </application>")
open(p, "w", encoding="utf-8").write(s)
print(s)
