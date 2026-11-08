import deploy
import time

# Check wakefulness
pwr = deploy.su("dumpsys power | grep -E 'mWakefulness='", check=False)
print("Current power:", pwr.strip())

if "Awake" not in pwr:
    print("Waking up first...")
    deploy.su("input keyevent 26")
    time.sleep(1.0)

print("Entering AOD (sleep)...")
deploy.su("input keyevent 26")
time.sleep(2.0)

print("Capturing AOD screenshot...")
deploy.shot("aod_flyme_v2")

logs = deploy.su("logcat -d | grep -iE 'QihangAod|FlymeClock' | tail -n 30", check=False)
print("=== LOGS ===")
print(logs)
