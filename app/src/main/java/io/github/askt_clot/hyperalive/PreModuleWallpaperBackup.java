package io.github.askt_clot.hyperalive;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模块应用前状态持久化备份与手动恢复管理器。
 *
 * 数据完整性保障：
 * 1. 全局单一事务锁 TX_LOCK 串行保护完整应用（备份->壁纸写入）、状态检查与完整恢复会话；
 * 2. 区分 NONE / VALID / CORRUPTED 三态：已有有效快照不重复拍，已有损坏快照绝不覆盖，无快照零写入；
 * 3. 静态壁纸仅从真实槽位 FD 或 root 只读复制实际槽文件并做图片解码校验，严禁伪造或降级为跟随桌面；
 * 4. HyperOS 4 OEM 自定义壁纸/模板通过固定白名单路径与元数据交叉核验，绝不仅凭 SET_LIVE_TO_CLEAR 推断默认；
 * 5. 捕获前后二次核对壁纸 ID 与组件防竞态；捕获或校验失败在任何设置/壁纸写入前终止应用且不留空目录；
 * 6. 恢复严格预检后才执行；对缺少服务端依据的 OEM 状态保守中止并保留备份，绝不通过清除回落渐层蒙混成功；
 * 7. 壁纸恢复后再用固定路径 /system/bin/settings --user <uid> 与安全引号还原原生息屏参数并回读核验；
 * 8. try/finally 保证 sys.qihang.allow_reset 恢复原值；仅全部成功才清理快照（递归删子目录）。
 */
public final class PreModuleWallpaperBackup {

    private static final String TAG = "PreModuleBackup";
    private static final String META_FILE = "meta.json";
    private static final String META_TMP = "meta.json.tmp";
    private static final String SYS_IMG_FILE = "wallpaper_system.png";
    private static final String LOCK_IMG_FILE = "wallpaper_lock.png";
    private static final String OEM_DIR_NAME = "oem";
    private static final String TYPE_OEM_HYPEROS = "OEM_HYPEROS";

    private static final ReentrantLock TX_LOCK = new ReentrantLock();

    private PreModuleWallpaperBackup() {}

    public enum BackupState {
        NONE,
        VALID,
        CORRUPTED
    }

    public static final class PreApplyBaseline {
        public final boolean valid;
        public final int userId;
        public final int sysId;
        public final int lockId;
        public final boolean hasKwp;
        public final String wpBindSource;
        public final String sysXmlPkg;
        public final String sysXmlCls;
        public final String lockXmlPkg;
        public final String lockXmlCls;
        public final String sysInfoPkg;
        public final String sysInfoCls;
        public final String lockInfoPkg;
        public final String lockInfoCls;
        public final boolean enterAmbientExisted;
        public final String enterAmbientValue;
        public final boolean alivePosExisted;
        public final String alivePosValue;

        PreApplyBaseline(int userId, int sysId, int lockId, boolean hasKwp, String wpBindSource,
                         String sysXmlPkg, String sysXmlCls, String lockXmlPkg, String lockXmlCls,
                         String sysInfoPkg, String sysInfoCls, String lockInfoPkg, String lockInfoCls,
                         boolean enterAmbientExisted, String enterAmbientValue,
                         boolean alivePosExisted, String alivePosValue) {
            this.valid = true;
            this.userId = userId;
            this.sysId = sysId;
            this.lockId = lockId;
            this.hasKwp = hasKwp;
            this.wpBindSource = wpBindSource != null ? wpBindSource : "";
            this.sysXmlPkg = sysXmlPkg != null ? sysXmlPkg : "";
            this.sysXmlCls = sysXmlCls != null ? sysXmlCls : "";
            this.lockXmlPkg = lockXmlPkg != null ? lockXmlPkg : "";
            this.lockXmlCls = lockXmlCls != null ? lockXmlCls : "";
            this.sysInfoPkg = sysInfoPkg != null ? sysInfoPkg : "";
            this.sysInfoCls = sysInfoCls != null ? sysInfoCls : "";
            this.lockInfoPkg = lockInfoPkg != null ? lockInfoPkg : "";
            this.lockInfoCls = lockInfoCls != null ? lockInfoCls : "";
            this.enterAmbientExisted = enterAmbientExisted;
            this.enterAmbientValue = enterAmbientValue != null ? enterAmbientValue : "";
            this.alivePosExisted = alivePosExisted;
            this.alivePosValue = alivePosValue != null ? alivePosValue : "";
        }
    }

    public static final class RollbackResult {
        public final boolean deleted;
        public final String reason;

        RollbackResult(boolean deleted, String reason) {
            this.deleted = deleted;
            this.reason = reason != null ? reason : "";
        }
    }

    public static final class SlotVerifyResult {
        public final boolean ok;
        public final String detail;

        SlotVerifyResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail != null ? detail : "";
        }
    }

    public static final class BackupPrepResult {
        public final boolean ok;
        public final boolean createdNew;
        public final String message;
        public final PreApplyBaseline baseline;

        BackupPrepResult(boolean ok, boolean createdNew, String message) {
            this(ok, createdNew, message, null);
        }

        BackupPrepResult(boolean ok, boolean createdNew, String message, PreApplyBaseline baseline) {
            this.ok = ok;
            this.createdNew = createdNew;
            this.message = message;
            this.baseline = baseline;
        }
    }

    public static final class BackupInspection {
        public final BackupState state;
        public final BackupMetadata metadata;

        BackupInspection(BackupState state, BackupMetadata metadata) {
            this.state = state;
            this.metadata = metadata;
        }
    }

    public static class BackupMetadata {
        public int version;
        public int userId;
        public long backupTime;
        public boolean enterAmbientExisted;
        public String enterAmbientValue;
        public boolean alivePosExisted;
        public String alivePosValue;
        public String systemType; // "STATIC" | "LIVE" | "DEFAULT" | "OEM_HYPEROS"
        public String systemPkg;
        public String systemCls;
        public String systemFile;
        public String lockType;   // "SAME_AS_SYSTEM" | "STATIC" | "LIVE" | "OEM_HYPEROS"
        public String lockPkg;
        public String lockCls;
        public String lockFile;
        public String oemHomeType = "";
        public String oemLockType = "";
        public String oemClockTemplateId = "";
        public int oemFileCount = 0;
        public JSONObject oemJson = null;
        public boolean defaultVerified = false;
        public String defaultSource = "";
        public String defaultOemHomeType = "";
        public String defaultOemLockType = "";
        public boolean hasModuleState = false;
        public int moduleAodStyle = -1;
        public String moduleAodPositions = null;
        public int modulePhotoTextures = -1;
        public int moduleLastKind = LastSet.KIND_FOLLOW;
        public String moduleLastComponent = "";
        public String moduleClockAodJson = "";
        public String moduleClockLockJson = "";
        public boolean valid;

        public boolean hasVerifiedDefaultProof() {
            return "DEFAULT".equals(systemType)
                    && version >= 2
                    && defaultVerified
                    && ("oem_bridge_confirmed".equals(defaultSource)
                        || "aosp_confirmed".equals(defaultSource));
        }

        public boolean isLegacyUnverifiedDefault() {
            return "DEFAULT".equals(systemType) && !hasVerifiedDefaultProof();
        }

        public String getFormattedTime() {
            if (backupTime <= 0) return "未知时间";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(backupTime));
        }

        public String getSystemDescription() {
            if (TYPE_OEM_HYPEROS.equals(systemType)) {
                return "HyperOS 自定义壁纸 (" + (oemHomeType.isEmpty() ? "image" : oemHomeType) + ")";
            } else if ("LIVE".equals(systemType)) {
                return "动态壁纸 (" + systemPkg + ")";
            } else if ("STATIC".equals(systemType)) {
                return "静态图片";
            } else if ("DEFAULT".equals(systemType)) {
                return isLegacyUnverifiedDefault()
                        ? "旧版未核验 DEFAULT 记录（缺少 OEM 证据/无原图）"
                        : "系统内置默认壁纸";
            }
            return "未知";
        }

        public String getLockDescription() {
            if (TYPE_OEM_HYPEROS.equals(lockType)) {
                String extra = !oemClockTemplateId.isEmpty() ? "，模板=" + oemClockTemplateId : "";
                return "HyperOS 自定义锁屏壁纸 (" + (oemLockType.isEmpty() ? "image" : oemLockType) + extra + ")";
            } else if ("SAME_AS_SYSTEM".equals(lockType)) {
                return "跟随桌面（合并槽）";
            } else if ("LIVE".equals(lockType)) {
                return "独立动态壁纸 (" + lockPkg + ")";
            } else if ("STATIC".equals(lockType)) {
                return "独立静态图片";
            }
            return "未知";
        }
    }

    public static class RestoreResult {
        public final boolean success;
        public final boolean partial;
        public final String message;

        public RestoreResult(boolean success, boolean partial, String message) {
            this.success = success;
            this.partial = partial;
            this.message = message;
        }

        public static RestoreResult success(String msg) {
            return new RestoreResult(true, false, msg);
        }

        public static RestoreResult partial(String msg) {
            return new RestoreResult(false, true, msg);
        }

        public static RestoreResult failure(String msg) {
            return new RestoreResult(false, false, msg);
        }
    }

    public static void lockTransaction() {
        TX_LOCK.lock();
    }

    public static void unlockTransaction() {
        if (TX_LOCK.isHeldByCurrentThread()) {
            TX_LOCK.unlock();
        }
    }

    private static int currentUserId() {
        return Process.myUid() / 100000;
    }

    /** 只读获取用户备份目录，绝不主动 mkdirs 产生写入。 */
    public static File getBackupDirReadOnly(Context ctx) {
        File base = new File(ctx.getFilesDir(), "pre_module_backup");
        return new File(base, "user_" + currentUserId());
    }

    private static File getBackupBaseDirReadOnly(Context ctx) {
        return new File(ctx.getFilesDir(), "pre_module_backup");
    }

    /** 在全局事务锁保护下原子读取状态与元数据（避免并发备份写入期间误判 NONE）。 */
    public static BackupInspection inspectBackupLocked(Context ctx) {
        if (ctx == null) {
            return new BackupInspection(BackupState.NONE, null);
        }
        lockTransaction();
        try {
            BackupState st = getBackupStateUnlocked(ctx);
            BackupMetadata meta = (st == BackupState.VALID) ? getBackupMetadataUnlocked(ctx) : null;
            return new BackupInspection(st, meta);
        } finally {
            unlockTransaction();
        }
    }

    /** 精确判定当前用户的应用前快照状态（受 TX_LOCK 保护，不产生任何目录/文件写入）。 */
    public static BackupState getBackupState(Context ctx) {
        if (ctx == null) return BackupState.NONE;
        lockTransaction();
        try {
            return getBackupStateUnlocked(ctx);
        } finally {
            unlockTransaction();
        }
    }

    private static BackupState getBackupStateUnlocked(Context ctx) {
        if (ctx == null) return BackupState.NONE;
        File userDir = getBackupDirReadOnly(ctx);
        if (!userDir.exists() || !userDir.isDirectory()) {
            return BackupState.NONE;
        }

        File metaFile = new File(userDir, META_FILE);
        if (!metaFile.exists()) {
            File[] files = userDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".tmp")) continue;
                    if (f.isFile() || (f.isDirectory() && hasNonTmpChildren(f))) {
                        return BackupState.CORRUPTED;
                    }
                }
            }
            return BackupState.NONE;
        }

        BackupMetadata meta = parseAndValidateMetadata(userDir, metaFile);
        return meta != null ? BackupState.VALID : BackupState.CORRUPTED;
    }

    private static boolean hasNonTmpChildren(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File c : children) {
            if (c.getName().endsWith(".tmp")) continue;
            if (c.isFile()) return true;
            if (c.isDirectory() && hasNonTmpChildren(c)) return true;
        }
        return false;
    }

    public static boolean hasValidBackup(Context ctx) {
        return getBackupState(ctx) == BackupState.VALID;
    }

    public static BackupMetadata getBackupMetadata(Context ctx) {
        if (ctx == null) return null;
        lockTransaction();
        try {
            return getBackupMetadataUnlocked(ctx);
        } finally {
            unlockTransaction();
        }
    }

    private static BackupMetadata getBackupMetadataUnlocked(Context ctx) {
        if (ctx == null) return null;
        File userDir = getBackupDirReadOnly(ctx);
        if (!userDir.exists()) return null;
        return parseAndValidateMetadata(userDir, new File(userDir, META_FILE));
    }

    private static BackupMetadata parseAndValidateMetadata(File userDir, File metaFile) {
        if (!metaFile.exists() || !metaFile.isFile() || metaFile.length() <= 0) {
            return null;
        }
        try {
            String jsonStr = readFileToString(metaFile);
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return null;
            }
            JSONObject json = new JSONObject(jsonStr);
            int ver = json.optInt("version", -1);
            if (ver != 1 && ver != 2) return null;
            if (json.optInt("userId", -1) != currentUserId()) return null;
            if (!json.optBoolean("valid", false)) return null;
            if (json.optLong("backupTime", 0L) <= 0L) return null;
            if (!json.has("enter_ambient_mode_existed") || !json.has("alive_wallpaper_position_existed")) {
                return null;
            }

            String sysType = json.optString("system_type", "");
            String lockType = json.optString("lock_type", "");
            if (!"STATIC".equals(sysType) && !"LIVE".equals(sysType)
                    && !"DEFAULT".equals(sysType) && !TYPE_OEM_HYPEROS.equals(sysType)) {
                return null;
            }
            if (!"SAME_AS_SYSTEM".equals(lockType) && !"STATIC".equals(lockType)
                    && !"LIVE".equals(lockType) && !TYPE_OEM_HYPEROS.equals(lockType)) {
                return null;
            }
            if (TYPE_OEM_HYPEROS.equals(sysType) != TYPE_OEM_HYPEROS.equals(lockType)) {
                return null;
            }

            BackupMetadata meta = new BackupMetadata();
            meta.version = ver;
            meta.userId = currentUserId();
            meta.backupTime = json.optLong("backupTime", 0L);
            meta.enterAmbientExisted = json.optBoolean("enter_ambient_mode_existed", false);
            meta.enterAmbientValue = json.optString("enter_ambient_mode_val", "");
            meta.alivePosExisted = json.optBoolean("alive_wallpaper_position_existed", false);
            meta.alivePosValue = json.optString("alive_wallpaper_position_val", "");
            meta.systemType = sysType;
            meta.systemPkg = json.optString("system_pkg", "");
            meta.systemCls = json.optString("system_cls", "");
            meta.systemFile = json.optString("system_file", "");
            meta.lockType = lockType;
            meta.lockPkg = json.optString("lock_pkg", "");
            meta.lockCls = json.optString("lock_cls", "");
            meta.lockFile = json.optString("lock_file", "");
            meta.defaultVerified = json.optBoolean("default_verified", false);
            meta.defaultSource = json.optString("default_source", "");
            meta.defaultOemHomeType = json.optString("default_oem_home_type", "");
            meta.defaultOemLockType = json.optString("default_oem_lock_type", "");

            // 校验桌面数据
            if ("STATIC".equals(sysType)) {
                if (!SYS_IMG_FILE.equals(meta.systemFile)) return null;
                if (!isValidImageFile(new File(userDir, meta.systemFile))) return null;
            } else if ("LIVE".equals(sysType)) {
                if (meta.systemPkg.isEmpty() || meta.systemCls.isEmpty()) return null;
                if (isHyperAliveName(null, meta.systemPkg, meta.systemCls)
                        || isMiuiImageWallpaperName(meta.systemPkg, meta.systemCls)) {
                    return null;
                }
            }

            // 校验锁屏数据
            if ("STATIC".equals(lockType)) {
                if (!LOCK_IMG_FILE.equals(meta.lockFile)) return null;
                if (!isValidImageFile(new File(userDir, meta.lockFile))) return null;
            } else if ("LIVE".equals(lockType)) {
                if (meta.lockPkg.isEmpty() || meta.lockCls.isEmpty()) return null;
                if (isHyperAliveName(null, meta.lockPkg, meta.lockCls)
                        || isMiuiImageWallpaperName(meta.lockPkg, meta.lockCls)) {
                    return null;
                }
            }

            // 校验 HyperOS OEM 快照完整性与 SHA-256
            if (TYPE_OEM_HYPEROS.equals(sysType)) {
                JSONObject oemObj = json.optJSONObject("oem");
                if (oemObj == null) return null;
                meta.oemHomeType = oemObj.optString("home_type", "");
                meta.oemLockType = oemObj.optString("lock_type", "");
                meta.oemClockTemplateId = oemObj.optString("clock_template_id", "");
                JSONArray filesArr = oemObj.optJSONArray("files");
                if (filesArr == null || filesArr.length() <= 0) return null;
                File oemDir = new File(userDir, OEM_DIR_NAME);
                if (!oemDir.isDirectory()) return null;
                for (int i = 0; i < filesArr.length(); i++) {
                    JSONObject fObj = filesArr.optJSONObject(i);
                    if (fObj == null) return null;
                    String relName = fObj.optString("rel_name", "");
                    long expectedSize = fObj.optLong("size", -1L);
                    String expectedSha = fObj.optString("sha256", "");
                    boolean isImg = fObj.optBoolean("is_image", false);
                    if (relName.isEmpty() || relName.contains("..") || relName.contains("/")
                            || relName.contains("\\") || expectedSize <= 0L || expectedSha.isEmpty()) {
                        return null;
                    }
                    File f = new File(oemDir, relName);
                    if (!f.isFile() || f.length() != expectedSize) return null;
                    if (!expectedSha.equals(HyperOsWallpaperHelper.sha256Hex(f))) return null;
                    if (isImg && !isValidImageFile(f)) return null;
                }
                if (oemObj.optJSONObject("settings") == null) return null;
                boolean hasHomeXml = false;
                boolean hasLockXml = false;
                boolean hasHomeImg = false;
                boolean hasLockImg = false;
                for (int i = 0; i < filesArr.length(); i++) {
                    String rn = filesArr.optJSONObject(i).optString("rel_name", "");
                    if ("data_home.xml".equals(rn)) hasHomeXml = true;
                    else if ("data_lock.xml".equals(rn)) hasLockXml = true;
                    else if ("home_wallpaper.jpg".equals(rn)) hasHomeImg = true;
                    else if ("lock_wallpaper.jpg".equals(rn)) hasLockImg = true;
                }
                if (!hasHomeXml || !hasLockXml) return null;
                if ("image".equals(meta.oemHomeType) && !hasHomeImg) return null;
                if ("image".equals(meta.oemLockType) && !hasLockImg) return null;
                meta.oemFileCount = filesArr.length();
                meta.oemJson = oemObj;
            }

            JSONObject modObj = json.optJSONObject("module_state");
            if (modObj != null) {
                meta.hasModuleState = true;
                meta.moduleAodStyle = modObj.optInt("aod_style", -1);
                meta.moduleAodPositions = modObj.optString(AodStyleSettings.KEY_POSITIONS, null);
                meta.modulePhotoTextures = modObj.optInt(PhotoTextureSettings.KEY, -1);
                meta.moduleLastKind = modObj.optInt("last_kind", LastSet.KIND_FOLLOW);
                meta.moduleLastComponent = modObj.optString("last_component", "");
                meta.moduleClockAodJson = modObj.optString("clock_aod", "");
                meta.moduleClockLockJson = modObj.optString("clock_lock", "");
            }

            meta.valid = true;
            return meta;
        } catch (Throwable t) {
            Log.w(TAG, "parseAndValidateMetadata failed", t);
            return null;
        }
    }

    private static WallpaperInfo querySlotWallpaperInfo(WallpaperManager wm, int which) throws Exception {
        if (which == WallpaperManager.FLAG_SYSTEM) {
            if (Build.VERSION.SDK_INT >= 34) {
                Method m = WallpaperManager.class.getMethod("getWallpaperInfo", int.class);
                return (WallpaperInfo) m.invoke(wm, which);
            }
            return wm.getWallpaperInfo();
        } else {
            if (Build.VERSION.SDK_INT >= 34) {
                Method m = WallpaperManager.class.getMethod("getWallpaperInfo", int.class);
                return (WallpaperInfo) m.invoke(wm, which);
            }
            return null;
        }
    }

    private static boolean isHyperAliveName(Context ctx, String pkg, String cls) {
        String myPkg = ctx != null ? ctx.getPackageName() : "io.github.askt_clot.hyperalive";
        if (pkg != null && (pkg.equals(myPkg) || "io.github.askt_clot.hyperalive".equals(pkg)
                || "com.hyperalive.aod".equals(pkg) || "com.qihang.aod".equals(pkg))) {
            return true;
        }
        return cls != null && (cls.contains("QihangWallpaper") || cls.contains("QihangHomeWallpaper")
                || cls.contains("QihangDynamicWallpaper"));
    }

    private static boolean isHyperAlive(Context ctx, WallpaperInfo info) {
        return info != null && isHyperAliveName(ctx, info.getPackageName(), info.getServiceName());
    }

    public static boolean isMiuiImageWallpaperName(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) {
            return false;
        }
        String normCls = normalizeClassName(pkg, cls);
        if ("com.miui.miwallpaper".equals(pkg)) {
            return "com.miui.miwallpaper.wallpaperservice.ImageWallpaper".equals(normCls)
                    || "com.miui.miwallpaper.ImageWallpaper".equals(normCls);
        }
        if ("com.android.systemui".equals(pkg)) {
            return "com.android.systemui.ImageWallpaper".equals(normCls)
                    || "com.android.systemui.wallpapers.ImageWallpaper".equals(normCls);
        }
        return false;
    }

    public static boolean isMiuiImageWallpaper(WallpaperInfo info) {
        return info != null && isMiuiImageWallpaperName(info.getPackageName(), info.getServiceName());
    }

    /** 兼容 MIUI 原生静态/画报锁屏组件（ImageWallpaper 与 MiuiKeyguardPictorialWallpaper）。 */
    public static boolean isMiuiStaticOrPictorialName(String pkg, String cls) {
        if (isMiuiImageWallpaperName(pkg, cls)) {
            return true;
        }
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) {
            return false;
        }
        String normCls = normalizeClassName(pkg, cls);
        return "com.miui.miwallpaper".equals(pkg)
                && ("com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper".equals(normCls)
                        || "com.miui.miwallpaper.MiuiKeyguardPictorialWallpaper".equals(normCls));
    }

    public static boolean isMiuiStaticOrPictorial(WallpaperInfo info) {
        return info != null && isMiuiStaticOrPictorialName(info.getPackageName(), info.getServiceName());
    }

    private static String resolveCleanSplitLockComp(
            WallpaperInfo lockInfo, WallpaperXmlState xml, HyperOsWallpaperHelper.BridgeProbeSummary bridge) {
        if (lockInfo != null && isMiuiStaticOrPictorial(lockInfo)) {
            String pkg = lockInfo.getPackageName();
            return pkg + "/" + normalizeClassName(pkg, lockInfo.getServiceName());
        }
        if (xml != null && isMiuiStaticOrPictorialName(xml.kwpPkg, xml.kwpCls)) {
            return xml.kwpPkg + "/" + normalizeClassName(xml.kwpPkg, xml.kwpCls);
        }
        if (bridge != null && HyperOsWallpaperHelper.isCleanActiveSplitLockComp(bridge.wmsLockComp)) {
            return bridge.wmsLockComp;
        }
        return "";
    }

    /** 检查当前任意槽位是否已被 HyperAlive 占用（查询异常时保守抛出，不误判）。 */
    public static boolean isOccupiedByHyperAliveStrict(Context ctx, WallpaperManager wm) throws Exception {
        WallpaperInfo sysInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
        if (isHyperAlive(ctx, sysInfo)) return true;
        int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
        if (lockId != -1 && Build.VERSION.SDK_INT >= 34) {
            WallpaperInfo lockInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
            if (isHyperAlive(ctx, lockInfo)) return true;
        }
        return false;
    }

    /**
     * 在用户选择备份并应用时准备原壁纸快照。备份失败后可由用户另行选择直接应用。
     */
    public static BackupPrepResult ensureBackupBeforeApply(Context ctx) {
        if (ctx == null) {
            return new BackupPrepResult(false, false, "Context 为空，无法安全备份");
        }
        lockTransaction();
        File stageDir = null;
        File sysTmp = null;
        File lockTmp = null;
        File metaTmp = null;
        try {
            BackupState state = getBackupStateUnlocked(ctx);
            if (state == BackupState.VALID) {
                return new BackupPrepResult(true, false, "已存在应用前有效快照，保留初始备份");
            }
            if (state == BackupState.CORRUPTED) {
                return new BackupPrepResult(false, false, "检测到已有损坏的应用前备份快照，为防止覆盖原记录已中止应用");
            }

            if (!Root.hasRoot()) {
                return new BackupPrepResult(false, false, "缺少 ROOT 权限，无法完整备份系统设置与壁纸槽位");
            }

            WallpaperManager wm = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) {
                return new BackupPrepResult(false, false, "无法访问 WallpaperManager，中止应用");
            }

            final int userId = currentUserId();

            // 1. 捕获前记录槽位 ID、组件与只读 wallpaper_info.xml 元数据（任一失败或不一致均保守中止）
            final int sysIdBefore;
            final int lockIdBefore;
            final WallpaperInfo sysInfoBefore;
            final WallpaperInfo lockInfoBefore;
            final WallpaperXmlState xmlBefore;
            try {
                sysIdBefore = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                lockIdBefore = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                sysInfoBefore = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                xmlBefore = readWallpaperInfoXml(userId);
            } catch (Throwable t) {
                return new BackupPrepResult(false, false, "查询系统壁纸槽位失败，已中止：" + t.getMessage());
            }

            if (xmlBefore == null || !xmlBefore.hasWp) {
                return new BackupPrepResult(false, false,
                        "无法通过只读 ABX/XML 解析核验系统壁纸元数据（wallpaper_info.xml），为防误判已中止应用");
            }
            if (xmlBefore.wpId != sysIdBefore) {
                return new BackupPrepResult(false, false,
                        "系统壁纸 ID 与元数据不一致（wm=" + sysIdBefore + ", xml=" + xmlBefore.wpId + "），已中止应用");
            }
            if ((lockIdBefore != -1) != xmlBefore.hasKwp) {
                return new BackupPrepResult(false, false,
                        "锁屏槽位状态与元数据不一致（lockId=" + lockIdBefore + ", hasKwp=" + xmlBefore.hasKwp + "），已中止应用");
            }
            if (xmlBefore.hasKwp && xmlBefore.kwpId != lockIdBefore) {
                return new BackupPrepResult(false, false,
                        "独立锁屏 ID 与元数据不一致（lockId=" + lockIdBefore + ", kwpId=" + xmlBefore.kwpId + "），已中止应用");
            }

            // 注意：Android 34+ 在无独立锁屏（lockId == -1）时 getWallpaperInfo(FLAG_LOCK) 可能回退返回桌面组件，
            // 故绝不能仅以 lockInfo != null 判定存在独立锁屏，仅在 lockId != -1 && xml.hasKwp 时视为独立锁屏。
            final boolean hasIndependentLock = (lockIdBefore != -1) && xmlBefore.hasKwp;
            try {
                if (hasIndependentLock && Build.VERSION.SDK_INT >= 34) {
                    lockInfoBefore = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
                } else {
                    lockInfoBefore = null;
                    if (Build.VERSION.SDK_INT >= 34) {
                        WallpaperInfo fallbackLock = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
                        if (isHyperAlive(ctx, fallbackLock)) {
                            return new BackupPrepResult(false, false, "当前壁纸已被 HyperAlive 占用，不能作为应用前原状态备份");
                        }
                        if (fallbackLock != null && sysInfoBefore != null && !sameComponent(fallbackLock, sysInfoBefore)) {
                            return new BackupPrepResult(false, false,
                                    "无独立锁屏记录但锁屏回退组件与桌面不一致，状态异常已中止");
                        }
                        if (fallbackLock != null && !xmlBefore.wpPkg.isEmpty()
                                && !sameComponentName(fallbackLock, xmlBefore.wpPkg, xmlBefore.wpCls)) {
                            return new BackupPrepResult(false, false,
                                    "无独立锁屏记录但锁屏回退组件与桌面元数据不一致，状态异常已中止");
                        }
                    }
                }
            } catch (Throwable t) {
                return new BackupPrepResult(false, false, "查询锁屏壁纸组件失败，已中止：" + t.getMessage());
            }

            if (isHyperAlive(ctx, sysInfoBefore)
                    || isHyperAliveName(ctx, xmlBefore.wpPkg, xmlBefore.wpCls)
                    || isHyperAlive(ctx, lockInfoBefore)
                    || (xmlBefore.hasKwp && isHyperAliveName(ctx, xmlBefore.kwpPkg, xmlBefore.kwpCls))) {
                return new BackupPrepResult(false, false, "当前壁纸已被 HyperAlive 占用，不能作为应用前原状态备份");
            }

            if (sysInfoBefore != null && !xmlBefore.wpPkg.isEmpty()
                    && !sameComponentName(sysInfoBefore, xmlBefore.wpPkg, xmlBefore.wpCls)) {
                return new BackupPrepResult(false, false,
                        "桌面壁纸组件与元数据不一致，已中止备份");
            }
            if (sysInfoBefore == null && !xmlBefore.wpPkg.isEmpty()
                    && !isMiuiImageWallpaperName(xmlBefore.wpPkg, xmlBefore.wpCls)) {
                return new BackupPrepResult(false, false,
                        "桌面壁纸元数据指向动态组件但系统查询为空，已中止备份");
            }
            if (hasIndependentLock && lockInfoBefore != null && !xmlBefore.kwpPkg.isEmpty()
                    && !sameComponentName(lockInfoBefore, xmlBefore.kwpPkg, xmlBefore.kwpCls)) {
                return new BackupPrepResult(false, false,
                        "独立锁屏组件与元数据不一致，已中止备份");
            }

            // 注意：绝不提前 mkdirs 创建 user_<uid> 目录，先使用独立临时暂存目录，全部核验后再原子提交
            File baseDir = getBackupBaseDirReadOnly(ctx);
            if (!baseDir.exists() && !baseDir.mkdirs() && !baseDir.isDirectory()) {
                Log.w(TAG, "stage=backup_prep source=storage committed=false code=base_dir_create_failed");
                return new BackupPrepResult(false, false, "无法创建备份存储目录，已中止本次应用");
            }
            stageDir = new File(baseDir, ".staging_user_" + userId + "_" + System.nanoTime());
            if (!stageDir.mkdirs()) {
                Log.w(TAG, "stage=backup_prep source=storage committed=false code=stage_dir_create_failed");
                return new BackupPrepResult(false, false, "无法创建备份临时工作区，已中止本次应用");
            }

            // 2. 精准捕获原生息屏设置（使用固定路径与 --user，区分不存在与空串）
            ContentResolver cr = ctx.getContentResolver();
            SettingRecord ambientRec = readSetting(cr, userId, "system", "enter_ambient_mode");
            SettingRecord posRec = readSetting(cr, userId, "system", "alive_wallpaper_position");
            if (!ambientRec.ok || !posRec.ok) {
                Log.w(TAG, "stage=backup_prep source=settings_system committed=false code=setting_read_failed");
                return new BackupPrepResult(false, false, "读取系统息屏设置失败，已中止本次应用");
            }
            JSONObject moduleStateJson = captureModuleStateJson(ctx);

            // 3. 捕获桌面槽位（及 HyperOS OEM 交叉核验）
            String sysType;
            String sysPkg = "";
            String sysCls = "";
            String sysFile = "";
            String lockType = "";
            String lockPkg = "";
            String lockCls = "";
            String lockFile = "";
            JSONObject oemJson = null;
            boolean defaultVerified = false;
            String defaultSource = "";
            String defaultOemHomeType = "";
            String defaultOemLockType = "";

            boolean sysIsLive = (sysInfoBefore != null && !isMiuiImageWallpaper(sysInfoBefore))
                    || (!xmlBefore.wpPkg.isEmpty() && !isMiuiImageWallpaperName(xmlBefore.wpPkg, xmlBefore.wpCls));
            if (sysIsLive) {
                if (sysInfoBefore == null || xmlBefore.wpPkg.isEmpty()
                        || !sameComponentName(sysInfoBefore, xmlBefore.wpPkg, xmlBefore.wpCls)) {
                    return new BackupPrepResult(false, false, "无法一致确认当前桌面动态壁纸组件，已中止备份");
                }
                sysType = "LIVE";
                sysPkg = sysInfoBefore.getPackageName();
                sysCls = normalizeClassName(sysPkg, sysInfoBefore.getServiceName());
            } else if (shouldRequireOemCrossValidation(sysInfoBefore, xmlBefore)) {
                // MIUI/HyperOS ImageWallpaper（含 SET_STATIC 且标准槽文件已存在场景）：
                // 严禁因标准 FD/文件可读跳过权威 OEM 交叉核验。
                HyperOsWallpaperHelper.OemProbeResult fsOemRes =
                        HyperOsWallpaperHelper.probeAndValidateOemState(userId, createRootGateway(cr, userId));
                String bridgeRaw = Root.probeHyperOsWallpaper(ctx, userId);
                HyperOsWallpaperHelper.BridgeProbeSummary bridgeSummary =
                        HyperOsWallpaperHelper.parseBridgeProbeOutput(bridgeRaw);
                String verifiedSplitLockComp = hasIndependentLock
                        ? resolveCleanSplitLockComp(lockInfoBefore, xmlBefore, bridgeSummary)
                        : "";
                HyperOsWallpaperHelper.OemProbeResult oemRes =
                        HyperOsWallpaperHelper.crossValidateWithBridge(
                                fsOemRes, bridgeSummary, userId, verifiedSplitLockComp);

                boolean stdSysAbsent = false;
                boolean stdLockAbsent = false;
                boolean capturedPlainStatic = false;
                boolean divergedSplitStatic = false;
                if (oemRes != null && oemRes.mode == HyperOsWallpaperHelper.Mode.CONFIRMED_DEFAULT) {
                    stdSysAbsent = confirmAllFilesAbsent(
                            "/data/system/users/" + userId + "/wallpaper",
                            "/data/system/users/" + userId + "/wallpaper_orig");
                    stdLockAbsent = confirmAllFilesAbsent(
                            "/data/system/users/" + userId + "/wallpaper_lock",
                            "/data/system/users/" + userId + "/wallpaper_lock_orig");
                    if (!isConfirmedDefaultState(sysIdBefore, lockIdBefore, sysInfoBefore, xmlBefore, true)
                            || !stdSysAbsent || !stdLockAbsent) {
                        sysTmp = new File(stageDir, SYS_IMG_FILE);
                        SlotCaptureResult capPlain = captureStaticSlot(
                                wm, userId, WallpaperManager.FLAG_SYSTEM, sysTmp, xmlBefore, sysIdBefore, sysInfoBefore);
                        capturedPlainStatic = (capPlain == SlotCaptureResult.FILE_OK);
                    }
                } else if (oemRes != null && oemRes.mode == HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE
                        && hasIndependentLock) {
                    // 原生双槽独立锁屏场景（如桌面 ImageWallpaper + 锁屏 MiuiKeyguardPictorialWallpaper）：
                    // 比对标准槽文件与 OEM hash；禁止在偏离时降级为 PLAIN_STATIC（会丢弃 OEM 模板/设置），
                    // 任何不一致或不可读一律零写入中止。
                    sysTmp = new File(stageDir, SYS_IMG_FILE);
                    SlotCaptureResult capSplitSys = captureStaticSlot(
                            wm, userId, WallpaperManager.FLAG_SYSTEM, sysTmp, xmlBefore, sysIdBefore, sysInfoBefore);
                    boolean sysStdExists = confirmAnyFileExists(
                            "/data/system/users/" + userId + "/wallpaper",
                            "/data/system/users/" + userId + "/wallpaper_orig");
                    boolean lockStdExists = confirmAnyFileExists(
                            "/data/system/users/" + userId + "/wallpaper_lock",
                            "/data/system/users/" + userId + "/wallpaper_lock_orig");
                    if (capSplitSys == SlotCaptureResult.FILE_OK) {
                        String sysSha = HyperOsWallpaperHelper.sha256Hex(sysTmp);
                        deleteQuietly(sysTmp);
                        sysTmp = null;
                        String lockSha = "";
                        boolean lockUnreadable = false;
                        if (lockStdExists) {
                            File probeLockTmp = new File(stageDir, "split_probe_lock.png");
                            SlotCaptureResult capSplitLock = captureStaticSlot(
                                    wm, userId, WallpaperManager.FLAG_LOCK, probeLockTmp,
                                    xmlBefore, lockIdBefore, lockInfoBefore);
                            if (capSplitLock == SlotCaptureResult.FILE_OK) {
                                lockSha = HyperOsWallpaperHelper.sha256Hex(probeLockTmp);
                            }
                            if (lockSha.isEmpty()) {
                                lockUnreadable = true;
                            }
                            deleteQuietly(probeLockTmp);
                        }
                        if (lockUnreadable) {
                            Log.w(TAG, "stage=backup_prep source=split_oem_check committed=false code=split_lock_std_unreadable");
                            return new BackupPrepResult(false, false,
                                    "检测到双槽独立锁屏标准文件存在但读取校验失败，为防状态不一致已中止本次应用");
                        }
                        divergedSplitStatic = isSplitStandardDivergedFromOem(
                                oemRes, true, sysSha, lockStdExists, lockSha);
                    } else if (sysStdExists || lockStdExists) {
                        deleteQuietly(sysTmp);
                        sysTmp = null;
                        Log.w(TAG, "stage=backup_prep source=split_oem_check committed=false code=split_std_unreadable");
                        return new BackupPrepResult(false, false,
                                "检测到双槽独立壁纸标准文件存在但读取校验失败，为防误覆盖桌面已中止本次应用");
                    } else {
                        deleteQuietly(sysTmp);
                        sysTmp = null;
                    }
                }

                MiuiStaticSysDecision decision = classifyMiuiStaticSystemSlot(
                        oemRes, xmlBefore, sysIdBefore, lockIdBefore, sysInfoBefore,
                        hasIndependentLock, stdSysAbsent, stdLockAbsent, capturedPlainStatic, divergedSplitStatic);
                if (decision.choice == MiuiStaticSysChoice.ABORT) {
                    Log.w(TAG, "stage=backup_prep source=oem_probe committed=false code="
                            + (oemRes != null ? oemRes.diagnosticCode : "oem_null"));
                    return new BackupPrepResult(false, false, decision.errorMessage);
                } else if (decision.choice == MiuiStaticSysChoice.CONFIRMED_DEFAULT) {
                    sysType = "DEFAULT";
                    defaultVerified = true;
                    defaultSource = "oem_bridge_confirmed";
                    defaultOemHomeType = oemRes.homeType != null ? oemRes.homeType : "";
                    defaultOemLockType = oemRes.lockType != null ? oemRes.lockType : "";
                } else if (decision.choice == MiuiStaticSysChoice.PLAIN_STATIC) {
                    sysType = "STATIC";
                    sysFile = SYS_IMG_FILE;
                } else {
                    File oemStageDir = new File(stageDir, OEM_DIR_NAME);
                    oemJson = stageOemCustomPayload(oemStageDir, oemRes);
                    if (oemJson == null) {
                        Log.w(TAG, "stage=backup_prep source=oem_stage committed=false code=oem_copy_or_verify_failed");
                        return new BackupPrepResult(false, false,
                                "备份并校验 HyperOS 自定义壁纸或锁屏模板文件失败，已中止本次应用");
                    }
                    sysType = TYPE_OEM_HYPEROS;
                    lockType = TYPE_OEM_HYPEROS;
                }
            } else {
                // 非 MIUI 原生普通静态 / 默认分支
                sysTmp = new File(stageDir, SYS_IMG_FILE);
                SlotCaptureResult capSys = captureStaticSlot(
                        wm, userId, WallpaperManager.FLAG_SYSTEM, sysTmp, xmlBefore, sysIdBefore, sysInfoBefore);
                if (capSys == SlotCaptureResult.FILE_OK) {
                    sysType = "STATIC";
                    sysFile = SYS_IMG_FILE;
                } else if (capSys == SlotCaptureResult.CONFIRMED_DEFAULT) {
                    sysType = "DEFAULT";
                    defaultVerified = true;
                    defaultSource = "aosp_confirmed";
                } else {
                    Log.w(TAG, "stage=backup_prep source=static_capture committed=false code=sys_slot_unavailable");
                    return new BackupPrepResult(false, false,
                            "无法获取当前桌面壁纸真实源文件或核实默认来源，请先在系统设置中选取一张可备份的壁纸");
                }
            }

            // 4. 捕获锁屏槽位（OEM_HYPEROS 已同时完整捕获桌面+锁屏模板，绝不将捕获失败降级为跟随桌面！）
            if (!TYPE_OEM_HYPEROS.equals(lockType)) {
                if (!hasIndependentLock) {
                    lockType = "SAME_AS_SYSTEM";
                } else {
                    boolean lockIsLive = (lockInfoBefore != null && !isMiuiStaticOrPictorial(lockInfoBefore))
                            || (!xmlBefore.kwpPkg.isEmpty() && !isMiuiStaticOrPictorialName(xmlBefore.kwpPkg, xmlBefore.kwpCls));
                    if (lockIsLive) {
                        if (Build.VERSION.SDK_INT < 34) {
                            return new BackupPrepResult(false, false,
                                    "当前系统版本（API < 34）不支持按槽位校验独立锁屏动态壁纸组件，无法安全备份与恢复");
                        }
                        if (lockInfoBefore == null || xmlBefore.kwpPkg.isEmpty()
                                || !sameComponentName(lockInfoBefore, xmlBefore.kwpPkg, xmlBefore.kwpCls)) {
                            return new BackupPrepResult(false, false, "无法一致确认当前独立锁屏动态壁纸组件，已中止备份");
                        }
                        lockType = "LIVE";
                        lockPkg = lockInfoBefore.getPackageName();
                        lockCls = normalizeClassName(lockPkg, lockInfoBefore.getServiceName());
                    } else {
                        lockTmp = new File(stageDir, LOCK_IMG_FILE);
                        SlotCaptureResult capLock = captureStaticSlot(
                                wm, userId, WallpaperManager.FLAG_LOCK, lockTmp, xmlBefore, lockIdBefore, lockInfoBefore);
                        if (capLock == SlotCaptureResult.FILE_OK) {
                            lockType = "STATIC";
                            lockFile = LOCK_IMG_FILE;
                        } else {
                            return new BackupPrepResult(false, false,
                                    "独立锁屏壁纸无法从系统槽位读取真实源文件，请先在系统设置中为锁屏挑选一张可备份的壁纸");
                        }
                    }
                }
            }

            // 5. 捕获后再次核对壁纸 ID、组件与元数据，防止并发/外部切换竞态
            try {
                int sysIdAfter = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                int lockIdAfter = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                WallpaperInfo sysInfoAfter = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                WallpaperInfo lockInfoAfter = (lockIdAfter != -1 && Build.VERSION.SDK_INT >= 34)
                        ? querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK) : null;
                WallpaperXmlState xmlAfter = readWallpaperInfoXml(userId);
                if (xmlAfter == null || !xmlAfter.hasWp
                        || sysIdBefore != sysIdAfter || lockIdBefore != lockIdAfter
                        || !sameComponent(sysInfoBefore, sysInfoAfter)
                        || !sameComponent(lockInfoBefore, lockInfoAfter)
                        || xmlBefore.wpId != xmlAfter.wpId
                        || xmlBefore.hasKwp != xmlAfter.hasKwp
                        || xmlBefore.kwpId != xmlAfter.kwpId
                        || !xmlBefore.wpBindSource.equals(xmlAfter.wpBindSource)
                        || !sameComponentName(xmlBefore.wpPkg, xmlBefore.wpCls, xmlAfter.wpPkg, xmlAfter.wpCls)
                        || !sameComponentName(xmlBefore.kwpPkg, xmlBefore.kwpCls, xmlAfter.kwpPkg, xmlAfter.kwpCls)) {
                    Log.w(TAG, "stage=backup_verify source=post_capture committed=false code=race_state_changed");
                    return new BackupPrepResult(false, false, "备份过程中检测到系统壁纸或元数据发生变化，已中止以保证数据一致");
                }
            } catch (Throwable t) {
                return new BackupPrepResult(false, false, "备份后复核槽位状态失败：" + t.getMessage());
            }

            // 6. 原子提交到 user_<uid> 目录
            File userDir = getBackupDirReadOnly(ctx);
            if (!userDir.exists() && !userDir.mkdirs() && !userDir.isDirectory()) {
                return new BackupPrepResult(false, false, "创建用户备份目录失败");
            }
            File sysDest = new File(userDir, SYS_IMG_FILE);
            File lockDest = new File(userDir, LOCK_IMG_FILE);
            File oemDest = new File(userDir, OEM_DIR_NAME);
            boolean commitOk = false;
            try {
                if ("STATIC".equals(sysType)) {
                    if (sysDest.exists()) sysDest.delete();
                    if (!sysTmp.renameTo(sysDest) || !isValidImageFile(sysDest)) {
                        return new BackupPrepResult(false, false, "保存桌面壁纸备份文件失败");
                    }
                    sysTmp = null;
                }
                if ("STATIC".equals(lockType)) {
                    if (lockDest.exists()) lockDest.delete();
                    if (!lockTmp.renameTo(lockDest) || !isValidImageFile(lockDest)) {
                        return new BackupPrepResult(false, false, "保存锁屏壁纸备份文件失败");
                    }
                    lockTmp = null;
                }
                if (TYPE_OEM_HYPEROS.equals(sysType)) {
                    if (oemDest.exists()) deleteRecursively(oemDest);
                    File oemStageDir = new File(stageDir, OEM_DIR_NAME);
                    if (!oemStageDir.renameTo(oemDest) || !oemDest.isDirectory()) {
                        return new BackupPrepResult(false, false, "提交 HyperOS OEM 快照目录失败");
                    }
                }

                JSONObject json = new JSONObject();
                json.put("version", 2);
                json.put("userId", userId);
                json.put("backupTime", System.currentTimeMillis());
                json.put("enter_ambient_mode_existed", ambientRec.existed);
                json.put("enter_ambient_mode_val", ambientRec.value != null ? ambientRec.value : "");
                json.put("alive_wallpaper_position_existed", posRec.existed);
                json.put("alive_wallpaper_position_val", posRec.value != null ? posRec.value : "");
                json.put("system_type", sysType);
                json.put("system_pkg", sysPkg);
                json.put("system_cls", sysCls);
                json.put("system_file", sysFile);
                json.put("lock_type", lockType);
                json.put("lock_pkg", lockPkg);
                json.put("lock_cls", lockCls);
                json.put("lock_file", lockFile);
                if ("DEFAULT".equals(sysType)) {
                    json.put("default_verified", defaultVerified);
                    json.put("default_source", defaultSource);
                    if (!defaultOemHomeType.isEmpty()) {
                        json.put("default_oem_home_type", defaultOemHomeType);
                    }
                    if (!defaultOemLockType.isEmpty()) {
                        json.put("default_oem_lock_type", defaultOemLockType);
                    }
                }
                if (oemJson != null) {
                    json.put("oem", oemJson);
                }
                if (moduleStateJson != null) {
                    json.put("module_state", moduleStateJson);
                }
                json.put("valid", true);

                metaTmp = new File(userDir, META_TMP);
                File metaFile = new File(userDir, META_FILE);
                try (FileOutputStream fos = new FileOutputStream(metaTmp)) {
                    fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.getFD().sync();
                }
                if (metaFile.exists()) metaFile.delete();
                if (!metaTmp.renameTo(metaFile)) {
                    return new BackupPrepResult(false, false, "写入备份元数据失败");
                }
                metaTmp = null;
                PreApplyBaseline baseline = new PreApplyBaseline(
                        userId,
                        sysIdBefore,
                        lockIdBefore,
                        xmlBefore.hasKwp,
                        xmlBefore.wpBindSource,
                        xmlBefore.wpPkg,
                        xmlBefore.wpCls,
                        xmlBefore.kwpPkg,
                        xmlBefore.kwpCls,
                        sysInfoBefore != null ? sysInfoBefore.getPackageName() : "",
                        sysInfoBefore != null ? sysInfoBefore.getServiceName() : "",
                        lockInfoBefore != null ? lockInfoBefore.getPackageName() : "",
                        lockInfoBefore != null ? lockInfoBefore.getServiceName() : "",
                        ambientRec.existed,
                        ambientRec.value,
                        posRec.existed,
                        posRec.value);
                commitOk = true;
                Log.i(TAG, "stage=backup_commit source=" + sysType + "/" + lockType + " committed=true");
                return new BackupPrepResult(true, true, "已原子备份当前用户应用前壁纸与原生息屏配置", baseline);
            } finally {
                if (!commitOk) {
                    deleteRecursively(userDir);
                    Log.w(TAG, "stage=backup_commit source=" + sysType + "/" + lockType + " committed=false code=rollback_uncommitted");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "stage=backup_prep source=exception committed=false", t);
            return new BackupPrepResult(false, false, "备份异常：" + t.getMessage());
        } finally {
            deleteQuietly(metaTmp);
            deleteRecursively(stageDir);
            unlockTransaction();
        }
    }

    private enum SlotCaptureResult {
        FILE_OK,
        CONFIRMED_DEFAULT,
        CHECK_HYPEROS_OEM,
        FAILED
    }

    enum MiuiStaticSysChoice {
        OEM_HYPEROS,
        CONFIRMED_DEFAULT,
        PLAIN_STATIC,
        ABORT
    }

    static final class MiuiStaticSysDecision {
        final MiuiStaticSysChoice choice;
        final String errorMessage;

        MiuiStaticSysDecision(MiuiStaticSysChoice choice, String errorMessage) {
            this.choice = choice;
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }
    }

    static boolean shouldRequireOemCrossValidation(WallpaperInfo sysInfo, WallpaperXmlState xml) {
        return (sysInfo != null && "com.miui.miwallpaper".equals(sysInfo.getPackageName())
                        && isMiuiImageWallpaper(sysInfo))
                || (xml != null && "com.miui.miwallpaper".equals(xml.wpPkg)
                        && isMiuiImageWallpaperName(xml.wpPkg, xml.wpCls));
    }

    static boolean isSplitStandardDivergedFromOem(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            boolean sysStdPresent, String sysStdSha256,
            boolean lockStdPresent, String lockStdSha256) {
        if (oemRes == null || oemRes.mode != HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE) {
            return false;
        }
        if (!sysStdPresent) {
            return lockStdPresent;
        }
        boolean homeMatches = "image".equals(oemRes.homeType)
                && !oemRes.expectedHomeSha256.isEmpty()
                && oemRes.expectedHomeSha256.equalsIgnoreCase(sysStdSha256 != null ? sysStdSha256 : "");
        if (!homeMatches) {
            return true;
        }
        if (!lockStdPresent) {
            return false;
        }
        if ("image".equals(oemRes.lockType)) {
            return oemRes.expectedLockSha256.isEmpty()
                    || !oemRes.expectedLockSha256.equalsIgnoreCase(lockStdSha256 != null ? lockStdSha256 : "");
        }
        return true;
    }

    static MiuiStaticSysDecision classifyMiuiStaticSystemSlot(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            WallpaperXmlState xml,
            int sysId,
            int lockId,
            WallpaperInfo sysInfo,
            boolean hasIndependentLock,
            boolean stdSysFilesAbsent,
            boolean stdLockFilesAbsent,
            boolean capturedPlainStaticFile) {
        return classifyMiuiStaticSystemSlot(
                oemRes, xml, sysId, lockId, sysInfo,
                hasIndependentLock, stdSysFilesAbsent, stdLockFilesAbsent, capturedPlainStaticFile, false);
    }

    static MiuiStaticSysDecision classifyMiuiStaticSystemSlot(
            HyperOsWallpaperHelper.OemProbeResult oemRes,
            WallpaperXmlState xml,
            int sysId,
            int lockId,
            WallpaperInfo sysInfo,
            boolean hasIndependentLock,
            boolean stdSysFilesAbsent,
            boolean stdLockFilesAbsent,
            boolean capturedPlainStaticFile,
            boolean divergedSplitStatic) {
        if (oemRes == null || oemRes.mode == HyperOsWallpaperHelper.Mode.AMBIGUOUS_OR_UNVERIFIED) {
            String msg = (oemRes != null && oemRes.userMessage != null && !oemRes.userMessage.isEmpty())
                    ? oemRes.userMessage : "HyperOS OEM 权威核验失败，已中止应用";
            return new MiuiStaticSysDecision(MiuiStaticSysChoice.ABORT, msg);
        }
        if (oemRes.mode == HyperOsWallpaperHelper.Mode.OEM_CUSTOM_IMAGE) {
            if (hasIndependentLock && divergedSplitStatic) {
                return new MiuiStaticSysDecision(MiuiStaticSysChoice.ABORT,
                        "检测到双槽独立壁纸标准槽文件与 HyperOS OEM 图档或模板不一致，禁止降级静默丢弃 OEM 模板/设置，已中止本次应用（零写入）");
            }
            if (hasIndependentLock && !oemRes.wmsSplitLock) {
                return new MiuiStaticSysDecision(MiuiStaticSysChoice.ABORT,
                        "HyperOS OEM 自定义壁纸检测到意外的独立锁屏槽位 ID，为安全已中止应用");
            }
            return new MiuiStaticSysDecision(MiuiStaticSysChoice.OEM_HYPEROS, "");
        }
        if (isConfirmedDefaultState(sysId, lockId, sysInfo, xml, true)
                && stdSysFilesAbsent && stdLockFilesAbsent) {
            return new MiuiStaticSysDecision(MiuiStaticSysChoice.CONFIRMED_DEFAULT, "");
        }
        if (xml != null && !"SET_LIVE_TO_CLEAR".equals(xml.wpBindSource) && capturedPlainStaticFile) {
            return new MiuiStaticSysDecision(MiuiStaticSysChoice.PLAIN_STATIC, "");
        }
        return new MiuiStaticSysDecision(MiuiStaticSysChoice.ABORT,
                "无法最终核实系统默认壁纸槽位拓扑或静态源文件一致，已中止应用");
    }

    enum ProbeStatus {
        EXISTS,
        ABSENT,
        ERROR
    }

    static final class WallpaperXmlState {
        boolean hasWp;
        int wpId = Integer.MIN_VALUE;
        String wpBindSource = "";
        String wpPkg = "";
        String wpCls = "";
        boolean hasKwp;
        int kwpId = -1;
        String kwpBindSource = "";
        String kwpPkg = "";
        String kwpCls = "";
    }

    /**
     * 批量只读探测一组路径是否存在（单次 su -M 调用）：
     * 1. 若路径为符号链接 (-L)，显式标记为 ERR（拒绝符号链接伪造）；
     * 2. 按索引 0..N-1 输出 __P:<idx>:<EXISTS|ABSENT|ERR> 及末尾 __EXIT:$?；
     * 3. 任何输出截断、缺失索引、重复索引、乱序杂讯或非零退出码均保守映射为 ProbeStatus.ERROR。
     */
    static ProbeStatus[] probeFilesReadOnlyBatch(String... paths) {
        if (paths == null || paths.length == 0) {
            return new ProbeStatus[0];
        }
        ProbeStatus[] errResult = new ProbeStatus[paths.length];
        java.util.Arrays.fill(errResult, ProbeStatus.ERROR);
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i];
            if (p == null || p.isEmpty()) {
                return errResult;
            }
            String q = Root.shellQuote(p);
            cmd.append("if [ -L ").append(q).append(" ]; then echo __P:").append(i).append(":ERR; ")
                    .append("elif [ -e ").append(q).append(" ]; then echo __P:").append(i).append(":EXISTS; ")
                    .append("elif [ ! -e ").append(q).append(" ]; then echo __P:").append(i).append(":ABSENT; ")
                    .append("else echo __P:").append(i).append(":ERR; fi; ");
        }
        cmd.append("echo __EXIT:$?");
        String out = Root.suMountMaster(cmd.toString(), 4000);
        return parseBatchProbeOutput(out, paths.length);
    }

    /**
     * 严格解析批量只读文件探测输出，要求 0..expectedCount-1 索引一一对应且以 __EXIT:0 结束。
     */
    static ProbeStatus[] parseBatchProbeOutput(String out, int expectedCount) {
        if (expectedCount <= 0) {
            return new ProbeStatus[0];
        }
        ProbeStatus[] errResult = new ProbeStatus[expectedCount];
        java.util.Arrays.fill(errResult, ProbeStatus.ERROR);
        if (out == null || out.isEmpty() || out.startsWith("suRaw")) {
            return errResult;
        }
        ProbeStatus[] parsed = new ProbeStatus[expectedCount];
        boolean sawExitZero = false;
        for (String line : out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("__EXIT:")) {
                if (!"__EXIT:0".equals(t) || sawExitZero) {
                    return errResult;
                }
                sawExitZero = true;
            } else if (t.startsWith("__P:")) {
                if (sawExitZero) {
                    return errResult;
                }
                String[] parts = t.split(":", -1);
                if (parts.length != 3) {
                    return errResult;
                }
                int idx;
                try {
                    idx = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return errResult;
                }
                if (idx < 0 || idx >= expectedCount || parsed[idx] != null) {
                    return errResult;
                }
                String st = parts[2];
                if ("EXISTS".equals(st)) {
                    parsed[idx] = ProbeStatus.EXISTS;
                } else if ("ABSENT".equals(st)) {
                    parsed[idx] = ProbeStatus.ABSENT;
                } else if ("ERR".equals(st)) {
                    parsed[idx] = ProbeStatus.ERROR;
                } else {
                    return errResult;
                }
            } else {
                return errResult;
            }
        }
        if (!sawExitZero) {
            return errResult;
        }
        for (int i = 0; i < expectedCount; i++) {
            if (parsed[i] == null) {
                return errResult;
            }
        }
        return parsed;
    }

    /** 明确只读探测单个文件是否存在；任何命令失败或输出不明确都返回 ERROR（绝不当作不存在）。 */
    private static ProbeStatus probeFileReadOnly(String path) {
        if (path == null || path.isEmpty()) return ProbeStatus.ERROR;
        return probeFilesReadOnlyBatch(path)[0];
    }

    /** 确认一组路径均明确探测为 ABSENT（单次批量探测，任一存在或探测失败均返回 false）。 */
    private static boolean confirmAllFilesAbsent(String... paths) {
        if (paths == null || paths.length == 0) return false;
        ProbeStatus[] statuses = probeFilesReadOnlyBatch(paths);
        if (statuses.length != paths.length) return false;
        for (ProbeStatus st : statuses) {
            if (st != ProbeStatus.ABSENT) {
                return false;
            }
        }
        return true;
    }

    /** 确认至少一个候选路径明确探测为 EXISTS（单次批量探测，未返回 ERROR 且确实存在）。 */
    private static boolean confirmAnyFileExists(String... paths) {
        if (paths == null || paths.length == 0) return false;
        ProbeStatus[] statuses = probeFilesReadOnlyBatch(paths);
        for (ProbeStatus st : statuses) {
            if (st == ProbeStatus.EXISTS) {
                return true;
            }
        }
        return false;
    }

    /** 仅通过真实槽位 FD 或 root 只读槽文件复制并校验图片有效性；绝不用 getBuiltInDrawable/getDrawable 兜底。 */
    private static SlotCaptureResult captureStaticSlot(
            WallpaperManager wm, int userId, int which, File tmpFile,
            WallpaperXmlState xml, int slotId, WallpaperInfo slotInfo) {
        boolean xmlClearDefault = (which == WallpaperManager.FLAG_SYSTEM)
                && xml != null && xml.hasWp
                && "SET_LIVE_TO_CLEAR".equals(xml.wpBindSource);

        // 1. WallpaperManager.getWallpaperFile（若元数据明确为 SET_LIVE_TO_CLEAR，不盲信可能由系统返回的默认 FD）
        if (Build.VERSION.SDK_INT >= 24 && !xmlClearDefault) {
            try {
                ParcelFileDescriptor pfd = wm.getWallpaperFile(which);
                if (pfd != null) {
                    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                         FileOutputStream out = new FileOutputStream(tmpFile)) {
                        byte[] buf = new byte[16384];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                        out.getFD().sync();
                    }
                    if (isValidImageFile(tmpFile)) {
                        return SlotCaptureResult.FILE_OK;
                    }
                    deleteQuietly(tmpFile);
                }
            } catch (Throwable t) {
                Log.w(TAG, "getWallpaperFile(which=" + which + ") 不可用: " + t.getMessage());
                deleteQuietly(tmpFile);
            }
        }

        // 2. Root 只读探测并从真实槽文件复制（Java 先创建宿主文件保持属主为 app UID）
        String[] candidates = (which == WallpaperManager.FLAG_LOCK)
                ? new String[]{"/data/system/users/" + userId + "/wallpaper_lock",
                               "/data/system/users/" + userId + "/wallpaper_lock_orig"}
                : new String[]{"/data/system/users/" + userId + "/wallpaper",
                               "/data/system/users/" + userId + "/wallpaper_orig"};

        boolean anyFileExists = false;
        boolean anyProbeError = false;
        for (String src : candidates) {
            ProbeStatus st = probeFileReadOnly(src);
            if (st == ProbeStatus.ERROR) {
                anyProbeError = true;
                continue;
            }
            if (st == ProbeStatus.ABSENT) {
                continue;
            }
            anyFileExists = true;
            try {
                // 预创建 app 可读写的目标文件，> 重定向不会更改文件属主
                new FileOutputStream(tmpFile).close();
                String cmd = "if [ -s " + Root.shellQuote(src) + " ]; then /system/bin/cat "
                        + Root.shellQuote(src) + " > " + Root.shellQuote(tmpFile.getAbsolutePath())
                        + " && echo __COPY_OK; fi; echo __EXIT=$?";
                String out = Root.suMountMaster(cmd, 5000);
                if (out != null && out.contains("__EXIT=0") && out.contains("__COPY_OK")
                        && isValidImageFile(tmpFile)) {
                    return SlotCaptureResult.FILE_OK;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Root copy slot " + src + " failed", t);
            }
            deleteQuietly(tmpFile);
        }

        // 3. 标准槽文件均不存在且探测无错时：
        //    - 若属于 com.miui.miwallpaper，严禁仅凭 SET_LIVE_TO_CLEAR + 标准文件不存在推断默认，
        //      必须交给 HyperOsWallpaperHelper 核验 theme_magic / templates / constant_template_editor_info；
        //    - 非 MIUI 原生桌面无文件状态时再严格核实 CONFIRMED_DEFAULT。
        if (which == WallpaperManager.FLAG_SYSTEM && !anyFileExists && !anyProbeError) {
            boolean isMiuiPkg = (slotInfo != null && "com.miui.miwallpaper".equals(slotInfo.getPackageName()))
                    || (xml != null && "com.miui.miwallpaper".equals(xml.wpPkg));
            if (isMiuiPkg) {
                return SlotCaptureResult.CHECK_HYPEROS_OEM;
            }
            boolean expectMerged = (xml != null && !xml.hasKwp);
            int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
            if (expectMerged) {
                if (lockId != -1) {
                    return SlotCaptureResult.FAILED;
                }
                if (!confirmAllFilesAbsent(
                        "/data/system/users/" + userId + "/wallpaper_lock",
                        "/data/system/users/" + userId + "/wallpaper_lock_orig")) {
                    return SlotCaptureResult.FAILED;
                }
            }
            if (isConfirmedDefaultState(slotId, lockId, slotInfo, xml, expectMerged)) {
                return SlotCaptureResult.CONFIRMED_DEFAULT;
            }
        }

        return SlotCaptureResult.FAILED;
    }

    /**
     * 核实当前系统桌面壁纸状态确系 clear 后的系统默认静态引擎状态。
     * 注意：Android SDK 对静态壁纸允许 getWallpaperInfo 返回 null，
     * 因此允许 sysInfo 为 null；但若 sysInfo 非空，则必须精确匹配可信系统静态组件及 ABX。
     */
    private static boolean isConfirmedDefaultState(
            int sysId, int lockId, WallpaperInfo sysInfo, WallpaperXmlState xml, boolean requireMerged) {
        if (xml == null || !xml.hasWp || sysId <= 0 || xml.wpId != sysId) {
            return false;
        }
        if (requireMerged && (lockId != -1 || xml.hasKwp)) {
            return false;
        }
        if (!isMiuiImageWallpaperName(xml.wpPkg, xml.wpCls)) {
            return false;
        }
        if (sysInfo != null) {
            if (!isMiuiImageWallpaper(sysInfo) || !sameComponentName(sysInfo, xml.wpPkg, xml.wpCls)) {
                return false;
            }
        }
        return "SET_LIVE_TO_CLEAR".equals(xml.wpBindSource);
    }

    /**
     * 只读读取并结构化解析 /data/system/users/<userId>/wallpaper_info.xml：
     * 1. 优先使用 /system/bin/abx2xml <path> - 转换 ABX；
     * 2. 若设备无 abx2xml 或 abx2xml 不支持（如纯文本 XML 设备），仅在确认非二进制 ABX 且为合法纯 XML 时回退解析；
     * 3. 任一命令失败、截断或结构异常均返回 null，由调用方保守中止。
     */
    private static WallpaperXmlState readWallpaperInfoXml(int userId) {
        String xmlPath = "/data/system/users/" + userId + "/wallpaper_info.xml";
        if (probeFileReadOnly(xmlPath) != ProbeStatus.EXISTS) {
            return null;
        }

        String abxCmd = "if [ -x /system/bin/abx2xml ]; then "
                + "/system/bin/abx2xml " + Root.shellQuote(xmlPath) + " - 2>/dev/null; "
                + "rc=$?; echo ''; echo __ABX_EXIT=$rc; "
                + "else echo __NO_ABX2XML; fi";
        String abxOut = Root.suMountMaster(abxCmd, 4000);
        if (abxOut == null || abxOut.isEmpty()) {
            return null;
        }

        String bodyFromAbx = extractPayloadWithExitZero(abxOut, "__ABX_EXIT=");
        if (bodyFromAbx != null) {
            return parseWallpaperInfoXml(bodyFromAbx);
        }

        // 仅在无 abx2xml 或 abx2xml 返回非 0（可能为非 ABX 纯 XML 文件）时尝试读取纯文本 XML
        String catCmd = "/system/bin/cat " + Root.shellQuote(xmlPath) + " 2>/dev/null; echo ''; echo __CAT_EXIT=$?";
        String catOut = Root.suMountMaster(catCmd, 4000);
        if (catOut == null || catOut.isEmpty()) {
            return null;
        }
        String rawPlain = extractPayloadWithExitZero(catOut, "__CAT_EXIT=");
        if (rawPlain == null || rawPlain.startsWith("ABX")) {
            return null;
        }
        for (int i = 0; i < rawPlain.length(); i++) {
            char ch = rawPlain.charAt(i);
            if (ch == 0 || (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r')) {
                return null;
            }
        }
        return parseWallpaperInfoXml(rawPlain);
    }

    private static String extractPayloadWithExitZero(String out, String exitPrefix) {
        if (out == null) return null;
        String[] lines = out.split("\r?\n", -1);
        int lastNonEmpty = lines.length - 1;
        while (lastNonEmpty >= 0 && lines[lastNonEmpty].trim().isEmpty()) {
            lastNonEmpty--;
        }
        if (lastNonEmpty < 0 || !(exitPrefix + "0").equals(lines[lastNonEmpty].trim())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastNonEmpty; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        String payload = sb.toString().trim();
        return payload.isEmpty() ? null : payload;
    }

    private static WallpaperXmlState parseWallpaperInfoXml(String rawXml) {
        if (rawXml == null) return null;
        String body = rawXml.trim();
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1).trim();
        }
        if (body.startsWith("<?xml")) {
            int endPi = body.indexOf("?>");
            if (endPi < 0) return null;
            body = body.substring(endPi + 2).trim();
        }
        if (!body.startsWith("<wp") && !body.startsWith("<kwp")) {
            return null;
        }

        // AOSP wallpaper_info.xml 在顶层并列写 <wp> 与 <kwp>，包一层根节点确保 XmlPullParser 兼容双标签
        String wrapped = "<_root_>" + body + "</_root_>";
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(wrapped));

            WallpaperXmlState state = new WallpaperXmlState();
            String currentSlot = null;
            int wpCount = 0;
            int kwpCount = 0;
            boolean wpIdFound = false;
            boolean kwpIdFound = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    int depth = parser.getDepth();
                    if (depth == 2 && "wp".equals(tag)) {
                        wpCount++;
                        if (wpCount > 1) return null;
                        currentSlot = "wp";
                        state.hasWp = true;
                        String idStr = parser.getAttributeValue(null, "id");
                        if (idStr == null || idStr.isEmpty()) return null;
                        state.wpId = Integer.parseInt(idStr.trim());
                        wpIdFound = true;
                        String bs = parser.getAttributeValue(null, "bindSource");
                        state.wpBindSource = bs != null ? bs.trim() : "";
                        String comp = parser.getAttributeValue(null, "component");
                        if (comp != null && !comp.trim().isEmpty()) {
                            ComponentName cn = ComponentName.unflattenFromString(comp.trim());
                            if (cn == null) return null;
                            state.wpPkg = cn.getPackageName();
                            state.wpCls = normalizeClassName(cn.getPackageName(), cn.getClassName());
                        }
                    } else if (depth == 2 && "kwp".equals(tag)) {
                        kwpCount++;
                        if (kwpCount > 1) return null;
                        currentSlot = "kwp";
                        state.hasKwp = true;
                        String idStr = parser.getAttributeValue(null, "id");
                        if (idStr != null && !idStr.isEmpty()) {
                            state.kwpId = Integer.parseInt(idStr.trim());
                            kwpIdFound = true;
                        }
                        String bs = parser.getAttributeValue(null, "bindSource");
                        state.kwpBindSource = bs != null ? bs.trim() : "";
                        String comp = parser.getAttributeValue(null, "component");
                        if (comp != null && !comp.trim().isEmpty()) {
                            ComponentName cn = ComponentName.unflattenFromString(comp.trim());
                            if (cn == null) return null;
                            state.kwpPkg = cn.getPackageName();
                            state.kwpCls = normalizeClassName(cn.getPackageName(), cn.getClassName());
                        }
                    } else if (depth == 3 && "description".equals(tag) && currentSlot != null) {
                        String comp = parser.getAttributeValue(null, "component");
                        if (comp != null && !comp.trim().isEmpty()) {
                            ComponentName cn = ComponentName.unflattenFromString(comp.trim());
                            if (cn == null) return null;
                            String pkg = cn.getPackageName();
                            String cls = normalizeClassName(pkg, cn.getClassName());
                            if ("wp".equals(currentSlot)) {
                                if (!state.wpPkg.isEmpty() && !sameComponentName(state.wpPkg, state.wpCls, pkg, cls)) {
                                    return null;
                                }
                                state.wpPkg = pkg;
                                state.wpCls = cls;
                            } else if ("kwp".equals(currentSlot)) {
                                if (!state.kwpPkg.isEmpty() && !sameComponentName(state.kwpPkg, state.kwpCls, pkg, cls)) {
                                    return null;
                                }
                                state.kwpPkg = pkg;
                                state.kwpCls = cls;
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.getDepth() == 2) {
                    String tag = parser.getName();
                    if ("wp".equals(tag) || "kwp".equals(tag)) {
                        currentSlot = null;
                    }
                }
                event = parser.next();
            }

            if (!state.hasWp || !wpIdFound) {
                return null;
            }
            if (state.hasKwp && !kwpIdFound) {
                return null;
            }
            return state;
        } catch (Throwable t) {
            Log.w(TAG, "parseWallpaperInfoXml failed", t);
            return null;
        }
    }

    /** 手动恢复入口（独立于 LSPosed，受全局事务锁保护，预检失败零写入）。 */
    public static RestoreResult restoreBackupSync(Context ctx) {
        if (ctx == null) {
            return RestoreResult.failure("Context 为空，未执行任何操作。");
        }
        lockTransaction();
        try {
            // 1. 预检（此阶段设备零写入）
            BackupState state = getBackupStateUnlocked(ctx);
            if (state == BackupState.NONE) {
                Log.i(TAG, "stage=restore source=none committed=false code=no_backup");
                return RestoreResult.failure("未检测到应用前壁纸备份快照，未作任何更改。");
            }
            if (state == BackupState.CORRUPTED) {
                Log.w(TAG, "stage=restore source=corrupted committed=false code=corrupted_backup");
                return RestoreResult.failure("检测到备份快照文件损坏或不完整，为防误操作未执行任何更改。");
            }

            BackupMetadata meta = getBackupMetadataUnlocked(ctx);
            if (meta == null) {
                return RestoreResult.failure("备份元数据校验失败，未作任何更改。");
            }
            if (!Root.hasRoot()) {
                return RestoreResult.failure("缺少 ROOT 权限，无法恢复壁纸与原生系统参数，未作任何更改。");
            }

            File userDir = getBackupDirReadOnly(ctx);
            WallpaperManager wm = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) {
                return RestoreResult.failure("无法获取 WallpaperManager，未作任何更改。");
            }

            boolean isOemHyperOs = TYPE_OEM_HYPEROS.equals(meta.systemType) || TYPE_OEM_HYPEROS.equals(meta.lockType);
            JSONObject preparedOemSpec = null;
            if (isOemHyperOs) {
                if (meta.userId != currentUserId()) {
                    return RestoreResult.failure("备份所属用户（user " + meta.userId + "）与当前用户不一致，未作任何更改。");
                }
                if (meta.oemJson == null) {
                    return RestoreResult.failure("HyperOS OEM 备份元数据缺失，未作任何更改。");
                }
                StringBuilder oemPrecheckErr = new StringBuilder();
                preparedOemSpec = prepareOemRestoreSpec(meta, userDir, oemPrecheckErr);
                if (preparedOemSpec == null) {
                    String errMsg = oemPrecheckErr.length() > 0
                            ? oemPrecheckErr.toString()
                            : "HyperOS OEM 快照声明、图片校验和或模板路径预检失败，未作任何更改。";
                    return RestoreResult.failure(errMsg);
                }
            } else {
                // 预检目标文件/组件可用性及结构化校验能力（API29-33 无可靠核验能力时在写入前阻止）
                if ("STATIC".equals(meta.systemType) && !isValidImageFile(new File(userDir, meta.systemFile))) {
                    return RestoreResult.failure("桌面静态壁纸快照文件损坏，未作任何更改。");
                }
                if ("LIVE".equals(meta.systemType) && !isComponentAvailable(ctx, meta.systemPkg, meta.systemCls)) {
                    return RestoreResult.failure("原桌面动态壁纸组件未安装 (" + meta.systemPkg + ")，未作任何更改。");
                }
                if ("STATIC".equals(meta.lockType) && !isValidImageFile(new File(userDir, meta.lockFile))) {
                    return RestoreResult.failure("锁屏静态壁纸快照文件损坏，未作任何更改。");
                }
                if ("LIVE".equals(meta.lockType)) {
                    if (Build.VERSION.SDK_INT < 34) {
                        return RestoreResult.failure("当前系统版本（API < 34）缺少独立锁屏动态壁纸槽位校验接口，为防止不可验证的恢复已中止，未作任何更改。");
                    }
                    if (!isComponentAvailable(ctx, meta.lockPkg, meta.lockCls)) {
                        return RestoreResult.failure("原锁屏动态壁纸组件未安装 (" + meta.lockPkg + ")，未作任何更改。");
                    }
                }
                if (meta.isLegacyUnverifiedDefault()) {
                    HyperOsWallpaperHelper.LegacyDefaultSafetyResult safety = checkLegacyDefaultSafety(ctx, meta);
                    if (!safety.safeToProceedDefaultClear) {
                        Log.w(TAG, "stage=restore source=legacy_default committed=false code=" + safety.code);
                        return RestoreResult.failure(safety.userMessage);
                    }
                }
                if ("DEFAULT".equals(meta.systemType) && Build.VERSION.SDK_INT < 34) {
                    return RestoreResult.failure("当前系统版本（API < 34）缺少默认来源元数据校验能力，为防止不可验证的默认恢复已中止，未作任何更改。");
                }
                if (readWallpaperInfoXml(meta.userId) == null) {
                    return RestoreResult.failure("无法通过只读 ABX/XML 解析核验系统壁纸元数据（wallpaper_info.xml），为防止不可验证的恢复已中止，未作任何更改。");
                }
            }

            // 2. 进入实际写入阶段，必须在 finally 还原 sys.qihang.allow_reset
            String origAllowReset = Root.su("/system/bin/getprop sys.qihang.allow_reset", 2000).trim();
            Root.su("/system/bin/setprop sys.qihang.allow_reset 1", 2000);
            try {
                boolean sysOk = false;
                boolean lockOk = false;
                boolean oemExtraOk = true;
                StringBuilder failDetail = new StringBuilder();

                // 2.1 先恢复壁纸槽位并核验实际生效状态
                if (isOemHyperOs) {
                    int[] oemOutcome = restoreOemHyperOsAndVerify(ctx, meta, userDir, preparedOemSpec, failDetail);
                    sysOk = oemOutcome[0] == 1;
                    lockOk = oemOutcome[1] == 1;
                    oemExtraOk = oemOutcome[2] == 1;
                } else if ("SAME_AS_SYSTEM".equals(meta.lockType)) {
                    int merged = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                    if ("STATIC".equals(meta.systemType)) {
                        sysOk = restoreStaticSlotAndVerify(ctx, wm, meta.userId,
                                new File(userDir, meta.systemFile), merged, true);
                        lockOk = sysOk;
                        if (!sysOk) failDetail.append("合并静态壁纸写入或槽位校验失败");
                    } else if ("LIVE".equals(meta.systemType)) {
                        sysOk = restoreLiveSlotAndVerify(ctx, wm, meta.userId, meta.systemPkg, meta.systemCls, merged);
                        lockOk = sysOk;
                        if (!sysOk) failDetail.append("合并动态壁纸绑定或无独立锁屏校验失败");
                    } else if ("DEFAULT".equals(meta.systemType)) {
                        sysOk = restoreDefaultSlotAndVerify(ctx, wm, meta.userId, merged, true);
                        lockOk = sysOk;
                        if (!sysOk) failDetail.append("系统默认壁纸重置或真实默认元数据校验失败");
                    }
                } else {
                    // 独立槽：先恢复桌面(which=1)，再恢复锁屏(which=2)
                    if ("STATIC".equals(meta.systemType)) {
                        sysOk = restoreStaticSlotAndVerify(ctx, wm, meta.userId,
                                new File(userDir, meta.systemFile), WallpaperManager.FLAG_SYSTEM, false);
                    } else if ("LIVE".equals(meta.systemType)) {
                        sysOk = restoreLiveSlotAndVerify(ctx, wm, meta.userId,
                                meta.systemPkg, meta.systemCls, WallpaperManager.FLAG_SYSTEM);
                    } else if ("DEFAULT".equals(meta.systemType)) {
                        sysOk = restoreDefaultSlotAndVerify(ctx, wm, meta.userId,
                                WallpaperManager.FLAG_SYSTEM, false);
                    }
                    if (!sysOk) failDetail.append("桌面槽恢复失败; ");

                    if ("STATIC".equals(meta.lockType)) {
                        lockOk = restoreStaticSlotAndVerify(ctx, wm, meta.userId,
                                new File(userDir, meta.lockFile), WallpaperManager.FLAG_LOCK, false);
                    } else if ("LIVE".equals(meta.lockType)) {
                        lockOk = restoreLiveSlotAndVerify(ctx, wm, meta.userId,
                                meta.lockPkg, meta.lockCls, WallpaperManager.FLAG_LOCK);
                    }
                    if (!lockOk) failDetail.append("锁屏槽恢复失败; ");

                    // 独立锁屏恢复完成后再复核桌面槽未被连带改写或占回 HyperAlive
                    if (sysOk && lockOk && !verifySystemSlotStillIntact(ctx, wm, meta)) {
                        sysOk = false;
                        failDetail.append("恢复锁屏后桌面槽状态校验异常; ");
                    }
                }

                // 2.2 壁纸之后再恢复模块确实改动的原生息屏参数，并回读确认
                boolean ambientOk = restoreAndVerifySystemSetting(
                        ctx.getContentResolver(), meta.userId, "enter_ambient_mode",
                        meta.enterAmbientExisted, meta.enterAmbientValue);
                boolean posOk = restoreAndVerifySystemSetting(
                        ctx.getContentResolver(), meta.userId, "alive_wallpaper_position",
                        meta.alivePosExisted, meta.alivePosValue);
                boolean settingsOk = ambientOk && posOk && oemExtraOk;
                if (!(ambientOk && posOk)) {
                    failDetail.append("原生息屏系统参数恢复或回读校验失败");
                }

                // 3. 唯有桌面、锁屏、系统参数全部成功并校验通过才还原模块配置并结束此次恢复会话
                if (sysOk && lockOk && settingsOk) {
                    restoreModuleStateIfPresent(ctx, meta);
                    clearBackup(ctx);
                    Log.i(TAG, "stage=restore source=" + meta.systemType + "/" + meta.lockType + " committed=true");
                    return RestoreResult.success(
                            "已完整恢复应用前的桌面、锁屏壁纸，并还原/清除了模块写入的息屏系统参数（HyperOS 系统自有息屏主题未作改动）。");
                }
                if (sysOk || lockOk) {
                    Log.w(TAG, "stage=restore source=" + meta.systemType + "/" + meta.lockType + " committed=false code=partial");
                    return RestoreResult.partial(
                            "部分恢复："
                                    + "桌面=" + (sysOk ? "成功" : "失败")
                                    + "，锁屏=" + (lockOk ? "成功" : "失败")
                                    + "，息屏参数=" + (settingsOk ? "已还原" : "未完整还原")
                                    + "（" + failDetail.toString().trim() + "）。备份已保留，可重试。");
                }
                Log.w(TAG, "stage=restore source=" + meta.systemType + "/" + meta.lockType + " committed=false code=failed");
                return RestoreResult.failure(
                        "壁纸恢复未成功（" + failDetail.toString().trim() + "）。备份已保留，请核对当前壁纸状态后重试。");
            } finally {
                String restoreAllow = "1".equals(origAllowReset) ? "1" : "0";
                Root.su("/system/bin/setprop sys.qihang.allow_reset " + restoreAllow, 2000);
            }
        } catch (Throwable t) {
            Log.e(TAG, "restoreBackupSync failed", t);
            return RestoreResult.failure("恢复过程发生异常：" + t.getMessage() + "。备份已保留，请核对当前状态后重试。");
        } finally {
            unlockTransaction();
        }
    }

    /** 兼容旧签名的安全保守入口：无应用前基线时不删除新建备份。 */
    public static RollbackResult rollbackCreatedBackupIfUnapplied(Context ctx) {
        return rollbackCreatedBackupIfUnapplied(ctx, null);
    }

    /**
     * 仅在能够严格证明设备真实壁纸槽位、组件、wallpaper_info.xml 与系统息屏配置均未发生任何变化时
     * 才清理刚新建的备份；任何部分绑定、外部切换或查询失败，一律保留备份快照并如实返回！
     */
    public static RollbackResult rollbackCreatedBackupIfUnapplied(Context ctx, PreApplyBaseline baseline) {
        if (ctx == null) {
            return new RollbackResult(false, "Context 为空，已保留备份快照");
        }
        if (baseline == null || !baseline.valid) {
            Log.w(TAG, "stage=rollback_backup source=apply_failed committed=true code=no_baseline_keep_backup");
            return new RollbackResult(false, "缺少应用前状态基线，无法确认零写入，已保留备份快照");
        }
        lockTransaction();
        try {
            WallpaperManager wm = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) {
                Log.w(TAG, "stage=rollback_backup source=apply_failed committed=true code=no_wm_keep_backup");
                return new RollbackResult(false, "无法访问 WallpaperManager，已保留备份快照");
            }
            try {
                if (isOccupiedByHyperAliveStrict(ctx, wm)) {
                    Log.w(TAG, "stage=rollback_backup source=apply_failed committed=true code=occupied_keep_backup");
                    return new RollbackResult(false, "检测到壁纸已被 HyperAlive 部分绑定或占用，已保留备份快照");
                }
                int userId = currentUserId();
                int sysIdNow = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                int lockIdNow = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                WallpaperInfo sysInfoNow = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                WallpaperInfo lockInfoNow = (lockIdNow != -1 && Build.VERSION.SDK_INT >= 34)
                        ? querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK) : null;
                WallpaperXmlState xmlNow = readWallpaperInfoXml(userId);

                ContentResolver cr = ctx.getContentResolver();
                SettingRecord ambientNow = readSetting(cr, userId, "system", "enter_ambient_mode");
                SettingRecord posNow = readSetting(cr, userId, "system", "alive_wallpaper_position");

                RollbackResult check = evaluateBaselineUnchanged(
                        baseline, userId, false,
                        sysIdNow, lockIdNow,
                        sysInfoNow != null ? sysInfoNow.getPackageName() : "",
                        sysInfoNow != null ? sysInfoNow.getServiceName() : "",
                        lockInfoNow != null ? lockInfoNow.getPackageName() : "",
                        lockInfoNow != null ? lockInfoNow.getServiceName() : "",
                        xmlNow, ambientNow, posNow);
                if (!check.deleted) {
                    Log.w(TAG, "stage=rollback_backup source=apply_failed committed=true code=state_changed_keep_backup");
                    return check;
                }
            } catch (Throwable t) {
                Log.w(TAG, "stage=rollback_backup source=apply_failed committed=true code=query_err_keep_backup");
                return new RollbackResult(false, "核查应用后状态异常（" + t.getMessage() + "），已保留备份快照");
            }

            clearBackup(ctx);
            Log.i(TAG, "stage=rollback_backup source=apply_failed committed=false code=cleaned_verified_unchanged");
            return new RollbackResult(true, "已核实壁纸槽位与系统参数均未改动，已清理本次新建备份");
        } finally {
            unlockTransaction();
        }
    }

    /** 纯状态机判定：仅当所有壁纸 ID、组件、XML 与系统设置与基线完全一致时允许删除新建备份。 */
    static RollbackResult evaluateBaselineUnchanged(
            PreApplyBaseline baseline,
            int userId,
            boolean occupiedByHyperAlive,
            int sysIdNow,
            int lockIdNow,
            String sysInfoPkgNow,
            String sysInfoClsNow,
            String lockInfoPkgNow,
            String lockInfoClsNow,
            WallpaperXmlState xmlNow,
            SettingRecord ambientNow,
            SettingRecord posNow) {
        if (baseline == null || !baseline.valid || baseline.userId != userId) {
            return new RollbackResult(false, "无有效基线或用户切换");
        }
        if (occupiedByHyperAlive) {
            return new RollbackResult(false, "当前壁纸已被 HyperAlive 占用");
        }
        if (sysIdNow != baseline.sysId || lockIdNow != baseline.lockId) {
            return new RollbackResult(false, "壁纸槽位 ID 已发生变化");
        }
        if (!sameComponentName(baseline.sysInfoPkg, baseline.sysInfoCls, sysInfoPkgNow, sysInfoClsNow)) {
            return new RollbackResult(false, "桌面壁纸组件已发生变化");
        }
        if (baseline.hasKwp
                && !sameComponentName(baseline.lockInfoPkg, baseline.lockInfoCls, lockInfoPkgNow, lockInfoClsNow)) {
            return new RollbackResult(false, "独立锁屏组件已发生变化");
        }
        if (xmlNow == null || !xmlNow.hasWp || xmlNow.wpId != baseline.sysId
                || xmlNow.hasKwp != baseline.hasKwp
                || (baseline.hasKwp && xmlNow.kwpId != baseline.lockId)
                || !baseline.wpBindSource.equals(xmlNow.wpBindSource)
                || !sameComponentName(baseline.sysXmlPkg, baseline.sysXmlCls, xmlNow.wpPkg, xmlNow.wpCls)
                || (baseline.hasKwp && !sameComponentName(baseline.lockXmlPkg, baseline.lockXmlCls, xmlNow.kwpPkg, xmlNow.kwpCls))) {
            return new RollbackResult(false, "wallpaper_info.xml 元数据或组件已发生变化");
        }
        if (ambientNow == null || !ambientNow.ok
                || ambientNow.existed != baseline.enterAmbientExisted
                || !safeEq(ambientNow.value, baseline.enterAmbientValue)) {
            return new RollbackResult(false, "enter_ambient_mode 系统设置已发生变化");
        }
        if (posNow == null || !posNow.ok
                || posNow.existed != baseline.alivePosExisted
                || !safeEq(posNow.value, baseline.alivePosValue)) {
            return new RollbackResult(false, "alive_wallpaper_position 系统设置已发生变化");
        }
        return new RollbackResult(true, "已核实所有真实壁纸与系统设置均未改变");
    }

    /**
     * 在 SetWall 返回后真实校验 WMS 槽位与只读 wallpaper_info.xml 是否已切至目标组件与预期拓扑。
     * 绝不仅靠命令行文本断言应用成功。
     */
    public static SlotVerifyResult verifyAppliedSlots(
            Context ctx, boolean dual, String homePkg, String homeCls, String lockPkg, String lockCls) {
        if (ctx == null) {
            return new SlotVerifyResult(false, "Context 为空");
        }
        WallpaperManager wm = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
        if (wm == null) {
            return new SlotVerifyResult(false, "无法访问 WallpaperManager");
        }
        int userId = currentUserId();
        SlotVerifyResult last = new SlotVerifyResult(false, "未完成校验");
        for (int i = 0; i < 6; i++) {
            try {
                if (i > 0) Thread.sleep(150);
                int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                WallpaperInfo sysInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                WallpaperInfo lockInfo = (Build.VERSION.SDK_INT >= 34)
                        ? querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK) : null;
                WallpaperXmlState xml = readWallpaperInfoXml(userId);

                last = evaluateAppliedSlots(
                        dual, homePkg, homeCls, lockPkg, lockCls,
                        Build.VERSION.SDK_INT,
                        sysId, lockId,
                        sysInfo != null ? sysInfo.getPackageName() : "",
                        sysInfo != null ? sysInfo.getServiceName() : "",
                        lockInfo != null ? lockInfo.getPackageName() : "",
                        lockInfo != null ? lockInfo.getServiceName() : "",
                        xml);
                if (last.ok) {
                    return last;
                }
            } catch (Throwable t) {
                last = new SlotVerifyResult(false, "槽位校验异常：" + t.getMessage());
            }
        }
        return last;
    }

    /** 纯核验逻辑：校验目标槽位 ID、组件名与 wallpaper_info.xml 拓扑是否严格匹配预期目标。 */
    static SlotVerifyResult evaluateAppliedSlots(
            boolean dual,
            String homePkg,
            String homeCls,
            String lockPkg,
            String lockCls,
            int sdkInt,
            int sysId,
            int lockId,
            String sysInfoPkg,
            String sysInfoCls,
            String lockInfoPkg,
            String lockInfoCls,
            WallpaperXmlState xml) {
        if (sysId <= 0) {
            return new SlotVerifyResult(false, "桌面壁纸 ID 无效 (" + sysId + ")");
        }
        if (!sameComponentName(homePkg, homeCls, sysInfoPkg, sysInfoCls)) {
            return new SlotVerifyResult(false, "桌面实际组件与目标不符 (" + sysInfoPkg + "/" + sysInfoCls + ")");
        }
        if (xml == null || !xml.hasWp || xml.wpId != sysId) {
            return new SlotVerifyResult(false, "wallpaper_info.xml 桌面记录缺失或 ID 不匹配");
        }
        if (!sameComponentName(homePkg, homeCls, xml.wpPkg, xml.wpCls)) {
            return new SlotVerifyResult(false, "wallpaper_info.xml 桌面组件与目标不符");
        }
        if (!dual) {
            if (lockId != -1 || xml.hasKwp) {
                return new SlotVerifyResult(false, "三态合并应用后仍残留独立锁屏槽位 (lockId=" + lockId + ")");
            }
            if (sdkInt >= 34 && lockInfoPkg != null && !lockInfoPkg.isEmpty()
                    && !sameComponentName(homePkg, homeCls, lockInfoPkg, lockInfoCls)) {
                return new SlotVerifyResult(false, "三态锁屏回退组件与目标不符 (" + lockInfoPkg + "/" + lockInfoCls + ")");
            }
            return new SlotVerifyResult(true, "已核实合并槽位及元数据与目标动态壁纸完全一致");
        }
        if (lockId <= 0 || !xml.hasKwp || xml.kwpId != lockId) {
            return new SlotVerifyResult(false, "双槽应用后独立锁屏槽位未生效或 ID 不匹配 (lockId=" + lockId + ")");
        }
        if (sdkInt >= 34 && !sameComponentName(lockPkg, lockCls, lockInfoPkg, lockInfoCls)) {
            return new SlotVerifyResult(false, "锁屏实际组件与目标不符 (" + lockInfoPkg + "/" + lockInfoCls + ")");
        }
        if (!sameComponentName(lockPkg, lockCls, xml.kwpPkg, xml.kwpCls)) {
            return new SlotVerifyResult(false, "wallpaper_info.xml 锁屏组件与目标不符");
        }
        return new SlotVerifyResult(true, "已核实桌面与锁屏双槽组件及元数据均与目标动态壁纸完全一致");
    }

    private static boolean safeEq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static boolean restoreStaticSlotAndVerify(
            Context ctx, WallpaperManager wm, int userId, File file, int which, boolean expectMerged) {
        int newId;
        try (FileInputStream fis = new FileInputStream(file)) {
            // allowBackup=false，避免触发系统云备份流程
            newId = wm.setStream(fis, null, false, which);
        } catch (Throwable t) {
            Log.e(TAG, "setStream failed for which=" + which, t);
            return false;
        }
        if (newId <= 0) {
            return false;
        }

        // 轮询验证壁纸实际落盘到目标槽位、元数据刷新且真实槽文件存在
        for (int i = 0; i < 15; i++) {
            try {
                if (i > 0) Thread.sleep(200);
                if (expectMerged) {
                    WallpaperInfo sysInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                    if (isHyperAlive(ctx, sysInfo)) continue;
                    if (Build.VERSION.SDK_INT >= 34
                            && isHyperAlive(ctx, querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK))) {
                        continue;
                    }
                    int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                    int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                    if (sysId != newId || lockId != -1) continue;

                    WallpaperXmlState xml = readWallpaperInfoXml(userId);
                    if (xml == null || !xml.hasWp || xml.hasKwp || xml.wpId != newId
                            || isHyperAliveName(ctx, xml.wpPkg, xml.wpCls)) {
                        continue;
                    }
                    if (!confirmAnyFileExists(
                            "/data/system/users/" + userId + "/wallpaper",
                            "/data/system/users/" + userId + "/wallpaper_orig")) {
                        continue;
                    }
                    return true;
                } else {
                    int actualId = wm.getWallpaperId(which);
                    if (actualId != newId) continue;

                    if (which == WallpaperManager.FLAG_SYSTEM) {
                        if (isHyperAlive(ctx, querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM))) {
                            continue;
                        }
                        WallpaperXmlState xml = readWallpaperInfoXml(userId);
                        if (xml == null || !xml.hasWp || xml.wpId != newId
                                || isHyperAliveName(ctx, xml.wpPkg, xml.wpCls)) {
                            continue;
                        }
                        if (!confirmAnyFileExists(
                                "/data/system/users/" + userId + "/wallpaper",
                                "/data/system/users/" + userId + "/wallpaper_orig")) {
                            continue;
                        }
                        return true;
                    } else if (which == WallpaperManager.FLAG_LOCK) {
                        if (Build.VERSION.SDK_INT >= 34
                                && isHyperAlive(ctx, querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK))) {
                            continue;
                        }
                        WallpaperXmlState xml = readWallpaperInfoXml(userId);
                        if (xml == null || !xml.hasKwp || xml.kwpId != newId
                                || isHyperAliveName(ctx, xml.kwpPkg, xml.kwpCls)) {
                            continue;
                        }
                        if (!confirmAnyFileExists(
                                "/data/system/users/" + userId + "/wallpaper_lock",
                                "/data/system/users/" + userId + "/wallpaper_lock_orig")) {
                            continue;
                        }
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean restoreLiveSlotAndVerify(
            Context ctx, WallpaperManager wm, int userId, String pkg, String cls, int which) {
        String out = Root.setWallpaperComponentRaw(ctx, pkg, cls, which);
        if (!Root.isSetWallSuccess(out)) {
            Log.w(TAG, "SetWall raw return invalid: " + out);
            return false;
        }

        // 不能仅凭返回字符串判断成功，必须由 WallpaperManager 与只读 wallpaper_info.xml 双重查实目标槽位与组件
        for (int i = 0; i < 15; i++) {
            try {
                if (i > 0) Thread.sleep(200);
                if (which == (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK)) {
                    int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                    int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                    if (sysId <= 0 || lockId != -1) continue;

                    WallpaperInfo sys = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                    if (!matchesComponent(sys, pkg, cls)) continue;

                    // Android 34+ 无独立锁屏时 getWallpaperInfo(FLAG_LOCK) 可能回退返回桌面组件：
                    // 若非空则必须与目标一致，且必须结合 lockId == -1 与 !xml.hasKwp 确认无独立锁屏槽。
                    if (Build.VERSION.SDK_INT >= 34) {
                        WallpaperInfo fallbackLock = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
                        if (fallbackLock != null && !matchesComponent(fallbackLock, pkg, cls)) continue;
                    }

                    WallpaperXmlState xml = readWallpaperInfoXml(userId);
                    if (xml == null || !xml.hasWp || xml.hasKwp || xml.wpId != sysId
                            || !sameComponentName(pkg, cls, xml.wpPkg, xml.wpCls)) {
                        continue;
                    }
                    return true;
                } else if (which == WallpaperManager.FLAG_SYSTEM) {
                    int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                    if (sysId <= 0) continue;

                    WallpaperInfo sys = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                    if (!matchesComponent(sys, pkg, cls)) continue;

                    WallpaperXmlState xml = readWallpaperInfoXml(userId);
                    if (xml == null || !xml.hasWp || xml.wpId != sysId
                            || !sameComponentName(pkg, cls, xml.wpPkg, xml.wpCls)) {
                        continue;
                    }
                    return true;
                } else if (which == WallpaperManager.FLAG_LOCK) {
                    if (Build.VERSION.SDK_INT < 34) {
                        return false;
                    }
                    int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                    if (lockId <= 0) continue;

                    WallpaperInfo lock = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
                    if (!matchesComponent(lock, pkg, cls)) continue;

                    WallpaperXmlState xml = readWallpaperInfoXml(userId);
                    if (xml == null || !xml.hasKwp || xml.kwpId != lockId
                            || !sameComponentName(pkg, cls, xml.kwpPkg, xml.kwpCls)) {
                        continue;
                    }
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static HyperOsWallpaperHelper.LegacyDefaultSafetyResult checkLegacyDefaultSafety(
            Context ctx, BackupMetadata meta) {
        return checkLegacyDefaultWithSources(
                meta,
                createRootGateway(ctx.getContentResolver(), meta.userId),
                Root.probeHyperOsWallpaper(ctx, meta.userId));
    }

    static HyperOsWallpaperHelper.LegacyDefaultSafetyResult checkLegacyDefaultWithSources(
            BackupMetadata meta, HyperOsWallpaperHelper.Gateway gw, String bridgeRaw) {
        if (meta == null || !meta.isLegacyUnverifiedDefault()) {
            return new HyperOsWallpaperHelper.LegacyDefaultSafetyResult(true, "not_legacy_default", "");
        }
        HyperOsWallpaperHelper.OemProbeResult fsOemRes =
                HyperOsWallpaperHelper.probeAndValidateOemState(meta.userId, gw);
        HyperOsWallpaperHelper.BridgeProbeSummary bridgeSummary =
                HyperOsWallpaperHelper.parseBridgeProbeOutput(bridgeRaw);
        return HyperOsWallpaperHelper.evaluateLegacyDefaultConflict(fsOemRes, bridgeSummary);
    }

    /** 恢复系统默认壁纸并在 clear 后轮询确认新 ID、静态引擎、SET_LIVE_TO_CLEAR 来源、无文件与无独立锁屏（合并时）。 */
    private static boolean restoreDefaultSlotAndVerify(
            Context ctx, WallpaperManager wm, int userId, int which, boolean expectMerged) {
        boolean invoked = false;
        try {
            wm.clear(which);
            invoked = true;
        } catch (Throwable t) {
            Log.w(TAG, "wm.clear(" + which + ") failed, fallback SetWall clear", t);
        }
        if (!invoked) {
            String out = Root.appProcess(ctx, "io.github.askt_clot.hyperalive.root.SetWall",
                    "clear", ctx.getPackageName(), String.valueOf(which));
            if (!Root.isSetWallSuccess(out)) {
                return false;
            }
        }

        for (int i = 0; i < 25; i++) {
            try {
                if (i > 0) Thread.sleep(200);
                int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                WallpaperInfo sysInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
                if (sysId <= 0 || isHyperAlive(ctx, sysInfo)
                        || (sysInfo != null && !isMiuiImageWallpaper(sysInfo))) {
                    continue;
                }
                int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
                WallpaperInfo fallbackLock = null;
                if (expectMerged) {
                    if (lockId != -1) continue;
                    if (Build.VERSION.SDK_INT >= 34) {
                        fallbackLock = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_LOCK);
                        if (isHyperAlive(ctx, fallbackLock)
                                || (fallbackLock != null && !isMiuiImageWallpaper(fallbackLock))
                                || (fallbackLock != null && sysInfo != null && !sameComponent(fallbackLock, sysInfo))) {
                            continue;
                        }
                    }
                    if (!confirmAllFilesAbsent(
                            "/data/system/users/" + userId + "/wallpaper",
                            "/data/system/users/" + userId + "/wallpaper_orig",
                            "/data/system/users/" + userId + "/wallpaper_lock",
                            "/data/system/users/" + userId + "/wallpaper_lock_orig")) {
                        continue;
                    }
                } else {
                    if (!confirmAllFilesAbsent(
                            "/data/system/users/" + userId + "/wallpaper",
                            "/data/system/users/" + userId + "/wallpaper_orig")) {
                        continue;
                    }
                }

                WallpaperXmlState xml = readWallpaperInfoXml(userId);
                if (isConfirmedDefaultState(sysId, lockId, sysInfo, xml, expectMerged)
                        && !isHyperAliveName(ctx, xml.wpPkg, xml.wpCls)
                        && (fallbackLock == null || sameComponentName(fallbackLock, xml.wpPkg, xml.wpCls))) {
                    boolean isMiuiPkg = "com.miui.miwallpaper".equals(xml.wpPkg);
                    if (isMiuiPkg) {
                        HyperOsWallpaperHelper.OemProbeResult oemCheck =
                                HyperOsWallpaperHelper.probeAndValidateOemState(
                                        userId, createRootGateway(ctx.getContentResolver(), userId));
                        if (oemCheck.mode != HyperOsWallpaperHelper.Mode.CONFIRMED_DEFAULT) {
                            continue;
                        }
                    }
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 独立双槽恢复完成后复核桌面槽位未被连带破坏。 */
    private static boolean verifySystemSlotStillIntact(Context ctx, WallpaperManager wm, BackupMetadata meta) {
        try {
            int sysId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            int lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
            WallpaperInfo sysInfo = querySlotWallpaperInfo(wm, WallpaperManager.FLAG_SYSTEM);
            if (isHyperAlive(ctx, sysInfo)) return false;

            WallpaperXmlState xml = readWallpaperInfoXml(meta.userId);
            if (xml == null || !xml.hasWp || xml.wpId != sysId
                    || isHyperAliveName(ctx, xml.wpPkg, xml.wpCls)) {
                return false;
            }
            if ("LIVE".equals(meta.systemType)) {
                return matchesComponent(sysInfo, meta.systemPkg, meta.systemCls)
                        && sameComponentName(meta.systemPkg, meta.systemCls, xml.wpPkg, xml.wpCls);
            }
            if ("DEFAULT".equals(meta.systemType)) {
                return isConfirmedDefaultState(sysId, lockId, sysInfo, xml, false)
                        && confirmAllFilesAbsent(
                                "/data/system/users/" + meta.userId + "/wallpaper",
                                "/data/system/users/" + meta.userId + "/wallpaper_orig");
            }
            if ("STATIC".equals(meta.systemType)) {
                return confirmAnyFileExists(
                        "/data/system/users/" + meta.userId + "/wallpaper",
                        "/data/system/users/" + meta.userId + "/wallpaper_orig");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    static final class SettingRecord {
        final boolean ok;
        final boolean existed;
        final String value;

        SettingRecord(boolean ok, boolean existed, String value) {
            this.ok = ok;
            this.existed = existed;
            this.value = value;
        }
    }

    /**
     * 精确读取 system / secure setting（固定路径与 --user）：
     * 1. 进程内 ContentResolver 读取到非 null 值时直接返回（保留多行文本、前后空白、空串 "" 与字面量 "null"）；
     * 2. 回退 Root 时通过 suRaw 定界符协议读取，严禁使用 Root.su(...).trim() 破坏首尾空白或多行 JSON；
     * 3. 严格区分键不存在 (existed=false)、空字符串 "" (existed=true, value="") 与字面量 "null" (existed=true, value="null")。
     */
    private static SettingRecord readSetting(ContentResolver cr, int userId, String table, String key) {
        if ((!"system".equals(table) && !"secure".equals(table))
                || key == null || !key.matches("[A-Za-z0-9_.-]+")) {
            return new SettingRecord(false, false, "");
        }
        String inProc = null;
        boolean inProcRead = false;
        if (cr != null && userId == currentUserId()) {
            try {
                if ("system".equals(table)) {
                    inProc = Settings.System.getString(cr, key);
                    inProcRead = true;
                } else if ("secure".equals(table)) {
                    inProc = Settings.Secure.getString(cr, key);
                    inProcRead = true;
                }
            } catch (Throwable ignored) {}
        }
        if (inProcRead && inProc != null) {
            return new SettingRecord(true, true, inProc);
        }

        String qKey = Root.shellQuote(key);
        String qNullEntry = Root.shellQuote(key + "=null");
        String cmd = "printf '__QH_BEGIN__\\n'; "
                + "/system/bin/settings get --user " + userId + " " + table + " " + qKey + "; "
                + "printf '__QH_END__:%d\\n' $?; "
                + "LIST_OUT=$(/system/bin/settings list --user " + userId + " " + table + "); "
                + "LIST_RC=$?; "
                + "printf '__QH_LIST_RC__:%d\\n' $LIST_RC; "
                + "if [ $LIST_RC -eq 0 ]; then "
                + "printf '%s\\n' \"$LIST_OUT\" | /system/bin/grep -F -x " + qNullEntry
                + " >/dev/null 2>&1 && printf '__QH_LITERAL_NULL__:1\\n' || printf '__QH_LITERAL_NULL__:0\\n'; "
                + "fi";
        String rawOut = Root.suRaw(4000, "-c", cmd);
        return parseRawSettingOutput(rawOut);
    }

    /**
     * 按固定分隔边界解析 settings get + settings list 校核输出：
     * - 严禁对原始输出做全局 CRLF 替换或 trim，100% 原样保留值内的 CRLF (\r\n)、空白字符与结尾换行；
     * - 对所有值均严格校验完整协议头、退出码、trailer 完整性及唯一性，拒绝截断、重复或冲突标志；
     * - 严格区分键不存在 (existed=false)、空字符串 "" (existed=true, value="") 与字面量 "null" (existed=true, value="null")。
     */
    static SettingRecord parseRawSettingOutput(String rawOut) {
        if (rawOut == null || rawOut.isEmpty() || rawOut.startsWith("suRaw") || rawOut.startsWith("ERR ")) {
            return new SettingRecord(false, false, "");
        }
        final String beginMarker = "__QH_BEGIN__\n";
        if (!rawOut.startsWith(beginMarker)) {
            return new SettingRecord(false, false, "");
        }
        if (rawOut.indexOf("__QH_BEGIN__", beginMarker.length()) >= 0) {
            return new SettingRecord(false, false, "");
        }
        final String endPrefix = "\n__QH_END__:";
        final String endMarker = "\n__QH_END__:0\n";
        int firstEndPrefix = rawOut.indexOf(endPrefix, beginMarker.length());
        int endIdx = rawOut.indexOf(endMarker, beginMarker.length());
        if (firstEndPrefix < beginMarker.length() || firstEndPrefix != endIdx) {
            return new SettingRecord(false, false, "");
        }
        if (rawOut.indexOf(endPrefix, endIdx + 1) >= 0) {
            return new SettingRecord(false, false, "");
        }
        String val = rawOut.substring(beginMarker.length(), endIdx);
        String trailer = rawOut.substring(endIdx + endMarker.length());
        if (trailer.isEmpty() || !trailer.endsWith("\n")) {
            return new SettingRecord(false, false, "");
        }

        String[] trailerLines = trailer.split("\n", -1);
        int listRcCount = 0;
        int literalNullCount = 0;
        Boolean literalNull = null;
        for (int i = 0; i < trailerLines.length; i++) {
            String line = trailerLines[i];
            if (i == trailerLines.length - 1 && line.isEmpty()) {
                continue;
            }
            if ("__QH_LIST_RC__:0".equals(line)) {
                if (listRcCount != 0 || literalNullCount != 0) {
                    return new SettingRecord(false, false, "");
                }
                listRcCount++;
            } else if ("__QH_LITERAL_NULL__:1".equals(line)) {
                if (listRcCount != 1 || literalNullCount != 0) {
                    return new SettingRecord(false, false, "");
                }
                literalNullCount++;
                literalNull = Boolean.TRUE;
            } else if ("__QH_LITERAL_NULL__:0".equals(line)) {
                if (listRcCount != 1 || literalNullCount != 0) {
                    return new SettingRecord(false, false, "");
                }
                literalNullCount++;
                literalNull = Boolean.FALSE;
            } else {
                return new SettingRecord(false, false, "");
            }
        }
        if (listRcCount != 1 || literalNullCount != 1 || literalNull == null) {
            return new SettingRecord(false, false, "");
        }
        if (!"null".equals(val)) {
            if (literalNull.booleanValue()) {
                return new SettingRecord(false, false, "");
            }
            return new SettingRecord(true, true, val);
        }
        if (literalNull.booleanValue()) {
            return new SettingRecord(true, true, "null");
        }
        return new SettingRecord(true, false, "");
    }

    /** 恢复并回读确认单个系统设置项（包含不存在与空串）。 */
    private static boolean restoreAndVerifySystemSetting(
            ContentResolver cr, int userId, String key, boolean origExisted, String origVal) {
        return restoreAndVerifySetting(cr, userId, "system", key, origExisted, origVal);
    }

    /** 恢复并回读确认 system / secure 表的单个设置项（包含不存在与空串）。 */
    static boolean restoreAndVerifySetting(
            ContentResolver cr, int userId, String table, String key, boolean origExisted, String origVal) {
        if ((!"system".equals(table) && !"secure".equals(table))
                || key == null || !key.matches("[A-Za-z0-9_]+")) {
            return false;
        }
        String cmd;
        if (origExisted) {
            cmd = "/system/bin/settings put --user " + userId + " " + table + " " + key + " "
                    + Root.shellQuote(origVal != null ? origVal : "") + "; echo __EXIT=$?";
        } else {
            cmd = "/system/bin/settings delete --user " + userId + " " + table + " " + key + "; echo __EXIT=$?";
        }
        String out = Root.su(cmd, 5000);
        if (out == null || !out.contains("__EXIT=0")) {
            return false;
        }

        SettingRecord after = readSetting(cr, userId, table, key);
        if (!after.ok) return false;
        if (!origExisted) {
            return !after.existed;
        }
        String expected = origVal != null ? origVal : "";
        return after.existed && expected.equals(after.value);
    }

    static JSONObject prepareOemRestoreSpec(BackupMetadata meta, File userDir) {
        return prepareOemRestoreSpec(meta, userDir, null);
    }

    private static JSONObject prepareOemRestoreSpec(BackupMetadata meta, File userDir, StringBuilder failReason) {
        try {
            if (meta == null || meta.oemJson == null || userDir == null) return null;
            File oemDir = new File(userDir, OEM_DIR_NAME);
            if (!oemDir.isDirectory()) return null;

            String homeXmlStr = readFileToString(new File(oemDir, "data_home.xml"));
            String lockXmlStr = readFileToString(new File(oemDir, "data_lock.xml"));
            HyperOsWallpaperHelper.WallpaperDataXml homeXml = HyperOsWallpaperHelper.parseWallpaperDataXml(homeXmlStr);
            HyperOsWallpaperHelper.WallpaperDataXml lockXml = HyperOsWallpaperHelper.parseWallpaperDataXml(lockXmlStr);
            if (homeXml == null || lockXml == null) return null;

            JSONArray filesArr = meta.oemJson.optJSONArray("files");
            if (filesArr == null) return null;
            String homeImgPath = "";
            String homeSha = "";
            String lockImgPath = "";
            String lockSha = "";
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject fObj = filesArr.optJSONObject(i);
                if (fObj == null) return null;
                String relName = fObj.optString("rel_name", "");
                String srcPath = fObj.optString("source_path", "");
                String sha = fObj.optString("sha256", "");
                if (relName.startsWith("tpl_")) {
                    if (!HyperOsWallpaperHelper.isWhitelistTemplatePath(srcPath, meta.userId)) {
                        return null;
                    }
                } else if ("home_wallpaper.jpg".equals(relName)) {
                    homeImgPath = new File(oemDir, relName).getAbsolutePath();
                    homeSha = sha;
                } else if ("lock_wallpaper.jpg".equals(relName)) {
                    lockImgPath = new File(oemDir, relName).getAbsolutePath();
                    lockSha = sha;
                }
            }

            if ("image".equals(homeXml.type) && (homeImgPath.isEmpty() || homeSha.isEmpty())) return null;
            if ("image".equals(lockXml.type) && (lockImgPath.isEmpty() || lockSha.isEmpty())) return null;

            JSONObject settingsObj = meta.oemJson.optJSONObject("settings");
            JSONObject secureObj = settingsObj != null ? settingsObj.optJSONObject("secure") : null;
            String ch1 = readBackedSettingVal(secureObj, "wallpaper_changed_1");
            String ch2 = readBackedSettingVal(secureObj, "wallpaper_changed_2");
            boolean allowSplitRestore = meta.oemJson.optBoolean("allow_split_restore", false);
            String expectedWmsLockComp = meta.oemJson.optString("wms_lock_comp", "");
            if (allowSplitRestore && !HyperOsWallpaperHelper.isCleanActiveSplitLockComp(expectedWmsLockComp)) {
                return null;
            }

            JSONObject spec = HyperOsWallpaperHelper.buildBridgeRestoreSpecJson(
                    meta.userId, homeXml, lockXml,
                    homeImgPath, homeSha,
                    lockImgPath, lockSha,
                    meta.oemClockTemplateId,
                    ch1, ch2,
                    allowSplitRestore, expectedWmsLockComp);
            if (HyperOsWallpaperHelper.isSplitRestoreUnsafeSameLayer(spec)) {
                if (failReason != null) {
                    failReason.append("检测到分槽 OEM 快照桌面与锁屏同图且特效/模糊及受信任来源包将触发系统 sameLayer 锁屏黑屏/合并风险，已中止恢复并完整保留现有快照，未作任何更改。");
                }
                return null;
            }
            return spec;
        } catch (Throwable t) {
            Log.w(TAG, "prepareOemRestoreSpec failed", t);
            return null;
        }
    }

    private static String readBackedSettingVal(JSONObject tableObj, String key) {
        if (tableObj == null || key == null) return "";
        JSONObject item = tableObj.optJSONObject(key);
        if (item == null || !item.optBoolean("existed", false)) return "";
        return item.optString("val", "");
    }

    /** 返回 int[]{sysOk(0/1), lockOk(0/1), oemSettingsAndTemplatesOk(0/1)} */
    private static int[] restoreOemHyperOsAndVerify(
            Context ctx, BackupMetadata meta, File userDir, JSONObject specJson, StringBuilder failDetail) {
        File oemDir = new File(userDir, OEM_DIR_NAME);
        File specTmp = null;
        try {
            // 1. 先恢复 AOD 模板文件与锁定编辑设置，确保系统服务在切换壁纸回调期间能读到原始模板
            boolean preTplOk = restoreAndVerifyOemTemplates(oemDir, meta.userId, meta.oemJson);
            if (!preTplOk) {
                failDetail.append("AOD 模板文件预还原校验未完全通过; ");
            }
            JSONObject settingsObj = meta.oemJson.optJSONObject("settings");
            restoreOemSettingsTable(ctx.getContentResolver(), meta.userId, "secure",
                    settingsObj != null ? settingsObj.optJSONObject("secure") : null, false);
            restoreOemSettingsTable(ctx.getContentResolver(), meta.userId, "system",
                    settingsObj != null ? settingsObj.optJSONObject("system") : null, false);

            // 2. 通过 HyperOsWallpaperBridge -restore 调用 MiWallPaperControllerService AIDL 恢复壁纸与收敛校验
            specTmp = new File(ctx.getFilesDir(), ".oem_restore_spec_" + meta.userId + "_" + System.nanoTime() + ".json");
            try (FileOutputStream fos = new FileOutputStream(specTmp)) {
                fos.write(specJson.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }

            String bridgeOut = Root.restoreHyperOsWallpaper(ctx, meta.userId, specTmp.getAbsolutePath());
            HyperOsWallpaperHelper.BridgeRestoreResult bridgeRes =
                    HyperOsWallpaperHelper.parseBridgeRestoreOutput(bridgeOut);
            if (!bridgeRes.ok || !bridgeRes.converged) {
                failDetail.append("HyperOS AIDL 恢复失败(").append(bridgeRes.error);
                if (!bridgeRes.detail.isEmpty()) {
                    failDetail.append(": ").append(bridgeRes.detail);
                }
                failDetail.append("); ");
                return new int[]{0, 0, preTplOk ? 1 : 0};
            }

            // 3. 墙纸切换收敛后再次强制对齐并回读校验 OEM system/secure 设置与模板文件 SHA-256
            boolean secOk = restoreOemSettingsTable(ctx.getContentResolver(), meta.userId, "secure",
                    settingsObj != null ? settingsObj.optJSONObject("secure") : null, true);
            boolean sysTblOk = restoreOemSettingsTable(ctx.getContentResolver(), meta.userId, "system",
                    settingsObj != null ? settingsObj.optJSONObject("system") : null, true);
            boolean postTplOk = restoreAndVerifyOemTemplates(oemDir, meta.userId, meta.oemJson);
            if (!secOk || !sysTblOk) {
                failDetail.append("HyperOS 锁屏/息屏设置回读校验未全通过; ");
            }
            if (!postTplOk) {
                failDetail.append("HyperOS 锁屏/息屏模板文件终验未通过; ");
            }
            return new int[]{1, 1, (secOk && sysTblOk && postTplOk) ? 1 : 0};
        } catch (Throwable t) {
            Log.w(TAG, "restoreOemHyperOsAndVerify failed", t);
            failDetail.append("HyperOS OEM 恢复异常: ").append(t.getMessage()).append("; ");
            return new int[]{0, 0, 0};
        } finally {
            deleteQuietly(specTmp);
        }
    }

    private static boolean restoreOemSettingsTable(
            ContentResolver cr, int userId, String table, JSONObject tableObj, boolean verifyAll) {
        if (tableObj == null) return false;
        boolean allOk = true;
        java.util.Iterator<String> keys = tableObj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = tableObj.optJSONObject(key);
            if (item == null) {
                allOk = false;
                continue;
            }
            boolean existed = item.optBoolean("existed", false);
            String val = item.optString("val", "");
            boolean ok = restoreAndVerifySetting(cr, userId, table, key, existed, val);
            if (verifyAll && !ok) {
                allOk = false;
            }
        }
        return allOk;
    }

    private static boolean restoreAndVerifyOemTemplates(File oemDir, int userId, JSONObject oemJson) {
        if (oemDir == null || oemJson == null) return false;
        JSONArray filesArr = oemJson.optJSONArray("files");
        if (filesArr == null) return false;
        boolean allOk = true;
        for (int i = 0; i < filesArr.length(); i++) {
            JSONObject fObj = filesArr.optJSONObject(i);
            if (fObj == null) {
                allOk = false;
                continue;
            }
            String relName = fObj.optString("rel_name", "");
            if (!relName.startsWith("tpl_")) continue;
            String dstPath = fObj.optString("source_path", "");
            String expectedSha = fObj.optString("sha256", "");
            if (!HyperOsWallpaperHelper.isWhitelistTemplatePath(dstPath, userId) || expectedSha.isEmpty()) {
                return false;
            }
            File srcFile = new File(oemDir, relName);
            if (!srcFile.isFile() || !expectedSha.equalsIgnoreCase(HyperOsWallpaperHelper.sha256Hex(srcFile))) {
                return false;
            }
            String dstParent = new File(dstPath).getParent();
            String qParent = Root.shellQuote(dstParent);
            String qDst = Root.shellQuote(dstPath);
            String cmd = "/system/bin/mkdir -p " + qParent
                    + " && /system/bin/cat " + Root.shellQuote(srcFile.getAbsolutePath())
                    + " > " + qDst
                    + " && /system/bin/chmod 0644 " + qDst
                    + " && (P_UG=$(/system/bin/stat -c '%u:%g' " + qParent + " 2>/dev/null); [ -n \"$P_UG\" ] && /system/bin/chown \"$P_UG\" " + qDst + " 2>/dev/null || true)"
                    + " && /system/bin/sha256sum " + qDst
                    + "; echo __EXIT=$?";
            String out = Root.suMountMaster(cmd, 5000);
            if (!verifySha256SumOutput(out, expectedSha)) {
                allOk = false;
            }
        }
        return allOk;
    }

    static boolean verifySha256SumOutput(String out, String expectedSha) {
        if (out == null || !out.contains("__EXIT=0") || expectedSha == null || expectedSha.isEmpty()) {
            return false;
        }
        for (String line : out.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("__EXIT=")) continue;
            String[] parts = t.split("\\s+");
            if (parts.length >= 1 && expectedSha.equalsIgnoreCase(parts[0])) {
                return true;
            }
        }
        return false;
    }

    private static HyperOsWallpaperHelper.Gateway createRootGateway(final ContentResolver cr, final int userId) {
        return new HyperOsWallpaperHelper.Gateway() {
            @Override
            public int probeFile(String path) {
                if (path == null || path.isEmpty()) return -1;
                String cmd = "if [ -f " + Root.shellQuote(path) + " ] && [ -s " + Root.shellQuote(path)
                        + " ]; then echo __P=1; elif [ ! -e " + Root.shellQuote(path)
                        + " ]; then echo __P=0; else echo __P=-1; fi; echo __EXIT=$?";
                String out = Root.suMountMaster(cmd, 3000);
                if (out == null || !out.contains("__EXIT=0")) return -1;
                if (out.contains("__P=1")) return 1;
                if (out.contains("__P=0")) return 0;
                return -1;
            }

            @Override
            public HyperOsWallpaperHelper.TextReadResult readTextFile(String path) {
                ProbeStatus st = probeFileReadOnly(path);
                if (st == ProbeStatus.ERROR) return new HyperOsWallpaperHelper.TextReadResult(-1, null);
                if (st == ProbeStatus.ABSENT) return new HyperOsWallpaperHelper.TextReadResult(0, null);
                String out = Root.suRawMountMaster(5000, "-c", "/system/bin/cat " + Root.shellQuote(path));
                if (out == null || out.startsWith("suRaw 失败:")) {
                    return new HyperOsWallpaperHelper.TextReadResult(-1, null);
                }
                if (out.trim().isEmpty()) {
                    return new HyperOsWallpaperHelper.TextReadResult(-1, null);
                }
                return new HyperOsWallpaperHelper.TextReadResult(1, out);
            }

            @Override
            public HyperOsWallpaperHelper.SettingEntry readSetting(String table, String key) {
                SettingRecord rec = PreModuleWallpaperBackup.readSetting(cr, userId, table, key);
                return new HyperOsWallpaperHelper.SettingEntry(rec.ok, rec.existed, rec.value);
            }
        };
    }

    private static JSONObject stageOemCustomPayload(File oemStageDir, HyperOsWallpaperHelper.OemProbeResult oemRes) {
        if (oemStageDir == null || oemRes == null || oemRes.filesToBackup.isEmpty()) {
            return null;
        }
        if (!oemStageDir.exists() && !oemStageDir.mkdirs()) {
            return null;
        }
        try {
            JSONObject oemObj = new JSONObject();
            oemObj.put("home_type", oemRes.homeType);
            oemObj.put("lock_type", oemRes.lockType);
            oemObj.put("clock_template_id", oemRes.clockTemplateId != null ? oemRes.clockTemplateId : "");
            if (oemRes.wmsSplitLock) {
                oemObj.put("allow_split_restore", true);
                if (!oemRes.wmsLockComp.isEmpty()) {
                    oemObj.put("wms_lock_comp", oemRes.wmsLockComp);
                }
            }

            JSONArray filesArr = new JSONArray();
            for (HyperOsWallpaperHelper.OemFileSpec spec : oemRes.filesToBackup) {
                File dest = new File(oemStageDir, spec.relName);
                if (!copyRootFileToAppFile(spec.sourcePath, dest)) {
                    return null;
                }
                if (spec.imageFile && !isValidImageFile(dest)) {
                    return null;
                }
                String sha = HyperOsWallpaperHelper.sha256Hex(dest);
                if (sha.isEmpty() || dest.length() <= 0L) {
                    return null;
                }
                if ("home_wallpaper.jpg".equals(spec.relName)
                        && !oemRes.expectedHomeSha256.isEmpty()
                        && !oemRes.expectedHomeSha256.equalsIgnoreCase(sha)) {
                    Log.w(TAG, "stage=oem_stage source=home_sha committed=false code=bridge_sha_mismatch");
                    return null;
                }
                if ("lock_wallpaper.jpg".equals(spec.relName)
                        && !oemRes.expectedLockSha256.isEmpty()
                        && !oemRes.expectedLockSha256.equalsIgnoreCase(sha)) {
                    Log.w(TAG, "stage=oem_stage source=lock_sha committed=false code=bridge_sha_mismatch");
                    return null;
                }
                JSONObject fObj = new JSONObject();
                fObj.put("rel_name", spec.relName);
                fObj.put("source_path", spec.sourcePath);
                fObj.put("size", dest.length());
                fObj.put("sha256", sha);
                fObj.put("is_image", spec.imageFile);
                filesArr.put(fObj);
            }
            oemObj.put("files", filesArr);

            JSONObject settingsObj = new JSONObject();
            settingsObj.put("secure", settingMapToJson(oemRes.secureSettings));
            settingsObj.put("system", settingMapToJson(oemRes.systemSettings));
            oemObj.put("settings", settingsObj);
            return oemObj;
        } catch (Throwable t) {
            Log.w(TAG, "stageOemCustomPayload failed", t);
            return null;
        }
    }

    private static JSONObject settingMapToJson(Map<String, HyperOsWallpaperHelper.SettingEntry> map) throws Exception {
        JSONObject obj = new JSONObject();
        if (map != null) {
            for (Map.Entry<String, HyperOsWallpaperHelper.SettingEntry> e : map.entrySet()) {
                JSONObject item = new JSONObject();
                HyperOsWallpaperHelper.SettingEntry entry = e.getValue();
                item.put("existed", entry != null && entry.existed);
                item.put("val", (entry != null && entry.value != null) ? entry.value : "");
                obj.put(e.getKey(), item);
            }
        }
        return obj;
    }

    private static boolean copyRootFileToAppFile(String srcPath, File dest) {
        if (srcPath == null || srcPath.isEmpty() || dest == null) return false;
        try {
            new FileOutputStream(dest).close();
            String cmd = "if [ -s " + Root.shellQuote(srcPath) + " ]; then /system/bin/cat "
                    + Root.shellQuote(srcPath) + " > " + Root.shellQuote(dest.getAbsolutePath())
                    + " && echo __COPY_OK; fi; echo __EXIT=$?";
            String out = Root.suMountMaster(cmd, 8000);
            return out != null && out.contains("__EXIT=0") && out.contains("__COPY_OK") && dest.length() > 0L;
        } catch (Throwable t) {
            Log.w(TAG, "copyRootFileToAppFile failed", t);
            deleteQuietly(dest);
            return false;
        }
    }

    /** 采集本模块自有配置快照（供备份恢复及 applyScene 失败回滚复用）。 */
    static JSONObject captureModuleStateJson(Context ctx) {
        if (ctx == null) return null;
        try {
            JSONObject obj = new JSONObject();
            obj.put("aod_style", AodStyleSettings.getStyle(ctx));
            obj.put(AodStyleSettings.KEY_POSITIONS,
                    AodStyleSettings.encodePositions(AodStyleSettings.getPositions(ctx)));
            obj.put(PhotoTextureSettings.KEY, PhotoTextureSettings.get(ctx));
            obj.put("last_kind", LastSet.kind(ctx));
            obj.put("last_component", LastSet.component(ctx));
            obj.put("clock_aod", serializeClockConfig(ClockSettings.getAodDirect(ctx)).toString());
            obj.put("clock_lock", serializeClockConfig(ClockSettings.getLockDirect(ctx)).toString());
            return obj;
        } catch (Throwable t) {
            return null;
        }
    }

    static void restoreModuleStateJson(Context ctx, JSONObject obj) {
        restoreModuleStateJsonStrict(ctx, obj, true);
    }

    static boolean restoreModuleStateJsonStrict(Context ctx, JSONObject obj, boolean sendBroadcast) {
        if (ctx == null || obj == null) return false;
        try {
            boolean ok = true;
            int textures = obj.optInt(PhotoTextureSettings.KEY, -1);
            boolean reloadTextures = PhotoTextureSettings.valid(textures)
                    && textures != PhotoTextureSettings.get(ctx);
            if (PhotoTextureSettings.valid(textures) && !PhotoTextureSettings.saveStrict(ctx, textures)) {
                ok = false;
            }
            int style = obj.optInt("aod_style", -1);
            if (!restoreAodPositions(ctx, obj.optString(AodStyleSettings.KEY_POSITIONS, null))) {
                ok = false;
            }
            if (style >= 0) {
                boolean stOk = sendBroadcast
                        ? AodStyleSettings.setStyleStrict(ctx, style)
                        : AodStyleSettings.setStyleLocalStrict(ctx, style);
                if (!stOk) ok = false;
            }
            ClockSettings.Config aodCfg = deserializeClockConfig(obj.optString("clock_aod", ""));
            ClockSettings.Config lockCfg = deserializeClockConfig(obj.optString("clock_lock", ""));
            if (aodCfg != null || lockCfg != null) {
                boolean clkOk = sendBroadcast
                        ? ClockSettings.saveStrict(ctx, aodCfg, lockCfg)
                        : ClockSettings.saveLocalStrict(ctx, aodCfg, lockCfg);
                if (!clkOk) ok = false;
            }
            int lastKind = obj.optInt("last_kind", LastSet.KIND_FOLLOW);
            String lastComp = obj.optString("last_component", "");
            boolean reloadDynamic = sendBroadcast && (lastKind == LastSet.KIND_DYNAMIC
                    || LastSet.kind(ctx) == LastSet.KIND_DYNAMIC);
            if (!LastSet.saveStrict(ctx, lastKind, lastComp)) {
                ok = false;
            }
            if (sendBroadcast && (reloadDynamic || reloadTextures)) {
                try {
                    Intent reload = new Intent(QihangWallpaper.ACTION_MODE);
                    reload.setPackage(ctx.getPackageName());
                    reload.putExtra("reload_photo", true);
                    ctx.sendBroadcast(reload);
                } catch (RuntimeException error) {
                    Log.w(TAG, "壁纸配置已恢复，渲染刷新通知未送达", error);
                }
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "restoreModuleStateJsonStrict failed", t);
            return false;
        }
    }

    private static boolean restoreAodPositions(Context ctx, String positions) {
        return positions == null || AodStyleSettings.savePositionsStrict(ctx, positions);
    }

    private static void restoreModuleStateIfPresent(Context ctx, BackupMetadata meta) {
        if (ctx == null || meta == null || !meta.hasModuleState) return;
        try {
            if (PhotoTextureSettings.valid(meta.modulePhotoTextures)) {
                PhotoTextureSettings.saveStrict(ctx, meta.modulePhotoTextures);
            }
            if (!restoreAodPositions(ctx, meta.moduleAodPositions)) {
                Log.w(TAG, "恢复息屏位置失败");
            }
            if (meta.moduleAodStyle >= 0) {
                AodStyleSettings.setStyle(ctx, meta.moduleAodStyle);
            }
            ClockSettings.Config aodCfg = deserializeClockConfig(meta.moduleClockAodJson);
            ClockSettings.Config lockCfg = deserializeClockConfig(meta.moduleClockLockJson);
            if (aodCfg != null || lockCfg != null) {
                ClockSettings.save(ctx, aodCfg, lockCfg);
            }
            LastSet.save(ctx, meta.moduleLastKind, meta.moduleLastComponent);
        } catch (Throwable t) {
            Log.w(TAG, "restoreModuleStateIfPresent failed", t);
        }
    }

    private static JSONObject serializeClockConfig(ClockSettings.Config cfg) throws Exception {
        JSONObject j = new JSONObject();
        if (cfg == null) cfg = new ClockSettings.Config();
        j.put("enabled", cfg.enabled);
        j.put("style", cfg.style);
        j.put("color", cfg.color);
        j.put("scale", cfg.scale);
        j.put("weight", cfg.weight);
        j.put("offsetY", cfg.offsetY);
        j.put("showLunar", cfg.showLunar);
        return j;
    }

    private static ClockSettings.Config deserializeClockConfig(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(jsonStr);
            ClockSettings.Config c = new ClockSettings.Config();
            c.enabled = j.optBoolean("enabled", false);
            c.style = j.optInt("style", ClockSettings.STYLE_DOUBLE_STACK);
            c.color = j.optInt("color", 0xFFFFFFFF);
            c.scale = (float) j.optDouble("scale", 1.0);
            c.weight = j.optInt("weight", 500);
            c.offsetY = j.optInt("offsetY", 0);
            c.showLunar = j.optBoolean("showLunar", false);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalizeClassName(String pkg, String cls) {
        return HyperOsWallpaperHelper.expandShortClassName(pkg, cls);
    }

    private static boolean sameComponentName(String pkgA, String clsA, String pkgB, String clsB) {
        if (pkgA == null) pkgA = "";
        if (pkgB == null) pkgB = "";
        if (!pkgA.equals(pkgB)) return false;
        return normalizeClassName(pkgA, clsA).equals(normalizeClassName(pkgB, clsB));
    }

    private static boolean sameComponentName(WallpaperInfo info, String pkg, String cls) {
        return info != null && sameComponentName(info.getPackageName(), info.getServiceName(), pkg, cls);
    }

    private static boolean matchesComponent(WallpaperInfo info, String pkg, String cls) {
        return sameComponentName(info, pkg, cls);
    }

    private static boolean sameComponent(WallpaperInfo a, WallpaperInfo b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return sameComponentName(a.getPackageName(), a.getServiceName(),
                b.getPackageName(), b.getServiceName());
    }

    private static boolean isValidImageFile(File file) {
        if (file == null || !file.exists() || !file.isFile() || !file.canRead() || file.length() <= 0) {
            return false;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            return opts.outWidth > 0 && opts.outHeight > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isComponentAvailable(Context ctx, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || cls == null || cls.isEmpty()) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getServiceInfo(new ComponentName(pkg, cls), 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearBackup(Context ctx) {
        if (ctx == null) return;
        try {
            File userDir = getBackupDirReadOnly(ctx);
            if (!userDir.exists()) return;
            if (!deleteRecursively(userDir)) {
                Log.w(TAG, "stage=clear_backup source=storage committed=false code=partial_delete_leftover");
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearBackup failed", t);
        }
    }

    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursively(c)) {
                        ok = false;
                    }
                }
            }
        }
        if (!f.delete()) {
            ok = false;
        }
        return ok;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                f.delete();
            } catch (Throwable ignored) {}
        }
    }

    private static String readFileToString(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = fis.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
