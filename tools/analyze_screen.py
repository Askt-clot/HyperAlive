import sys
from PIL import Image
import numpy as np

def analyze(path):
    im = Image.open(path).convert('RGB')
    w, h = im.size
    print(f"Image: {path}, Size: {w}x{h}")
    arr = np.array(im)
    # Bright pixels
    bright = (arr > 40).any(axis=2)
    rows = np.where(bright.any(axis=1))[0]
    cols = np.where(bright.any(axis=0))[0]
    if len(rows) == 0:
        print("Entire image is black/dark!")
        return
    print(f"Content vertical span: Y={rows[0]} to Y={rows[-1]}")
    print(f"Content horizontal span: X={cols[0]} to X={cols[-1]}")
    
    # Let's count bright pixels in vertical buckets of 50px
    print("\nVertical distribution (Y-bucket : bright pixel count):")
    for y in range(0, h, 100):
        cnt = bright[y:min(y+100, h), :].sum()
        if cnt > 0:
            print(f"  Y={y:4d}..{min(y+100, h):4d}: {cnt} pixels")

if __name__ == '__main__':
    analyze(sys.argv[1] if len(sys.argv) > 1 else 'shots/current_screen.png')
