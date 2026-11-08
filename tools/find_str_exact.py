import zipfile
import struct

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])

target = b'isCurtainAnimationNeeded'
for i in range(string_ids_size):
    str_off = struct.unpack('<I', dex[string_ids_off + i*4 : string_ids_off + (i+1)*4])[0]
    pos = str_off
    # read uleb128
    l = 0
    shift = 0
    while True:
        b = dex[pos]
        pos += 1
        l |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    s = dex[pos:pos+l]
    if s == target:
        print(f"Match string {i}: {s}")
        # now find this in method_ids
        method_ids_size, method_ids_off = struct.unpack('<II', dex[0x50:0x58])
        for m in range(method_ids_size):
            c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + m*8 : method_ids_off + (m+1)*8])
            if n_idx == i:
                print(f"  Found method {m}")
