import zipfile

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

for s in [b'aod_using_super_wallpaper', b'full_screen_aod_on']:
    idx = 0
    while True:
        idx = dex.find(s, idx)
        if idx == -1: break
        print(s.decode(), "at", hex(idx))
        idx += len(s)
