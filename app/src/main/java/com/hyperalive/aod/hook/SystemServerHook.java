package com.hyperalive.aod.hook;

import android.os.SystemClock;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;

/**
 * system_server 核心拦截管线：
 * 统管特权权限放行、壁纸静默篡改拦截、电源事件前馈、灭屏黑闪重定向及 Xiaomi Greezer 防冻结。
 */
public final class SystemServerHook {

    private static final int PRIMARY_POWER_GROUP_ID = 0;

    private static final ThreadLocal<Boolean> sBindingSelf = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    private SystemServerHook() {}

    public static void install(ClassLoader cl) {
        HookUtils.log("开始安装 system_server hooks...");
        HookUtils.propSet("sys.qihang.lsp_active", "1");
        HookUtils.propSet(StateCoordinator.PROP_WP_ACTIVE, "0");
        hookPrimaryDisplayWakefulness(cl);
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
                    if ("bindWallpaperComponentLocked".equals(param.method.getName())
                            && param.args != null && param.args.length > 0
                            && param.args[0] instanceof android.content.ComponentName) {
                        String targetPkg = ((android.content.ComponentName) param.args[0]).getPackageName();
                        sBindingSelf.set(HookUtils.isTarget(targetPkg));
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String mName = param.method.getName();
                if ("bindWallpaperComponentLocked".equals(mName)
                        || "clearWallpaperLocked".equals(mName)
                        || "switchWallpaper".equals(mName)
                        || "onUnlockUser".equals(mName)) {
                    sBindingSelf.set(Boolean.FALSE);
                    StateCoordinator.invalidateWallpaperOwnedCache();
                    StateCoordinator.postAsync(new Runnable() {
                        @Override
                        public void run() {
                            StateCoordinator.refreshWallpaperOwnershipSync(StateCoordinator.systemContext());
                        }
                    });
                }
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

        // 1.3.2 仅在 HyperAlive 当前确有占用系统壁纸且未显式允许重置时，拦截 com.miui.miwallpaper 的静默清空与重置指令
        HookUtils.hookAll(cl, "com.android.server.wallpaper.WallpaperManagerService", "setWallpaper", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (StateCoordinator.isResetAllowed() || !StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (StateCoordinator.isResetAllowed() || !StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (StateCoordinator.isResetAllowed() || !StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!Boolean.TRUE.equals(sBindingSelf.get()) && !StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
                HookUtils.log("★ onSwitchWallpaperFailLocked 触发: 阻止降级为 ImageWallpaper！");
                param.setResult(null);
            }
        });

        // 1.4 拦截 DisplayPowerController / DisplayPowerState.setScreenState：熄屏进入 AOD 瞬间（1.5s 过渡窗口内）
        //     仅在 HyperAlive 实际持有锁屏壁纸且开启 AOD 时生效，绝不做同步 Binder，仅桌面命中也不介入原生 AOD。
        XC_MethodHook screenStateHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length >= 1) {
                    Object stateObj = param.args[0];
                    if (stateObj instanceof Integer) {
                        int state = (Integer) stateObj;
                        if (NativeOnAodRampCoordinator.handleSetScreenState(param, state)) {
                            return;
                        }
                        if (!NativeOnAodRampCoordinator.isPrimaryDisplayComponent(param.thisObject)) return;
                        if (!StateCoordinator.isAodPolicyActiveFast()) return;
                        long elapsed = SystemClock.uptimeMillis() - StateCoordinator.sSleepAtMs;
                        if (state == 1 && StateCoordinator.sAsleep && elapsed >= 0 && elapsed < 1500L) {
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
                if (NativeOnAodRampCoordinator.handlePrepareColorFade(param)) {
                    return;
                }
                if (!NativeOnAodRampCoordinator.isPrimaryDisplayComponent(param.thisObject)) return;
                if (!StateCoordinator.isAodPolicyActiveFast()) return;
                long elapsed = SystemClock.uptimeMillis() - StateCoordinator.sSleepAtMs;
                if (StateCoordinator.sAsleep && elapsed >= 0 && elapsed < 1500L) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        NativeOnAodRampCoordinator.install(cl);

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

    private static void hookPrimaryDisplayWakefulness(ClassLoader cl) {
        // The rear screen can wake and sleep independently. Use the primary group's
        // actual transition before its listener updates the display and SystemUI.
        HookUtils.hookMethod(HookUtils.findClass("com.android.server.power.PowerGroup", cl),
                "setWakefulnessLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object groupId = HookUtils.getField(param.thisObject, "mGroupId");
                if (!Integer.valueOf(PRIMARY_POWER_GROUP_ID).equals(groupId)) return;
                int next = (Integer) param.args[0];
                Object previous = HookUtils.getField(param.thisObject, "mWakefulness");
                if (!(previous instanceof Integer) || previous.equals(next)) return;
                if (next == 1) {
                    NativeOnAodRampCoordinator.onPowerWakeSignal("PowerGroup_awake");
                    boolean biometric = isBiometricWake((Integer) param.args[3], (String) param.args[6]);
                    StateCoordinator.onPrimaryDisplayAwake(biometric);
                    return;
                }
                if (next != 0 && next != 3) return; // ASLEEP / DOZING
                if (Integer.valueOf(1).equals(previous) || Integer.valueOf(2).equals(previous)) {
                    StateCoordinator.cancelPrimaryWakeRecovery();
                    if (StateCoordinator.isLockWallpaperActiveFast()) {
                        StateCoordinator.onSleepIntent("PowerGroup reason=" + param.args[3]);
                        NativeOnAodRampCoordinator.onPowerSleepSignal("PowerGroup");
                    }
                }
            }
        }, int.class, long.class, int.class, int.class, int.class, String.class, String.class);
    }

    private static boolean isBiometricWake(int reason, String details) {
        if (reason == 17) return true; // WAKE_REASON_BIOMETRIC
        if (details == null) return false;
        String normalizedDetails = details.toLowerCase(java.util.Locale.ROOT);
        return normalizedDetails.contains("biometric") || normalizedDetails.contains("fingerprint");
    }

    private static void hookGreezer(ClassLoader cl) {
        HookUtils.log("开始安装 Greezer 防冻结 hooks...");

        XC_MethodHook wpAppHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isLockWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
                if (!StateCoordinator.isHyperAliveWallpaperActiveFast()) return;
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
