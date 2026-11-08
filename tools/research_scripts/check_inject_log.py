import deploy

out = deploy.su("logcat -d | grep -iE '注入|FlymeClockView|AOD sweep' | tail -n 50", check=False)
print("=== INJECT LOGS ===")
print(out)
