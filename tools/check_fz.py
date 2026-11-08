import subprocess, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
with open(r"qihang_lsp\shots\user_test_logcat.txt", "r", encoding="utf-8") as f:
    for l in f:
        if "10266" in l or "18678" in l:
            if any(k in l for k in ["FZ", "THAW", "freeze", "Greeze"]):
                print(l.strip()[:140])
