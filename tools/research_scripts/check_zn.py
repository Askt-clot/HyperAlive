import deploy

print(deploy.su("cat /data/adb/modules/zygisk_lsposed/zn_modules.txt", check=False))
