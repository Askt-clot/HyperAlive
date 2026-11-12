import subprocess

out = subprocess.check_output(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'shell', 'dumpsys', 'SurfaceFlinger'], text=True, errors='replace')

for block in out.split('+ Layer '):
    if 'visible region' in block.lower():
        lines = block.splitlines()
        print("Layer:", lines[0] if len(lines) > 0 else "")
        for l in lines[1:10]:
            if 'visible' in l.lower() or 'alpha' in l.lower() or 'flags' in l.lower() or 'z=' in l.lower():
                print("   ", l.strip())
