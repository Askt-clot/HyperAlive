import zipfile
import struct

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')
type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])
method_ids_size, method_ids_off = struct.unpack('<II', dex[0x50:0x58])

def get_string(idx):
    str_off = struct.unpack('<I', dex[string_ids_off + idx*4 : string_ids_off + (idx+1)*4])[0]
    pos = str_off
    while dex[pos] & 0x80: pos += 1
    pos += 1
    end = dex.find(b'\x00', pos)
    return dex[pos:end].decode('utf-8', errors='replace')

def read_uleb128(data, pos):
    res = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        res |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    return res, pos

pos = 0xafe46a
static_fields_size, pos = read_uleb128(dex, pos)
instance_fields_size, pos = read_uleb128(dex, pos)
direct_methods_size, pos = read_uleb128(dex, pos)
virtual_methods_size, pos = read_uleb128(dex, pos)

for _ in range(static_fields_size + instance_fields_size):
    _, pos = read_uleb128(dex, pos)
    _, pos = read_uleb128(dex, pos)

def dump_methods(count, name_type):
    global pos
    method_idx = 0
    for _ in range(count):
        diff, pos = read_uleb128(dex, pos)
        access_flags, pos = read_uleb128(dex, pos)
        code_off, pos = read_uleb128(dex, pos)
        method_idx += diff
        # lookup method name
        c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + method_idx*8 : method_ids_off + (method_idx+1)*8])
        m_name = get_string(n_idx)
        if 'Curtain' in m_name or 'ScreenOff' in m_name or 'wait' in m_name.lower():
            print(f"{name_type} {m_name} (code_off={hex(code_off)})")

dump_methods(direct_methods_size, "direct")
dump_methods(virtual_methods_size, "virtual")
