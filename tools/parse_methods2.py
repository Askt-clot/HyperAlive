import zipfile
import struct

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
dex = z.read('classes2.dex')
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

id_needed = find_string_id(b'isCurtainAnimationNeeded')
id_avail = find_string_id(b'isCurtainAnimationAvailable')
id_set_wait = find_string_id(b'setWaitingScreenOffAnimator')
print(f"In classes2.dex: id_needed={id_needed}, id_avail={id_avail}, id_set_wait={id_set_wait}")

method_ids_size, method_ids_off = struct.unpack('<II', dex[0x50:0x58])
for i in range(method_ids_size):
    c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + i*8 : method_ids_off + (i+1)*8])
    if n_idx in (id_needed, id_avail, id_set_wait):
        print(f"Method #{i}: n_idx={n_idx}, c_idx={c_idx}")
