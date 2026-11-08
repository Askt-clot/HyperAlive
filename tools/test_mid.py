import zipfile
import struct

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
dex = z.read('classes.dex')
target_id = 113069

method_ids_size, method_ids_off = struct.unpack('<II', dex[0x50:0x58])
print("method_ids_size:", method_ids_size)
for i in range(method_ids_size):
    c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + i*8 : method_ids_off + (i+1)*8])
    if n_idx == target_id:
        print("Found method_id:", i, "class_idx:", c_idx)
        mid_bytes = struct.pack('<H', i)
        for pos in range(len(dex)-3):
            if dex[pos] in (0x6e, 0x72, 0x74, 0x78) and dex[pos+2:pos+4] == mid_bytes:
                print(f"  invoke at {hex(pos)}")
