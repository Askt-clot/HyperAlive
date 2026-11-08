import subprocess
import time

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

# Check current orientation
print("Current window orientation:")
print(run_adb("dumpsys display | grep -E 'mCurrentOrientation|mDisplayId=0' | head -5"))

# Clear logcat
run_adb("logcat -c")
print("Cleared logcat. Now let's test rotating or launching landscape.")
