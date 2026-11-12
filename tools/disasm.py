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

    def get_field(self, idx):
        if idx >= self.field_ids_size: return '', ''
        class_idx, type_idx, name_idx = struct.unpack('<HHI', self.data[self.field_ids_off + idx * 8 : self.field_ids_off + (idx + 1) * 8])
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
data = zf.read('classes.dex')
dex = Dex(data)

for cidx in range(dex.class_defs_size):
    coff = dex.class_defs_off + cidx * 32
    class_idx, _, _, _, _, _, class_data_off, _ = struct.unpack('<IIIIIIII', data[coff:coff+32])
    if class_data_off == 0: continue
    cname = dex.get_type(class_idx)
    p = class_data_off
    sfields, p = uleb128(data, p); ifields, p = uleb128(data, p)
    dmeths, p = uleb128(data, p); vmeths, p = uleb128(data, p)
    for _ in range(sfields + ifields):
        _, p = uleb128(data, p); _, p = uleb128(data, p)
    
    # direct methods
    last_midx = 0
    for _ in range(dmeths):
        midx_diff, p = uleb128(data, p)
        access, p = uleb128(data, p)
        code_off, p = uleb128(data, p)
        midx = last_midx + midx_diff
        last_midx = midx
        c, m = dex.get_method(midx)
        if m == 'aodSuperWallpaperViewAlphaAnim':
            print(f'Direct match: {c}->{m} code_off={code_off}')

    # virtual methods reset last_midx = 0!
    last_midx = 0
    for _ in range(vmeths):
        midx_diff, p = uleb128(data, p)
        access, p = uleb128(data, p)
        code_off, p = uleb128(data, p)
        midx = last_midx + midx_diff
        last_midx = midx
        c, m = dex.get_method(midx)
        if m == 'aodSuperWallpaperViewAlphaAnim':
            print(f'Virtual match: {c}->{m} code_off={code_off}')
            registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', data[code_off:code_off+16])
            print(f'registers={registers_size}, ins={ins_size}, insns_size={insns_size}')
            insns = data[code_off+16 : code_off+16+insns_size*2]
            ip = 0
            while ip < len(insns):
                op = insns[ip]
                if op == 0x1a:
                    sidx = struct.unpack('<H', insns[ip+2:ip+4])[0]
                    print(f'  {ip:4d}: const-string \'{dex.get_string(sidx)}\'')
                    ip += 4
                elif op == 0x1b:
                    sidx = struct.unpack('<I', insns[ip+2:ip+6])[0]
                    print(f'  {ip:4d}: const-string/jumbo \'{dex.get_string(sidx)}\'')
                    ip += 6
                elif op in (0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58):
                    fidx = struct.unpack('<H', insns[ip+2:ip+4])[0]
                    fc, fn = dex.get_field(fidx)
                    print(f'  {ip:4d}: iget {fn}')
                    ip += 4
                elif op in (0x6e, 0x6f, 0x70, 0x71, 0x72):
                    mid = struct.unpack('<H', insns[ip+2:ip+4])[0]
                    mc, mn = dex.get_method(mid)
                    print(f'  {ip:4d}: invoke {mc}->{mn}')
                    ip += 6
                else:
                    ip += 2
