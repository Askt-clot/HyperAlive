import subprocess

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

aod_path = "/product/priv-app/MIUIAod/MIUIAod.apk"
out = run_adb(f"dexdump -d {aod_path} | grep -B 10 -A 30 'isUsingSuperWallpaperStyle'")
print(out[:4000])
