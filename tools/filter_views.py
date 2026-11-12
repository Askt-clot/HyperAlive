import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
in_views = False
for line in lines:
    if 'NotificationShadeWindowView' in line:
        in_views = True
    if in_views:
        if 'Clock' in line or 'clock' in line or 'Aod' in line or 'aod' in line or 'Keyguard' in line or 'Time' in line or 'NotificationShadeWindowView' in line:
            print(line)
        if 'Scroller' in line:
            # Views section might be over or deep
            pass
