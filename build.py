#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HyperAlive LSPosed 模块 — 免 Gradle 高速构建脚本
流程：javac 桩 -> aapt2 link -> javac 模块 -> d8 -> 追加 dex/assets -> zipalign -> apksigner (临时文件签名+校验后原子替换)
只依赖本地 Android SDK 与 JDK（通过参数、ANDROID_SDK_ROOT/ANDROID_HOME、JAVA_HOME/PATH 配置），不需要 Gradle 与外网环境。
"""
import argparse
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
REAL_ROOT = os.path.realpath(ROOT)
ALLOWED_BUILD_ROOTS = (
    os.path.realpath(os.path.join(REAL_ROOT, 'build')),
    os.path.realpath(os.path.join(REAL_ROOT, 'artifacts')),
)

APP_MAIN = os.path.join(ROOT, 'app', 'src', 'main')
SRC_DIR = os.path.join(APP_MAIN, 'java')
RES_DIR = os.path.join(APP_MAIN, 'res')
ASSETS_DIR = os.path.join(APP_MAIN, 'assets')
MANIFEST = os.path.join(APP_MAIN, 'AndroidManifest.xml')
STUB_DIR = os.path.join(ROOT, 'stub')

DEFAULT_KS = os.environ.get('HYPERALIVE_KEYSTORE', '')
DEFAULT_OUT = os.path.join(ROOT, 'build', 'cli_out')
DEFAULT_FINAL = os.environ.get(
    'HYPERALIVE_OUT_APK',
    os.path.join(ROOT, 'build', 'HyperAlive-v1.0.5.apk'),
)

# 保持对 import build 后读取或覆盖 build.KS / build.OUT / build.FINAL 的向后兼容
KS = DEFAULT_KS
OUT = DEFAULT_OUT
FINAL = DEFAULT_FINAL

INIT_CLASS = 'io.github.askt_clot.hyperalive.XposedInit'
MIN_API = 29
DEFAULT_TARGET_API = 35


def _version_key(name):
    return tuple(int(tok) for tok in re.findall(r'\d+', name))


def _resolve_tool(base_dir, stem):
    candidates = [stem + '.exe', stem + '.bat', stem] if os.name == 'nt' else [stem]
    for cand in candidates:
        p = os.path.join(base_dir, cand)
        if os.path.isfile(p):
            return p
    return os.path.join(base_dir, candidates[0])


def resolve_toolchain(sdk_override=None, javac_override=None):
    sdk = (
        sdk_override
        or os.environ.get('ANDROID_SDK_ROOT')
        or os.environ.get('ANDROID_HOME')
    )
    if not sdk or not os.path.isdir(sdk):
        raise SystemExit(
            '未配置有效 Android SDK：请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME，或传入 --sdk <path>'
        )

    bt_root = os.path.join(sdk, 'build-tools')
    bt_dirs = sorted(
        (d for d in glob.glob(os.path.join(bt_root, '*')) if os.path.isdir(d)),
        key=lambda p: _version_key(os.path.basename(p)),
        reverse=True,
    )
    if not bt_dirs:
        raise SystemExit('未在 Android SDK build-tools 中找到可用版本: %s' % bt_root)
    build_tools = bt_dirs[0]

    plat_root = os.path.join(sdk, 'platforms')
    plat_jars = sorted(
        (p for p in glob.glob(os.path.join(plat_root, 'android-*', 'android.jar')) if os.path.isfile(p)),
        key=lambda p: _version_key(os.path.basename(os.path.dirname(p))),
        reverse=True,
    )
    if not plat_jars:
        raise SystemExit('未在 Android SDK platforms 中找到 android.jar: %s' % plat_root)
    android_jar = plat_jars[0]

    javac = javac_override
    if not javac:
        java_home = os.environ.get('JAVA_HOME')
        if java_home:
            cand = os.path.join(java_home, 'bin', 'javac.exe' if os.name == 'nt' else 'javac')
            if os.path.isfile(cand):
                javac = cand
    if not javac:
        javac = shutil.which('javac')
    if not javac or not os.path.isfile(javac):
        raise SystemExit('未找到 javac：请配置 JAVA_HOME 或将 JDK bin 加入 PATH，或传入 --javac <path>')

    tools = {
        'sdk': sdk,
        'build_tools': build_tools,
        'android_jar': android_jar,
        'javac': javac,
        'aapt2': _resolve_tool(build_tools, 'aapt2'),
        'd8': _resolve_tool(build_tools, 'd8'),
        'zipalign': _resolve_tool(build_tools, 'zipalign'),
        'apksigner': _resolve_tool(build_tools, 'apksigner'),
    }
    for key in ('aapt2', 'd8', 'zipalign', 'apksigner'):
        if not os.path.isfile(tools[key]):
            raise SystemExit('Android build-tools 缺少工具 %s: %s' % (key, tools[key]))
    return tools


def format_cmd_for_log(cmd):
    if not isinstance(cmd, list):
        return str(cmd)
    redacted = []
    skip_next = False
    for tok in cmd:
        if skip_next:
            redacted.append('<REDACTED>')
            skip_next = False
        elif tok in ('--ks-pass', '--key-pass'):
            redacted.append(tok)
            skip_next = True
        else:
            redacted.append(tok)
    return ' '.join(redacted)


def run(cmd):
    print('>>', format_cmd_for_log(cmd))
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.stdout.strip():
        print(r.stdout.strip()[:4000])
    if r.returncode != 0:
        print('STDERR:', r.stderr.strip()[:8000])
        raise SystemExit('命令失败 (%d): %s' % (r.returncode, cmd[0]))
    return r.stdout


def java_files(d):
    return sorted(glob.glob(os.path.join(d, '**', '*.java'), recursive=True))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _has_symlink_or_reparse_in_chain(path):
    cur = os.path.abspath(path)
    visited = set()
    while cur and cur not in visited:
        visited.add(cur)
        if os.path.lexists(cur):
            if os.path.islink(cur):
                return True
            try:
                st = os.lstat(cur)
                if getattr(st, 'st_file_attributes', 0) & 0x400:
                    return True
            except OSError:
                pass
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent
    return False


def _is_strictly_under_any(target_real, allowed_roots):
    target_norm = os.path.normcase(os.path.normpath(target_real))
    for allowed in allowed_roots:
        allowed_norm = os.path.normcase(os.path.normpath(allowed))
        try:
            common = os.path.commonpath([allowed_norm, target_norm])
        except ValueError:
            continue
        if common == allowed_norm and target_norm != allowed_norm:
            return True
    return False


def validate_build_paths(out_dir, final_apk):
    """在任何目录清理或文件写入前严格校验 OUT 与输出 APK 路径范围，拒绝逃逸、符号链接、目录冲突及 cli_out 重叠。"""
    if not out_dir or not str(out_dir).strip():
        raise ValueError('OUT 临时构建目录不能为空')
    if not final_apk or not str(final_apk).strip():
        raise ValueError('输出 APK 路径不能为空')

    abs_out = os.path.abspath(out_dir)
    abs_final = os.path.abspath(final_apk)

    if _has_symlink_or_reparse_in_chain(abs_out):
        raise ValueError('拒绝包含符号链接或重解析点的 OUT 路径: %s' % abs_out)
    if _has_symlink_or_reparse_in_chain(abs_final):
        raise ValueError('拒绝包含符号链接或重解析点的输出 APK 路径: %s' % abs_final)

    real_out = os.path.realpath(abs_out)
    real_final = os.path.realpath(abs_final)

    if not _is_strictly_under_any(real_out, ALLOWED_BUILD_ROOTS):
        raise ValueError(
            '安全拒绝：OUT 目录 (%s) 必须位于工程专用构建目录 (%s) 的子目录内'
            % (real_out, ', '.join(ALLOWED_BUILD_ROOTS))
        )

    if not _is_strictly_under_any(real_final, ALLOWED_BUILD_ROOTS):
        raise ValueError(
            '安全拒绝：输出 APK (%s) 必须位于工程专用构建目录 (%s) 内，严禁写入基线/源码/外部目录'
            % (real_final, ', '.join(ALLOWED_BUILD_ROOTS))
        )

    if not real_final.lower().endswith('.apk'):
        raise ValueError('安全拒绝：输出 APK 路径必须以 .apk 结尾: %s' % real_final)

    if os.path.isdir(real_final):
        raise ValueError('安全拒绝：输出 APK 路径不能是已存在的目录: %s' % real_final)

    out_norm = os.path.normcase(os.path.normpath(real_out))
    final_norm = os.path.normcase(os.path.normpath(real_final))
    try:
        common_out_final = os.path.commonpath([out_norm, final_norm])
    except ValueError:
        common_out_final = ''
    if common_out_final == out_norm:
        raise ValueError('安全拒绝：输出 APK (%s) 不能位于将被清理的 OUT 临时目录 (%s) 内' % (real_final, real_out))

    return real_out, real_final


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description='HyperAlive 免 Gradle 构建脚本')
    parser.add_argument('--sdk', default=None, help='Android SDK 路径（默认读取 ANDROID_SDK_ROOT / ANDROID_HOME）')
    parser.add_argument('--javac', default=None, help='javac 路径（默认读取 JAVA_HOME / PATH）')
    parser.add_argument('--keystore', default=None, help='外部签名 keystore 路径（或设置 HYPERALIVE_KEYSTORE）')
    parser.add_argument('--out', '--out-apk', dest='out', default=None, help='输出 APK 路径（仅限 build/ 或 artifacts/ 下）')
    parser.add_argument('--ks-alias', default=os.environ.get('HYPERALIVE_KS_ALIAS'))
    parser.add_argument('--ks-pass', default=os.environ.get('HYPERALIVE_KS_PASS'))
    parser.add_argument('--key-pass', default=os.environ.get('HYPERALIVE_KEY_PASS'))
    return parser.parse_args([] if argv is None else argv)


def _normalize_apksigner_pass(raw):
    if raw is None:
        return None
    if raw.startswith(('pass:', 'env:', 'file:', 'stdin')):
        return raw
    return 'pass:' + raw


def main(argv=None):
    args = parse_args(argv)
    tools = resolve_toolchain(sdk_override=args.sdk, javac_override=args.javac)

    ks_raw = args.keystore if args.keystore is not None else KS
    if not ks_raw:
        raise SystemExit('未配置签名 keystore：请传入 --keystore <path> 或设置 HYPERALIVE_KEYSTORE')
    ks_path = os.path.abspath(ks_raw)
    if not os.path.isfile(ks_path):
        raise SystemExit('未找到签名文件: %s' % ks_path)

    ks_alias = args.ks_alias
    ks_pass = _normalize_apksigner_pass(args.ks_pass)
    key_pass = _normalize_apksigner_pass(args.key_pass) or ks_pass
    if not ks_alias or not ks_pass:
        raise SystemExit('未配置签名凭证：请传入 --ks-alias / --ks-pass（或设置 HYPERALIVE_KS_ALIAS / HYPERALIVE_KS_PASS）')

    raw_out_dir = OUT
    raw_final_apk = args.out if args.out is not None else FINAL
    out_dir, final_apk = validate_build_paths(raw_out_dir, raw_final_apk)

    os.chdir(ROOT)
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    os.makedirs(os.path.dirname(final_apk), exist_ok=True)
    stub_classes = os.path.join(out_dir, 'stub_classes')
    module_classes = os.path.join(out_dir, 'module_classes')

    stubs = java_files(STUB_DIR)
    print('Xposed 桩: %d 个源文件' % len(stubs))
    run([tools['javac'], '-nowarn', '-encoding', 'UTF-8', '-cp', tools['android_jar'], '-d', stub_classes] + stubs)

    base_apk = os.path.join(out_dir, 'base.apk')
    r_dir = os.path.join(out_dir, 'r')
    os.makedirs(r_dir)
    link = [tools['aapt2'], 'link', '-o', base_apk, '-I', tools['android_jar'],
            '--min-sdk-version', str(MIN_API), '--target-sdk-version', str(DEFAULT_TARGET_API),
            '--java', r_dir,
            '--manifest', MANIFEST]
    if glob.glob(os.path.join(RES_DIR, '**', '*'), recursive=True):
        flat = os.path.join(out_dir, 'res.zip')
        run([tools['aapt2'], 'compile', '--dir', RES_DIR, '-o', flat])
        link.append(flat)
    run(link)

    product_src = java_files(SRC_DIR)
    src = product_src + java_files(r_dir)
    print('模块产品源码: %d 个源文件 (含生成 R.java 编译总数: %d)' % (len(product_src), len(src)))
    run([tools['javac'], '-nowarn', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-cp', stub_classes + os.pathsep + tools['android_jar'], '-d', module_classes] + src)

    cls = glob.glob(os.path.join(module_classes, '**', '*.class'), recursive=True)
    dex_out = os.path.join(out_dir, 'dex')
    os.makedirs(dex_out)
    argfile = os.path.join(out_dir, 'd8_args.txt')
    with open(argfile, 'w', encoding='utf-8') as f:
        for c in cls:
            f.write(c.replace('\\', '/') + '\n')
    run([tools['d8'], '--min-api', str(MIN_API), '--lib', tools['android_jar'],
         '--classpath', stub_classes, '--output', dex_out, '@' + argfile])
    dex_path = os.path.join(dex_out, 'classes.dex')
    print('classes.dex = %d bytes' % os.path.getsize(dex_path))

    init_txt = os.path.join(out_dir, 'xposed_init')
    with open(init_txt, 'w', encoding='utf-8', newline='\n') as f:
        f.write(INIT_CLASS + '\n')

    staged = os.path.join(out_dir, 'stage.apk')
    with zipfile.ZipFile(base_apk) as zin, zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            zout.writestr(info, zin.read(info.filename))
        zout.write(dex_path, 'classes.dex')
        zout.write(init_txt, 'assets/xposed_init')
        n = 0
        for dirpath, _, files in os.walk(ASSETS_DIR):
            for f in files:
                p = os.path.join(dirpath, f)
                rel = 'assets/' + os.path.relpath(p, ASSETS_DIR).replace('\\', '/')
                ctype = zipfile.ZIP_STORED if f.lower().endswith(('.mp4', '.png', '.webp', '.jpg')) else zipfile.ZIP_DEFLATED
                zout.write(p, rel, compress_type=ctype)
                n += 1
        print('assets 打包 %d 个' % n)

    aligned = os.path.join(out_dir, 'aligned.apk')
    run([tools['zipalign'], '-p', '-f', '4', staged, aligned])

    signed_tmp = os.path.join(out_dir, 'signed_candidate.apk')
    run([tools['apksigner'], 'sign', '--ks', ks_path, '--ks-pass', ks_pass, '--key-pass', key_pass,
         '--ks-key-alias', ks_alias, '--min-sdk-version', str(MIN_API), '--out', signed_tmp, aligned])
    verify_out = run([tools['apksigner'], 'verify', '--print-certs', signed_tmp]).strip()
    os.replace(signed_tmp, final_apk)

    size = os.path.getsize(final_apk)
    digest = sha256_file(final_apk)
    print()
    print('构建完成: %s (%d bytes)' % (final_apk, size))
    print('SHA-256 : %s' % digest)
    with zipfile.ZipFile(final_apk) as z:
        dex = z.read('classes.dex')
    for probe in (b'XposedInit', b'updateScrims', b'checkPermission'):
        print('  dex 探测 %-16s : %s' % (probe.decode(), probe in dex))
    if verify_out:
        print(verify_out.splitlines()[-1])


if __name__ == '__main__':
    main(sys.argv[1:])
