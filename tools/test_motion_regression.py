#!/usr/bin/env python3
"""Run the real Motion/LensMatrices code with channel-level assertions.

Android Matrix is stubbed only to make the JVM harness possible. No assertion
uses matrix output, so this is not a graphics or on-device rendering test.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/askt_clot/hyperalive"


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
package io.github.askt_clot.hyperalive;
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
    private static float[] frameTo(Motion m) throws Exception {
        Field f=Motion.class.getDeclaredField("frameTo");
        f.setAccessible(true);
        return (float[]) f.get(m);
    }
    private static Motion aod(int style) {
        Motion m=new Motion(1200,2608);
        m.setAodStyle(style);
        m.applyState(Motion.AOD,System.nanoTime());
        return m;
    }

    public static void main(String[] args) throws Exception {
        long sailStart = 20_000 * MS;
        Motion splitSail = new Motion(1200, 2608);
        splitSail.setIndependentSailSurfaces(true);
        splitSail.applyState(Motion.AOD, sailStart);
        splitSail.setMode(Motion.HOME, true, sailStart);
        splitSail.advance(sailStart + 150 * MS);
        near(splitSail.homeRatio(), .5f, "split Sail mixes the two surfaces for 300 ms");
        splitSail.advance(sailStart + 300 * MS);
        near(splitSail.homeRatio(), 1f, "desktop owns the circle at 300 ms");
        near(splitSail.ratio[Motion.LOCK], 0f, "direct unlock does not include a lockscreen layer");
        near(splitSail.blur(), 16f, "the fading AOD lens keeps the OpenAlive desktop-end blur");
        check(splitSail.scale() < splitSail.expandedScale, "the desktop circle continues opening after the handoff");
        Motion sharedSail = new Motion(1200, 2608);
        sharedSail.applyState(Motion.AOD, sailStart);
        sharedSail.setMode(Motion.HOME, true, sailStart);
        sharedSail.advance(sailStart + 300 * MS);
        check(sharedSail.homeRatio() < 1f, "shared wallpapers continue on their existing timeline");

        Motion interruptedSail = new Motion(1200, 2608);
        interruptedSail.setIndependentSailSurfaces(true);
        interruptedSail.seekTransition(Motion.AOD, Motion.LOCK, 470, sailStart);
        float aodContribution = interruptedSail.ratio[Motion.AOD];
        float lockContribution = interruptedSail.ratio[Motion.LOCK];
        check(aodContribution > 0f && lockContribution > 0f, "seek restores the actual opening weights");
        interruptedSail.continueTo(Motion.HOME, sailStart);
        interruptedSail.advance(sailStart);
        near(interruptedSail.ratio[Motion.AOD], aodContribution, "unlock keeps the original AOD contribution");
        near(interruptedSail.ratio[Motion.LOCK], lockContribution, "unlock keeps the original lock contribution");
        interruptedSail.advance(sailStart + 150 * MS);
        near(interruptedSail.homeRatio(), .5f, "interrupted Sail opening hands off to home over 300 ms");
        interruptedSail.advance(sailStart + 300 * MS);
        near(interruptedSail.homeRatio(), 1f, "interrupted lock expansion finishes the desktop handoff");

        Motion liveLock = new Motion(1200, 2608);
        liveLock.setIndependentSailSurfaces(true);
        liveLock.applyState(Motion.AOD, sailStart);
        liveLock.setMode(Motion.LOCK, true, sailStart);
        Motion liveHome = new Motion(1200, 2608);
        liveHome.copyFrom(liveLock);
        long unlock = sailStart + 256 * MS;
        liveLock.advance(unlock);
        float originalWarp = liveLock.distortion();
        float originalScale = liveLock.scale();
        check(originalWarp > 0f, "lock still has visible refraction at the real quick-unlock timing");
        liveLock.continueTo(Motion.HOME, unlock);
        liveHome.continueTo(Motion.HOME, unlock);
        liveHome.advance(unlock);
        near(liveHome.distortion(), originalWarp, "destination preserves the first shown lens sample");
        near(liveHome.scale(), originalScale, "destination preserves the first shown opening radius");
        liveLock.advance(unlock + 120 * MS);
        liveHome.advance(unlock + 120 * MS);
        near(liveHome.distortion(), 0f, "the outgoing lock image straightens within 120 ms");
        near(liveHome.scale(), liveLock.scale(), "both engines keep the same continuing aperture");
        near(liveHome.homeRatio(), liveLock.homeRatio(), "both engines share the same fade clock");
        check(liveHome.scale() < liveHome.expandedScale, "the aperture continues after refraction ends");

        Motion lateUnlock = new Motion(1200, 2608);
        lateUnlock.setIndependentSailSurfaces(true);
        lateUnlock.seekTransition(Motion.AOD, Motion.LOCK, 1100, sailStart);
        lateUnlock.continueTo(Motion.HOME, sailStart);
        lateUnlock.advance(sailStart + 250 * MS);
        near(lateUnlock.homeRatio(), .5f, "a fully visible lockscreen keeps the ordinary 500 ms crossfade");

        StereoMotion hiddenUnlock = new StereoMotion(Motion.LOCK);
        hiddenUnlock.change(Motion.HOME, true);
        hiddenUnlock.advanceScene(0L);
        hiddenUnlock.advance(200 * MS, 1f);
        float visibleScale = hiddenUnlock.scale();
        hiddenUnlock.pause(false);
        hiddenUnlock.advanceScene(500 * MS);
        check(hiddenUnlock.active() && hiddenUnlock.scale() > visibleScale
                && hiddenUnlock.scale() < 1f, "quick return samples the remaining HOME transition");
        hiddenUnlock.advance(3000 * MS, 1f);
        near(hiddenUnlock.scale(), 1f, "returning after a hidden unlock starts in steady HOME");
        check(!hiddenUnlock.active(), "the hidden transition cannot replay when revealed");

        StereoMotion hiddenResleep = new StereoMotion(Motion.LOCK);
        hiddenResleep.change(Motion.HOME, true);
        hiddenResleep.advanceScene(0L);
        hiddenResleep.pause(false);
        hiddenResleep.finishScene();
        hiddenResleep.change(Motion.AOD, true);
        hiddenResleep.advance(100 * MS, 1f);
        near(hiddenResleep.scale(), 1f, "sleep with HOME obscured starts at the HOME scale");
        hiddenResleep.advance(200 * MS, 1f);
        near(hiddenResleep.scale(), .87f, "sleep still animates to the AOD scale");

        StereoMotion pausedPreview = new StereoMotion(Motion.LOCK);
        pausedPreview.change(Motion.HOME, true);
        pausedPreview.advance(0L, 1f);
        pausedPreview.advance(200 * MS, 1f);
        float pausedScale = pausedPreview.scale();
        pausedPreview.pause();
        pausedPreview.advance(3000 * MS, 1f);
        near(pausedPreview.scale(), pausedScale, "a hidden preview keeps its paused scene progress");

        FlymeVideoMotion clip = new FlymeVideoMotion(8_000_000L);
        clip.continuous(true);
        clip.visible(true);
        clip.advance(0L, 1f);
        check(clip.sourceTimeUs() == 16666L && clip.takeTick(), "video advances in the initial scene");
        clip.change(Motion.LOCK, true);
        clip.advance(20 * MS, 1f);
        check(clip.sourceTimeUs() == 33333L && clip.active(), "lock continues the same video");
        clip.change(Motion.HOME, true);
        clip.advance(40 * MS, 1f);
        check(clip.sourceTimeUs() == 50000L, "home continues the same video");
        clip.visible(false);
        clip.advance(15000 * MS, 1f);
        check(clip.sourceTimeUs() == 50000L && !clip.active(), "hidden time does not advance the video");
        clip.visible(true);
        clip.change(Motion.AOD, true);
        clip.advance(16000 * MS, 1f);
        check(clip.sourceTimeUs() == 66666L && clip.active(), "AOD resumes without a black reset");
        boolean sawWrap = false;
        long previousUs = clip.sourceTimeUs();
        for (int tick = 1; tick <= 510; tick++) {
            clip.advance(16000 * MS + tick * 16_666_667L, 1f);
            long nextUs = clip.sourceTimeUs();
            if (nextUs < previousUs) sawWrap = true;
            previousUs = nextUs;
        }
        check(sawWrap && clip.active(), "continuous video loops through the original endpoint");
        FlymeVideoMotion finite = new FlymeVideoMotion(8_000_000L);
        finite.visible(true);
        finite.advance(0L, 1f);
        finite.advance(8000 * MS, 1f);
        check(!finite.active(), "the finite mode stops after the original playback window");

        for (int style=0;style<=AodStyleSettings.STYLE_GALAXY;style++) {
            Motion moved=aod(style);
            float initialScale=moved.scale();
            float initialFrameScale=moved.frameScale();
            float initialRotation=moved.frameRotation();
            moved.setAodPosition(-0.12f,-0.28f);
            near(moved.translateX(),-0.12f,"moves the AOD lens horizontally");
            near(moved.frameY(),Motion.AOD_Y-0.28f,"moves the AOD mask vertically");
            near(moved.scale(),initialScale,"position keeps lens scale");
            near(moved.frameScale(),initialFrameScale,"position keeps mask scale");
            near(moved.frameRotation(),initialRotation,"position keeps mask rotation");
            long moveTime=System.nanoTime();
            moved.setMode(Motion.LOCK,true,moveTime);
            moved.advance(moveTime+2000*MS);
            near(moved.translateX(),0f,"lock lens keeps its original center");
            near(moved.frameY(),0f,"lock mask keeps its original center");
            moved.setMode(Motion.AOD,true,moveTime+2100*MS);
            moved.setAodPosition(0.2f,0.15f);
            moved.advance(moveTime+4000*MS);
            near(moved.frameX(),0.2f,"return to AOD follows the updated position");
            near(moved.translateY(),Motion.AOD_Y+0.15f,"return to AOD keeps the new vertical offset");
            Motion copied=new Motion(1080,2400);
            copied.copyFrom(moved);
            copied.seekTransition(Motion.AOD,Motion.HOME,0,moveTime+4100*MS);
            near(copied.translateX(),moved.translateX(),"handoff starts the lens from the saved position");
            near(copied.frameY(),moved.frameY(),"handoff starts the mask from the saved position");

            // A hidden desktop engine reconstructs the lock engine's progress.
            for (long elapsed:new long[]{0,100,400,1100}) {
                Motion m=aod(style);
                long now=System.nanoTime();
                m.seekTransition(Motion.AOD,Motion.LOCK,elapsed,now);
                finite(m,"seek style="+style+" elapsed="+elapsed);
                float scale=m.scale();
                float frameScale=m.frameScale();
                long frameMicros=m.frameMicros();
                m.continueTo(Motion.HOME,now);
                m.advance(now);
                near(m.scale(),scale,"seek/continue must not jump");
                near(m.frameScale(),frameScale,"mask geometry continues from the same point");
                check(m.frameMicros()==frameMicros,"mask frame continues from the same time");
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

        long fixedNow=123456789000L;
        Motion aligned=aod(0);
        aligned.setMode(Motion.LOCK,true,fixedNow);
        check(start(aligned)==fixedNow,"setMode startNanos must equal caller nowNanos exactly");
        aligned.seekTransition(Motion.AOD,Motion.LOCK,250,fixedNow);
        check(start(aligned)==fixedNow-250*MS,"seekTransition startNanos must align to caller nowNanos - elapsedMs");

        // Bug 3 regression: setAodStyle updates frame targets both while idle and during animation.
        Motion styleMotion=aod(0);
        float baselineBlur=styleMotion.frameValues[0];
        float baselineRot=styleMotion.frameValues[1];
        styleMotion.setAodStyle(AodStyleSettings.STYLE_LEAVE_LIGHT);
        check(styleMotion.aodStyle==AodStyleSettings.STYLE_LEAVE_LIGHT,"setAodStyle(LEAVE_LIGHT) updates idle aodStyle");
        styleMotion.setAodStyle(AodStyleSettings.STYLE_MOON);
        float moonBlur=styleMotion.frameValues[0];
        float moonRot=styleMotion.frameValues[1];
        check(moonBlur!=baselineBlur && moonRot!=baselineRot,"setAodStyle(MOON) updates idle frame blur & rotation immediately");
        check(frameTo(styleMotion)[0]==moonBlur && frameTo(styleMotion)[1]==moonRot,"setAodStyle(MOON) updates frameTo target");

        long animT=200000000000L;
        styleMotion.setAodStyle(AodStyleSettings.STYLE_SET_SAIL);
        styleMotion.applyState(Motion.HOME,animT);
        styleMotion.setMode(Motion.AOD,true,animT+10*MS);
        styleMotion.advance(animT+100*MS);
        check(styleMotion.animating(),"animation to AOD in progress before style switch");
        styleMotion.setAodStyle(AodStyleSettings.STYLE_MOON);
        check(styleMotion.frameValues[0]==moonBlur && styleMotion.frameValues[1]==moonRot,"setAodStyle(MOON) updates frame channels during animation");
        check(frameTo(styleMotion)[0]==moonBlur,"setAodStyle(MOON) updates frameTo during animation");

        Motion frame = aod(AodStyleSettings.STYLE_LEAVE_LIGHT);
        frame.setMode(Motion.LOCK, true, fixedNow);
        check(frame.totalDurationMs() == 717, "paired frame expansion ends with its geometry and blend");
        frame.advance(fixedNow + 250 * MS);
        near(frame.framePhotoAlpha(), .8535534f, "paired photo uses the reference cosine blend");
        frame.advance(fixedNow + 717 * MS);
        check(!frame.animating() && frame.frameMicros() == 483667, "paired frame completes on its end mask");
        frame.setMode(Motion.HOME, true, fixedNow + 900 * MS);
        check(frame.totalDurationMs() == 500, "full-screen handoff uses the composition duration");
        frame.advance(fixedNow + 1400 * MS);

        Motion seekFrame = aod(AodStyleSettings.STYLE_LEAVE_LIGHT);
        seekFrame.applyState(Motion.HOME, fixedNow);
        seekFrame.seekTransition(Motion.AOD, Motion.LOCK, 275, fixedNow);
        check(seekFrame.frameMicros() > 0 && seekFrame.frameMicros() < 483667,
                "paired frame seek reconstructs the canonical mask even from a full-screen state");

        frame.setMode(Motion.AOD, true, fixedNow + 1500 * MS);
        long turn = fixedNow + 1720 * MS;
        frame.advance(turn);
        float homeBeforeTurn = frame.homeRatio();
        float scaleBeforeTurn = frame.frameScale();
        long maskBeforeTurn = frame.frameMicros();
        frame.setMode(Motion.LOCK, true, turn);
        frame.advance(turn);
        near(frame.homeRatio(), homeBeforeTurn, "paired frame reversal preserves the current desktop texture mix");
        near(frame.frameScale(), scaleBeforeTurn, "paired frame reversal preserves current scale");
        check(frame.frameMicros() == maskBeforeTurn, "paired frame reversal preserves its mask sample");
        frame.advance(turn + 567 * MS);
        check(!frame.animating() && frame.homeRatio() == 0f, "paired frame reversal settles on lock");

        frame.applyState(Motion.AOD, fixedNow);
        frame.setMode(Motion.HOME, true, fixedNow);
        frame.advance(fixedNow + 200 * MS);
        check(frame.homeRatio() > 0f && frame.homeRatio() < 1f, "AOD still has its own weight during opening");
        near(frame.frameHomeRatio(), 1f, "frame opens directly into the desktop photo without mixing lock");
        check(!frame.frameExpanded(), "the masked scene remains visible during opening");
        frame.advance(fixedNow + 567 * MS);
        check(frame.frameExpanded(), "the expanded frame can present its wallpaper directly");
        frame.setMode(Motion.AOD, true, fixedNow + 700 * MS);
        frame.advance(fixedNow + 910 * MS);
        near(frame.frameHomeRatio(), 1f, "frame collapses from desktop without switching to lock");
        long rapidTurn = fixedNow + 910 * MS;
        frame.setMode(Motion.LOCK, true, rapidTurn);
        frame.advance(rapidTurn);
        near(frame.frameHomeRatio(), 1f, "turning towards lock keeps the visible background at the first sample");
        frame.advance(rapidTurn + 200 * MS);
        check(frame.frameHomeRatio() > 0f && frame.frameHomeRatio() < 1f,
                "an interrupted transition blends continuously between two visible wallpapers");

        frame.applyState(Motion.LOCK, fixedNow);
        frame.setMode(Motion.HOME, true, fixedNow);
        frame.advance(fixedNow + 250 * MS);
        near(frame.frameHomeRatio(), .5f, "expanded lock to home still blends both wallpapers");
        check(frame.frameExpanded(), "lock to home keeps full-screen framing");

        int[] addedStyles = {AodStyleSettings.STYLE_DOOR, AodStyleSettings.STYLE_MOUNTAIN,
                             AodStyleSettings.STYLE_GALAXY};
        float[] aodScales = {.917f, .833f, .833f};
        int[] maskEnds = {616667, 716667, 916667};
        int[] scaleDelays = {133, 0, 317};
        int[] openTimes = {717, 750, 950};
        for (int i=0;i<addedStyles.length;i++) {
            Motion m=aod(addedStyles[i]);
            near(m.frameScale(),aodScales[i],"steady AOD mask scale");
            near(m.frameBlur(),100f,"steady AOD mask blur");
            check(m.frameMicros()==0,"steady AOD uses start frame");

            m.setMode(Motion.HOME,true,animT);
            m.advance(animT+scaleDelays[i]*MS);
            near(m.frameScale(),aodScales[i],"mask geometry honors its opening delay");
            m.advance(animT+(scaleDelays[i]+100)*MS);
            check(m.frameScale()>aodScales[i],"mask expands after its opening delay");
            m.advance(animT+(openTimes[i]-1)*MS);
            check(m.homeRatio()<1f && m.animating(),"mask blend stays active up to its own endpoint");
            m.advance(animT+1250*MS);
            check(m.frameMicros()==maskEnds[i] && !m.animating(),"opening uses full-screen end frame");
            near(m.homeRatio(),1f,"opening finishes at HOME");

            long closeStart=animT+1500*MS;
            m.setMode(Motion.AOD,true,closeStart);
            long interruption=closeStart+300*MS;
            m.advance(interruption);
            float progress=m.ratio[Motion.AOD];
            long maskTime=m.frameMicros();
            check(maskTime>0 && maskTime<maskEnds[i],"closing advances mask backwards");
            m.setMode(Motion.HOME,true,interruption);
            m.advance(interruption);
            near(m.ratio[Motion.AOD],progress,"interrupted blend starts at its visible progress");
            check(m.frameMicros()==maskTime,"interrupted mask starts at its visible frame");
            m.advance(interruption+1250*MS);
            check(m.frameMicros()==maskEnds[i] && !m.animating(),"reversed opening settles");

            m.setMode(Motion.AOD,true,interruption+1500*MS);
            m.advance(interruption+2750*MS);
            check(m.frameMicros()==0 && !m.animating(),"closing returns to the AOD start frame");
            near(m.frameScale(),aodScales[i],"closing returns to AOD geometry");
        }
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
            "io/github/askt_clot/hyperalive/AodStyleSettings.java": (
                "package io.github.askt_clot.hyperalive; final class AodStyleSettings {"
                "static final int STYLE_SET_SAIL=0, STYLE_LEAVE_LIGHT=1, STYLE_MOON=2,"
                " STYLE_DOOR=3, STYLE_MOUNTAIN=4, STYLE_GALAXY=5;}"
            ),
            "io/github/askt_clot/hyperalive/MotionRegression.java": HARNESS,
        }
        stub_paths = []
        for relative, text in sources.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            stub_paths.append(str(path))
        subprocess.run([
            _resolve_jdk_tool("javac"), "-nowarn", "-encoding", "UTF-8", "-source", "8",
            "-target", "8", "-d", str(work), str(JAVA / "Motion.java"),
            str(JAVA / "WallpaperMotion.java"), str(JAVA / "FlymeVideoMotion.java"),
            str(JAVA / "WallpaperTransitionTime.java"), str(JAVA / "StereoMotion.java"),
            str(JAVA / "LensMatrices.java"), str(JAVA / "PhotoMaskStyle.java"), *stub_paths,
        ], check=True)
        subprocess.run([
            _resolve_jdk_tool("java"), "-cp", str(work), "io.github.askt_clot.hyperalive.MotionRegression",
        ], check=True)


if __name__ == "__main__":
    main()
