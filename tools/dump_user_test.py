import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", "logcat -d"], capture_output=True, text=True, errors='replace')
lines = p.stdout.splitlines()

filtered = []
for l in lines:
    if any(f"10-27 20:4{i}" in l for i in range(1, 8)):
        if any(k in l for k in ["QihangAod", "Wallpaper", "Greeze", "DisplayManager", "PowerManager", "wake", "sleep", "mode=", "tr="]):
            if not any(ig in l for ig in ["InspectView", "sweep", "acquireWakeLock", "releaseWakeLock"]):
                filtered.append(l)

out_text = "\n".join(filtered)
with open(r"qihang_lsp\shots\user_test_logcat.txt", "w", encoding="utf-8") as f:
    f.write(out_text)

print(f"Captured {len(filtered)} lines.")
for l in filtered[-60:]:
    print(l[:140])
