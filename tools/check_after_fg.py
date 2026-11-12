import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
in_h = False
for idx, line in enumerate(lines):
    if 'keyguard_foreground_layer' in line:
        for l in lines[idx:idx+80]:
            print("  ", l)
        break
