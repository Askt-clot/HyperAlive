import zipfile

with zipfile.ZipFile('MIUIAod.apk', 'r') as z:
    dex = z.read('classes.dex')

# 提取包含 AODView 的字符串或相关信息
import re
methods = re.findall(rb'AODView[a-zA-Z0-9_$]*', dex)
print("AODView matches:", set(methods[:30]))

linkage = re.findall(rb'LinkageStyleClockView[a-zA-Z0-9_$]*', dex)
print("Linkage matches:", set(linkage[:30]))
