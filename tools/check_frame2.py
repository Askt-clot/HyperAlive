"""反推真实映射：帧的每一行最像纹理的哪一行（左右都压到同一宽度比较）。"""
import numpy as np
from PIL import Image

ROOT = r'D:\DSH\default-workspace-2\qihang_lsp\shots'
frame = Image.open(ROOT + r'\gl_frame.png').convert('RGB')
tex = Image.open(ROOT + r'\tex_debug.png').convert('RGB')

CW = 64
F = np.asarray(frame.resize((CW, 256)), dtype=np.float32)     # (256, 64, 3)
T = np.asarray(tex.resize((CW, 256)), dtype=np.float32)

print('frame vs tex 逐行最佳匹配（y_frame -> y_tex）:')
for fy in range(0, 256, 16):
    row = F[fy]
    d = np.abs(T - row[None, :, :]).mean(axis=(1, 2))
    ty = int(d.argmin())
    print('  y_frame=%.3f -> y_tex=%.3f  diff=%.1f' % (fy / 255.0, ty / 255.0, d[ty]))

print()
print('frame 各列与 tex 各列匹配（x_frame -> x_tex，用中间一行）:')
print()
# 反向验证：假设映射是 x 方向也缩放，检查帧中心行的纹理覆盖
mid = 128
d = np.abs(T - F[mid][None, :, :]).mean(axis=(1, 2))
print('  中心行(frame y=0.5) 在全纹理上最相似行: %.3f' % (int(d.argmin()) / 255.0))

# 直接用「帧中心行」在纹理中滑动，看水平方向的对应
row = F[mid]
best = None
for shift in range(-64, 65, 4):
    trow = T[(mid + shift) % 256]
    diff = np.abs(trow - row).mean()
    if best is None or diff < best[0]:
        best = (diff, shift)
print('  水平方向整体最匹配的垂直偏移: shift=%d diff=%.1f' % (best[1], best[0]))

# 检查帧是否等于「纹理纵向压缩到中间 46%」：把纹理缩到 46% 高再放进帧中间比较
tex_small = np.asarray(tex.resize((CW, int(256 * 0.46))), dtype=np.float32)
band = F[int(256 * 0.27):int(256 * 0.27) + tex_small.shape[0]]
if band.shape[0] == tex_small.shape[0]:
    print('  「纹理占帧中央 46%% 高」假设的平均差: %.1f' % np.abs(band - tex_small).mean())
tex_fit = np.asarray(tex.resize((CW, 256)), dtype=np.float32)
print('  「纹理铺满整帧」假设的平均差: %.1f' % np.abs(F - tex_fit).mean())
print('  「纹理铺满且上下翻转」假设的平均差: %.1f' % np.abs(F - tex_fit[::-1]).mean())