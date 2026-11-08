import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
with open(r"qihang_lsp\shots\user_test_logcat.txt", "r", encoding="utf-8") as f:
    for l in f:
        if any(f"20:46:{s:02d}" in l for s in range(0, 28)):
            if any(k in l for k in ["QihangAod", "draw", "visible", "Greeze", "power", "wake", "display"]):
                print(l.strip()[:140])
