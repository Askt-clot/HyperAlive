#!/usr/bin/env python3
"""Exercise the production Java transition policy without Android or a device.

The scenarios reproduce wake/doze ordering and separate wallpaper engines;
the harness compiles the real policy rather than reimplementing it in Python.
"""
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JDK = Path(r"D:\Java\jdk-21\bin")
SOURCE = ROOT / "app/src/main/java/com/qihang/aod/TransitionPolicy.java"

HARNESS = r"""
import com.qihang.aod.TransitionPolicy;

public final class TransitionPolicyRegression {
    private static int assertions;

    private static void mode(String scenario, int expected, boolean sleep,
                             boolean doze, boolean wake, boolean biometric,
                             int scrim, boolean aod, boolean full) {
        int actual = TransitionPolicy.resolveMode(sleep, doze, wake, biometric,
                                                  scrim, aod, full);
        assertions++;
        if (actual != expected) {
            throw new AssertionError(scenario + ": expected " + expected
                    + ", got " + actual);
        }
    }

    private static void resume(String scenario, boolean expected, boolean home,
                               int localMode, boolean lockHistory, long elapsed,
                               boolean animating) {
        boolean actual = TransitionPolicy.canResumeLock(home, localMode,
                                                        lockHistory, elapsed,
                                                        animating);
        assertions++;
        if (actual != expected) {
            throw new AssertionError(scenario + ": expected " + expected
                    + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        // HOME -> sleep: the old UNLOCKED scrim must not cancel the new sleep.
        mode("new sleep before scrim callback", 0, true, false, false, false, 2, true, false);
        mode("sleep display callback", 0, true, true, false, false, 0, true, false);
        // FOD is now hidden; stopping rendering does not change the power mode.
        mode("AOD after FOD hides", 0, true, true, false, false, 0, true, false);
        // Successful fingerprint authentication owns HOME immediately, even
        // before the old DOZE/AOD callbacks have caught up.
        mode("fingerprint after FOD power saving", 2, false, true, true, true, 0, true, false);
        mode("fingerprint before wake display callback", 2, false, true, false, true, 1, true, false);
        mode("unlock settled", 2, false, false, false, false, 2, true, false);

        // The user can press power again during the biometric grace interval.
        mode("new sleep after biometric", 0, true, false, true, true, 2, true, false);
        mode("full screen AOD", 1, true, true, false, false, 2, true, true);
        mode("AOD disabled", 1, true, true, false, false, 0, false, false);
        mode("display-only doze signal", 0, false, true, false, false, 2, true, false);
        mode("normal wake with late DOZE and AOD scrim", 1, false, true, true, false, 0, true, false);
        mode("normal keyguard", 1, false, false, false, false, 1, true, false);

        // Duplicate callback delivery must leave a decision unchanged.
        for (int i = 0; i < 8; i++) {
            mode("duplicate biometric " + i, 2, false, true, true, true, 0, true, false);
            mode("duplicate sleep " + i, 0, true, true, false, false, 2, true, false);
        }

        // Separate home engine has deliberately ignored all LOCK commands.
        resume("dual slot resumes from local AOD", true, true, 0, true, 400, false);
        resume("dual slot missed AOD command", true, true, 2, true, 400, false);
        resume("single slot preserves live LOCK curve", true, false, 1, true, 400, true);
        resume("single slot slow live transition", true, false, 1, true, 1200, true);
        resume("direct AOD to HOME has no lock history", false, true, 0, false, 0, false);
        resume("single direct AOD to HOME", false, false, 0, false, 0, false);
        resume("finished lock must not replay", false, true, 0, true, 1150, false);
        resume("hidden home stale animation is irrelevant", false, true, 0, true, 1200, true);
        resume("single finished lock", false, false, 1, true, 1200, false);
        resume("single local AOD cannot invent lock progress", false, false, 0, true, 400, true);
        resume("invalid history time", false, true, 0, true, -1, false);

        System.out.println("Transition policy: " + assertions + " assertions passed");
    }
}
"""


def main():
    build = ROOT / "build/offline_tests"
    build.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="transition_", dir=build) as tmp:
        work = Path(tmp)
        harness = work / "TransitionPolicyRegression.java"
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run([
            str(JDK / "javac.exe"), "-nowarn", "-encoding", "UTF-8", "-source", "8",
            "-target", "8", "-d", str(work), str(SOURCE), str(harness),
        ], check=True)
        subprocess.run([
            str(JDK / "java.exe"), "-cp", str(work), "TransitionPolicyRegression",
        ], check=True)


if __name__ == "__main__":
    main()
