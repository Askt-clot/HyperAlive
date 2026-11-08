import deploy
import time

print("Waking up...")
deploy.su("input keyevent 26")
time.sleep(1.0)

print("Entering AOD...")
deploy.su("input keyevent 26")
time.sleep(1.0)

print("Broadcasting style change: single line + glacier blue...")
cmd = (
    "am broadcast -a com.qihang.aod.CLOCK_STYLE_CHANGED "
    "--ei clock_style 1 "
    "--ei clock_color -10443270 " # 0xFF60A5FA
    "--ef clock_scale 1.0 "
    "--ei clock_weight 700 "
    "--ei clock_offset_y 0 "
    "--ez clock_show_lunar true"
)
deploy.su(cmd)
time.sleep(1.0)

print("Capturing AOD screenshot...")
deploy.shot("aod_single_line_blue")
