import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
for line in lines:
    if 'keyguard_panel_view' in line or 'KeyguardClockContainer' in line or 'miui_keyguard_clock_container' in line or 'miui_keyguard_foreground_clock_container' in line:
        print(line)
