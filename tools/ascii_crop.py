from PIL import Image
import numpy as np

im = Image.open('shots/current_screen.png').convert('RGB')
crop = im.crop((100, 500, 1100, 1250))
crop.save('shots/crop_current.png')

# Let's render a mini ASCII preview
crop_small = crop.resize((80, 50))
arr = np.array(crop_small)
for row in arr:
    line = "".join("#" if (p > 50).any() else " " for p in row)
    if "#" in line:
        print(line)
