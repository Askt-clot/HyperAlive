import zipfile
import re

with zipfile.ZipFile('MIUIAod.apk', 'r') as z:
    dex = z.read('classes.dex')

# Let's inspect string references or disassemble methods referencing isUsingSuperWallpaperStyle
# Using simple pattern search
idx = 0
while True:
    idx = dex.find(b'isUsingSuperWallpaperStyle', idx)
    if idx == -1:
        break
    print(f"Match at offset {idx}")
    # print context
    start = max(0, idx - 100)
    end = min(len(dex), idx + 100)
    print("Context:", dex[start:end])
    idx += 26
