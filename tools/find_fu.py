import subprocess

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

out = run_adb("dexdump -f /system_ext/framework/miui-services.jar | grep -B 2 -A 5 'name.*:.*freeze[UP][bi]'")
print(out)
