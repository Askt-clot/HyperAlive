import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'SurfaceFlinger'], text=True, errors='replace')

lines = out.splitlines()
in_layers = False
for line in lines:
    if 'Display 4630946457447247251' in line or 'HWC layers' in line:
        in_layers = True
    if in_layers:
        if 'Display 4630946457447247252' in line:
            break
        print(line)
