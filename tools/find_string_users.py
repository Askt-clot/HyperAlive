import zipfile
import struct

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])

def find_string_id(target):
    for i in range(string_ids_size):
        str_off = struct.unpack('<I', dex[string_ids_off + i*4 : string_ids_off + (i+1)*4])[0]
        pos = str_off
        while dex[pos] & 0x80: pos += 1
        pos += 1
        if dex[pos:pos+len(target)] == target and dex[pos+len(target)] == 0:
            return i
    return -1

id1 = find_string_id(b'aod_using_super_wallpaper')
id2 = find_string_id(b'full_screen_aod_on')
print("id1 (aod_using_super_wallpaper):", id1)
print("id2 (full_screen_aod_on):", id2)

for name, sid in [("aod_using_super_wallpaper", id1), ("full_screen_aod_on", id2)]:
    sid_bytes = struct.pack('<I', sid)
    for pos in range(len(dex)-5):
        if dex[pos] == 0x1b and dex[pos+2:pos+6] == sid_bytes:
            print(name, "const-string/jumbo at", hex(pos))
            chunk = dex[max(0, pos-100):min(len(dex), pos+100)]
            print("".join(chr(c) if 32 <= c <= 126 else "." for c in chunk))
