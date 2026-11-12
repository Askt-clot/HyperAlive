import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
in_views = False
for line in lines:
    if 'NotificationShadeWindowView' in line:
        in_views = True
    if in_views:
        # print view hierarchy with indentation
        if any(w in line for w in ['Clock', 'clock', 'Time', 'time', 'date', 'Date', 'Aod', 'aod', 'keyguard', 'Keyguard', 'Battery', 'battery']):
            print(line)
        if 'View Hierarchy' in line and in_views:
            pass
