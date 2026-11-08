import deploy, time

try:
    deploy.su('pkill -f com.android.systemui', check=False)
except Exception:
    pass

time.sleep(3)
deploy.su('logcat -c')
print("SystemUI PIDs:")
print(deploy.su('ps -A | grep systemui'))

print("Triggering AOD (keyevent 26)...")
deploy.su('input keyevent 26')
time.sleep(2.5)

logs = deploy.su('logcat -d | grep QihangAod')
print("=== LOGCAT QihangAod ===")
print(logs)

deploy.su('input keyevent 26')
print("Done.")
