package com.hyperalive.aod;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模块诊断日志管理器（有界环形缓冲 + 私有文件持久化 + 可选 Root Hook logcat 读取）。
 *
 * <p>设计约束：
 * 1. 不申请任何新权限，不写外部存储，仅写应用私有 {@code files/module_diag_log.txt}；
 * 2. 严格做长度/行数有界裁剪，过滤每帧 FPS 等高频刷屏，脱敏潜在凭据字段，不记录图片内容；
 * 3. 无 Root 权限时安全降级，明确提示状态，不崩溃、不伪造成功。
 */
public final class ModuleDiagLog {

    static final int MAX_MEMORY_LINES = 160;
    static final int MAX_FILE_BYTES = 64 * 1024;
    static final int MAX_LINE_CHARS = 320;
    static final int MAX_HOOK_LOGCAT_LINES = 120;
    static final String LOG_FILE_NAME = "module_diag_log.txt";

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RING = new ArrayDeque<>(MAX_MEMORY_LINES);
    private static final ThreadLocal<Boolean> IN_BRIDGE_EMIT = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile(
            "(?i)(password|passcode|pin|lock_?pattern|unlock_?secret|credential)\\s*([:=])\\s*(\\S+)");
    private static final Pattern SECRET_CMD_PATTERN = Pattern.compile(
            "(?i)(--?(?:password|pin|passcode)|locksettings\\s+verify[^\\r\\n]*)");
    private static final Pattern DIAG_RC_PATTERN = Pattern.compile(
            "(?m)^__DIAG_LC_RC=(\\d+)\\s+__DIAG_TAIL_RC=(\\d+)\\s*$");

    interface LogcatRunner {
        LogcatResult run(long timeoutMs);
    }

    static final class LogcatResult {
        final boolean rootAvailable;
        final boolean ok;
        final String output;
        final String error;

        LogcatResult(boolean rootAvailable, boolean ok, String output, String error) {
            this.rootAvailable = rootAvailable;
            this.ok = ok;
            this.output = output != null ? output : "";
            this.error = error != null ? error : "";
        }
    }

    static final class DiagSnapshot {
        final boolean rootAvailable;
        final boolean hookCollectionOk;
        final boolean hasAppLogs;
        final boolean hasHookLogs;
        final String formattedText;
        final String errorMessage;

        DiagSnapshot(
                boolean rootAvailable,
                boolean hookCollectionOk,
                boolean hasAppLogs,
                boolean hasHookLogs,
                String formattedText,
                String errorMessage) {
            this.rootAvailable = rootAvailable;
            this.hookCollectionOk = hookCollectionOk;
            this.hasAppLogs = hasAppLogs;
            this.hasHookLogs = hasHookLogs;
            this.formattedText = formattedText != null ? formattedText : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }
    }

    static String buildHookLogcatCommand(int maxLines) {
        int bounded = Math.max(1, maxLines);
        // 先按模块 tag 过滤再做有界 tail，并分别保留 logcat 与 tail 的真实退出码，避免 tail=0 掩盖 logcat 失败
        return "{ LC_RC=$(( { { logcat -d -v time -s QihangLSP:* QihangAod:* PreModuleBackup:* 2>&1; "
                + "printf '%s\\n' \"$?\" >&4; } | tail -n " + bounded + " >&3 4>&-; } 4>&1 ) ); "
                + "TAIL_RC=$?; printf '\\n__DIAG_LC_RC=%s __DIAG_TAIL_RC=%s\\n' \"$LC_RC\" \"$TAIL_RC\"; } 3>&1";
    }

