import deploy

out = deploy.su("logcat -d | grep -E 'QihangAod|FlymeClock' | tail -n 40", check=False)
print("=== LOGCAT HOOKS ===")
print(out)
