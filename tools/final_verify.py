import subprocess
import time
from PIL import Image
import numpy as np

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, errors='replace')
    return p.stdout.strip()

print("1. Locking device...")
run(f'"{adb}" shell su -c "input keyevent 26"')
time.sleep(2)

print("2. Capturing AOD initial screen...")
run(f'"{adb}" shell screencap -d 4630946457447247251 -p /sdcard/verify_aod_1.png')
run(f'"{adb}" pull /sdcard/verify_aod_1.png shots/verify_aod_1.png')

print("3. Waiting 15s for burn-in shift / doze stabilization...")
time.sleep(15)
run(f'"{adb}" shell screencap -d 4630946457447247251 -p /sdcard/verify_aod_2.png')
run(f'"{adb}" pull /sdcard/verify_aod_2.png shots/verify_aod_2.png')

print("4. Waking up to lockscreen...")
run(f'"{adb}" shell su -c "input keyevent 26"')
time.sleep(1.5)
run(f'"{adb}" shell screencap -d 4630946457447247251 -p /sdcard/verify_lock.png')
run(f'"{adb}" pull /sdcard/verify_lock.png shots/verify_lock.png')

print("5. Unlocking to launcher...")
subprocess.run(['python', 'deploy.py', 'unlock'])
time.sleep(2)
run(f'"{adb}" shell screencap -d 4630946457447247251 -p /sdcard/verify_launcher.png')
run(f'"{adb}" pull /sdcard/verify_launcher.png shots/verify_launcher.png')

print("All screenshots captured successfully!")
