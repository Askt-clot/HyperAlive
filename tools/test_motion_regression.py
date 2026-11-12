#!/usr/bin/env python3
"""Run the real Motion/LensMatrices code with channel-level assertions.

Android Matrix is stubbed only to make the JVM harness possible. No assertion
uses matrix output, so this is not a graphics or on-device rendering test.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(r"D:\Java\jdk-21\bin")
JAVA = ROOT / "app/src/main/java/com/qihang/aod"

MATRIX = r"""
package android.opengl;
public final class Matrix {
    public static void setIdentityM(float[] a, int o) {
        for (int i=0; i<16; i++) a[o+i] = i%5 == 0 ? 1f : 0f;
    }
    public static void orthoM(float[] a,int o,float l,float r,float b,float t,float n,float f) {setIdentityM(a,o);}
    public static void setLookAtM(float[] a,int o,float ex,float ey,float ez,float cx,float cy,float cz,float ux,float uy,float uz) {setIdentityM(a,o);}
    public static boolean invertM(float[] d,int x,float[] s,int y) {System.arraycopy(s,y,d,x,16);return true;}
    public static void multiplyMM(float[] d,int x,float[] a,int y,float[] b,int z) {setIdentityM(d,x);}
    public static void translateM(float[] a,int o,float x,float y,float z) {}
    public static void scaleM(float[] a,int o,float x,float y,float z) {}
    public static void setRotateM(float[] a,int o,float angle,float x,float y,float z) {setIdentityM(a,o);}
}
"""

HARNESS = r"""
package com.qihang.aod;
import java.lang.reflect.Field;

public final class MotionRegression {
    private static int assertions;
    private static final long MS = 1000000L;

    private static void check(boolean pass, String message) {
        assertions++;
        if (!pass) throw new AssertionError(message);
    }
    private static void near(float a, float b, String message) {
        check(Float.isFinite(a) && Float.isFinite(b) && Math.abs(a-b)<0.00002f,
                message+" actual="+a+" expected="+b);
    }
    private static void finite(Motion m, String label) {
        for (int i=0;i<m.values.length;i++) check(Float.isFinite(m.values[i]),label+" lens channel "+i+"="+m.values[i]);
        for (int i=0;i<m.frameValues.length;i++) check(Float.isFinite(m.frameValues[i]),label+" frame channel "+i+"="+m.frameValues[i]);
    }
    private static long start(Motion m) throws Exception {
        Field f=Motion.class.getDeclaredField("startNanos");
        f.setAccessible(true);
        return f.getLong(m);
    }
    private static Motion aod(int style) {
        Motion m=new Motion(1200,2608);
        m.setAodStyle(style);
        m.applyState(Motion.AOD,System.nanoTime());
        return m;
    }

