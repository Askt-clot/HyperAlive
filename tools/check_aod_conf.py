import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

print(run_adb("settings list system | grep -i aod"))
print(run_adb("settings list secure | grep -i aod"))
