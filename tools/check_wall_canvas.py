import os

# Search QihangWallpaper and all related rendering files
for root, dirs, files in os.walk('app/src/main/java'):
    for f in files:
        if f.endswith('.java'):
            p = os.path.join(root, f)
            with open(p, 'r', encoding='utf-8', errors='ignore') as jf:
                content = jf.read()
                if 'Canvas' in content or 'drawText' in content or 'drawBitmap' in content or 'Clock' in content:
                    print(f"File: {p}")
                    for line in content.splitlines():
                        if 'drawText' in line or 'drawBitmap' in line:
                            print("  ", line.strip())
