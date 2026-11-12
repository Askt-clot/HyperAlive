package com.qihang.aod;

import com.qihang.aod.hook.AodHook;
import com.qihang.aod.hook.HookUtils;
import com.qihang.aod.hook.StateCoordinator;
import com.qihang.aod.hook.SystemServerHook;
import com.qihang.aod.hook.SystemUiHook;
import com.qihang.aod.hook.WallpaperPreviewHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperAlive LSPosed 模块主入口点：
 * 负责分发 system_server、SystemUI、MIUI AOD 以及自身进程的 Hook 逻辑。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String ACTION_MODE = StateCoordinator.ACTION_MODE;
    public static final String EXTRA_MODE = StateCoordinator.EXTRA_MODE;
    public static final String EXTRA_ANIMATE = StateCoordinator.EXTRA_ANIMATE;
    public static final String EXTRA_STATE = StateCoordinator.EXTRA_STATE;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return;
        HookUtils.log("handleLoadPackage: pkg=" + lp.packageName + " proc=" + lp.processName);

        try {
            if ("android".equals(lp.packageName) || "system".equals(lp.packageName)) {
                SystemServerHook.install(lp.classLoader);
            } else if ("com.android.systemui".equals(lp.packageName) || "miui.systemui.plugin".equals(lp.packageName)) {
                SystemUiHook.install(lp.classLoader);
                AodHook.install(lp.classLoader);
            } else if ("com.miui.aod".equals(lp.packageName)) {
                AodHook.install(lp.classLoader);
                WallpaperPreviewHook.installAod(lp.classLoader);
            } else if ("com.android.thememanager".equals(lp.packageName)) {
                WallpaperPreviewHook.installThemeManager(lp.classLoader);
            } else if (HookUtils.PKG_SELF.equals(lp.packageName)) {
                hookSelf(lp.classLoader);
            }
        } catch (Throwable t) {
            HookUtils.log("handleLoadPackage 异常 " + lp.packageName, t);
        }
    }

    private void hookSelf(ClassLoader cl) {
        HookUtils.hookAll(cl, "com.qihang.aod.LspState", "isLsposedHooked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(Boolean.TRUE);
            }
        });
        HookUtils.log("模块自身 LspState.isLsposedHooked 已注入返回 true");
    }
}
