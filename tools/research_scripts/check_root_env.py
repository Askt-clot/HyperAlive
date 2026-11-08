import deploy

for cmd in ['which magisk', 'which ksu', 'which apd', 'getprop ro.build.version.release', 'ps -A | grep -iE "lspd|magisk|zygisk"']:
    print("==>", cmd)
    try:
        print(deploy.su(cmd, check=False).strip())
    except Exception as e:
        print("err:", e)
