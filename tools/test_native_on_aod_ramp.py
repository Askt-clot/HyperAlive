#!/usr/bin/env python3
"""Portable JVM adversarial harness for NativeOnAodRampCoordinator + SystemServerHook.

Compiles the real production hook classes alongside external JVM shims under
tools/native_on_aod_ramp/ in a temporary directory under build/offline_tests
so no test or Android server shim classes are ever compiled into APKs.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP_JAVA = ROOT / "app/src/main/java"
HARNESS_ROOT = ROOT / "tools/native_on_aod_ramp"


def _resolve_jdk_tool(name: str) -> str:
    exe = f"{name}.exe" if os.name == "nt" else name
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = Path(java_home) / "bin" / exe
        if cand.is_file():
            return str(cand)
    found = shutil.which(name)
    if found:
        return found
    raise SystemExit(f"Missing JDK tool '{name}': configure JAVA_HOME or PATH")


def main() -> None:
    javac = _resolve_jdk_tool("javac")
    java = _resolve_jdk_tool("java")
    build = ROOT / "build/offline_tests"
    build.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="native_ramp_", dir=build) as tmp:
        out = Path(tmp) / "classes"
        out.mkdir(parents=True, exist_ok=True)
        srcs = [str(p) for p in sorted((HARNESS_ROOT / "shims").rglob("*.java"))]
        srcs.extend([
            str(APP_JAVA / "com/hyperalive/aod/hook/HookUtils.java"),
            str(APP_JAVA / "com/hyperalive/aod/hook/SystemServerHook.java"),
            str(APP_JAVA / "com/hyperalive/aod/hook/NativeOnAodRampCoordinator.java"),
            str(HARNESS_ROOT / "com/hyperalive/aod/hook/NativeOnAodRampTest.java"),
        ])
        subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-d", str(out)] + srcs, check=True)
        subprocess.run([java, "-cp", str(out), "com.hyperalive.aod.hook.NativeOnAodRampTest"], check=True)


if __name__ == "__main__":
    main()
