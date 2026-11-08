import deploy

for cmd in ['lspctl status', 'lspctl modules', '/data/adb/ksu/bin/lspctl status', '/data/adb/modules/zygisk_lsposed/lspctl status']:
    print("==>", cmd)
    print(deploy.su(cmd, check=False).strip())
