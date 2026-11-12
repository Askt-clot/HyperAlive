package com.qihang.aod.hook;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * system_server 核心拦截管线：
 * 统管特权权限放行、壁纸静默篡改拦截、电源事件前馈、灭屏黑闪重定向及 Xiaomi Greezer 防冻结。
 */
public final class SystemServerHook {

    private SystemServerHook() {}

    public static void install(ClassLoader cl) {
        HookUtils.log("开始安装 system_server hooks...");
        HookUtils.propSet("sys.qihang.lsp_active", "1");
        hookPowerAodSignal(cl);
        hookPrimaryDisplaySleep(cl);
        hookGreezer(cl);
        hookWallpaperCommands(cl);

        // 1.1 放行 AMBIENT_WALLPAPER 权限
        HookUtils.hookAll(cl, "com.android.server.pm.PackageManagerService", "checkPermission", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    if (HookUtils.PERM_AMBIENT.equals(param.args[0]) && HookUtils.isTarget((String) param.args[1])) {
                        param.setResult(0); // PERMISSION_GRANTED
                    }
                }
            }
        });

        // 1.2 放行壁纸 uid 校验
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "enforcePackageBelongsToUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && HookUtils.isTarget((String) param.args[0])) {
                    param.setResult(null);
                }
            }
        });

        // 1.3 放行 isSetWallpaperAllowed 门禁并修复潜在 NPE
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "isSetWallpaperAllowed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && HookUtils.isTarget((String) param.args[0])) {
                    param.setResult(Boolean.TRUE);
                }
            }
        });

        // 1.3.1 追踪 WallpaperManagerService 核心壁纸切换与回退链路
        XC_MethodHook wpTraceHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("WMS.").append(param.method.getName()).append("(");
                    if (param.args != null) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(param.args[i]);
                        }
                    }
                    sb.append(") callerUid=").append(android.os.Binder.getCallingUid())
                      .append(" callerPid=").append(android.os.Binder.getCallingPid());
                    HookUtils.log(sb.toString());
                } catch (Throwable ignored) {}
            }
        };
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "bindWallpaperComponentLocked", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "clearWallpaperLocked", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "switchWallpaper", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponentChecked", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponent", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaper", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onUnlockUser", wpTraceHook);
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onSwitchWallpaperFailLocked", wpTraceHook);

        // 1.3.2 拦截系统自带壁纸应用（com.miui.miwallpaper）在开机/解锁时静默下发的清空与重置壁纸指令
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (StateCoordinator.isResetAllowed()) return;
                if (param.args != null && param.args.length >= 2) {
                    String pkg = String.valueOf(param.args[1]);
                    Object name = param.args[0];
                    if ("com.miui.miwallpaper".equals(pkg) && name == null) {
                        HookUtils.log("★ 成功拦截 com.miui.miwallpaper setWallpaper(name=null) 重置壁纸！保护启航一体化壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "clearWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (StateCoordinator.isResetAllowed()) return;
                if (param.args != null && param.args.length >= 1) {
                    String pkg = String.valueOf(param.args[0]);
                    if ("com.miui.miwallpaper".equals(pkg)) {
                        HookUtils.log("★ 成功拦截 com.miui.miwallpaper clearWallpaper！保护启航一体化壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaperComponentChecked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (StateCoordinator.isResetAllowed()) return;
                if (param.args != null && param.args.length >= 2) {
                    String pkg = String.valueOf(param.args[1]);
                    if ("com.miui.miwallpaper".equals(pkg)) {
                        HookUtils.log("★ 成功拦截 com.miui.miwallpaper setWallpaperComponentChecked！保护启航一体化壁纸！");
                        param.setResult(null);
                    }
                }
            }
        });
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "onSwitchWallpaperFailLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (StateCoordinator.isResetAllowed()) return;
                HookUtils.log("★ onSwitchWallpaperFailLocked 触发: 阻止降级为 ImageWallpaper！");
                param.setResult(null);
            }
        });

        // 1.4 拦截 DisplayPowerController / DisplayPowerState.setScreenState：熄屏进入 AOD 瞬间（1.5s 过渡窗口内）
        //     严禁将屏幕硬件置为 STATE_OFF (1)，彻底杜绝底层屏幕硬件先断电再点亮导致的物理黑闪！
        XC_MethodHook screenStateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object stateObj = param.args[0];
                    if (stateObj instanceof Integer) {
                        int state = (Integer) stateObj;
                        long elapsed = SystemClock.uptimeMillis() - StateCoordinator.sSleepAtMs;
                        if (state == 1 && StateCoordinator.sAsleep && elapsed >= 0 && elapsed < 1500L && StateCoordinator.isAodEnabled(null)) {
                            HookUtils.log(param.thisObject.getClass().getSimpleName() + ".setScreenState: 拦截熄屏初态 STATE_OFF(1) -> 强制重定向为 STATE_DOZE(3) (elapsed=" + elapsed + "ms)");
                            param.args[0] = 3; // Display.STATE_DOZE
                            StateCoordinator.ensureQihangThawed();
                        }
                    }
                }
            }
        };
        HookUtils.hookAll(cl, "com.android.server.display.DisplayPowerController", "setScreenState", screenStateHook);
        HookUtils.hookAll(cl, "com.android.server.display.DisplayPowerControllerImpl", "setScreenState", screenStateHook);
        HookUtils.hookAll(cl, "com.android.server.display.DisplayPowerState", "setScreenState", screenStateHook);
        HookUtils.hookAll(cl, "com.android.server.display.DisplayPowerState", "prepareColorFade", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                long elapsed = SystemClock.uptimeMillis() - StateCoordinator.sSleepAtMs;
                if (StateCoordinator.sAsleep && elapsed >= 0 && elapsed < 1500L && StateCoordinator.isAodEnabled(null)) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        HookUtils.log("system_server hooks 安装完成");
    }

    /** Greezer can discard a broadcast even when the renderer's cgroup is thawed. */
    private static void hookWallpaperCommands(ClassLoader cl) {
        int allowCount = HookUtils.hookAll(cl, "com.miui.server.greeze.GreezeManagerService",
                "isAllowBroadcast", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length != 5 || !(param.args[0] instanceof Integer)) return;
                int caller = (Integer) param.args[0];
                if (HookUtils.PKG_SELF.equals(param.args[3])
                        && StateCoordinator.ACTION_MODE.equals(param.args[4])
                        && isWallpaperCommandSender(caller, param.args[1])) {
                    // Keep isRestrictReceiver's normal thawUidAsync side effect.
                    param.setResult(Boolean.TRUE);
                }
            }
        });
        HookUtils.log("Wallpaper command Greezer allow hooks=" + allowCount);
        if (allowCount > 0) return;
        int count = HookUtils.hookAll(cl, "com.android.server.am.BroadcastQueueModernStubImpl",
                "checkReceiverIfRestricted", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3) return;
                Object record = param.args[1];
                Object value = HookUtils.getField(record, "intent");
                if (!(value instanceof android.content.Intent)) return;
                android.content.Intent intent = (android.content.Intent) value;
                if (!StateCoordinator.ACTION_MODE.equals(intent.getAction())
                        || !HookUtils.PKG_SELF.equals(intent.getPackage())) return;
                Object app = param.args[2];
                Object info = HookUtils.getField(app, "info");
                Object receiverPackage = HookUtils.getField(info, "packageName");
                if (!HookUtils.PKG_SELF.equals(receiverPackage)) return;
                Object uid = HookUtils.getField(record, "callingUid");
                if (!(uid instanceof Integer)) return;
                int caller = (Integer) uid;
                if (!isWallpaperCommandSender(caller, HookUtils.getField(record, "callerPackage"))) return;
                // Only this module's explicit state command bypasses the MIUI cache gate.
                // The framework's permission, exported-receiver and other skip checks still run.
                param.setResult(Boolean.FALSE);
            }
        });
        HookUtils.log("Wallpaper command Greezer gate hooks=" + count);
    }

    private static volatile int sSystemUiUid = -1;

    private static boolean isWallpaperCommandSender(int uid, Object callerPackage) {
        if (uid == 0 || uid == 1000 || (uid > 0 && uid == StateCoordinator.getQihangUid())) return true;
        final String systemUi = "com.android.systemui";
        if (uid <= 0 || !systemUi.equals(callerPackage)) return false;
        // HyperOS may assign SystemUI its own app UID instead of UID 1000.
        if (sSystemUiUid < 0) {
            try {
                android.content.Context context = StateCoordinator.systemContext();
                if (context != null) sSystemUiUid = context.getPackageManager().getPackageUid(systemUi, 0);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return uid == sSystemUiUid;
    }

    private static void hookPrimaryDisplaySleep(ClassLoader cl) {
        // Inactivity timeout changes PowerGroup wakefulness directly, bypassing goToSleep.
        // Arm the existing transition before PowerGroup notifies the display controller.
        HookUtils.hookMethod(HookUtils.findClass("com.android.server.power.PowerGroup", cl),
                "setWakefulnessLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                int next = (Integer) param.args[0];
                if (next != 0 && next != 3) return; // ASLEEP / DOZING
                Object groupId = HookUtils.getField(param.thisObject, "mGroupId");
                Object previous = HookUtils.getField(param.thisObject, "mWakefulness");
                if (Integer.valueOf(0).equals(groupId)
                        && (Integer.valueOf(1).equals(previous) || Integer.valueOf(2).equals(previous))) {
                    StateCoordinator.onSleepIntent("PowerGroup reason=" + param.args[3]);
                }
            }
        }, int.class, long.class, int.class, int.class, int.class, String.class, String.class);
    }

    private static void hookPowerAodSignal(ClassLoader cl) {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String n = p.method.getName();
                if (n.startsWith("goToSleep")) {
                    StateCoordinator.onSleepIntent("goToSleep");
                } else if (n.startsWith("wakeUp") || n.startsWith("wakePowerGroup")) {
                    boolean isBiometric = false;
                    if (p.args != null) {
                        for (Object arg : p.args) {
                            if (arg instanceof String) {
                                String s = ((String) arg).toLowerCase();
                                if (s.contains("biometric") || s.contains("fingerprint")) {
                                    isBiometric = true;
                                    break;
                                }
                            }
                            if (arg instanceof Integer && ((Integer) arg) == 17) {
                                isBiometric = true;
                                break;
                            }
                        }
                    }
                    if (isBiometric) {
                        HookUtils.log("电源前馈：指纹唤醒 (BIOMETRIC) → 广播 mode=2 (HOME)");
                        StateCoordinator.onBiometricWakeIntent();
                    } else {
                        HookUtils.log("电源前馈：正常唤醒 (wakeUp) → 广播 mode=1 (LOCK)");
                        StateCoordinator.onWakeIntent();
                    }
                }
            }
        };

        for (String clsName : new String[]{
                "com.android.server.power.PowerManagerService",
                "com.android.server.power.PowerManagerService$BinderService"
        }) {
            try {
                Class<?> pm = Class.forName(clsName, false, cl);
                for (Method m : pm.getDeclaredMethods()) {
                    String name = m.getName();
                    if (name.startsWith("goToSleep") || name.startsWith("wakeUp") || name.startsWith("wakePowerGroup")) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, cb);
                    }
                }
            } catch (Throwable t) {
                HookUtils.log("hookPowerAodSignal 挂载 " + clsName + " 失败: " + t);
            }
        }
    }

    private static void hookGreezer(ClassLoader cl) {
        HookUtils.log("开始安装 Greezer 防冻结 hooks...");

        XC_MethodHook wpAppHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String) {
                        String pkg = (String) arg0;
                        if (HookUtils.PKG_SELF.equals(pkg) || HookUtils.isTarget(pkg)) {
                            param.setResult(Boolean.TRUE);
                        }
                    } else if (arg0 instanceof Integer) {
                        int uid = (Integer) arg0;
                        int qUid = StateCoordinator.getQihangUid();
                        if (qUid > 0 && uid == qUid) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        };
        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isWallPaperApp", wpAppHook);

        XC_MethodHook noRestrictHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String) {
                        String pkg = (String) arg0;
                        if (HookUtils.PKG_SELF.equals(pkg) || HookUtils.isTarget(pkg)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        };
        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isNoRestrictApp", noRestrictHook);
        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "isNoRestrictFreezeable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object arg0 = param.args[0];
                    if (arg0 instanceof String && (HookUtils.PKG_SELF.equals(arg0) || HookUtils.isTarget((String) arg0))) {
                        param.setResult(Boolean.TRUE);
                        return;
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                        int uid = (Integer) param.args[1];
                        if (uid > 0 && uid == StateCoordinator.getQihangUid()) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "freezeActionForImmobulus", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (HookUtils.PKG_SELF.equals(pkg) || HookUtils.isTarget(pkg)) {
                            HookUtils.log("★ Greezer 防冻结拦截: 阻止 AurogonImmobulusMode.freezeActionForImmobulus(pkg=" + pkg + ")！");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "checkAppForImmobulusMode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (HookUtils.PKG_SELF.equals(pkg) || HookUtils.isTarget(pkg)) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.AurogonImmobulusMode", "getWallpaperPackageName", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int qUid = StateCoordinator.getQihangUid();
                    Field lockPkg = param.thisObject.getClass().getDeclaredField("mLockWallPaperPackageName");
                    lockPkg.setAccessible(true);
                    Field lockUid = param.thisObject.getClass().getDeclaredField("mLockWallPaperUid");
                    lockUid.setAccessible(true);
                    lockPkg.set(param.thisObject, HookUtils.PKG_SELF);
                    if (qUid > 0) {
                        lockUid.set(param.thisObject, qUid);
                    }
                    HookUtils.log("★ AurogonImmobulusMode.getWallpaperPackageName: 覆写 mLockWallPaperPackageName=" + HookUtils.PKG_SELF + " uid=" + qUid);
                } catch (Throwable t) {
                    HookUtils.log("覆写 AurogonImmobulusMode 壁纸字段异常: " + t);
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.FreezeUtils", "writeNodeByCgroupV2", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    int pid = (Integer) param.args[0];
                    int val = (Integer) param.args[1];
                    if (val == 1 && StateCoordinator.isQihangPid(pid)) {
                        HookUtils.log("★ Greezer 防冻结熔断: 阻止向 cgroup.freeze 写入 1 (pid=" + pid + ")！伪装写入成功");
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.FreezeUtils", "writeNode", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    String path = String.valueOf(param.args[0]);
                    int val = (Integer) param.args[1];
                    if (val == 1 && StateCoordinator.isQihangPath(path)) {
                        HookUtils.log("★ Greezer 防冻结熔断: 阻止向 cgroup 写入 1 (path=" + path + ")！");
                        param.setResult(Boolean.TRUE);
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezePid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int pid = (Integer) param.args[0];
                    if (StateCoordinator.isQihangPid(pid)) {
                        HookUtils.log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezePid(" + pid + ")");
                        param.setResult(Boolean.FALSE);
                        return;
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                        int uid = (Integer) param.args[1];
                        if (uid > 0 && uid == StateCoordinator.getQihangUid()) {
                            HookUtils.log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezePid(" + pid + ", uid=" + uid + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int uid = (Integer) param.args[0];
                    if (uid > 0 && uid == StateCoordinator.getQihangUid()) {
                        HookUtils.log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezeUid(" + uid + ")");
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.FreezeUtils", "freezeByCgroupV2", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object info = param.args[0];
                        Field f = info.getClass().getField("mPackageName");
                        String pkg = (String) f.get(info);
                        if (HookUtils.PKG_SELF.equals(pkg) || HookUtils.isTarget(pkg)) {
                            HookUtils.log("★ Greezer 防冻结拦截: 阻止 FreezeUtils.freezeByCgroupV2(pkg=" + pkg + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeAction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 2) {
                    int uid = (Integer) param.args[0];
                    int pid = (Integer) param.args[1];
                    int qUid = StateCoordinator.getQihangUid();
                    if ((qUid > 0 && uid == qUid) || StateCoordinator.isQihangPid(pid)) {
                        HookUtils.log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeAction(uid=" + uid + " pid=" + pid + ")");
                        param.setResult(Boolean.FALSE);
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeUid", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    int uid = (Integer) param.args[0];
                    int qUid = StateCoordinator.getQihangUid();
                    if (qUid > 0 && uid == qUid) {
                        HookUtils.log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeUid(uid=" + uid + ")");
                        param.setResult(null);
                    }
                }
            }
        });

        HookUtils.hookAll(cl, "com.miui.server.greeze.GreezeManagerService", "freezeProcess", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1 && param.args[0] != null) {
                    try {
                        Object proc = param.args[0];
                        Field pf = proc.getClass().getField("processName");
                        String pname = (String) pf.get(proc);
                        if (HookUtils.PKG_SELF.equals(pname) || (pname != null && pname.startsWith(HookUtils.PKG_SELF))) {
                            HookUtils.log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeProcess(" + pname + ")");
                            param.setResult(Boolean.FALSE);
                            return;
                        }
                        Field uf = proc.getClass().getField("uid");
                        int uid = (Integer) uf.get(proc);
                        int qUid = StateCoordinator.getQihangUid();
                        if (qUid > 0 && uid == qUid) {
                            HookUtils.log("★ Greezer 防冻结拦截: 阻止 GreezeManagerService.freezeProcess(uid=" + uid + ")");
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        HookUtils.log("Greezer 防冻结 hooks 安装完成");
    }
}
