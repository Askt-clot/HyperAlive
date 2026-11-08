#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HyperAlive LSPosed 模块 — 免 Gradle 高速构建脚本
流程：javac 桩 -> aapt2 link -> javac 模块 -> d8 -> 追加 dex/assets -> zipalign -> apksigner
只依赖本地 Android SDK 与 JDK，不需要 Gradle 与外网环境。
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
APP_MAIN = os.path.join(ROOT, 'app', 'src', 'main')
SRC_DIR = os.path.join(APP_MAIN, 'java')
RES_DIR = os.path.join(APP_MAIN, 'res')
ASSETS_DIR = os.path.join(APP_MAIN, 'assets')
MANIFEST = os.path.join(APP_MAIN, 'AndroidManifest.xml')
STUB_DIR = os.path.join(ROOT, 'stub')

SDK = r'C:\Android\Sdk'
BUILD_TOOLS = os.path.join(SDK, 'build-tools', '37.0.0')
ANDROID_JAR = os.path.join(SDK, 'platforms', 'android-37.0', 'android.jar')
JAVAC = r'javac'

AAPT2 = os.path.join(BUILD_TOOLS, 'aapt2.exe')
D8 = os.path.join(BUILD_TOOLS, 'd8.bat')
ZIPALIGN = os.path.join(BUILD_TOOLS, 'zipalign.exe')
APKSIGNER = os.path.join(BUILD_TOOLS, 'apksigner.bat')

KS_QIHANG = os.path.join(ROOT, 'qihang.keystore')
KS_DEBUG = os.path.join(ROOT, 'debug.keystore')
if os.path.exists(KS_QIHANG):
    KS = KS_QIHANG
    KS_ALIAS = 'qihang'
    KS_PASS = 'pass:android'
else:
    KS = KS_DEBUG
    KS_ALIAS = 'androiddebugkey'
    KS_PASS = 'pass:android'
    if not os.path.exists(KS_DEBUG):
        print('[+] 未检测到签名证书，自动生成本地调试证书 debug.keystore...')
        try:
            subprocess.run(['keytool', '-genkeypair', '-v', '-keystore', KS_DEBUG,
                           '-alias', KS_ALIAS, '-keyalg', 'RSA', '-keysize', '2048',
                           '-validity', '10000', '-storepass', 'android', '-keypass', 'android',
                           '-dname', 'CN=Android Debug,O=Android,C=US'], check=True)
        except Exception as _e:
            print('[!] 自动生成证书失败，请确保 keytool 在环境变量 PATH 中:', _e)
OUT = os.path.join(ROOT, 'build', 'cli_out')
FINAL = os.path.join(ROOT, 'HyperAlive.apk')
INIT_CLASS = 'com.qihang.aod.XposedInit'
MIN_API = 29


def run(cmd):
    print('>>', ' '.join(cmd) if isinstance(cmd, list) else cmd)
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.stdout.strip():
        print(r.stdout.strip()[:4000])
    if r.returncode != 0:
        print('STDERR:', r.stderr.strip()[:8000])
        raise SystemExit('命令失败 (%d): %s' % (r.returncode, cmd[0]))
    return r.stdout


def java_files(d):
    return sorted(glob.glob(os.path.join(d, '**', '*.java'), recursive=True))


def main():
    os.chdir(ROOT)
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    stub_classes = os.path.join(OUT, 'stub_classes')
    module_classes = os.path.join(OUT, 'module_classes')

    stubs = java_files(STUB_DIR)
    print('Xposed 桩: %d 个源文件' % len(stubs))
    run([JAVAC, '-nowarn', '-encoding', 'UTF-8', '-cp', ANDROID_JAR, '-d', stub_classes] + stubs)

    # aapt2 link 先跑：除基础 APK 外还生成 R.java（javac 需要）
    base_apk = os.path.join(OUT, 'base.apk')
    r_dir = os.path.join(OUT, 'r')
    os.makedirs(r_dir)
    link = [AAPT2, 'link', '-o', base_apk, '-I', ANDROID_JAR,
            '--min-sdk-version', str(MIN_API), '--target-sdk-version', '35',
            '--java', r_dir,
            '--manifest', MANIFEST]
    if glob.glob(os.path.join(RES_DIR, '**', '*'), recursive=True):
        flat = os.path.join(OUT, 'res.zip')
        run([AAPT2, 'compile', '--dir', RES_DIR, '-o', flat])
        link.append(flat)
    run(link)

    src = java_files(SRC_DIR) + java_files(r_dir)
    print('模块源码: %d 个源文件' % len(src))
    run([JAVAC, '-nowarn', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-cp', stub_classes + os.pathsep + ANDROID_JAR, '-d', module_classes] + src)

    cls = glob.glob(os.path.join(module_classes, '**', '*.class'), recursive=True)
    dex_out = os.path.join(OUT, 'dex')
    os.makedirs(dex_out)
    argfile = os.path.join(OUT, 'd8_args.txt')
    with open(argfile, 'w', encoding='utf-8') as f:
        for c in cls:
            f.write(c.replace('\\', '/') + '\n')
    run([D8, '--min-api', str(MIN_API), '--lib', ANDROID_JAR,
         '--classpath', stub_classes, '--output', dex_out, '@' + argfile])
    dex_path = os.path.join(dex_out, 'classes.dex')
    print('classes.dex = %d bytes' % os.path.getsize(dex_path))

    init_txt = os.path.join(OUT, 'xposed_init')
    with open(init_txt, 'w', encoding='utf-8', newline='\n') as f:
        f.write(INIT_CLASS + '\n')

    staged = os.path.join(OUT, 'stage.apk')
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

    aligned = os.path.join(OUT, 'aligned.apk')
    run([ZIPALIGN, '-p', '-f', '4', staged, aligned])
    if os.path.exists(FINAL):
        os.remove(FINAL)
    run([APKSIGNER, 'sign', '--ks', KS, '--ks-pass', KS_PASS, '--key-pass', KS_PASS,
         '--ks-key-alias', KS_ALIAS, '--min-sdk-version', str(MIN_API), '--out', FINAL, aligned])

    size = os.path.getsize(FINAL)
    print()
    print('构建完成: %s (%d bytes)' % (FINAL, size))
    with zipfile.ZipFile(FINAL) as z:
        dex = z.read('classes.dex')
    for probe in (b'XposedInit', b'updateScrims', b'checkPermission'):
        print('  dex 探测 %-16s : %s' % (probe.decode(), probe in dex))
    print(run([APKSIGNER, 'verify', '--print-certs', FINAL]).strip().splitlines()[-1])


if __name__ == '__main__':
    main()
