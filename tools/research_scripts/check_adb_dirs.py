import deploy

print(deploy.su('find /data/adb -maxdepth 3', check=False))
