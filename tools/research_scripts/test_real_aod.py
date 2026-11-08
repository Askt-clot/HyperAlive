import deploy
import time

print("Screen off (keyevent 26)...")
deploy.su('input keyevent 26')
time.sleep(3.5)

print("Capturing AOD screenshot...")
deploy.shot('real_aod_verified')

print("Reading AOD logs...")
logs = deploy.su("logcat -d | grep -iE 'QihangAod|FlymeClock|AODView|LinkageStyle|SecurityException' | tail -n 80", check=False)
print("=== LOGS ===")
print(logs)

print("Waking up screen...")
deploy.su('input keyevent 26')
