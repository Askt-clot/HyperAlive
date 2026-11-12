from PIL import Image
import numpy as np

im = Image.open('shots/lock_screen.png').convert('RGB')
arr = np.array(im)
# Find white pixels
white = (arr[:, :, 0] > 240) & (arr[:, :, 1] > 240) & (arr[:, :, 2] > 240)
# Look around Y=500 to 1200
print("White pixels in lock_screen.png:")
for y in range(400, 1500, 100):
    cnt = white[y:y+100, :].sum()
    if cnt > 0:
        print(f"  Y={y}..{y+100}: {cnt}")