    static LogcatResult parseHookLogcatSuOutput(String out) {
        if (out == null || out.trim().isEmpty()) {
            return new LogcatResult(true, false, "", "logcat 返回为空或执行失败");
        }
        String trimmed = out.trim();
        if (trimmed.equals("ERR") || trimmed.startsWith("ERR ")) {
            return new LogcatResult(true, false, "", trimmed);
        }
        java.util.regex.Matcher m = DIAG_RC_PATTERN.matcher(out);
        if (!m.find()) {
            return new LogcatResult(true, false, "", "logcat 通道未返回有效退出码: " + sanitizeLine(trimmed));
        }
        int lcRc;
        int tailRc;
        try {
            lcRc = Integer.parseInt(m.group(1));
            tailRc = Integer.parseInt(m.group(2));
        } catch (Throwable t) {
            return new LogcatResult(true, false, "", "解析 logcat 退出码失败");
        }
        String cleanedOut = DIAG_RC_PATTERN.matcher(out).replaceAll("");
        if (lcRc != 0 || tailRc != 0) {
            String errBody = cleanedOut.trim();
            String errMsg = "logcat 采集失败(logcatRc=" + lcRc + ", tailRc=" + tailRc + ")"
                    + (errBody.isEmpty() ? "" : (": " + sanitizeLine(errBody)));
            return new LogcatResult(true, false, "", errMsg);
        }
        return new LogcatResult(true, true, cleanedOut, "");
    }

    private static final LogcatRunner DEFAULT_LOGCAT_RUNNER = timeoutMs -> {
        if (!Root.hasRoot()) {
            return new LogcatResult(false, false, "", "未获取 Root 权限或 su 不可用");
        }
        String out = Root.su(buildHookLogcatCommand(MAX_HOOK_LOGCAT_LINES), timeoutMs);
        return parseHookLogcatSuOutput(out);
    };

    private ModuleDiagLog() {
    }

    static boolean isNoisyFrameSpam(String line) {
        if (line == null) return true;
        return line.contains("AOD自转帧率=")
                || line.contains("预览 angle=")
                || line.contains("drawFrame ")
                || line.contains("eglSwapBuffers ");
    }

