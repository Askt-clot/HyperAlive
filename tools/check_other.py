from PIL import Image
import numpy as np

im = Image.open('shots/crop_current.png').convert('RGB')
arr = np.array(im)

mask = (arr[:, :, 0] < 180) & ((arr > 30).any(axis=2))
img = Image.fromarray((mask * 255).astype(np.uint8))
img.save('shots/mask_other.png')

small = img.resize((80, 40))
arr_sm = np.array(small)
for row in arr_sm:
    line = "".join("#" if p > 100 else " " for p in row)
    if "#" in line:
        print(line)
