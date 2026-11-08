import subprocess
adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"
p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", "pm list packages -U | grep -E 'qihang|alive'"], capture_output=True, text=True)
print(p.stdout)
