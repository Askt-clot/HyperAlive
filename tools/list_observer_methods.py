import zipfile, struct

class Dex:
    def __init__(self, data):
        self.data = data
        self.string_ids_size, self.string_ids_off = struct.unpack('<II', data[56:64])
        self.type_ids_size, self.type_ids_off = struct.unpack('<II', data[64:72])
        self.proto_ids_size, self.proto_ids_off = struct.unpack('<II', data[72:80])
        self.field_ids_size, self.field_ids_off = struct.unpack('<II', data[80:88])
        self.method_ids_size, self.method_ids_off = struct.unpack('<II', data[88:96])
        self.class_defs_size, self.class_defs_off = struct.unpack('<II', data[96:104])

    def get_string(self, idx):
        if idx >= self.string_ids_size: return ''
        off = struct.unpack('<I', self.data[self.string_ids_off + idx * 4: self.string_ids_off + (idx + 1) * 4])[0]
        p = off
        while (self.data[p] & 0x80): p += 1
        p += 1
        end = self.data.find(b'\x00', p)
        return self.data[p:end].decode('utf-8', errors='replace')

    def get_type(self, idx):
        if idx >= self.type_ids_size: return ''
        str_idx = struct.unpack('<I', self.data[self.type_ids_off + idx * 4: self.type_ids_off + (idx + 1) * 4])[0]
        return self.get_string(str_idx)

    def get_method(self, idx):
        if idx >= self.method_ids_size: return '', ''
        class_idx, proto_idx, name_idx = struct.unpack('<HHI', self.data[self.method_ids_off + idx * 8 : self.method_ids_off + (idx + 1) * 8])
        return self.get_type(class_idx), self.get_string(name_idx)

def uleb128(data, p):
    val = 0; shift = 0
    while True:
        b = data[p]; p += 1
        val |= (b & 0x7f) << shift
        if not (b & 0x80): break
        shift += 7
    return val, p

zf = zipfile.ZipFile('D:/ButtonHyperAlive/HyperAlive-debug/tools/MiuiSystemUI.apk')
data = zf.read('classes3.dex')
dex = Dex(data)

for cidx in range(dex.class_defs_size):
    coff = dex.class_defs_off + cidx * 32
    class_idx, _, _, _, _, _, class_data_off, _ = struct.unpack('<IIIIIIII', data[coff:coff+32])
    if class_data_off == 0: continue
    cname = dex.get_type(class_idx)
    if 'KeyguardCommonSettingObserver;' in cname and '$' not in cname:
        p = class_data_off
        sfields, p = uleb128(data, p); ifields, p = uleb128(data, p)
        dmeths, p = uleb128(data, p); vmeths, p = uleb128(data, p)
        for _ in range(sfields + ifields):
            _, p = uleb128(data, p); _, p = uleb128(data, p)
        
        last_midx = 0
        for _ in range(dmeths):
            midx_diff, p = uleb128(data, p); access, p = uleb128(data, p); code_off, p = uleb128(data, p)
            midx = last_midx + midx_diff; last_midx = midx
            c, m = dex.get_method(midx)
            print(f'direct: {m}')

        last_midx = 0
        for _ in range(vmeths):
            midx_diff, p = uleb128(data, p); access, p = uleb128(data, p); code_off, p = uleb128(data, p)
            midx = last_midx + midx_diff; last_midx = midx
            c, m = dex.get_method(midx)
            print(f'virtual: {m}')
