package com.hyperalive.aod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 编辑器会话级照片草稿存储与事务提交/回滚管理器。
 *
 * <p>核心不变量与恢复边界：
 * 1. 所有选图、裁切、默认示例载入、跟随开关切换、留光默认相框生成均只读写当前
 *    {@code sessionId} 对应的私有草稿目录（{@code filesDir/editor_drafts/<sessionId>/}）
 *    与私有草稿偏好（{@code photo_crop_draft_<sessionId>}），绝不提前触碰已提交壁纸槽位
 *    （{@link PhotoStore#LOCK}/{@link PhotoStore#HOME}/{@link PhotoStore#FRAME}、CE/DE raw、DE 镜像或
 *    {@code photo_crop_prefs}/{@code home_follow_lock}）；草稿内部修改在失败时回滚至修订前状态。
 * 2. 事务持久日志（{@code journal.json} + 原子 {@code tx_state}）必须在首次写入任何模块状态或照片前创建并
 *    {@code FD.sync()}，记录全部 12 个受允许的 CE/DE 槽位与原图文件、逐键裁切偏好存在性与数值、跟随偏好及模块状态
 *    （{@code AodStyleSettings}/{@code ClockSettings}/{@code LastSet}）；整个应用、WMS 壁纸绑定与
 *    {@code verifyAppliedSlots} 核验期间均保持 {@code PENDING}，仅在端到端核验通过后才转为 {@code COMMITTED} 并清理。
 * 3. 恢复/回滚/清理全程受 {@link PreModuleWallpaperBackup#lockTransaction()} 保护并尊重活跃会话租约与活跃事务 ID，
 *    绝不与进行中的应用并发互踩，也绝不在回滚/恢复失败或清单缺失损坏时删除证据目录或误报成功；残留未解决恢复将阻止后续新应用（fail-closed）。
 * 4. 系统壁纸服务恢复边界：应用私有文件（CE/DE 照片与原图）及模块 SharedPreferences 均受本地日志回滚与重启恢复保护；
 *    但 {@code WallpaperManagerService} 的绑定位于 {@code system_server} 跨进程服务端，进程内绑定/核验失败可立即执行模块侧回滚并保留
 *    {@link PreModuleWallpaperBackup}（仅当严格证明系统壁纸与基线零变化时才清理新建备份）；若在 WMS 绑定中途发生强杀（SIGKILL），
 *    重启后应用恢复机制将把本模块内部文件与配置恢复至事务前状态，系统壁纸还原由保留的应用前备份（「恢复应用前壁纸」）兜底。
 */
final class PhotoDraftStore {

    static final String DRAFT_ROOT_DIR = "editor_drafts";
    static final String SNAPSHOT_ROOT_DIR = "commit_snapshots";
    static final String SNAPSHOT_JOURNAL_FILE = "journal.json";
    static final String TX_STATE_FILE = "tx_state";
    static final String TX_STATE_PREPARING = "PREPARING";
    static final String TX_STATE_PENDING = "PENDING";
    static final String TX_STATE_COMMITTED = "COMMITTED";
    static final String TX_STATE_RECOVERY_FAILED = "RECOVERY_FAILED";
    static final int JOURNAL_VERSION = 1;

    static final String DRAFT_PREF_PREFIX = "photo_crop_draft_";
    static final String CORRUPT_REVISION_PREFIX = ".revision_broken.";
    static final String EDITOR_PREF_NAME = "qihang_editor";
    static final String KEY_HOME_FOLLOW_LOCK = "home_follow_lock";
    static final String KEY_DIRTY_PREFIX = "dirty_";
    static final String COMMITTED_CROP_PREFS = "photo_crop_prefs";

    static final String[] ALL_SLOTS = {
            PhotoStore.LOCK,
            PhotoStore.HOME,
            PhotoStore.FRAME
    };

    static final String[] ALL_STORAGE_KEYS = {
            "ce:" + PhotoStore.LOCK,
            "de:" + PhotoStore.LOCK,
            "ce:" + PhotoStore.HOME,
            "de:" + PhotoStore.HOME,
            "ce:" + PhotoStore.FRAME,
            "de:" + PhotoStore.FRAME,
            "ce:raw_" + PhotoStore.LOCK,
            "de:raw_" + PhotoStore.LOCK,
            "ce:raw_" + PhotoStore.HOME,
            "de:raw_" + PhotoStore.HOME,
            "ce:raw_" + PhotoStore.FRAME,
            "de:raw_" + PhotoStore.FRAME
    };

    private static final long ROTATION_GRACE_MS = 15_000L;
    private static final SecureRandom RNG = new SecureRandom();

    private static final Object LEASE_LOCK = new Object();
    private static final Map<String, Set<Object>> ACTIVE_LEASES = new HashMap<>();
    private static final Map<String, Long> PENDING_RECREATE_SESSIONS = new HashMap<>();
    private static final Set<String> ACTIVE_TX_IDS = ConcurrentHashMap.newKeySet();

    private static final Object STARTUP_RECOVERY_LOCK = new Object();
    private static volatile boolean sStartupRecoveryTriggered = false;
    private static volatile boolean sStartupRecoveryWaitingUnlock = false;
    private static volatile CountDownLatch sStartupRecoveryLatch = new CountDownLatch(0);
    private static volatile RecoveryResult sLastStartupRecoveryResult = null;
    private static final CopyOnWriteArrayList<StartupRecoveryListener> sRecoveryListeners =
            new CopyOnWriteArrayList<>();

    interface StartupRecoveryListener {
        void onStartupRecoveryFinished(RecoveryResult result);
    }

    interface CommitTargetWriter {
        void writeSlotFile(Context ctx, File srcFile, File ceDst, File deDst, boolean syncDeRequired)
                throws IOException;
    }

    interface RecoveryFaultInjector {
        void beforeRestoreFile(String storageKey, File targetFile) throws IOException;
        void beforeDeleteTarget(String storageKey, File targetFile) throws IOException;
        boolean allowCropPrefsCommit();
        boolean allowFollowPrefCommit();
        boolean allowModuleStateRestore();
    }

    interface DraftRevisionFaultHook {
        boolean beforeDraftPrefsCommit(String sessionId, String slot);
    }

    interface DraftRevisionRollbackFaultHook {
        void beforeRollbackSlotFile(String sessionId, String slot) throws IOException;
        boolean allowDeleteSlotOnRollback(String sessionId, String slot);
        void beforeRollbackRawFile(String sessionId, String slot) throws IOException;
        boolean allowDeleteRawOnRollback(String sessionId, String slot);
        boolean allowRollbackPrefsCommit(String sessionId, String slot);
    }

    interface TxLifecycleFaultHook {
        void beforeWriteTxState(File snapDir, String targetState) throws IOException;
        boolean allowDeleteSnapshotDir(File snapDir);
    }

    private static volatile RecoveryFaultInjector sRecoveryFaultInjector = null;
    private static volatile DraftRevisionFaultHook sDraftRevisionFaultHook = null;
    private static volatile DraftRevisionRollbackFaultHook sDraftRevisionRollbackFaultHook = null;
    private static volatile TxLifecycleFaultHook sTxLifecycleFaultHook = null;
    private static final Map<String, String> CORRUPT_SESSIONS_MEM = new ConcurrentHashMap<>();

    static void setRecoveryFaultInjectorForTest(RecoveryFaultInjector injector) {
        sRecoveryFaultInjector = injector;
    }

    static void setDraftRevisionFaultHookForTest(DraftRevisionFaultHook hook) {
        sDraftRevisionFaultHook = hook;
    }

    static void setDraftRevisionRollbackFaultHookForTest(DraftRevisionRollbackFaultHook hook) {
        sDraftRevisionRollbackFaultHook = hook;
    }

    static void setTxLifecycleFaultHookForTest(TxLifecycleFaultHook hook) {
        sTxLifecycleFaultHook = hook;
    }

    static final CommitTargetWriter DEFAULT_COMMIT_WRITER = (ctx, srcFile, ceDst, deDst, syncDeRequired) -> {
        if (srcFile == null || !srcFile.isFile() || srcFile.length() <= 0) {
            throw new IOException("待提交源文件缺失或为空: " + (srcFile == null ? "null" : srcFile.getName()));
        }
        if (ceDst == null) {
            throw new IOException("CE 目标路径为空");
        }
        copyFileAtomicStrict(srcFile, ceDst);
        if (syncDeRequired) {
            if (deDst == null) {
                throw new IOException("DE 目标路径为空: " + ceDst.getName());
            }
            copyFileAtomicStrict(srcFile, deDst);
        }
    };

    static final class SlotBackupEntry {
        final String storageKey;
        final File targetFile;
        final boolean existed;
        final String backupName;
        final File backupCopy;

        SlotBackupEntry(String storageKey, File targetFile, boolean existed, String backupName, File backupCopy) {
            this.storageKey = storageKey;
            this.targetFile = targetFile;
            this.existed = existed;
            this.backupName = backupName != null ? backupName : "";
            this.backupCopy = backupCopy;
        }
    }

    static final class RollbackResult {
        final boolean ok;
        final boolean snapshotPreserved;
        final String detail;

        RollbackResult(boolean ok, boolean snapshotPreserved, String detail) {
            this.ok = ok;
            this.snapshotPreserved = snapshotPreserved;
            this.detail = detail != null ? detail : "";
        }
    }

    static final class RecoveryResult {
        final boolean ok;
        final int recoveredCount;
        final int cleanedCount;
        final int failedCount;
        final List<String> unresolvedTxIds;
        final String detail;

        RecoveryResult(
                boolean ok,
                int recoveredCount,
                int cleanedCount,
                int failedCount,
                List<String> unresolvedTxIds,
                String detail) {
            this.ok = ok;
            this.recoveredCount = recoveredCount;
            this.cleanedCount = cleanedCount;
            this.failedCount = failedCount;
            this.unresolvedTxIds = unresolvedTxIds != null
                    ? Collections.unmodifiableList(new ArrayList<>(unresolvedTxIds))
                    : Collections.emptyList();
            this.detail = detail != null ? detail : "";
        }

        boolean hasChanges() {
            return recoveredCount > 0;
        }
    }

    static final class CommitSnapshot {
        final String txId;
        final File snapshotDir;
        final long createdAtMs;
        final List<SlotBackupEntry> fileEntries = new ArrayList<>();
        final boolean[] cropXPresent = new boolean[ALL_SLOTS.length];
        final boolean[] cropYPresent = new boolean[ALL_SLOTS.length];
        final boolean[] cropZoomPresent = new boolean[ALL_SLOTS.length];
        final float[] cropX = new float[ALL_SLOTS.length];
        final float[] cropY = new float[ALL_SLOTS.length];
        final float[] cropZoom = new float[ALL_SLOTS.length];
        final boolean hasFollowPref;
        final boolean followPrefValue;
        final JSONObject moduleState;

        CommitSnapshot(
                String txId,
                File snapshotDir,
                long createdAtMs,
                boolean hasFollowPref,
                boolean followPrefValue,
                JSONObject moduleState) {
            this.txId = txId;
            this.snapshotDir = snapshotDir;
            this.createdAtMs = createdAtMs;
            this.hasFollowPref = hasFollowPref;
            this.followPrefValue = followPrefValue;
            this.moduleState = moduleState;
        }

        RollbackResult rollbackDetailed(Context ctx, boolean sendBroadcast) {
            RecoveryFaultInjector injector = sRecoveryFaultInjector;
            StringBuilder errs = new StringBuilder();
            boolean allOk = true;

            for (SlotBackupEntry entry : fileEntries) {
                try {
                    if (entry.existed) {
                        if (entry.backupCopy == null || !entry.backupCopy.isFile() || entry.backupCopy.length() <= 0) {
                            allOk = false;
                            appendErr(errs, "备份文件缺失: " + entry.storageKey);
                            continue;
                        }
                        if (injector != null) {
                            injector.beforeRestoreFile(entry.storageKey, entry.targetFile);
                        }
                        copyFileAtomicStrict(entry.backupCopy, entry.targetFile);
                        if (!entry.targetFile.isFile() || entry.targetFile.length() != entry.backupCopy.length()) {
                            allOk = false;
                            appendErr(errs, "还原校验不一致: " + entry.storageKey);
                        }
                    } else if (entry.targetFile != null && entry.targetFile.exists()) {
                        if (injector != null) {
                            injector.beforeDeleteTarget(entry.storageKey, entry.targetFile);
                        }
                        if (!entry.targetFile.delete() || entry.targetFile.exists()) {
                            allOk = false;
                            appendErr(errs, "清理新增文件失败: " + entry.storageKey);
                        }
                    }
                } catch (Throwable t) {
                    allOk = false;
                    appendErr(errs, entry.storageKey + ": " + t.getMessage());
                    Log.w(Root.TAG, "回滚文件失败 " + entry.storageKey + ": " + t.getMessage());
                }
            }

            try {
                if (injector != null && !injector.allowCropPrefsCommit()) {
                    throw new IOException("注入的裁切偏好提交失败");
                }
                SharedPreferences cp = ctx.getSharedPreferences(COMMITTED_CROP_PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = cp.edit();
                for (int i = 0; i < ALL_SLOTS.length; i++) {
                    String slot = ALL_SLOTS[i];
                    if (cropXPresent[i]) {
                        ed.putFloat("x_" + slot, cropX[i]);
                    } else {
                        ed.remove("x_" + slot);
                    }
                    if (cropYPresent[i]) {
                        ed.putFloat("y_" + slot, cropY[i]);
                    } else {
                        ed.remove("y_" + slot);
                    }
                    if (cropZoomPresent[i]) {
                        ed.putFloat("zoom_" + slot, cropZoom[i]);
                    } else {
                        ed.remove("zoom_" + slot);
                    }
                }
                if (!ed.commit()) {
                    allOk = false;
                    appendErr(errs, "裁切偏好 commit 返回 false");
                }
            } catch (Throwable t) {
                allOk = false;
                appendErr(errs, "裁切偏好: " + t.getMessage());
                Log.w(Root.TAG, "回滚裁切偏好失败: " + t.getMessage());
            }

            try {
                if (injector != null && !injector.allowFollowPrefCommit()) {
                    throw new IOException("注入的跟随偏好提交失败");
                }
                SharedPreferences ep = ctx.getSharedPreferences(EDITOR_PREF_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = ep.edit();
                if (hasFollowPref) {
                    ed.putBoolean(KEY_HOME_FOLLOW_LOCK, followPrefValue);
                } else {
                    ed.remove(KEY_HOME_FOLLOW_LOCK);
                }
                if (!ed.commit()) {
                    allOk = false;
                    appendErr(errs, "跟随偏好 commit 返回 false");
                }
            } catch (Throwable t) {
                allOk = false;
                appendErr(errs, "跟随偏好: " + t.getMessage());
                Log.w(Root.TAG, "回滚跟随偏好失败: " + t.getMessage());
            }

            if (moduleState != null) {
                try {
                    if (injector != null && !injector.allowModuleStateRestore()) {
                        throw new IOException("注入的模块状态恢复失败");
                    }
                    if (!PreModuleWallpaperBackup.restoreModuleStateJsonStrict(ctx, moduleState, sendBroadcast)) {
                        allOk = false;
                        appendErr(errs, "模块状态偏好还原失败");
                    }
                } catch (Throwable t) {
                    allOk = false;
                    appendErr(errs, "模块状态: " + t.getMessage());
                }
            }

            String summary = allOk
                    ? "照片、裁切偏好与模块状态已完整回滚"
                    : ("回滚未完全成功（保留事务快照证据）: " + errs);
            ModuleDiagLog.record(ctx, (allOk ? "照片事务回滚完成" : "✖ " + summary));
            return new RollbackResult(allOk, !allOk || snapshotDir.exists(), summary);
        }

        boolean rollback(Context ctx) {
            return rollbackTransactionSnapshot(ctx, this, true).ok;
        }

        static CommitSnapshot fromJournalDir(Context ctx, File dir) throws IOException {
            if (ctx == null || dir == null || !dir.isDirectory()) {
                throw new IOException("快照目录无效");
            }
            File journalFile = new File(dir, SNAPSHOT_JOURNAL_FILE);
            if (!journalFile.isFile() || journalFile.length() <= 0) {
                throw new IOException("事务日志缺失或为空: " + dir.getName());
            }
            String rawJson = new String(
                    java.nio.file.Files.readAllBytes(journalFile.toPath()),
                    StandardCharsets.UTF_8);
            final JSONObject root;
            try {
                root = new JSONObject(rawJson);
            } catch (Throwable t) {
                throw new IOException("事务日志 JSON 解析失败: " + t.getMessage(), t);
            }
            if (root.optInt("version", -1) != JOURNAL_VERSION) {
                throw new IOException("不支持的事务日志版本: " + root.optInt("version", -1));
            }
            String txId = root.optString("txId", "");
            if (!isValidTxId(txId) || !dir.getName().equals(txId)) {
                throw new IOException("事务 ID 与目录不匹配: " + txId);
            }
            long createdAtMs = root.optLong("createdAtMs", 0L);

            JSONObject followObj = root.optJSONObject("follow");
            if (followObj == null || !followObj.has("present") || !followObj.has("value")) {
                throw new IOException("事务日志缺少 follow 字段");
            }
            boolean hasFollow = followObj.optBoolean("present", false);
            boolean followVal = followObj.optBoolean("value", true);

            JSONObject modObj = root.optJSONObject("moduleState");
            if (modObj == null || !modObj.has("aod_style") || !modObj.has("last_kind")) {
                throw new IOException("事务日志缺少 moduleState 字段");
            }

            CommitSnapshot snap = new CommitSnapshot(txId, dir, createdAtMs, hasFollow, followVal, modObj);

            JSONObject cropRoot = root.optJSONObject("cropPrefs");
            if (cropRoot == null) {
                throw new IOException("事务日志缺少 cropPrefs 字段");
            }
            for (int i = 0; i < ALL_SLOTS.length; i++) {
                String slot = ALL_SLOTS[i];
                JSONObject item = cropRoot.optJSONObject(slot);
                if (item == null || !item.has("hasX") || !item.has("hasY") || !item.has("hasZoom")) {
                    throw new IOException("cropPrefs 缺少槽位结构: " + slot);
                }
                snap.cropXPresent[i] = item.optBoolean("hasX", false);
                snap.cropYPresent[i] = item.optBoolean("hasY", false);
                snap.cropZoomPresent[i] = item.optBoolean("hasZoom", false);
                if (snap.cropXPresent[i]) {
                    snap.cropX[i] = parseFiniteFloat(item.optString("x", ""), "x_" + slot);
                }
                if (snap.cropYPresent[i]) {
                    snap.cropY[i] = parseFiniteFloat(item.optString("y", ""), "y_" + slot);
                }
                if (snap.cropZoomPresent[i]) {
                    snap.cropZoom[i] = parseFiniteFloat(item.optString("zoom", ""), "zoom_" + slot);
                }
            }

            JSONArray filesArr = root.optJSONArray("files");
            if (filesArr == null || filesArr.length() != ALL_STORAGE_KEYS.length) {
                throw new IOException("files 条目数量不完整");
            }
            Set<String> seenKeys = new HashSet<>();
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject fe = filesArr.optJSONObject(i);
                if (fe == null || !fe.has("key") || !fe.has("existed")) {
                    throw new IOException("files 条目格式损坏 #" + i);
                }
                String key = fe.optString("key", "");
                File ownedTarget = resolveOwnedTargetFile(ctx, key);
                if (ownedTarget == null || !seenKeys.add(key)) {
                    throw new IOException("非法或重复的存储键: " + key);
                }
                boolean existed = fe.optBoolean("existed", false);
                String bakName = fe.optString("backup", "");
                File bakFile = null;
                if (existed) {
                    if (!isValidBackupFileName(bakName)) {
                        throw new IOException("非法备份文件名: " + bakName);
                    }
                    bakFile = new File(dir, bakName);
                    ensureWithinParent(dir, bakFile);
                    if (!bakFile.isFile() || bakFile.length() <= 0) {
                        throw new IOException("备份文件缺失或为空: " + bakName);
                    }
                }
                snap.fileEntries.add(new SlotBackupEntry(key, ownedTarget, existed, bakName, bakFile));
            }
            for (String expected : ALL_STORAGE_KEYS) {
                if (!seenKeys.contains(expected)) {
                    throw new IOException("事务日志遗漏存储键: " + expected);
                }
            }
            return snap;
        }
    }

    static final class CommitResult {
        final boolean ok;
        final String detail;
        final CommitSnapshot snapshot;

        CommitResult(boolean ok, String detail, CommitSnapshot snapshot) {
            this.ok = ok;
            this.detail = detail != null ? detail : "";
            this.snapshot = snapshot;
        }
    }

    private PhotoDraftStore() {
    }

    static String newSessionId() {
        long r = RNG.nextLong() & 0xFFFFFFFFL;
        return "d" + System.currentTimeMillis() + "_" + Long.toHexString(r);
    }

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null
                && !sessionId.contains("..")
                && !sessionId.contains("/")
                && !sessionId.contains("\\")
                && sessionId.matches("d[0-9a-zA-Z_]{4,48}");
    }

    static boolean isValidTxId(String txId) {
        return txId != null
                && !txId.contains("..")
                && !txId.contains("/")
                && !txId.contains("\\")
                && txId.matches("tx_[0-9a-zA-Z_]{4,64}");
    }

    static boolean isSupportedSlot(String slot) {
        return PhotoStore.LOCK.equals(slot)
                || PhotoStore.HOME.equals(slot)
                || PhotoStore.FRAME.equals(slot);
    }

    static boolean isValidStagedFileName(String name) {
        return name != null
                && !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\")
                && name.matches("photo-[A-Za-z0-9._-]+");
    }

    static boolean isValidBackupFileName(String name) {
        return name != null
                && !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\")
                && name.matches("[A-Za-z0-9._-]{5,80}\\.bak");
    }

    static File resolveOwnedTargetFile(Context ctx, String storageKey) {
        if (ctx == null || storageKey == null) return null;
        final boolean isDe;
        final String name;
        if (storageKey.startsWith("ce:")) {
            isDe = false;
            name = storageKey.substring(3);
        } else if (storageKey.startsWith("de:")) {
            isDe = true;
            name = storageKey.substring(3);
        } else {
            return null;
        }
        String baseSlot = name.startsWith("raw_") ? name.substring(4) : name;
        if (!isSupportedSlot(baseSlot)) {
            return null;
        }
        return isDe ? PhotoStore.deFile(ctx, name) : PhotoStore.file(ctx, name);
    }

    private static float parseFiniteFloat(String s, String label) throws IOException {
        if (s == null || s.isEmpty()) {
            throw new IOException("无效浮点值 " + label);
        }
        try {
            float v = Float.parseFloat(s);
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new IOException("非有限浮点值 " + label);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IOException("无法解析浮点值 " + label, e);
        }
    }

    private static void appendErr(StringBuilder sb, String msg) {
        if (msg == null || msg.isEmpty()) return;
        if (sb.length() > 0) sb.append("; ");
        sb.append(msg);
    }

    // ---- 活跃草稿租约 & 启动恢复协调 ----

    static void acquireDraftLease(String sessionId, Object owner) {
        if (!isValidSessionId(sessionId) || owner == null) return;
        synchronized (LEASE_LOCK) {
            PENDING_RECREATE_SESSIONS.remove(sessionId);
            Set<Object> owners = ACTIVE_LEASES.get(sessionId);
            if (owners == null) {
                owners = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
                ACTIVE_LEASES.put(sessionId, owners);
            }
            owners.add(owner);
        }
    }

    static void releaseDraftLease(String sessionId, Object owner, boolean pendingRecreate) {
        if (!isValidSessionId(sessionId) || owner == null) return;
        synchronized (LEASE_LOCK) {
            Set<Object> owners = ACTIVE_LEASES.get(sessionId);
            if (owners != null) {
                owners.remove(owner);
                if (owners.isEmpty()) {
                    ACTIVE_LEASES.remove(sessionId);
                }
            }
            if (pendingRecreate) {
                PENDING_RECREATE_SESSIONS.put(sessionId, System.currentTimeMillis() + ROTATION_GRACE_MS);
            }
        }
    }

    static void releaseDraftLeaseAndDiscardIfIdle(Context ctx, String sessionId, Object owner) {
        if (!isValidSessionId(sessionId)) return;
        boolean shouldDelete = false;
        synchronized (LEASE_LOCK) {
            if (owner != null) {
                Set<Object> owners = ACTIVE_LEASES.get(sessionId);
                if (owners != null) {
                    owners.remove(owner);
                    if (owners.isEmpty()) {
                        ACTIVE_LEASES.remove(sessionId);
                    }
                }
            }
            PENDING_RECREATE_SESSIONS.remove(sessionId);
            shouldDelete = !isSessionActiveLocked(sessionId);
        }
        if (shouldDelete && ctx != null) {
            discardSession(ctx, sessionId);
        }
    }

    static boolean isSessionActive(String sessionId) {
        if (!isValidSessionId(sessionId)) return false;
        synchronized (LEASE_LOCK) {
            return isSessionActiveLocked(sessionId);
        }
    }

    private static boolean isSessionActiveLocked(String sessionId) {
        Set<Object> owners = ACTIVE_LEASES.get(sessionId);
        if (owners != null && !owners.isEmpty()) {
            return true;
        }
        Long deadline = PENDING_RECREATE_SESSIONS.get(sessionId);
        if (deadline != null) {
            if (System.currentTimeMillis() <= deadline) {
                return true;
            }
            PENDING_RECREATE_SESSIONS.remove(sessionId);
        }
        return false;
    }

    static void addStartupRecoveryListener(StartupRecoveryListener listener) {
        if (listener != null && !sRecoveryListeners.contains(listener)) {
            sRecoveryListeners.add(listener);
        }
    }

    static void removeStartupRecoveryListener(StartupRecoveryListener listener) {
        if (listener != null) {
            sRecoveryListeners.remove(listener);
        }
    }

    static RecoveryResult getLastStartupRecoveryResult() {
        return sLastStartupRecoveryResult;
    }

    static void resetStartupRecoveryForTest() {
        synchronized (STARTUP_RECOVERY_LOCK) {
            sStartupRecoveryTriggered = false;
            sStartupRecoveryWaitingUnlock = false;
            sStartupRecoveryLatch = new CountDownLatch(0);
            sLastStartupRecoveryResult = null;
        }
    }

    private static boolean isUserUnlockedSafe(Context ctx) {
        if (ctx == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Object umObj = ctx.getSystemService(Context.USER_SERVICE);
                if (umObj instanceof UserManager) {
                    return ((UserManager) umObj).isUserUnlocked();
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static boolean isMainLooperThread() {
        try {
            Looper main = Looper.getMainLooper();
            return main != null && main == Looper.myLooper();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 进程启动路径通用入口（Application / ContentProvider / WallpaperService / Activity）：
     * 在后台线程获取全局事务锁并执行 {@link #recoverIncompleteCommits(Context)}，避免主线程做重文件拷贝。
     */
    static void triggerStartupRecoveryAsync(Context ctx) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final boolean unlocked = isUserUnlockedSafe(appCtx);
        final CountDownLatch latch;
        synchronized (STARTUP_RECOVERY_LOCK) {
            if (sStartupRecoveryTriggered) {
                if (sStartupRecoveryWaitingUnlock && unlocked) {
                    // 解锁后重新触发完整恢复
                } else {
                    return;
                }
            }
            if (!unlocked) {
                sStartupRecoveryTriggered = true;
                sStartupRecoveryWaitingUnlock = true;
                sStartupRecoveryLatch = new CountDownLatch(0);
                return;
            }
            sStartupRecoveryTriggered = true;
            sStartupRecoveryWaitingUnlock = false;
            latch = new CountDownLatch(1);
            sStartupRecoveryLatch = latch;
        }

        Thread t = new Thread(() -> {
            RecoveryResult res = null;
            try {
                res = recoverIncompleteCommits(appCtx);
                sLastStartupRecoveryResult = res;
            } catch (Throwable th) {
                Log.w(Root.TAG, "启动后台事务恢复异常: " + th.getMessage(), th);
            } finally {
                latch.countDown();
            }
            if (res != null) {
                for (StartupRecoveryListener listener : sRecoveryListeners) {
                    try {
                        listener.onStartupRecoveryFinished(res);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "QihangStartupRecovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 后台消费者（如 {@link WallpaperPreviewProvider}、{@link LensRenderer} GL 线程、{@link ClockConfigProvider} Binder 线程）
     * 读取正式照片与配置前调用：若存在未完成的启动恢复则等待完成，绝不在主线程阻塞恢复 I/O。
     */
    static void awaitStartupRecoveryIfNonMainThread(Context ctx) {
        if (ctx == null) return;
        if (!sStartupRecoveryTriggered) {
            triggerStartupRecoveryAsync(ctx);
        }
        if (isMainLooperThread()) {
            return;
        }
        CountDownLatch latch = sStartupRecoveryLatch;
        if (latch != null && latch.getCount() > 0) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static File draftRootDir(Context ctx) {
        return new File(ctx.getFilesDir(), DRAFT_ROOT_DIR);
    }

    static File sessionDir(Context ctx, String sessionId) throws IOException {
        if (ctx == null) throw new IOException("Context 为空");
        if (!isValidSessionId(sessionId)) {
            throw new IOException("非法草稿会话 ID: " + sessionId);
        }
        File root = draftRootDir(ctx);
        File dir = new File(root, sessionId);
        ensureWithinParent(root, dir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建草稿目录");
        }
        return dir;
    }

    private static File existingSessionDirOrNull(Context ctx, String sessionId) {
        if (ctx == null || !isValidSessionId(sessionId)) return null;
        try {
            File root = draftRootDir(ctx);
            File dir = new File(root, sessionId);
            ensureWithinParent(root, dir);
            return dir.isDirectory() ? dir : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    static File slotFile(Context ctx, String sessionId, String slot) throws IOException {
        if (!isSupportedSlot(slot)) {
            throw new IOException("非法草稿照片槽位: " + slot);
        }
        File dir = sessionDir(ctx, sessionId);
        File out = new File(dir, slot);
        ensureWithinParent(dir, out);
        return out;
    }

    static File rawFile(Context ctx, String sessionId, String slot) throws IOException {
        if (!isSupportedSlot(slot)) {
            throw new IOException("非法草稿原图槽位: " + slot);
        }
        File dir = sessionDir(ctx, sessionId);
        File out = new File(dir, "raw_" + slot);
        ensureWithinParent(dir, out);
        return out;
    }

    static File resolveCropSourceFile(Context ctx, String sessionId, String stagedFileName) {
        if (ctx == null || !isValidStagedFileName(stagedFileName)) {
            return null;
        }
        try {
            if (isValidSessionId(sessionId)) {
                File dir = sessionDir(ctx, sessionId);
                File candidate = new File(dir, stagedFileName);
                ensureWithinParent(dir, candidate);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
            File legacy = new File(ctx.getFilesDir(), stagedFileName);
            ensureWithinParent(ctx.getFilesDir(), legacy);
            return legacy.isFile() ? legacy : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    static SharedPreferences cropPrefs(Context ctx, String sessionId) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("Invalid draft sessionId: " + sessionId);
        }
        return ctx.getSharedPreferences(DRAFT_PREF_PREFIX + sessionId, Context.MODE_PRIVATE);
    }

    static boolean isSlotDirty(Context ctx, String sessionId, String slot) {
        if (ctx == null || !isValidSessionId(sessionId) || !isSupportedSlot(slot)) {
            return false;
        }
        return cropPrefs(ctx, sessionId).getBoolean(KEY_DIRTY_PREFIX + slot, false);
    }

    static boolean hasAnyDirtyPhoto(Context ctx, String sessionId) {
        for (String slot : ALL_SLOTS) {
            if (isSlotDirty(ctx, sessionId, slot)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDraftSlot(Context ctx, String sessionId, String slot) {
        File dir = existingSessionDirOrNull(ctx, sessionId);
        if (dir == null || !isSupportedSlot(slot)) return false;
        File f = new File(dir, slot);
        return f.isFile() && f.length() > 0;
    }

    static boolean hasEffective(Context ctx, String sessionId, String slot) {
        return hasDraftSlot(ctx, sessionId, slot) || PhotoStore.has(ctx, slot);
    }

    /**
     * 解析编辑器预览应使用的照片源标识：
     * 草稿中存在有效文件时返回受校验的草稿绝对路径，否则退回正式槽位名。
     */
    static String effectiveSource(Context ctx, String sessionId, String slot) {
        if (hasDraftSlot(ctx, sessionId, slot)) {
            try {
                return slotFile(ctx, sessionId, slot).getCanonicalPath();
            } catch (IOException ignored) {
            }
        }
        return slot;
    }

    static boolean isValidatedDraftSlotPath(Context ctx, String path) {
        if (ctx == null || path == null || path.isEmpty() || path.contains("..")) return false;
        try {
            File root = draftRootDir(ctx);
            File f = new File(path);
            if (!isSupportedSlot(f.getName())) {
                return false;
            }
            ensureWithinParent(root, f);
            return f.isFile() && f.length() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    static Bitmap loadSource(Context ctx, String slotOrValidatedDraftPath) {
        if (ctx == null || slotOrValidatedDraftPath == null) return null;
        if (isSupportedSlot(slotOrValidatedDraftPath)) {
            return PhotoStore.load(ctx, slotOrValidatedDraftPath);
        }
        if (!isValidatedDraftSlotPath(ctx, slotOrValidatedDraftPath)) {
            Log.w(Root.TAG, "拒绝加载未通过校验的草稿路径");
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(slotOrValidatedDraftPath, o);
        } catch (Throwable t) {
            Log.w(Root.TAG, "解码草稿照片失败: " + t.getMessage());
            return null;
        }
    }

    static File stageFromUri(Context ctx, String sessionId, Uri uri, String targetSlot) throws IOException {
        if (!isSupportedSlot(targetSlot)) {
            throw new IOException("非法目标槽位: " + targetSlot);
        }
        File dir = sessionDir(ctx, sessionId);
        String fileName = "photo-" + UUID.randomUUID() + ".jpg";
        File out = new File(dir, fileName);
        ensureWithinParent(dir, out);

        final int maxDim = 2560;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("无法打开选中的图片");
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) {
            sample *= 2;
        }
        BitmapFactory.Options dec = new BitmapFactory.Options();
        dec.inSampleSize = sample;
        dec.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, dec);
        }
        if (bmp == null) {
            throw new IOException("无法解码选中的图片");
        }
        int rotate = readExifRotation(ctx, uri);
        if (rotate != 0 || Math.max(bmp.getWidth(), bmp.getHeight()) > maxDim) {
            Matrix m = new Matrix();
            if (rotate != 0) m.postRotate(rotate);
            int curMax = Math.max(bmp.getWidth(), bmp.getHeight());
            if (curMax > maxDim) {
                float s = (float) maxDim / curMax;
                m.postScale(s, s);
            }
            Bitmap transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (transformed != bmp) {
                bmp.recycle();
                bmp = transformed;
            }
        }

        File tmp = new File(dir, fileName + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)) {
                    throw new IOException("编码暂存图失败");
                }
                fos.flush();
                fos.getFD().sync();
            }
            atomicReplace(tmp, out);
        } finally {
            bmp.recycle();
            if (tmp.exists()) {
                tmp.delete();
            }
        }
        // 不在此处擦除草稿裁切坐标；若用户在裁切页取消，已有可用草稿必须原封不动保留
        return out;
    }

    static File stageExistingOrFallback(Context ctx, String sessionId, String targetSlot) {
        if (!isSupportedSlot(targetSlot)) return null;
        File staged = null;
        try {
            File dir = sessionDir(ctx, sessionId);
            String fileName = "photo-" + UUID.randomUUID() + ".jpg";
            staged = new File(dir, fileName);
            ensureWithinParent(dir, staged);

            File candidate = resolveExistingSourceCandidate(ctx, sessionId, targetSlot);
            if (candidate != null) {
                copyFileAtomicStrict(candidate, staged);
            } else {
                copyAssetDefaultToFile(ctx, staged);
            }

            SharedPreferences dp = cropPrefs(ctx, sessionId);
            if (!dp.contains("zoom_" + targetSlot)) {
                SharedPreferences cp = ctx.getSharedPreferences(COMMITTED_CROP_PREFS, Context.MODE_PRIVATE);
                if (cp.contains("zoom_" + targetSlot)) {
                    boolean committed = dp.edit()
                            .putFloat("x_" + targetSlot, cp.getFloat("x_" + targetSlot, 0.5f))
                            .putFloat("y_" + targetSlot, cp.getFloat("y_" + targetSlot, 0.5f))
                            .putFloat("zoom_" + targetSlot, cp.getFloat("zoom_" + targetSlot, 1.0f))
                            .commit();
                    if (!committed) {
                        staged.delete();
                        return null;
                    }
                }
            }
            return staged;
        } catch (Throwable t) {
            if (staged != null && staged.exists()) {
                staged.delete();
            }
            Log.e(Root.TAG, "准备草稿裁切源文件失败", t);
            return null;
        }
    }

    private static File resolveExistingSourceCandidate(Context ctx, String sessionId, String targetSlot) {
        try {
            File draftRaw = rawFile(ctx, sessionId, targetSlot);
            if (draftRaw.isFile() && draftRaw.length() > 0) return draftRaw;
            File draftSlot = slotFile(ctx, sessionId, targetSlot);
            if (draftSlot.isFile() && draftSlot.length() > 0) return draftSlot;
        } catch (IOException ignored) {
        }
        File committedRaw = PhotoStore.rawFile(ctx, targetSlot);
        if (committedRaw.isFile() && committedRaw.length() > 0) return committedRaw;
        File committedDeRaw = PhotoStore.deFile(ctx, "raw_" + targetSlot);
        if (committedDeRaw.isFile() && committedDeRaw.length() > 0) return committedDeRaw;
        File committedSlot = PhotoStore.file(ctx, targetSlot);
        if (committedSlot.isFile() && committedSlot.length() > 0) return committedSlot;
        File committedDe = PhotoStore.deFile(ctx, targetSlot);
        if (committedDe.isFile() && committedDe.length() > 0) return committedDe;
        return null;
    }

    static void saveCroppedToDraft(
            Context ctx,
            String sessionId,
            String targetSlot,
            File rawInputFile,
            Bitmap croppedImage,
            float saveX,
            float saveY,
            float saveZoom) throws IOException {
        commitDraftSlotRevision(
                ctx,
                sessionId,
                targetSlot,
                croppedImage,
                rawInputFile,
                false,
                true,
                saveX,
                saveY,
                saveZoom);
    }

    static boolean loadDefaultExampleToDraft(Context ctx, String sessionId, String targetSlot) {
        if (!isSupportedSlot(targetSlot)) return false;
        try {
            Bitmap bmp;
            try (InputStream in = ctx.getAssets().open("photo/default.jpg")) {
                bmp = BitmapFactory.decodeStream(in);
            }
            if (bmp == null) return false;
            try {
                commitDraftSlotRevision(
                        ctx,
                        sessionId,
                        targetSlot,
                        bmp,
                        null,
                        true,
                        false,
                        0.5f,
                        0.5f,
                        1.0f);
            } finally {
                bmp.recycle();
            }
            return true;
        } catch (Throwable t) {
            Log.w(Root.TAG, "草稿载入示例相片失败: " + t.getMessage());
            return false;
        }
    }

    static boolean syncFrameToLockDraftWithDefaultCrop(Context ctx, String sessionId) {
        try {
            File src = resolveExistingSourceCandidate(ctx, sessionId, PhotoStore.LOCK);
            Bitmap decoded;
            if (src != null) {
                decoded = BitmapFactory.decodeFile(src.getAbsolutePath());
            } else {
                try (InputStream in = ctx.getAssets().open("photo/default.jpg")) {
                    decoded = BitmapFactory.decodeStream(in);
                }
            }
            if (decoded == null) return false;
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int side = Math.min(w, h);
            if (side <= 0) {
                decoded.recycle();
                return false;
            }
            int x = (w - side) / 2;
            int y = (h - side) / 2;
            Bitmap cropped = Bitmap.createBitmap(decoded, x, y, side, side);
            if (cropped != decoded) {
                decoded.recycle();
            }
            try {
                commitDraftSlotRevision(
                        ctx,
                        sessionId,
                        PhotoStore.FRAME,
                        cropped,
                        src,
                        src == null,
                        false,
                        0.5f,
                        0.5f,
                        1.0f);
            } finally {
                cropped.recycle();
            }
            return true;
        } catch (Throwable t) {
            Log.e(Root.TAG, "同步留光相框草稿失败", t);
            return false;
        }
    }

    /**
     * 先在同会话临时文件中完成位图 PNG、原图与前一版本备份的落盘及 fsync，再发布槽位、原图与草稿偏好；
     * 若 {@code SharedPreferences.commit()} 返回 false 或期间任一步抛异常，立即将草稿槽位、原图及坐标/dirty 偏好
     * 原封不动恢复至本次修订前状态。
     */
    private static void commitDraftSlotRevision(
            Context ctx,
            String sessionId,
            String targetSlot,
            Bitmap slotBitmap,
            File rawSourceFile,
            boolean rawFromDefaultAsset,
            boolean hasCropCoords,
            float saveX,
            float saveY,
            float saveZoom) throws IOException {
        if (!isSupportedSlot(targetSlot)) {
            throw new IOException("非法目标槽位: " + targetSlot);
        }
        File dir = sessionDir(ctx, sessionId);
        File dstSlot = slotFile(ctx, sessionId, targetSlot);
        File dstRaw = rawFile(ctx, sessionId, targetSlot);
        String nonce = String.valueOf(System.nanoTime());

        File stagedSlotTmp = new File(dir, targetSlot + ".rev_stage." + nonce);
        File stagedRawTmp = null;
        File prevSlotBak = new File(dir, targetSlot + ".rev_prev." + nonce);
        File prevRawBak = new File(dir, "raw_" + targetSlot + ".rev_prev." + nonce);

        SharedPreferences dp = cropPrefs(ctx, sessionId);
        boolean priorXPresent = dp.contains("x_" + targetSlot);
        boolean priorYPresent = dp.contains("y_" + targetSlot);
        boolean priorZoomPresent = dp.contains("zoom_" + targetSlot);
        float priorX = dp.getFloat("x_" + targetSlot, 0.5f);
        float priorY = dp.getFloat("y_" + targetSlot, 0.5f);
        float priorZoom = dp.getFloat("zoom_" + targetSlot, 1.0f);
        boolean priorDirtyPresent = dp.contains(KEY_DIRTY_PREFIX + targetSlot);
        boolean priorDirtyVal = dp.getBoolean(KEY_DIRTY_PREFIX + targetSlot, false);

        boolean priorSlotExisted = dstSlot.isFile() && dstSlot.length() > 0;
        boolean priorRawExisted = dstRaw.isFile() && dstRaw.length() > 0;
        boolean slotPublished = false;
        boolean rawPublished = false;
        boolean canCleanupPrevBackups = false;

        try {
            // 1. 先完整编码并 fsync 新草稿图与新原图到临时文件（未修改任何现有草稿）
            writeBitmapPngToFileStrict(slotBitmap, stagedSlotTmp);
            if (rawFromDefaultAsset) {
                stagedRawTmp = new File(dir, "raw_" + targetSlot + ".rev_stage." + nonce);
                copyAssetDefaultToFileStrict(ctx, stagedRawTmp);
            } else if (rawSourceFile != null && rawSourceFile.isFile() && rawSourceFile.length() > 0) {
                stagedRawTmp = new File(dir, "raw_" + targetSlot + ".rev_stage." + nonce);
                copyFileAtomicStrict(rawSourceFile, stagedRawTmp);
            }

            // 2. 备份修订前草稿（如有）
            if (priorSlotExisted) {
                copyFileAtomicStrict(dstSlot, prevSlotBak);
            }
            if (priorRawExisted) {
                copyFileAtomicStrict(dstRaw, prevRawBak);
            }

            // 3. 发布文件与偏好（任一步失败或 commit()==false 立即恢复前版）
            atomicReplace(stagedSlotTmp, dstSlot);
            slotPublished = true;
            if (stagedRawTmp != null) {
                atomicReplace(stagedRawTmp, dstRaw);
                rawPublished = true;
            }

            DraftRevisionFaultHook hook = sDraftRevisionFaultHook;
            if (hook != null && !hook.beforeDraftPrefsCommit(sessionId, targetSlot)) {
                throw new IOException("草稿偏好提交返回 false（测试注入）");
            }

            SharedPreferences.Editor ed = dp.edit();
            if (hasCropCoords) {
                ed.putFloat("x_" + targetSlot, saveX)
                        .putFloat("y_" + targetSlot, saveY)
                        .putFloat("zoom_" + targetSlot, saveZoom);
            } else {
                ed.remove("x_" + targetSlot)
                        .remove("y_" + targetSlot)
                        .remove("zoom_" + targetSlot);
            }
            ed.putBoolean(KEY_DIRTY_PREFIX + targetSlot, true);
            if (!ed.commit()) {
                throw new IOException("提交草稿裁切偏好失败");
            }
            canCleanupPrevBackups = true;
            clearCorruptRevisionForSlot(ctx, sessionId, targetSlot);
        } catch (Throwable t) {
            DraftRevisionRollbackFaultHook rbHook = sDraftRevisionRollbackFaultHook;
            boolean revisionRollbackOk = true;
            StringBuilder rbErrSb = new StringBuilder();
            if (slotPublished) {
                try {
                    if (priorSlotExisted) {
                        if (!prevSlotBak.isFile()) {
                            throw new IOException("修订前草稿槽位备份缺失: " + prevSlotBak.getName());
                        }
                        if (rbHook != null) {
                            rbHook.beforeRollbackSlotFile(sessionId, targetSlot);
                        }
                        copyFileAtomicStrict(prevSlotBak, dstSlot);
                    } else if (dstSlot.exists()) {
                        if (rbHook != null && !rbHook.allowDeleteSlotOnRollback(sessionId, targetSlot)) {
                            throw new IOException("删除新建草稿槽位被阻止: " + dstSlot.getName());
                        }
                        if (!dstSlot.delete()) {
                            throw new IOException("无法删除新建草稿槽位: " + dstSlot.getName());
                        }
                    }
                } catch (Throwable rbErr) {
                    revisionRollbackOk = false;
                    appendErr(rbErrSb, "槽位回滚失败(" + targetSlot + "): " + rbErr.getMessage());
                    Log.w(Root.TAG, "回滚草稿槽位失败: " + rbErr.getMessage());
                }
            }
            if (rawPublished) {
                try {
                    if (priorRawExisted) {
                        if (!prevRawBak.isFile()) {
                            throw new IOException("修订前草稿原图备份缺失: " + prevRawBak.getName());
                        }
                        if (rbHook != null) {
                            rbHook.beforeRollbackRawFile(sessionId, targetSlot);
                        }
                        copyFileAtomicStrict(prevRawBak, dstRaw);
                    } else if (dstRaw.exists()) {
                        if (rbHook != null && !rbHook.allowDeleteRawOnRollback(sessionId, targetSlot)) {
                            throw new IOException("删除新建草稿原图被阻止: " + dstRaw.getName());
                        }
                        if (!dstRaw.delete()) {
                            throw new IOException("无法删除新建草稿原图: " + dstRaw.getName());
                        }
                    }
                } catch (Throwable rbErr) {
                    revisionRollbackOk = false;
                    appendErr(rbErrSb, "原图回滚失败(" + targetSlot + "): " + rbErr.getMessage());
                    Log.w(Root.TAG, "回滚草稿原图失败: " + rbErr.getMessage());
                }
            }
            if (slotPublished || rawPublished) {
                try {
                    if (rbHook != null && !rbHook.allowRollbackPrefsCommit(sessionId, targetSlot)) {
                        throw new IOException("回滚草稿裁切偏好被阻止");
                    }
                    SharedPreferences.Editor rbEd = dp.edit();
                    if (priorXPresent) rbEd.putFloat("x_" + targetSlot, priorX);
                    else rbEd.remove("x_" + targetSlot);
                    if (priorYPresent) rbEd.putFloat("y_" + targetSlot, priorY);
                    else rbEd.remove("y_" + targetSlot);
                    if (priorZoomPresent) rbEd.putFloat("zoom_" + targetSlot, priorZoom);
                    else rbEd.remove("zoom_" + targetSlot);
                    if (priorDirtyPresent) rbEd.putBoolean(KEY_DIRTY_PREFIX + targetSlot, priorDirtyVal);
                    else rbEd.remove(KEY_DIRTY_PREFIX + targetSlot);
                    if (!rbEd.commit()) {
                        throw new IOException("草稿偏好回滚 commit() 返回 false");
                    }
                } catch (Throwable rbErr) {
                    revisionRollbackOk = false;
                    appendErr(rbErrSb, "偏好回滚失败(" + targetSlot + "): " + rbErr.getMessage());
                    Log.w(Root.TAG, "回滚草稿偏好失败: " + rbErr.getMessage());
                }
            }
            if (!revisionRollbackOk) {
                String detail = rbErrSb.toString();
                markSessionRevisionCorrupt(ctx, sessionId, targetSlot, detail, prevSlotBak, prevRawBak);
                ModuleDiagLog.record(ctx, "✖ 草稿修订回滚失败(" + targetSlot + ")：" + detail
                        + "（已保留恢复备份并禁止该草稿用于应用）");
                throw new IOException("草稿修订失败且回滚未完全成功（已保留恢复备份并锁定该草稿禁止应用，请重新裁切该槽位或取消重选）："
                        + detail + " | 原错误：" + t.getMessage(), t);
            }
            canCleanupPrevBackups = true;
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("更新草稿槽位失败: " + t.getMessage(), t);
        } finally {
            if (stagedSlotTmp.exists()) stagedSlotTmp.delete();
            if (stagedRawTmp != null && stagedRawTmp.exists()) stagedRawTmp.delete();
            if (canCleanupPrevBackups) {
                if (prevSlotBak.exists()) prevSlotBak.delete();
                if (prevRawBak.exists()) prevRawBak.delete();
            }
        }
    }

    static boolean hasCorruptRevision(Context ctx, String sessionId) {
        if (!isValidSessionId(sessionId)) return false;
        if (CORRUPT_SESSIONS_MEM.containsKey(sessionId)) {
            return true;
        }
        File dir = existingSessionDirOrNull(ctx, sessionId);
        if (dir == null) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f != null && f.isFile() && f.getName().startsWith(CORRUPT_REVISION_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    static String getCorruptRevisionDetail(Context ctx, String sessionId) {
        if (!isValidSessionId(sessionId)) return "";
        String mem = CORRUPT_SESSIONS_MEM.get(sessionId);
        File dir = existingSessionDirOrNull(ctx, sessionId);
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                StringBuilder sb = new StringBuilder();
                for (File f : files) {
                    if (f != null && f.isFile() && f.getName().startsWith(CORRUPT_REVISION_PREFIX)) {
                        try {
                            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                            appendErr(sb, txt);
                        } catch (Throwable ignored) {
                            appendErr(sb, f.getName());
                        }
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        }
        return mem != null ? mem : "";
    }

    private static void markSessionRevisionCorrupt(
            Context ctx,
            String sessionId,
            String targetSlot,
            String detail,
            File prevSlotBak,
            File prevRawBak) {
        String safeDetail = (detail != null ? detail : "")
                + (prevSlotBak != null && prevSlotBak.isFile() ? (" [slotBak=" + prevSlotBak.getName() + "]") : "")
                + (prevRawBak != null && prevRawBak.isFile() ? (" [rawBak=" + prevRawBak.getName() + "]") : "");
        CORRUPT_SESSIONS_MEM.put(sessionId, safeDetail);
        try {
            File dir = sessionDir(ctx, sessionId);
            File marker = new File(dir, CORRUPT_REVISION_PREFIX + targetSlot);
            ensureWithinParent(dir, marker);
            writeTextFileAtomicStrict(safeDetail, marker);
        } catch (Throwable t) {
            Log.w(Root.TAG, "写入草稿损坏标记失败: " + t.getMessage());
        }
    }

    private static void clearCorruptRevisionForSlot(Context ctx, String sessionId, String targetSlot) {
        if (!isValidSessionId(sessionId) || !isSupportedSlot(targetSlot)) return;
        File dir = existingSessionDirOrNull(ctx, sessionId);
        boolean stillHasMarker = false;
        if (dir != null) {
            File marker = new File(dir, CORRUPT_REVISION_PREFIX + targetSlot);
            if (marker.exists()) {
                marker.delete();
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f == null) continue;
                    String n = f.getName();
                    if (n.startsWith(targetSlot + ".rev_prev.") || n.startsWith("raw_" + targetSlot + ".rev_prev.")) {
                        f.delete();
                    } else if (f.isFile() && n.startsWith(CORRUPT_REVISION_PREFIX)) {
                        stillHasMarker = true;
                    }
                }
            }
        }
        if (!stillHasMarker) {
            CORRUPT_SESSIONS_MEM.remove(sessionId);
        }
    }

    // ---- 持久化事务快照、日志与生命周期管理 ----

    /**
     * 在全局事务锁内创建并持久化事务前完整快照（CE/DE 槽位与原图、逐键裁切偏好、跟随偏好、模块状态），
     * 并在全部数据落盘 {@code FD.sync()} 后将 {@code tx_state} 原子切换为 {@link #TX_STATE_PENDING}。
     */
    static CommitSnapshot beginTransactionSnapshot(Context ctx) throws IOException {
        if (ctx == null) {
            throw new IOException("Context 为空");
        }
        PreModuleWallpaperBackup.lockTransaction();
        try {
            File snapRoot = new File(ctx.getFilesDir(), SNAPSHOT_ROOT_DIR);
            long nowMs = System.currentTimeMillis();
            String txId = "tx_" + nowMs + "_" + Long.toHexString(RNG.nextLong() & 0xFFFFFFFFL);
            File snapDir = new File(snapRoot, txId);
            if (!snapDir.mkdirs()) {
                throw new IOException("无法创建事务快照目录");
            }
            ACTIVE_TX_IDS.add(txId);
            boolean promotedToPending = false;
            try {
                writeTxStateAtomic(snapDir, TX_STATE_PREPARING);

                SharedPreferences ep = ctx.getSharedPreferences(EDITOR_PREF_NAME, Context.MODE_PRIVATE);
                boolean hasFollow = ep.contains(KEY_HOME_FOLLOW_LOCK);
                boolean followVal = ep.getBoolean(KEY_HOME_FOLLOW_LOCK, true);
                JSONObject modState = PreModuleWallpaperBackup.captureModuleStateJson(ctx);
                if (modState == null) {
                    throw new IOException("采集模块状态快照失败");
                }

                CommitSnapshot snap = new CommitSnapshot(txId, snapDir, nowMs, hasFollow, followVal, modState);

                SharedPreferences cp = ctx.getSharedPreferences(COMMITTED_CROP_PREFS, Context.MODE_PRIVATE);
                for (int i = 0; i < ALL_SLOTS.length; i++) {
                    String slot = ALL_SLOTS[i];
                    if (cp.contains("x_" + slot)) {
                        snap.cropXPresent[i] = true;
                        snap.cropX[i] = cp.getFloat("x_" + slot, 0.5f);
                    }
                    if (cp.contains("y_" + slot)) {
                        snap.cropYPresent[i] = true;
                        snap.cropY[i] = cp.getFloat("y_" + slot, 0.5f);
                    }
                    if (cp.contains("zoom_" + slot)) {
                        snap.cropZoomPresent[i] = true;
                        snap.cropZoom[i] = cp.getFloat("zoom_" + slot, 1.0f);
                    }
                }

                for (String key : ALL_STORAGE_KEYS) {
                    File target = resolveOwnedTargetFile(ctx, key);
                    addSnapshotFileEntry(snap, key, target);
                }
                writeSnapshotJournal(snap);
                writeTxStateAtomic(snapDir, TX_STATE_PENDING);
                promotedToPending = true;
                return snap;
            } finally {
                if (!promotedToPending) {
                    ACTIVE_TX_IDS.remove(txId);
                    deleteRecursiveChecked(snapDir);
                }
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    static CommitSnapshot createCommitSnapshot(Context ctx) throws IOException {
        return beginTransactionSnapshot(ctx);
    }

    private static void writeSnapshotJournal(CommitSnapshot snap) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("version", JOURNAL_VERSION);
            root.put("txId", snap.txId);
            root.put("createdAtMs", snap.createdAtMs);

            JSONArray filesArr = new JSONArray();
            for (SlotBackupEntry entry : snap.fileEntries) {
                JSONObject fe = new JSONObject();
                fe.put("key", entry.storageKey);
                fe.put("existed", entry.existed);
                fe.put("backup", entry.backupName);
                filesArr.put(fe);
            }
            root.put("files", filesArr);

            JSONObject cropRoot = new JSONObject();
            for (int i = 0; i < ALL_SLOTS.length; i++) {
                JSONObject item = new JSONObject();
                item.put("hasX", snap.cropXPresent[i]);
                item.put("x", snap.cropXPresent[i] ? Float.toString(snap.cropX[i]) : "");
                item.put("hasY", snap.cropYPresent[i]);
                item.put("y", snap.cropYPresent[i] ? Float.toString(snap.cropY[i]) : "");
                item.put("hasZoom", snap.cropZoomPresent[i]);
                item.put("zoom", snap.cropZoomPresent[i] ? Float.toString(snap.cropZoom[i]) : "");
                cropRoot.put(ALL_SLOTS[i], item);
            }
            root.put("cropPrefs", cropRoot);

            JSONObject followObj = new JSONObject();
            followObj.put("present", snap.hasFollowPref);
            followObj.put("value", snap.followPrefValue);
            root.put("follow", followObj);

            root.put("moduleState", snap.moduleState);

            File journalFile = new File(snap.snapshotDir, SNAPSHOT_JOURNAL_FILE);
            writeTextFileAtomicStrict(root.toString(), journalFile);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("序列化事务日志失败: " + t.getMessage(), t);
        }
    }

    static void writeTxStateAtomic(File snapDir, String state) throws IOException {
        if (snapDir == null || !snapDir.isDirectory()) {
            throw new IOException("快照目录不存在");
        }
        TxLifecycleFaultHook hook = sTxLifecycleFaultHook;
        if (hook != null) {
            hook.beforeWriteTxState(snapDir, state);
        }
        writeTextFileAtomicStrict(state, new File(snapDir, TX_STATE_FILE));
    }

    private static boolean deleteSnapshotDirChecked(File snapDir) {
        if (snapDir == null) return true;
        TxLifecycleFaultHook hook = sTxLifecycleFaultHook;
        if (hook != null && !hook.allowDeleteSnapshotDir(snapDir)) {
            return false;
        }
        return deleteRecursiveChecked(snapDir);
    }

    static String readTxState(File snapDir) {
        if (snapDir == null || !snapDir.isDirectory()) return "";
        File f = new File(snapDir, TX_STATE_FILE);
        if (!f.isFile()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void completeTransactionSnapshot(Context ctx, CommitSnapshot snapshot) throws IOException {
        if (snapshot == null) return;
        PreModuleWallpaperBackup.lockTransaction();
        try {
            if (snapshot.snapshotDir != null && snapshot.snapshotDir.isDirectory()) {
                // 必须先持久化 COMMITTED 状态（不可吞错），仅在落盘成功后才释放活跃 ID 并清理快照目录
                writeTxStateAtomic(snapshot.snapshotDir, TX_STATE_COMMITTED);
            }
            if (snapshot.txId != null) {
                ACTIVE_TX_IDS.remove(snapshot.txId);
            }
            if (snapshot.snapshotDir != null && !deleteSnapshotDirChecked(snapshot.snapshotDir)) {
                String msg = "已写入 COMMITTED 状态，但清理已提交事务快照目录失败: " + snapshot.snapshotDir.getName();
                Log.w(Root.TAG, msg);
                if (ctx != null) {
                    ModuleDiagLog.record(ctx, "⚠ " + msg);
                }
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    static RollbackResult rollbackTransactionSnapshot(Context ctx, CommitSnapshot snapshot, boolean sendBroadcast) {
        if (snapshot == null) {
            return new RollbackResult(false, false, "事务快照为空，无法回滚");
        }
        PreModuleWallpaperBackup.lockTransaction();
        try {
            RollbackResult rb = snapshot.rollbackDetailed(ctx, sendBroadcast);
            if (snapshot.txId != null) {
                ACTIVE_TX_IDS.remove(snapshot.txId);
            }
            if (rb.ok) {
                if (snapshot.snapshotDir != null && snapshot.snapshotDir.isDirectory()) {
                    try {
                        writeTxStateAtomic(snapshot.snapshotDir, TX_STATE_COMMITTED);
                    } catch (Throwable stateErr) {
                        String msg = rb.detail + "（但最终状态 COMMITTED 持久化失败，已保留完整可恢复快照："
                                + stateErr.getMessage() + "）";
                        Log.w(Root.TAG, msg);
                        if (ctx != null) {
                            ModuleDiagLog.record(ctx, "✖ " + msg);
                        }
                        return new RollbackResult(false, true, msg);
                    }
                }
                boolean deleted = snapshot.snapshotDir == null || deleteSnapshotDirChecked(snapshot.snapshotDir);
                if (!deleted) {
                    return new RollbackResult(
                            true,
                            true,
                            rb.detail + "（已写入 COMMITTED 但清理快照目录失败，待后续清理）");
                }
                return new RollbackResult(true, false, rb.detail);
            } else {
                try {
                    if (snapshot.snapshotDir != null && snapshot.snapshotDir.isDirectory()) {
                        writeTxStateAtomic(snapshot.snapshotDir, TX_STATE_RECOVERY_FAILED);
                    }
                } catch (Throwable ignored) {
                }
                return new RollbackResult(false, true, rb.detail);
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    static boolean hasUnresolvedRecovery(Context ctx) {
        if (ctx == null) return false;
        PreModuleWallpaperBackup.lockTransaction();
        try {
            File snapRoot = new File(ctx.getFilesDir(), SNAPSHOT_ROOT_DIR);
            File[] dirs = snapRoot.listFiles();
            if (dirs == null) return false;
            for (File dir : dirs) {
                if (!dir.isDirectory()) continue;
                if (ACTIVE_TX_IDS.contains(dir.getName())) continue;
                String st = readTxState(dir);
                if (TX_STATE_COMMITTED.equals(st)) {
                    continue;
                }
                if (TX_STATE_PREPARING.equals(st) && isEmptyPreparingDir(dir)) {
                    continue;
                }
                return true;
            }
            return false;
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    private static boolean isEmptyPreparingDir(File dir) {
        File journal = new File(dir, SNAPSHOT_JOURNAL_FILE);
        return !journal.exists();
    }

    /**
     * 启动/前置安全恢复：在 {@link PreModuleWallpaperBackup#lockTransaction()} 保护下检查所有非活跃事务快照目录：
     * - {@code COMMITTED} 或未写日志的残留 {@code PREPARING} 直接清理；
     * - {@code PENDING} 或 {@code RECOVERY_FAILED} 严格校验 {@code journal.json} 与备份文件后还原全部 CE/DE 槽位与原图、
     *   裁切偏好、跟随偏好与模块状态；
     * - 若日志损坏、备份缺失或任意复制/删除/偏好提交失败，保留全部快照与日志证据并标记 {@code RECOVERY_FAILED}，绝不误报成功。
     */
    static RecoveryResult recoverIncompleteCommits(Context ctx) {
        if (ctx == null) {
            return new RecoveryResult(false, 0, 0, 1, null, "Context 为空");
        }
        PreModuleWallpaperBackup.lockTransaction();
        try {
            File snapRoot = new File(ctx.getFilesDir(), SNAPSHOT_ROOT_DIR);
            File[] dirs = snapRoot.listFiles();
            if (dirs == null || dirs.length == 0) {
                return new RecoveryResult(true, 0, 0, 0, null, "无未完成事务快照");
            }

            int recovered = 0;
            int cleaned = 0;
            int failed = 0;
            List<String> unresolved = new ArrayList<>();
            StringBuilder detailSb = new StringBuilder();

            for (File dir : dirs) {
                if (ACTIVE_TX_IDS.contains(dir.getName())) {
                    continue;
                }
                if (!dir.isDirectory()) {
                    if (dir.delete()) {
                        cleaned++;
                    } else {
                        failed++;
                        unresolved.add(dir.getName());
                        appendErr(detailSb, "无法删除异常文件: " + dir.getName());
                    }
                    continue;
                }

                String txState = readTxState(dir);
                if (TX_STATE_COMMITTED.equals(txState)) {
                    if (deleteSnapshotDirChecked(dir)) {
                        cleaned++;
                    } else {
                        failed++;
                        unresolved.add(dir.getName());
                        appendErr(detailSb, "无法删除已提交快照目录: " + dir.getName());
                    }
                    continue;
                }

                if (TX_STATE_PREPARING.equals(txState) && isEmptyPreparingDir(dir)) {
                    if (deleteSnapshotDirChecked(dir)) {
                        cleaned++;
                    } else {
                        failed++;
                        unresolved.add(dir.getName());
                        appendErr(detailSb, "无法删除中断 PREPARING 目录: " + dir.getName());
                    }
                    continue;
                }

                // PENDING / RECOVERY_FAILED / 未知状态 -> 必须经严格日志校验与恢复
                try {
                    CommitSnapshot snap = CommitSnapshot.fromJournalDir(ctx, dir);
                    RollbackResult rb = snap.rollbackDetailed(ctx, false);
                    if (rb.ok) {
                        writeTxStateAtomic(dir, TX_STATE_COMMITTED);
                        if (deleteSnapshotDirChecked(dir)) {
                            recovered++;
                        } else {
                            failed++;
                            unresolved.add(dir.getName());
                            appendErr(detailSb, "已恢复内容但删除快照失败: " + dir.getName());
                        }
                    } else {
                        try {
                            writeTxStateAtomic(dir, TX_STATE_RECOVERY_FAILED);
                        } catch (Throwable ignored) {
                        }
                        failed++;
                        unresolved.add(dir.getName());
                        appendErr(detailSb, dir.getName() + ": " + rb.detail);
                    }
                } catch (Throwable t) {
                    try {
                        writeTxStateAtomic(dir, TX_STATE_RECOVERY_FAILED);
                    } catch (Throwable ignored) {
                    }
                    failed++;
                    unresolved.add(dir.getName());
                    appendErr(detailSb, "日志/备份校验失败(" + dir.getName() + "): " + t.getMessage());
                    Log.w(Root.TAG, "崩溃恢复未完成快照失败: " + t.getMessage());
                }
            }

            boolean allOk = (failed == 0);
            String finalDetail;
            if (allOk) {
                finalDetail = recovered > 0
                        ? ("已按日志恢复 " + recovered + " 个未完成事务快照")
                        : (cleaned > 0 ? ("已清理 " + cleaned + " 个已结束事务快照") : "无未完成事务快照");
            } else {
                finalDetail = "未完成事务恢复存在失败（已保留快照证据待重试或排查）: " + detailSb;
            }
            if (recovered > 0 || !allOk) {
                ModuleDiagLog.record(ctx, (allOk ? "" : "✖ ") + finalDetail);
            }
            return new RecoveryResult(allOk, recovered, cleaned, failed, unresolved, finalDetail);
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    private static void addSnapshotFileEntry(CommitSnapshot snap, String key, File target) throws IOException {
        boolean existed = target != null && target.isFile();
        String bakName = "";
        File copy = null;
        if (existed) {
            bakName = key.replace(':', '_') + ".bak";
            if (!isValidBackupFileName(bakName)) {
                throw new IOException("非法备份键: " + key);
            }
            copy = new File(snap.snapshotDir, bakName);
            ensureWithinParent(snap.snapshotDir, copy);
            copyFileAtomicStrict(target, copy);
        }
        snap.fileEntries.add(new SlotBackupEntry(key, target, existed, bakName, copy));
    }

    /**
     * 在已有事务快照（处于 {@link #TX_STATE_PENDING}）内提交草稿照片、CE+DE 原图、裁切坐标与跟随偏好；
     * 绝不提前清除 {@code PENDING} 状态或销毁快照（由外层在系统壁纸绑定及核验全通过后统一完成）。
     */
    static void commitDraftPhotosIntoTransaction(
            Context ctx,
            CommitSnapshot snapshot,
            String sessionId,
            boolean pendingFollowLock,
            CommitTargetWriter customWriter) throws IOException {
        if (snapshot == null) {
            throw new IOException("事务快照为空");
        }
        CommitTargetWriter writer = customWriter != null ? customWriter : DEFAULT_COMMIT_WRITER;
        if (isValidSessionId(sessionId)) {
            if (hasCorruptRevision(ctx, sessionId)) {
                throw new IOException("草稿修订回滚曾失败且尚未修复/丢弃，禁止提交至正式壁纸："
                        + getCorruptRevisionDetail(ctx, sessionId));
            }
            SharedPreferences dp = cropPrefs(ctx, sessionId);
            SharedPreferences cp = ctx.getSharedPreferences(COMMITTED_CROP_PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor cropEd = cp.edit();

            for (String slot : ALL_SLOTS) {
                boolean dirty = dp.getBoolean(KEY_DIRTY_PREFIX + slot, false);
                if (!dirty) {
                    continue;
                }
                File draftSlot = slotFile(ctx, sessionId, slot);
                if (!draftSlot.isFile() || draftSlot.length() <= 0) {
                    throw new IOException("草稿照片缺失: " + slot);
                }
                writer.writeSlotFile(
                        ctx,
                        draftSlot,
                        PhotoStore.file(ctx, slot),
                        PhotoStore.deFile(ctx, slot),
                        true);

                File draftRaw = rawFile(ctx, sessionId, slot);
                File ceRawDst = PhotoStore.rawFile(ctx, slot);
                File deRawDst = PhotoStore.deFile(ctx, "raw_" + slot);
                if (draftRaw.isFile() && draftRaw.length() > 0) {
                    writer.writeSlotFile(ctx, draftRaw, ceRawDst, deRawDst, true);
                } else {
                    // 无对应草稿原图时清理旧已提交原图，避免后续裁切退回不相干旧图
                    if (ceRawDst.exists() && !ceRawDst.delete()) {
                        throw new IOException("无法清理过期 CE 原图: " + ceRawDst.getName());
                    }
                    if (deRawDst != null && deRawDst.exists() && !deRawDst.delete()) {
                        throw new IOException("无法清理过期 DE 原图: " + deRawDst.getName());
                    }
                }

                if (dp.contains("x_" + slot)) {
                    cropEd.putFloat("x_" + slot, dp.getFloat("x_" + slot, 0.5f));
                } else {
                    cropEd.remove("x_" + slot);
                }
                if (dp.contains("y_" + slot)) {
                    cropEd.putFloat("y_" + slot, dp.getFloat("y_" + slot, 0.5f));
                } else {
                    cropEd.remove("y_" + slot);
                }
                if (dp.contains("zoom_" + slot)) {
                    cropEd.putFloat("zoom_" + slot, dp.getFloat("zoom_" + slot, 1.0f));
                } else {
                    cropEd.remove("zoom_" + slot);
                }
            }
            if (!cropEd.commit()) {
                throw new IOException("提交裁切参数偏好失败");
            }
        }

        // 未在本轮修改的已存在正式照片与原图同样确保 CE/DE 镜像一致且严格校验
        for (String slot : ALL_SLOTS) {
            syncCommittedFileToDeIfNeeded(ctx, slot, writer);
            syncCommittedFileToDeIfNeeded(ctx, "raw_" + slot, writer);
        }

        SharedPreferences ep = ctx.getSharedPreferences(EDITOR_PREF_NAME, Context.MODE_PRIVATE);
        if (!ep.edit().putBoolean(KEY_HOME_FOLLOW_LOCK, pendingFollowLock).commit()) {
            throw new IOException("提交桌面跟随偏好失败");
        }
    }

    private static void syncCommittedFileToDeIfNeeded(
            Context ctx, String name, CommitTargetWriter writer) throws IOException {
        File ceFile = PhotoStore.file(ctx, name);
        if (ceFile != null && ceFile.isFile() && ceFile.length() > 0) {
            File deFile = PhotoStore.deFile(ctx, name);
            if (deFile == null || !deFile.isFile() || deFile.length() != ceFile.length()
                    || deFile.lastModified() < ceFile.lastModified()) {
                writer.writeSlotFile(ctx, ceFile, ceFile, deFile, true);
            }
        }
    }

    /**
     * 独立提交入口（创建事务快照 -> 提交草稿 CE/DE/原图/偏好 -> 若任一步失败则立即按快照回滚）；
     * 成功时返回持有活跃 {@link CommitSnapshot}（仍处于 {@code PENDING}）的结果，由调用方在后续外部步骤全部成功后调用
     * {@link #completeTransactionSnapshot(Context, CommitSnapshot)} 或失败后调用
     * {@link #rollbackTransactionSnapshot(Context, CommitSnapshot, boolean)}。
     */
    static CommitResult commitDraft(
            Context ctx,
            String sessionId,
            boolean pendingFollowLock,
            CommitTargetWriter customWriter) {
        PreModuleWallpaperBackup.lockTransaction();
        try {
            if (hasUnresolvedRecovery(ctx)) {
                String msg = "存在未解决的历史异常事务快照，已阻止提交";
                ModuleDiagLog.record(ctx, "✖ " + msg);
                return new CommitResult(false, msg, null);
            }
            CommitSnapshot snapshot;
            try {
                snapshot = beginTransactionSnapshot(ctx);
            } catch (Throwable t) {
                String msg = "创建事务前置快照失败: " + t.getMessage();
                ModuleDiagLog.record(ctx, "✖ " + msg);
                return new CommitResult(false, msg, null);
            }

            try {
                commitDraftPhotosIntoTransaction(ctx, snapshot, sessionId, pendingFollowLock, customWriter);
                ModuleDiagLog.record(ctx, "照片草稿已写入 CE/DE 正式槽位（等待核验落定）");
                return new CommitResult(true, "ok", snapshot);
            } catch (Throwable t) {
                RollbackResult rb = rollbackTransactionSnapshot(ctx, snapshot, true);
                String msg = "提交照片草稿失败（" + rb.detail + "）: " + t.getMessage();
                ModuleDiagLog.record(ctx, "✖ " + msg);
                return new CommitResult(false, msg, null);
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    static void discardSession(Context ctx, String sessionId) {
        if (ctx == null || !isValidSessionId(sessionId)) return;
        if (isSessionActive(sessionId)) {
            return;
        }
        PreModuleWallpaperBackup.lockTransaction();
        try {
            if (isSessionActive(sessionId)) {
                return;
            }
            deleteSessionDirAndPrefs(ctx, sessionId);
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    private static void deleteSessionDirAndPrefs(Context ctx, String sessionId) {
        CORRUPT_SESSIONS_MEM.remove(sessionId);
        File dir = existingSessionDirOrNull(ctx, sessionId);
        if (dir != null) {
            deleteRecursiveChecked(dir);
        }
        String prefName = DRAFT_PREF_PREFIX + sessionId;
        try {
            ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().commit();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(prefName);
            }
        } catch (Throwable ignored) {
        }
    }

    static void cleanupStaleSessions(Context ctx, String keepSessionId) {
        if (ctx == null) return;
        PreModuleWallpaperBackup.lockTransaction();
        try {
            File root = draftRootDir(ctx);
            File[] children = root.listFiles();
            if (children == null) return;
            for (File child : children) {
                String name = child.getName();
                if (keepSessionId != null && keepSessionId.equals(name)) {
                    continue;
                }
                if (isSessionActive(name)) {
                    continue;
                }
                CORRUPT_SESSIONS_MEM.remove(name);
                deleteRecursiveChecked(child);
                if (isValidSessionId(name)) {
                    String prefName = DRAFT_PREF_PREFIX + name;
                    try {
                        ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().commit();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            ctx.deleteSharedPreferences(prefName);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            PreModuleWallpaperBackup.unlockTransaction();
        }
    }

    private static void writeBitmapPngToFileStrict(Bitmap bmp, File dst) throws IOException {
        if (bmp == null || dst == null) {
            throw new IOException("位图或目标路径为空");
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        File tmp = new File(parent, dst.getName() + ".tmp." + System.nanoTime());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw new IOException("PNG 压缩失败: " + dst.getName());
                }
                fos.flush();
                fos.getFD().sync();
            }
            atomicReplace(tmp, dst);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    static void writeTextFileAtomicStrict(String content, File dst) throws IOException {
        if (dst == null) {
            throw new IOException("目标文件为空");
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        File tmp = new File(parent, dst.getName() + ".tmp." + System.nanoTime());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            atomicReplace(tmp, dst);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    static void copyFileAtomicStrict(File src, File dst) throws IOException {
        if (src == null || !src.isFile() || src.length() <= 0) {
            throw new IOException("源文件无效: " + (src == null ? "null" : src.getName()));
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        if (src.getCanonicalFile().equals(dst.getCanonicalFile())) {
            return;
        }
        File tmp = new File(parent, dst.getName() + ".tmp." + System.nanoTime());
        try {
            byte[] buf = new byte[8192];
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.getFD().sync();
            }
            atomicReplace(tmp, dst);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copyAssetDefaultToFileStrict(Context ctx, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        File tmp = new File(parent, dst.getName() + ".tmp." + System.nanoTime());
        try {
            byte[] buf = new byte[8192];
            try (InputStream in = ctx.getAssets().open("photo/default.jpg");
                 FileOutputStream out = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.getFD().sync();
            }
            atomicReplace(tmp, dst);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copyAssetDefaultToFile(Context ctx, File dst) throws IOException {
        copyAssetDefaultToFileStrict(ctx, dst);
    }

    static void atomicReplace(File tmp, File dst) throws IOException {
        if (tmp.renameTo(dst)) {
            dst.setLastModified(System.currentTimeMillis());
            return;
        }
        if (dst.exists() && !dst.delete()) {
            throw new IOException("无法替换已有文件: " + dst.getAbsolutePath());
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("重命名落盘失败: " + dst.getAbsolutePath());
        }
        dst.setLastModified(System.currentTimeMillis());
    }

    private static void ensureWithinParent(File parent, File child) throws IOException {
        String p = parent.getCanonicalPath();
        String c = child.getCanonicalPath();
        String prefix = p.endsWith(File.separator) ? p : (p + File.separator);
        if (!c.equals(p) && !c.startsWith(prefix)) {
            throw new IOException("路径越界拒绝访问: " + child.getName());
        }
    }

    private static int readExifRotation(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    static boolean deleteRecursiveChecked(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                if (!deleteRecursiveChecked(c)) {
                    ok = false;
                }
            }
        }
        if (!f.delete()) {
            ok = false;
        }
        return ok && !f.exists();
    }

    static void deleteRecursive(File f) {
        deleteRecursiveChecked(f);
    }
}
