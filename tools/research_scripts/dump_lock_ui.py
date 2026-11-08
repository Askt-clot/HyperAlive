import deploy, time

print("Waking screen to lockscreen...")
deploy.su("input keyevent 26")
time.sleep(1.0)

# Check if screen is awake
pwr = deploy.su("dumpsys power | grep -E 'mWakefulness='")
if "Awake" not in pwr:
    deploy.su("input keyevent 26")
    time.sleep(1.0)

print("Dumping UI tree...")
import uidump
