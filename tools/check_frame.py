"""对比 GL 输出帧与上传的纹理，验证 uv 映射到底是不是「整屏 = 整图（上下翻转）」。"""
import numpy as np
from PIL import Image

ROOT = r'D:\DSH\default-workspace-2\qihang_lsp\shots'
frame = np.asarray(Image.open(ROOT + r'\gl_frame.png').convert('RGB'), dtype=np.float32)
tex = np.asarray(Image.open(ROOT + r'\tex_debug.png').convert('RGB'), dtype=np.float32)
H, W, _ = frame.shape
th, tw, _ = tex.shape
print('frame', W, 'x', H, ' tex', tw, 'x', th)

ys = np.linspace(0, H - 1, 40).astype(int)
xs = np.linspace(0, W - 1, 24).astype(int)
diff_full = []
diff_center = []
for y in ys:
    for x in xs:
        # 期望：frame(x,y) == tex(x/W, 1 - y/H)（shader 里 y 做了翻转）
        tx = min(tw - 1, int(x / W * tw))
        ty = min(th - 1, int((1 - y / H) * th))
        d = np.abs(frame[y, x] - tex[ty, tx]).mean()
        diff_full.append(d)
        if 0.2 < x / W < 0.8 and 0.2 < y / H < 0.8:
            diff_center.append(d)
print('整屏平均绝对差: %.1f   中心区平均绝对差: %.1f' % (np.mean(diff_full), np.mean(diff_center)))

# 反推：frame 的哪一块对应 tex 的整幅？用行/列方差找内容边界
row_std = frame.std(axis=(1, 2))
col_std = frame.std(axis=(0, 2))
nz_rows = np.where(row_std > 3)[0]
nz_cols = np.where(col_std > 3)[0]
print('有内容的行范围: %d..%d (%.2f..%.2f)' % (nz_rows[0], nz_rows[-1], nz_rows[0] / H, nz_rows[-1] / H)
      if len(nz_rows) else '全平')
print('有内容的列范围: %d..%d (%.2f..%.2f)' % (nz_cols[0], nz_cols[-1], nz_cols[0] / W, nz_cols[-1] / W)
      if len(nz_cols) else '全平')

# 直接按行统计：找出「与纹理同一行最相似」的 y 偏移
best = []
for y in ys:
    row = frame[y]
    scores = []
    for ty in range(0, th, max(1, th // 60)):
        ref = np.asarray(Image.fromarray(tex[ty].astype(np.uint8)).resize((W, 1)))[0].astype(np.float32)
        scores.append((np.abs(row - ref).mean(), ty))
    scores.sort()
    best.append((y, scores[0][1], round(scores[0][0], 1)))
print('帧行 -> 纹理行 最佳匹配（前 12 个）:')
for b in best[:12]:
    print('   y=%4d (%.2f) -> texRow=%4d (%.2f) diff=%.1f' % (b[0], b[0] / H, b[1], b[1] / th, b[2]))