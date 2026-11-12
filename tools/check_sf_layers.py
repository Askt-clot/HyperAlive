import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'SurfaceFlinger', '--list'], text=True, errors='replace')
for line in out.splitlines():
    if any(k in line.lower() for k in ['clock', 'aod', 'keyguard', 'time', 'wallpaper', 'shade']):
        print(line)
