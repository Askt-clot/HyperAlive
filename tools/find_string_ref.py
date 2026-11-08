import zipfile
import struct

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
dex = z.read('classes.dex')

string_id = 122665
sid_bytes = struct.pack('<I', string_id)
for pos in range(len(dex)-5):
    if dex[pos] == 0x1b and dex[pos+2:pos+6] == sid_bytes:
        print("Found const-string/jumbo at offset:", hex(pos))
        chunk = dex[max(0, pos-200):min(len(dex), pos+200)]
        print("".join(chr(c) if 32 <= c <= 126 else "." for c in chunk))
