import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'], text=True, errors='replace')

lines = out.splitlines()
for idx, line in enumerate(lines):
    if 'View Hierarchy:' in line or 'View Hierarchy' in line:
        print(f"Match at line {idx}: {line}")
        for l in lines[idx:idx+100]:
            print("  ", l)
