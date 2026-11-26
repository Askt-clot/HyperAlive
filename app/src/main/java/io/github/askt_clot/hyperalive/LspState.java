package io.github.askt_clot.hyperalive;

import android.content.Context;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * LSPosed 只读激活状态判定：
 * 1. 统一状态枚举 State (ENABLED / DISABLED / UNKNOWN)；
 * 2. 仅负责只读探测 LSPosed DB 与运行态；
 * 3. 严禁在检测到 LSP 停用时自动清空或改写用户壁纸，恢复仅由用户显式手动路径执行。
 */
public final class LspState {

    private static final String TAG = Root.TAG;
    private static final String PROP_LSP_ACTIVE = "sys.qihang.lsp_active";

    public enum State {
        ENABLED,
        DISABLED,
        UNKNOWN
    }

    private LspState() {}

    /**
     * 核心桩方法：
     * 由 XposedInit 在 LSPosed 注入本应用 (io.github.askt_clot.hyperalive) 时 Hook 替换返回 true。
     * 注意：此方法仅反映当前进程在启动时是否曾被注入，不能在无 Root 条件下单独作为当前 LSPosed 实时已激活的依据
     *（用户在管理器关闭模块并撤销 Root 后，未重启的应用进程内旧 Hook 仍会返回 true）。
     */
    public static boolean isLsposedHooked() {
        return false;
    }

    /**
     * 检查 system_server 是否已写入激活属性标记。
     * 注意：系统属性在 system_server 重启前会一直保留，不能在无 Root 条件下单独作为当前 LSPosed 实时已激活的依据。
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
     * 统一查询 LSPosed 状态（自动探测 Root）。
     */
    public static State queryLspState(Context ctx) {
        return queryLspState(ctx, Root.hasRoot());
    }

    /**
     * 统一查询 LSPosed 状态（允许复用同一轮已探测的 hasRoot 结果，避免重复 fork su）：
     * 1. 具有 ROOT 时：直接查询当前用户对应 LSPosed WAL 数据库 modules_config.db；
     *    - 唯有明确 enabled=0 时 -> 确证 DISABLED（优先于 lspd 进程检测，即使进程内残存旧 Hook 也以此为准）；
     *    - enabled=1 且作用域精确包含 (system 或 android) 与 com.android.systemui 且 lspd 进程计数为正整数 -> ENABLED；
     *    - enabled=-1（未配置/缺少记录）、lspd 退出或查询异常、作用域缺失、SQL 失败 -> UNKNOWN（绝不返回 DISABLED）；
     * 2. 无 ROOT 时：无法查询 LSPosed 真实数据库，而进程内 Hook 与系统属性均为上次启动/注入时的粘滞快照，
     *    若用户在 LSPosed 关闭模块并同时撤销 Root，继续采信粘滞标记会误报「LSP 已激活」，因此无 Root 时统一返回 UNKNOWN。
     */
    public static State queryLspState(Context ctx, boolean hasRoot) {
        if (ctx == null || !hasRoot) {
            return State.UNKNOWN;
        }
        try {
            int userId = android.os.Process.myUid() / 100000;
            String db = Root.appProcess(
                    ctx, "io.github.askt_clot.hyperalive.root.LspDb", "-get", ctx.getPackageName(), String.valueOf(userId));
            int enabled = parseEnabledFlag(db);
            if (enabled == 0) {
                Log.i(TAG, "LSPosed DB 明确为停用 enabled=0 (userId=" + userId + ", out="
                        + (db != null ? db.trim() : "") + ")");
                return State.DISABLED;
            }
            if (enabled == 1) {
                boolean inScope = hasRequiredCoreScopes(db);
                String lspdOut = Root.su("ps -A | grep -c ' lspd$'", 8000);
                boolean lspd = isPositiveProcessCount(lspdOut);
                if (lspd && inScope) {
                    return State.ENABLED;
                }
                Log.w(TAG, "LSPosed DB enabled=1 但环境未就绪 (lspd=" + lspd + ", inScope=" + inScope + ")");
                return State.UNKNOWN;
            }
            if (enabled == -1) {
                Log.i(TAG, "LSPosed DB 缺少模块记录 enabled=-1 (userId=" + userId + ")");
                return State.UNKNOWN;
            }
        } catch (Throwable t) {
            Log.w(TAG, "查询 LSPosed DB 异常", t);
            return State.UNKNOWN;
        }
        return State.UNKNOWN;
    }

