import zipfile
import re

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

for match in re.finditer(rb'setWaitingScreenOffAnimator|isWaitingScreenOffAnimator|notifyScreenOffAnimatorEnd', dex):
    start = max(0, match.start() - 200)
    end = min(len(dex), match.end() + 200)
    print("--- MATCH ---")
    print(dex[start:end])
