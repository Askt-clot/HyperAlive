import zipfile

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

for s in [b'isCurtainAnimationNeeded', b'isCurtainAnimationAvailable', b'CurtainAnimation']:
    idx = 0
    while True:
        idx = dex.find(s, idx)
        if idx == -1: break
        print(s.decode(), "at", hex(idx))
        idx += len(s)