    /**
     * 解析 LspDb 输出中的 enabled=<int> 字段；任何错误或缺失均返回 -99。
     */
    static int parseEnabledFlag(String dbOut) {
        if (dbOut == null || dbOut.contains("ERR") || dbOut.startsWith("suRaw")) {
            return -99;
        }
        for (String token : dbOut.trim().split("\\s+")) {
            if (token.startsWith("enabled=")) {
                String val = token.substring("enabled=".length()).trim();
                if ("0".equals(val)) return 0;
                if ("1".equals(val)) return 1;
                if ("-1".equals(val)) return -1;
                return -99;
            }
        }
        return -99;
    }

    /**
     * 提取 LspDb 输出中 scope= 后的逗号分隔包名集合，严格按完整 token 匹配，
     * 防止 "system" 子串误匹配 "com.android.systemui"。
     */
    public static Set<String> parseScopeTokens(String dbOut) {
        Set<String> tokens = new HashSet<>();
        if (dbOut == null || dbOut.contains("ERR") || dbOut.startsWith("suRaw")) {
            return tokens;
        }
        int idx = dbOut.indexOf("scope=");
        if (idx < 0) {
            return tokens;
        }
        String tail = dbOut.substring(idx + "scope=".length());
        int nl = tail.indexOf('\n');
        if (nl >= 0) {
            tail = tail.substring(0, nl);
        }
        int sp = tail.indexOf(' ');
        if (sp >= 0) {
            tail = tail.substring(0, sp);
        }
        for (String item : tail.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    /**
     * 判断 LspDb 输出的作用域列表是否包含指定完整包名 token。
     */
    public static boolean hasScopeToken(String dbOut, String expectedPkg) {
        if (expectedPkg == null || expectedPkg.trim().isEmpty()) {
            return false;
        }
        return parseScopeTokens(dbOut).contains(expectedPkg.trim());
    }

    /**
     * 核心作用域必须同时包含系统框架（"system" 或 "android"）与系统界面（"com.android.systemui"）。
     */
    static boolean hasRequiredCoreScopes(String dbOut) {
        Set<String> scopes = parseScopeTokens(dbOut);
        return (scopes.contains("system") || scopes.contains("android"))
                && scopes.contains("com.android.systemui");
    }

    /**
     * 严格校验进程计数输出为正整数（> 0），任何超时、ERR、空串或非数字均返回 false。
     */
    static boolean isPositiveProcessCount(String psOut) {
        if (psOut == null || psOut.contains("ERR") || psOut.startsWith("suRaw")) {
            return false;
        }
        String trimmed = null;
        for (String line : psOut.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (trimmed != null) {
                    // 多行杂讯视为异常输出
                    return false;
                }
                trimmed = t;
            }
        }
        if (trimmed == null || !trimmed.matches("\\d+")) {
            return false;
        }
        try {
            return Integer.parseInt(trimmed) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 纯函数判定（供离线回归测试直接验证无 Root 时忽略粘滞标记、enabled=0 优先级、严格 scope token 与 lspd 正整数校验）。
     */
    static State evaluateLspState(boolean hasRoot, String dbOut, String lspdCountOut) {
        if (!hasRoot) {
            return State.UNKNOWN;
        }
        return evaluateLspDbAndProcess(dbOut, lspdCountOut);
    }

    static State evaluateLspDbAndProcess(String dbOut, String lspdCountOut) {
        int enabled = parseEnabledFlag(dbOut);
        if (enabled == 0) {
            return State.DISABLED;
        }
        if (enabled == 1) {
            if (hasRequiredCoreScopes(dbOut) && isPositiveProcessCount(lspdCountOut)) {
                return State.ENABLED;
            }
            return State.UNKNOWN;
        }
        return State.UNKNOWN;
    }
}
