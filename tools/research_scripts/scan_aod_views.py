import zipfile
import re

pat = re.compile(b'Lcom/miui/aod/[a-zA-Z0-9_/$]+View;')
found = set()
with zipfile.ZipFile('MIUIAod.apk', 'r') as z:
    for name in z.namelist():
        if name.startswith('classes') and name.endswith('.dex'):
            matches = pat.findall(z.read(name))
            for m in matches:
                found.add(m.decode('latin1'))

for s in sorted(list(found)):
    print(s)
