import subprocess

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", "su", "-c", cmd], capture_output=True, text=True, errors='replace')
    return p.stdout

out = run_adb("grep -i -E 'FreezeUtils|GreezeManagerService' /data/adb/lspd/log/modules_*.log | tail -n 40")
print(out)
