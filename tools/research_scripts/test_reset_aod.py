import deploy
import time

print("Waking up...")
deploy.su("input keyevent 26")
time.sleep(1.0)

print("Entering AOD...")
deploy.su("input keyevent 26")
time.sleep(1.0)

print("Switching back to default double stack + pure white...")
cmd = (
    "am broadcast -a com.qihang.aod.CLOCK_STYLE_CHANGED "
    "--ei clock_style 0 "
    "--ei clock_color -1 " # 0xFFFFFFFF
    "--ef clock_scale 1.0 "
    "--ei clock_weight 500 "
    "--ei clock_offset_y 0 "
    "--ez clock_show_lunar true"
)
deploy.su(cmd)
time.sleep(1.0)

print("Capturing AOD screenshot...")
deploy.shot("aod_double_white_final")
