import deploy

cmds = [
    "dumpsys power | grep -E 'mWakefulness=|Display Power:'",
    "settings get secure doze_always_on",
    "settings get secure doze_enabled",
    "settings get system aod_mode",
    "settings list system | grep -i aod",
    "settings list secure | grep -i aod"
]

for cmd in cmds:
    print('==>', cmd)
    try:
        print(deploy.su(cmd).strip())
    except Exception as e:
        print(e)
