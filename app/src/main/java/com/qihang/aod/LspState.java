package com.qihang.aod;

import android.content.Context;
import android.util.Log;

/**
 * LSPosed 激活状态判定与异常防御：
 * 1. 提供被 XposedInit 直接替换的桩方法 isLsposedHooked()；
 * 2. 检查 system_server 启动时打上的 sys.qihang.lsp_active 标记；
 * 3. 兜底检查 LSPosed modules_config.db 数据库中的启用状态；
 * 4. 若模块检测到未激活或在 LSPosed 中被用户关闭，立即强制恢复原生静态壁纸，避免二次开机卡死。
 */
public final class LspState {

    private static final String TAG = Root.TAG;
    private static final String PROP_LSP_ACTIVE = "sys.qihang.lsp_active";

    private LspState() {
    }

    /**
     * 核心桩方法：
     * 由 XposedInit 在 LSPosed 注入本应用 (com.qihang.aod) 时 Hook 替换返回 true。
     * 若 LSPosed 未激活或本模块在管理器中被关闭，则原生返回 false。
     */
    public static boolean isLsposedHooked() {
        return false;
    }

    /**
     * 检查 system_server 是否已成功加载本模块并写入激活标记。
     */
    public static boolean isSystemServerHooked() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object val = sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_LSP_ACTIVE, "0");
            return "1".equals(val);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 综合判定 LSPosed 是否处于激活状态：
     * 1. 优先校验内存级 Hook (isLsposedHooked)；
     * 2. 其次校验系统级属性 (sys.qihang.lsp_active)；
     * 3. 若有 root，直接查询 LSPosed 数据库 modules_config.db。
     */
    public static boolean isLsposedActive(Context ctx) {
        if (isLsposedHooked()) {
            return true;
        }
        if (isSystemServerHooked()) {
            return true;
        }
        if (ctx != null && Root.hasRoot()) {
            try {
                String db = Root.appProcess(ctx, "com.qihang.aod.root.LspDb", "-get", ctx.getPackageName());
                if (db != null && db.contains("enabled=1")) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 若检测到 LSPosed 未激活，自动触发恢复系统原生壁纸。
     *
     * @return true 表示已触发回退，false 表示 LSPosed 正常激活
     */
    public static boolean checkFallbackIfInactive(final Context ctx) {
        if (isLsposedActive(ctx)) {
            return false;
        }
        Log.e(TAG, "★ 严重告警：检测到 LSPosed 处于未激活状态（模块可能被用户在 LSPosed 中禁用）！");
        Log.e(TAG, "★ 为防止系统开机二次卡屏，正在紧急将壁纸强制恢复为系统原生静态壁纸...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Root.restoreDefaultWallpaper(ctx);
            }
        }, "qihang-auto-fallback").start();
        return true;
    }
}
