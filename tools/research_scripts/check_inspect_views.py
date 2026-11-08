import deploy

# Dump views with id containing 'clock' or 'keyguard' or 'status'
out = deploy.su("logcat -d | grep -iE 'InspectView' | tail -n 80", check=False)
print("=== RECENT INSPECT VIEW LOGS ===")
print(out)
