import deploy

out = deploy.su("cat /data/adb/lspd/log/modules_*.log | grep -A 2 -B 2 -iE 'qihang|systemui'", check=False)
print("--- modules.log ---")
print(out)
