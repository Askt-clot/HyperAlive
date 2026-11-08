import zipfile
import re

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

for match in re.finditer(rb'full_screen_aod|super_wallpaper|screen_off_animator', dex):
    start = max(0, match.start() - 100)
    end = min(len(dex), match.end() + 100)
    print("--- MATCH ---")
    print(dex[start:end])
