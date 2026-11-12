from PIL import Image
import numpy as np

im = Image.open('shots/current_screen.png').convert('RGB')
arr = np.array(im)

# Let's save a thresholded or color-separated version
# Clock text is typically pure white (255, 255, 255)
# Wallpaper lens is colored or dimmed
print("Max RGB:", arr.max(axis=(0,1)))

white = (arr[:, :, 0] > 200) & (arr[:, :, 1] > 200) & (arr[:, :, 2] > 200)
colored = ((arr > 30).any(axis=2)) & ~white

print("White pixels count:", white.sum())
print("Colored/dim pixels count:", colored.sum())

# Where are white pixels?
w_rows = np.where(white.any(axis=1))[0]
if len(w_rows) > 0:
    print(f"White pixels Y range: {w_rows[0]} to {w_rows[-1]}")
    # ASCII of white pixels only
    w_crop = white[w_rows[0]:w_rows[-1]+1, :]
    # downsample
    from PIL import Image as Img
    w_img = Img.fromarray((w_crop * 255).astype(np.uint8))
    w_img.save('shots/white_pixels.png')
    w_img_small = w_img.resize((100, int(100 * w_crop.shape[0] / w_crop.shape[1] * 0.5)))
    w_arr = np.array(w_img_small)
    print("White pixels ASCII:")
    for row in w_arr:
        line = "".join("#" if p > 100 else " " for p in row)
        if "#" in line:
            print(line)

# Where are non-white colored pixels?
c_rows = np.where(colored.any(axis=1))[0]
if len(c_rows) > 0:
    print(f"\nColored pixels Y range: {c_rows[0]} to {c_rows[-1]}")
    c_crop = colored[c_rows[0]:c_rows[-1]+1, :]
    c_img = Img.fromarray((c_crop * 255).astype(np.uint8))
    c_img.save('shots/colored_pixels.png')
