import subprocess
import os

# Let's use baksmali or python to disassemble com/miui/aod/Utils
# Let's search dex for Lcom/miui/aod/Utils;
with open('MIUIAod.apk', 'rb') as f:
    import zipfile
    z = zipfile.ZipFile(f)
    dex_data = z.read('classes.dex')

# Let's find methods of com.miui.aod.Utils
# We can use a simple dex parser or grep
idx = 0
for m in [b'isUsingSuperWallpaperStyle', b'isUsingLinkageStyle', b'isUsingOriginalBackground']:
    pos = 0
    print("=== Searches for", m, "===")
    while True:
        pos = dex_data.find(m, pos)
        if pos == -1: break
        print("At offset:", hex(pos))
        pos += len(m)
