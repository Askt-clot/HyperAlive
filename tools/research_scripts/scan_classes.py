import zipfile
import re

def find_classes(apk_path, keyword_pattern):
    results = set()
    pat = re.compile(keyword_pattern, re.IGNORECASE)
    with zipfile.ZipFile(apk_path, 'r') as z:
        for name in z.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                data = z.read(name)
                # 匹配 Lcom/miui/...;
                matches = re.findall(b'L[a-zA-Z0-9_/$]+;', data)
                for m in matches:
                    s = m.decode('latin1')
                    if pat.search(s):
                        results.add(s)
    return sorted(list(results))

print("=== MIUIAod.apk matching Clock/Aod ===")
for c in find_classes('MIUIAod.apk', r'aod.*clock|clock.*view')[:40]:
    print(" ", c)

print("\n=== MIUISystemUIPlugin.apk matching KeyguardClock/ClockView ===")
for c in find_classes('MIUISystemUIPlugin.apk', r'keyguard.*clock|clock.*view')[:40]:
    print(" ", c)