    public static void main(String[] args) throws Exception {
        for (int style=0;style<3;style++) {
            // A hidden desktop engine reconstructs the lock engine's progress.
            for (long elapsed:new long[]{0,100,400,1100}) {
                Motion m=aod(style);
                long now=System.nanoTime();
                m.seekTransition(Motion.AOD,Motion.LOCK,elapsed,now);
                finite(m,"seek style="+style+" elapsed="+elapsed);
                float scale=m.scale();
                m.continueTo(Motion.HOME,now);
                m.advance(now);
                near(m.scale(),scale,"seek/continue must not jump");
                finite(m,"continued style="+style);
            }
        }

        Motion reference=aod(0);
        reference.setMode(Motion.LOCK,true,System.nanoTime());
        long originalStart=start(reference);
        long handover=originalStart+400*MS;
        reference.advance(handover);
        float handoverScale=reference.scale();
        check(handoverScale>Motion.AOD_SCALE,"lock has already visibly expanded");

        Motion desktop=aod(0);
        desktop.seekTransition(Motion.AOD,Motion.LOCK,400,handover);
        near(desktop.scale(),handoverScale,"hidden desktop matches visible lock");
        desktop.continueTo(Motion.HOME,handover);
        reference.continueTo(Motion.HOME,handover);
        near(reference.scale(),handoverScale,"live lock handover is continuous");
        check(reference.lastTransition()==0,"handover must preserve original tr0");
        check(reference.remainingMs(handover)==750,"handover retains 750ms, not a new full animation");
        float prev=handoverScale;
        for (long t=400;t<=1150;t+=50) {
            long now=originalStart+t*MS;
            reference.advance(now);
            desktop.advance(now);
            near(desktop.scale(),reference.scale(),"same expansion trajectory at "+t);
            check(reference.scale()>=prev,"expansion never collapses or restarts");
            finite(desktop,"desktop continuation at "+t);
            prev=reference.scale();
        }
        check(!desktop.animating(),"continuation finishes at the original deadline");
        near(desktop.scale(),desktop.expandedScale,"continuation finishes full screen");

        // Simulate a long interval without any render frames (FOD power saving).
        Motion sleeping=aod(0);
        sleeping.advance(System.nanoTime()-15000*MS);
        sleeping.setMode(Motion.HOME,true,System.nanoTime());
        long wakeStart=start(sleeping);
        sleeping.advance(wakeStart);
        near(sleeping.scale(),Motion.AOD_SCALE,"direct wake starts from the sleeping circle");
        check(sleeping.lastTransition()==2 && sleeping.animating(),"direct wake plays tr2");
        sleeping.advance(wakeStart+100*MS);
        check(sleeping.scale()>Motion.AOD_SCALE,"direct wake starts expanding immediately");
        long unchangedStart=start(sleeping);
        sleeping.setMode(Motion.HOME,true,wakeStart+100*MS);
        check(start(sleeping)==unchangedStart,"duplicate HOME never restarts expansion");
        sleeping.advance(wakeStart+1000*MS);
        near(sleeping.scale(),sleeping.expandedScale,"direct wake completes in 1000ms");
        finite(sleeping,"direct wake complete");

        // Power is pressed during expansion, then a fingerprint reverses collapse.
        Motion interrupted=aod(0);
        interrupted.setMode(Motion.HOME,true,System.nanoTime());
        interrupted.advance(start(interrupted)+200*MS);
        interrupted.setMode(Motion.AOD,true,System.nanoTime());
        interrupted.advance(start(interrupted)+180*MS);
        float reverseScale=interrupted.scale();
        interrupted.setMode(Motion.HOME,true,System.nanoTime());
        long reverseStart=start(interrupted);
        interrupted.advance(reverseStart);
        near(interrupted.scale(),reverseScale,"interrupted collapse resumes at current size");
        interrupted.advance(reverseStart+1000*MS);
        near(interrupted.scale(),interrupted.expandedScale,"reverse transition reaches HOME");
        finite(interrupted,"interrupted transition");
        System.out.println("Motion regression: "+assertions+" assertions passed");
    }
}
"""


def main():
    build = ROOT / "build/offline_tests"
    build.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="motion_", dir=build) as tmp:
        work = Path(tmp)
        sources = {
            "android/opengl/Matrix.java": MATRIX,
            "android/util/Log.java": "package android.util; public final class Log {public static int i(String t,String m){return 0;}}",
            "com/qihang/aod/AodStyleSettings.java": "package com.qihang.aod; final class AodStyleSettings {static final int STYLE_SET_SAIL=0, STYLE_LEAVE_LIGHT=1, STYLE_MOON=2;}",
            "com/qihang/aod/MotionRegression.java": HARNESS,
        }
        stub_paths = []
        for relative, text in sources.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            stub_paths.append(str(path))
        subprocess.run([
            str(JDK / "javac.exe"), "-nowarn", "-encoding", "UTF-8", "-source", "8",
            "-target", "8", "-d", str(work), str(JAVA / "Motion.java"),
            str(JAVA / "LensMatrices.java"), *stub_paths,
        ], check=True)
        subprocess.run([
            str(JDK / "java.exe"), "-cp", str(work), "com.qihang.aod.MotionRegression",
        ], check=True)


if __name__ == "__main__":
    main()
