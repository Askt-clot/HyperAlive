import zipfile
import subprocess
import os

# Check where isUsingSuperWallpaperStyle is called in MIUIAod.apk
apk_path = 'MIUIAod.apk'
if not os.path.exists(apk_path):
    # pull it or check if it exists
    print("Checking on device...")
    subprocess.run(['<ANDROID_SDK_PATH>\\platform-tools\\adb.exe', 'pull', '/product/priv-app/MIUIAod/MIUIAod.apk', '.'])

# Search for isUsingSuperWallpaperStyle in dex files
with zipfile.ZipFile('MIUIAod.apk', 'r') as z:
    for name in z.namelist():
        if name.endswith('.dex'):
            data = z.read(name)
            if b'isUsingSuperWallpaperStyle' in data:
                print(f"Found in {name}")
