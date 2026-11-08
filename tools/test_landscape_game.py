import subprocess
import time

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

print("1. Clearing logcat...")
run_adb("logcat -c")

print("2. Launching 云鸣潮 (com.kuro.cloudgame)...")
print(run_adb("monkey -p com.kuro.cloudgame -c android.intent.category.LAUNCHER 1"))

print("3. Waiting 5s for game to enter landscape...")
time.sleep(5)

print("Display rotation:")
print(run_adb("dumpsys display | grep -E 'mCurrentOrientation|mDisplayId=0' | head -5"))

print("4. Pressing power key to lock screen...")
run_adb("input keyevent 26")

print("5. Waiting 4s in sleep...")
time.sleep(4)

print("6. Grabbing logs...")
log = run_adb("logcat -d | grep -E 'QihangAod|AOD|AODView|FlymeClock|DozeHost|ScrimController|Greeze' | tail -n 80")
print("=== LOGCAT SNIPPET ===")
print(log)

# Wake up and go home
run_adb("input keyevent 26")
time.sleep(1)
run_adb("input keyevent 3")
run_adb("am force-stop com.kuro.cloudgame")
print("Done, stopped game and returned to home.")
