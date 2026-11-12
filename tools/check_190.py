from PIL import Image
import numpy as np

im = Image.open('shots/crop_current.png').convert('RGB')
arr = np.array(im)

mask = (arr[:, :, 0] >= 180) & (arr[:, :, 0] <= 200) & (arr[:, :, 1] >= 180) & (arr[:, :, 1] <= 200) & (arr[:, :, 2] >= 180) & (arr[:, :, 2] <= 200)
img = Image.fromarray((mask * 255).astype(np.uint8))
img.save('shots/mask_190.png')

# Print ASCII of mask_190
small = img.resize((100, 35))
arr_sm = np.array(small)
for row in arr_sm:
    line = "".join("#" if p > 100 else " " for p in row)
    if "#" in line:
        print(line)
