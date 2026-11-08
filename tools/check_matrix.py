"""复刻 android.opengl.Matrix 运算，检查 LensMatrices 的 photo 矩阵到底把 uv 映射成什么。"""
import math


def ident():
    return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]


def mul(a, b):
    """Column-major: 与 android Matrix.multiplyMM(result, a, b) 一致（result = a*b）。"""
    r = [0.0] * 16
    for col in range(4):
        for row in range(4):
            r[col * 4 + row] = sum(a[k * 4 + row] * b[col * 4 + k] for k in range(4))
    return r


def translate(x, y, z=0.0):
    m = ident()
    m[12], m[13], m[14] = x, y, z
    return m


def scale(x, y, z=1.0):
    m = ident()
    m[0], m[5], m[10] = x, y, z
    return m


def rotate(deg, ax, ay, az):
    m = ident()
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    l = math.sqrt(ax * ax + ay * ay + az * az)
    if l == 0:
        return m
    x, y, z = ax / l, ay / l, az / l
    nc = 1 - c
    m[0] = x * x * nc + c
    m[1] = y * x * nc + z * s
    m[2] = x * z * nc - y * s
    m[4] = x * y * nc - z * s
    m[5] = y * y * nc + c
    m[6] = y * z * nc + x * s
    m[8] = x * z * nc + y * s
    m[9] = y * z * nc - x * s
    m[10] = z * z * nc + c
    return m


def ortho(l, r, b, t, n, f):
    m = ident()
    m[0] = 2 / (r - l)
    m[5] = 2 / (t - b)
    m[10] = -2 / (f - n)
    m[12] = -(r + l) / (r - l)
    m[13] = -(t + b) / (t - b)
    m[14] = -(f + n) / (f - n)
    return m


def look_at(ex, ey, ez, cx, cy, cz, ux, uy, uz):
    fx, fy, fz = cx - ex, cy - ey, cz - ez
    fl = math.sqrt(fx * fx + fy * fy + fz * fz)
    fx, fy, fz = fx / fl, fy / fl, fz / fl
    sx, sy, sz = fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux
    sl = math.sqrt(sx * sx + sy * sy + sz * sz)
    sx, sy, sz = sx / sl, sy / sl, sz / sl
    ux2, uy2, uz2 = sy * fz - sz * fy, sz * fx - sx * fz, sx * fy - sy * fx
    m = ident()
    m[0], m[4], m[8] = sx, sy, sz
    m[1], m[5], m[9] = ux2, uy2, uz2
    m[2], m[6], m[10] = -fx, -fy, -fz
    m[12] = -(sx * ex + sy * ey + sz * ez)
    m[13] = -(ux2 * ex + uy2 * ey + uz2 * ez)
    m[14] = fx * ex + fy * ey + fz * ez
    return m


def inv(m):
    """通用 4x4 求逆（行主序推导后转列主序存放）。"""
    a = [[m[c * 4 + r] for c in range(4)] for r in range(4)]
    n = 4
    aug = [row[:] + [1.0 if i == j else 0.0 for j in range(n)] for i, row in enumerate(a)]
    for i in range(n):
        p = max(range(i, n), key=lambda r: abs(aug[r][i]))
        if abs(aug[p][i]) < 1e-12:
            raise ValueError('singular')
        aug[i], aug[p] = aug[p], aug[i]
        pv = aug[i][i]
        aug[i] = [v / pv for v in aug[i]]
        for r in range(n):
            if r != i and aug[r][i] != 0:
                f = aug[r][i]
                aug[r] = [v - f * w for v, w in zip(aug[r], aug[i])]
    out = [0.0] * 16
    for r in range(4):
        for c in range(4):
            out[c * 4 + r] = aug[r][4 + c]
    return out


def transform(m, x, y):
    return (m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13])


W, H = 1200, 2608
proj = inv(ortho(-W / 2, W / 2, -H / 2, H / 2, 1, 3))
view = inv(look_at(0, 0, 2, 0, 0, 0, 0, 1, 0))


def photo_matrix(x, y, angle, lens_scale):
    translation = inv(translate(W * x, H * y))
    origin = inv(translate(-W / 2, -H / 2))
    sc = inv(scale(W, H))
    pivot = inv(translate(0, -200))
    unpivot = inv(translate(0, 200))
    rot = inv(rotate(angle, 0, 0, 1))
    result = ident()
    for m in (sc, origin, unpivot, rot, pivot, translation, view, proj):
        result = mul(result, m)
    return result


for (xx, yy, ang, ls, tag) in [(0, 0, -360, 1.24, 'LOCK 稳态'),
                               (0, 0.335, -100, 0.22, 'AOD 稳态')]:
    M = photo_matrix(xx, yy, ang, ls)
    print('==', tag)
    for (u, v) in [(0, 0), (1, 0), (0, 1), (1, 1), (0.5, 0.5), (0.25, 0.25)]:
        tx, ty = transform(M, u * 2 - 1, v * 2 - 1)
        print('   uv(%.2f,%.2f) -> tex(%.3f,%.4f)  [y翻转后 %.3f,%.4f]' % (u, v, tx, ty, tx, 1 - ty))