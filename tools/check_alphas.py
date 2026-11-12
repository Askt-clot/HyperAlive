import subprocess

# We can run an adb shell command or use dumpsys to check alpha
# Let's inspect dumpsys activity service com.android.systemui/.SystemUIService for alphas
out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

for line in out.splitlines():
    if 'keyguard_root_view' in line or 'keyguard_panel_view' in line or 'aod_root_view' in line or 'miui_keyguard_clock_container' in line:
        print(line)
