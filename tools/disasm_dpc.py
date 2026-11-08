import zipfile

z = zipfile.ZipFile('qihang_lsp/tools/services.jar')
dex = z.read('classes2.dex')

idx = 0
while True:
    idx = dex.find(b'setWaitingScreenOffAnimator', idx)
    if idx == -1: break
    print("Found at offset:", hex(idx))
    # print surrounding bytes (printable ascii)
    chunk = dex[max(0, idx - 150):min(len(dex), idx + 150)]
    printable = "".join(chr(c) if 32 <= c <= 126 else "." for c in chunk)
    print(printable)
    idx += len('setWaitingScreenOffAnimator')
