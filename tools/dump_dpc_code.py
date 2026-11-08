import zipfile
import struct

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

# Let's find "isCurtainAnimationNeeded" method definition in DisplayPowerControllerImpl
# Class defs are at offset 0x17b7d8 (class_defs_size=6934)
# Let's find DisplayPowerControllerImpl class def
class_defs_size, class_defs_off = struct.unpack('<II', dex[0x60:0x68])
type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])

def get_string(idx):
    str_off = struct.unpack('<I', dex[string_ids_off + idx*4 : string_ids_off + (idx+1)*4])[0]
    pos = str_off
    while dex[pos] & 0x80: pos += 1
    pos += 1
    end = dex.find(b'\x00', pos)
    return dex[pos:end].decode('utf-8', errors='replace')

def get_type_descriptor(idx):
    descriptor_idx = struct.unpack('<I', dex[type_ids_off + idx*4 : type_ids_off + (idx+1)*4])[0]
    return get_string(descriptor_idx)

dpc_class_def_off = -1
for i in range(class_defs_size):
    c_off = class_defs_off + i * 32
    class_idx = struct.unpack('<I', dex[c_off:c_off+4])[0]
    desc = get_type_descriptor(class_idx)
    if desc == 'Lcom/android/server/display/DisplayPowerControllerImpl;':
        print("Found DisplayPowerControllerImpl class_def at", hex(c_off))
        dpc_class_def_off = c_off
        break

if dpc_class_def_off != -1:
    # class_data_off is at c_off + 24
    class_data_off = struct.unpack('<I', dex[dpc_class_def_off+24 : dpc_class_def_off+28])[0]
    print("class_data_off:", hex(class_data_off))
