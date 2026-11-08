import deploy

out = deploy.su("cat /data/adb/lspd/log/verbose_*.log | grep -iE 'systemui|qihang' | tail -n 30", check=False)
print("--- verbose.log ---")
print(out)
