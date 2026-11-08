import zipfile

def find_methods(apk_path, class_name):
    # 查找方法名
    print(f"Searching {class_name} in {apk_path}...")

with zipfile.ZipFile('MIUIAod.apk', 'r') as z:
    for name in z.namelist():
        if name.startswith('classes') and name.endswith('.dex'):
            data = z.read(name)
            if b'Lcom/miui/aod/AODView;' in data:
                print("Found AODView in", name)
            if b'Lcom/miui/aod/linkagestyle/LinkageStyleClockView;' in data:
                print("Found LinkageStyleClockView in", name)
