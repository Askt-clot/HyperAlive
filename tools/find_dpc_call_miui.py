import zipfile
import struct

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
target = b'setWaitingScreenOffAnimator'

for name in z.namelist():
    if not name.endswith('.dex'): continue
    dex = z.read(name)
    if target not in dex: continue
    print(name, "has string")
    string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])
    target_id = -1
    for i in range(string_ids_size):
        str_off = struct.unpack('<I', dex[string_ids_off + i*4 : string_ids_off + (i+1)*4])[0]
        pos = str_off
        while dex[pos] & 0x80: pos += 1
        pos += 1
        if dex[pos:pos+len(target)] == target and dex[pos+len(target)] == 0:
            target_id = i
            break
    print(name, "target_id:", target_id)
    method_ids_size, method_ids_off = struct.unpack('<II', dex[0x50:0x58])
    for i in range(method_ids_size):
        c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + i*8 : method_ids_off + (i+1)*8])
        if n_idx == target_id:
            print(name, "found method_id:", i)
            mid_bytes = struct.pack('<H', i)
            for pos in range(len(dex)-3):
                if dex[pos] in (0x6e, 0x72, 0x74, 0x78) and dex[pos+2:pos+4] == mid_bytes:
                    print(f"  invoke at {hex(pos)}")
