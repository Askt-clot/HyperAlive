import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'SurfaceFlinger'], text=True, errors='replace')
for line in out.splitlines():
    if line.startswith("===") or line.endswith(":") or "Composition" in line or "HWC" in line or "Layer" in line:
        if len(line.strip()) < 80:
            print(line.strip())
