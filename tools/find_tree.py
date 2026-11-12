import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
for idx, line in enumerate(lines):
    if 'com.android.systemui.statusbar.phone.CentralSurfacesImpl' in line:
        print(f"CentralSurfaces at line {idx}")
    if 'NotificationShadeWindowView' in line:
        print(f"NotificationShadeWindowView at line {idx}: {line}")
        # print next 80 lines
        for l in lines[idx:idx+60]:
            print("  ", l)
        break
