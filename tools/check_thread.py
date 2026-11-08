import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
with open(r"qihang_lsp\shots\user_test_logcat.txt", "r", encoding="utf-8") as f:
    for l in f:
        if "20:46:2" in l or "20:46:3" in l:
            if any(k in l for k in ["Greezer", "Denial", "wakeUp", "mode=1", "tr=0", "KEYGUARD"]):
                print(l.strip()[:140])
