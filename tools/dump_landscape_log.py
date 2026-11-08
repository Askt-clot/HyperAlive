import subprocess
import time
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

adb = r"<ANDROID_SDK_PATH>\platform-tools\adb.exe"

def run_adb(cmd):
    p = subprocess.run([adb, "-s", "<device-id>", "shell", cmd], capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.stdout

log = run_adb("logcat -d | grep -E 'QihangAod|AOD|AODView|FlymeClock|DozeHost|ScrimController|Greeze' | tail -n 120")
with open("qihang_lsp/shots/landscape_test_log.txt", "w", encoding="utf-8") as f:
    f.write(log)
print("Saved landscape_test_log.txt with", len(log.splitlines()), "lines")
