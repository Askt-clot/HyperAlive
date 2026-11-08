import subprocess
import time
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", cmd], capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.stdout

print("1. Clearing logcat...")
run_adb("logcat -c")

print("2. Launching 云鸣潮 (com.kuro.cloudgame)...")
run_adb("monkey -p com.kuro.cloudgame -c android.intent.category.LAUNCHER 1")

print("3. Waiting 6s for game to enter landscape...")
time.sleep(6)

print("Display rotation:")
print(run_adb("dumpsys display | grep -E 'mCurrentOrientation|mDisplayId=0' | head -5"))

print("4. Pressing power key via su to lock screen...")
run_adb("input keyevent 26")

print("5. Waiting 4s in sleep...")
time.sleep(4)

print("6. Grabbing logs...")
log = run_adb("logcat -d | grep -E 'QihangAod|AODView' | tail -n 40")
print("=== LOGCAT ===")
print(log)

# Wake up and go home
run_adb("input keyevent 26")
time.sleep(1)
run_adb("input keyevent 3")
run_adb("am force-stop com.kuro.cloudgame")
print("Done, returned to home.")
