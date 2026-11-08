import deploy

for cmd in [
    "settings get secure doze_enabled",
    "settings get secure doze_always_on",
    "settings get system aod_mode",
    "settings get system aod_show_style",
    "settings get system doze_pulse_on_pick_up",
    "dumpsys power | grep -iE 'mWakefulness|mHolding|Display Power'",
    "dumpsys display | grep -iE 'mScreenState|DisplayDeviceInfo'"
]:
    print(f"=== {cmd} ===")
    print(deploy.su(cmd, check=False))
