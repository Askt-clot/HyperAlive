import zipfile
import struct

z = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar')
dex = z.read('classes.dex')

target_offset = 0x3aba50

# Scan backwards from target_offset to find code_item
for pos in range(target_offset, target_offset - 400, -2):
    # code_item has insns_size at offset +12
    insns_size = struct.unpack('<I', dex[pos+12:pos+16])[0]
    insns_byte_len = insns_size * 2
    if pos + 16 <= target_offset < pos + 16 + insns_byte_len:
        print(f"Found code_item at {hex(pos)}, insns_size={insns_size}")
        code_item_off = pos
        break

# Now search method_id / class_data that points to this code_item
# In class_data: direct_methods and virtual_methods have code_off as uleb128!
print("Code item offset:", hex(code_item_off))

# Let's search class_defs for classes matching DisplayPowerControllerImpl
# class_defs offset is at 0x64
class_defs_size, class_defs_off = struct.unpack('<II', dex[0x60:0x68])
print(f"class_defs_size={class_defs_size}, off={hex(class_defs_off)}")
