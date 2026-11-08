from PIL import Image
import numpy as np

img = Image.open('shots/real_aod_verified.png').convert('RGB')
w, h = img.size
print(f"Image shape: {w}x{h}")

arr = np.array(img)
gray = np.mean(arr, axis=2)

# Non-black region
mask = gray > 25
ys, xs = np.where(mask)
if len(xs) > 0:
    min_x, max_x = np.min(xs), np.max(xs)
    min_y, max_y = np.min(ys), np.max(ys)
    print(f"Non-black content bounds: x=[{min_x}, {max_x}], y=[{min_y}, {max_y}], w={max_x-min_x}, h={max_y-min_y}")
else:
    print("Screen is completely black!")

# Analyze brightness in several bands
# Band 1: y in [200, 600] (clock top row)
# Band 2: y in [600, 1000] (clock bottom row)
# Band 3: y in [1000, 1400] (date/lunar)
# Band 4: y in [1400, 2000] (wallpaper center)
for y1, y2, label in [
    (150, 450, "Hour row (approx)"),
    (450, 750, "Minute row (approx)"),
    (750, 1000, "Date / Lunar (approx)"),
    (1000, 1600, "Center ring / Wallpaper"),
    (1800, 2400, "Bottom area (battery/fingerprint)")
]:
    band = gray[y1:y2, :]
    nonzeros = np.sum(band > 30)
    max_val = np.max(band)
    mean_val = np.mean(band)
    print(f"{label} [{y1}:{y2}]: max={max_val:.1f}, mean={mean_val:.1f}, bright_pixels={nonzeros}")
