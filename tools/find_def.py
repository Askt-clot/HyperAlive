import zipfile, struct

class Dex:
    def __init__(self, data):
        self.data = data
        self.string_ids_size, self.string_ids_off = struct.unpack('<II', data[56:64])
        self.type_ids_size, self.type_ids_off = struct.unpack('<II', data[64:72])
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

zf = zipfile.ZipFile('D:/ButtonHyperAlive/HyperAlive-debug/tools/MiuiSystemUI.apk')
for dname in ['classes.dex', 'classes2.dex', 'classes3.dex']:
    data = zf.read(dname)
    dex = Dex(data)
    for cidx in range(dex.class_defs_size):
        coff = dex.class_defs_off + cidx * 32
        class_idx = struct.unpack('<I', data[coff:coff+4])[0]
        cname = dex.get_type(class_idx)
        if 'KeyguardCommonSettingObserver;' in cname:
            print(f'{dname} defines {cname}')
