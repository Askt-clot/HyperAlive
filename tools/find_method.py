import zipfile

dex = zipfile.ZipFile('qihang_lsp/tools/miui-services.jar').read('classes.dex')

target_str = b'wait screen off animator: '
str_pos = dex.find(target_str)
print("String at:", hex(str_pos))

# Let's see what class contains this string.
# In DEX, string table IDs can be found, or we can look near the string in dex.
# Or better: write a small Java tool using dexmaker / smali or reflection to find out!