    static String sanitizeLine(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.isEmpty() || isNoisyFrameSpam(cleaned)) {
            return "";
        }
        cleaned = SECRET_KV_PATTERN.matcher(cleaned).replaceAll("$1$2***");
        cleaned = SECRET_CMD_PATTERN.matcher(cleaned).replaceAll("[redacted]");
        if (cleaned.length() > MAX_LINE_CHARS) {
            cleaned = cleaned.substring(0, MAX_LINE_CHARS - 3) + "...";
        }
        return cleaned;
    }

    private static String formatTimestampNow() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static void record(Context ctx, String rawLine) {
        String cleaned = sanitizeLine(rawLine);
        if (cleaned.isEmpty()) return;
        String entry = formatTimestampNow() + " | " + cleaned;
        synchronized (LOCK) {
            while (RING.size() >= MAX_MEMORY_LINES) {
                RING.removeFirst();
            }
            RING.addLast(entry);
            File logFile = resolveLogFile(ctx);
            if (logFile != null) {
                appendEntryToFileLocked(logFile, entry);
            }
        }
        if (!Boolean.TRUE.equals(IN_BRIDGE_EMIT.get())) {
            IN_BRIDGE_EMIT.set(Boolean.TRUE);
            try {
                forwardToLsposedBridge(cleaned);
            } catch (Throwable ignored) {
            } finally {
                IN_BRIDGE_EMIT.set(Boolean.FALSE);
            }
        }
    }

    interface BridgeObserverForTest {
        void onForward(String cleanedEntry);
    }

    static volatile BridgeObserverForTest sBridgeObserverForTest;

    /**
     * 供 XposedInit.hookSelf 在自身进程被注入时 Hook 的无副作用桥接方法。
     * 未注入 LSPosed 时保持为空实现，不引用任何 Xposed API，不写框架日志文件，也不调用 Root。
     */
    static void forwardToLsposedBridge(String cleanedEntry) {
        // No-op without LSPosed self-hook injection.
        BridgeObserverForTest observer = sBridgeObserverForTest;
        if (observer != null) {
            observer.onForward(cleanedEntry);
        }
    }

    static File resolveLogFile(Context ctx) {
        if (ctx == null) return null;
        try {
            File dir = ctx.getFilesDir();
            return dir != null ? new File(dir, LOG_FILE_NAME) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void appendEntryToFileLocked(File file, String entry) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            byte[] lineBytes = (entry + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(lineBytes);
                out.flush();
            }
            if (file.length() > MAX_FILE_BYTES) {
                trimLogFileLocked(file);
            }
        } catch (Throwable t) {
            Log.w(Root.TAG, "写入诊断日志失败: " + t.getMessage());
        }
    }

    private static void trimLogFileLocked(File file) {
        try {
            List<String> lines = readLinesFromFileLocked(file, MAX_MEMORY_LINES);
            int keepFrom = Math.max(0, lines.size() - MAX_MEMORY_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = keepFrom; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            File tmp = new File(file.getParentFile(), LOG_FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(data);
                out.flush();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readLinesFromFileLocked(File file, int limit) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.isFile() || file.length() <= 0) {
            return lines;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) {
                if (!l.trim().isEmpty()) {
                    lines.add(l);
                }
            }
        } catch (Throwable ignored) {
        }
        if (lines.size() > limit) {
            return new ArrayList<>(lines.subList(lines.size() - limit, lines.size()));
        }
        return lines;
    }

    static List<String> readAppLogs(Context ctx) {
        synchronized (LOCK) {
            File file = resolveLogFile(ctx);
            List<String> fromFile = readLinesFromFileLocked(file, MAX_MEMORY_LINES);
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
            return new ArrayList<>(RING);
        }
    }

    static void clearForTesting(Context ctx) {
        synchronized (LOCK) {
            RING.clear();
            File f = resolveLogFile(ctx);
            if (f != null && f.exists()) {
                f.delete();
            }
        }
    }

    static DiagSnapshot collectDiagnostics(Context ctx) {
        return collectDiagnostics(ctx, DEFAULT_LOGCAT_RUNNER);
    }

    static DiagSnapshot collectDiagnostics(Context ctx, LogcatRunner runner) {
        List<String> appLines = readAppLogs(ctx);
        boolean hasApp = !appLines.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 应用与模块操作日志（最近 ").append(MAX_MEMORY_LINES).append(" 条）===\n");
        if (!hasApp) {
            sb.append("（暂无操作记录；执行选图、应用、备份或恢复后会记录于此）\n");
        } else {
            for (String line : appLines) {
                sb.append(line).append('\n');
            }
        }

        sb.append("\n=== 系统作用域 / Hook 日志 (logcat) ===\n");
        boolean rootOk = false;
        boolean collectionOk = false;
        boolean hasHook = false;
        String errMsg = "";
        LogcatRunner activeRunner = runner != null ? runner : DEFAULT_LOGCAT_RUNNER;
        try {
            LogcatResult res = activeRunner.run(8000);
            rootOk = res != null && res.rootAvailable;
            if (res == null || !res.rootAvailable) {
                sb.append("未获取 Root 权限（或 su 不可用），无法读取系统进程 Hook 实时日志。\n")
                        .append("仅展示上方应用内操作记录；授予 Root 权限后点「刷新」可读取 Hook 日志。\n");
            } else if (!res.ok) {
                errMsg = res.error;
                sb.append("读取系统 Hook 日志失败：")
                        .append(res.error.isEmpty() ? "未知错误" : sanitizeLine(res.error))
                        .append('\n');
            } else {
                collectionOk = true;
                List<String> filteredHook = new ArrayList<>();
                String[] rawLines = res.output.split("\\r?\\n");
                for (String raw : rawLines) {
                    if (raw == null) continue;
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("--------- beginning of")) {
                        continue;
                    }
                    String s = sanitizeLine(trimmed);
                    if (!s.isEmpty()) {
                        filteredHook.add(s);
                    }
                }
                if (filteredHook.size() > MAX_HOOK_LOGCAT_LINES) {
                    filteredHook = filteredHook.subList(
                            filteredHook.size() - MAX_HOOK_LOGCAT_LINES, filteredHook.size());
                }
                if (filteredHook.isEmpty()) {
                    sb.append("（当前无匹配的 QihangLSP / QihangAod / PreModuleBackup 日志）\n");
                } else {
                    hasHook = true;
                    for (String hl : filteredHook) {
                        sb.append(hl).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            errMsg = String.valueOf(t.getMessage());
            sb.append("读取系统 Hook 日志异常：").append(sanitizeLine(String.valueOf(t))).append('\n');
        }

        return new DiagSnapshot(rootOk, collectionOk, hasApp, hasHook, sb.toString().trim(), errMsg);
    }
}
