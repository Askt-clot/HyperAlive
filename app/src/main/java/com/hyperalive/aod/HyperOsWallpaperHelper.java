package com.hyperalive.aod;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * HyperOS 4 (Android 17) OEM 壁纸与锁屏/息屏模板状态探测、交叉核验与快照辅助类。
 *
 * <p>设计约束：
 * 1. 仅允许访问当前用户的固定白名单路径，拒绝路径穿越或历史缓存拼接；
 * 2. 严格区分真实 OEM 自定义状态、已核验系统默认状态与残留/矛盾状态；
 * 3. 日志仅记录阶段、来源分类、核验码与提交状态，绝不打印用户图片路径/URI 或敏感配置正文；
 * 4. 结合 {@code HyperOsWallpaperBridge} AIDL 探针交叉核验真实壁纸类型/路径/拓扑与恢复收敛。
 */
public final class HyperOsWallpaperHelper {

    private static final String TAG = "PreModuleBackup";

    public static final String MIUI_IMAGE_COMPONENT_FULL =
            "com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.ImageWallpaper";
    public static final String MIUI_IMAGE_COMPONENT_SHORT =
            "com.miui.miwallpaper/.wallpaperservice.ImageWallpaper";
    public static final String MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL =
            "com.miui.miwallpaper/com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper";
    public static final String MIUI_KEYGUARD_PICTORIAL_COMPONENT_SHORT =
            "com.miui.miwallpaper/.wallpaperservice.MiuiKeyguardPictorialWallpaper";

    public static final String[] TEMPLATE_WHITELIST_FILES = {
            "wallpaper_home",
            "wallpaper",
            "subject",
            "subject_mask"
    };

    public static final String[] OEM_SECURE_SETTING_KEYS = {
            "constant_template_editor_info",
            "image_bg_selector",
            "aod_category_name",
            "Linkage",
            "keyguard_template_status",
            "flag_lock_wallpaper_type",
            "wallpaper_changed_1",
            "wallpaper_changed_2",
            "wallpaper_effect_type_1",
            "wallpaper_effect_type_2",
            "wallpaper_matting_support_1",
            "wallpaper_matting_support_2"
    };

    public static final String[] OEM_SYSTEM_SETTING_KEYS = {
            "aod_category_name",
            "Linkage"
    };

    private HyperOsWallpaperHelper() {}

    public enum Mode {
        CONFIRMED_DEFAULT,
        OEM_CUSTOM_IMAGE,
        AMBIGUOUS_OR_UNVERIFIED
    }

    public static final class SettingEntry {
        public final boolean ok;
        public final boolean existed;
        public final String value;

        public SettingEntry(boolean ok, boolean existed, String value) {
            this.ok = ok;
            this.existed = existed;
            this.value = value != null ? value : "";
        }
    }

    public static final class TextReadResult {
        public final int status; // 1 = OK, 0 = ABSENT, -1 = ERROR
        public final String content;

        public TextReadResult(int status, String content) {
            this.status = status;
            this.content = content;
        }
    }

    public interface Gateway {
        int probeFile(String path);

        TextReadResult readTextFile(String path);

        SettingEntry readSetting(String table, String key);
    }

    public static final class WallpaperDataXml {
        public int which = -1;
        public String type = "";
        public String lastType = "";
        public String component = "";
        public String lastComponent = "";
        public int pathSize = 0;
        public String path0 = "";
        public int originWidth = 0;
        public int originHeight = 0;
        public String clockStyleType = "";
        public int[] clockTypeInfo = null;
        public int effectId = 0;
        public String enableBlur = "";
        public String supportDark = "";
        public String carousel = "";
        public String loopVideo = "";
        public String content = "";
        public int allKeyFrame = 0;
        public String lastPendingPkg = "";
        public boolean supportMatting = false;
        public String doodleEnable = "";
        public String rawXml = "";
    }

    public static final class TemplateSummary {
        public boolean valid;
        public long updateTemplate;
        public String homeResourceType = "";
        public String homeSourcePath = "";
        public String lockResourceType = "";
        public String lockSourcePath = "";
        public String lockSubjectPath = "";
        public String lockClockTemplateId = "";
        public String templateName = "";
        public boolean hasHomeMatrix;
        public boolean hasLockMatrix;

        public boolean hasImageResource() {
            return "image".equals(homeResourceType) || "image".equals(lockResourceType);
        }
    }

    public static final class OemFileSpec {
        public final String relName;
        public final String sourcePath;
        public final boolean imageFile;

        public OemFileSpec(String relName, String sourcePath, boolean imageFile) {
            this.relName = relName;
            this.sourcePath = sourcePath;
            this.imageFile = imageFile;
        }
    }

    public static final class OemProbeResult {
        public final Mode mode;
        public final String diagnosticCode;
        public final String userMessage;
        public final String homeType;
        public final String lockType;
        public final String clockTemplateId;
        public final List<OemFileSpec> filesToBackup;
        public final Map<String, SettingEntry> secureSettings;
        public final Map<String, SettingEntry> systemSettings;
        public final String expectedHomeSha256;
        public final String expectedLockSha256;
        public final boolean wmsSplitLock;
        public final String wmsLockComp;

        OemProbeResult(Mode mode, String diagnosticCode, String userMessage,
                       String homeType, String lockType, String clockTemplateId,
                       List<OemFileSpec> filesToBackup,
                       Map<String, SettingEntry> secureSettings,
                       Map<String, SettingEntry> systemSettings) {
            this(mode, diagnosticCode, userMessage, homeType, lockType, clockTemplateId,
                    filesToBackup, secureSettings, systemSettings, "", "", false, "");
        }

        OemProbeResult(Mode mode, String diagnosticCode, String userMessage,
                       String homeType, String lockType, String clockTemplateId,
                       List<OemFileSpec> filesToBackup,
                       Map<String, SettingEntry> secureSettings,
                       Map<String, SettingEntry> systemSettings,
                       String expectedHomeSha256,
                       String expectedLockSha256) {
            this(mode, diagnosticCode, userMessage, homeType, lockType, clockTemplateId,
                    filesToBackup, secureSettings, systemSettings,
                    expectedHomeSha256, expectedLockSha256, false, "");
        }

        OemProbeResult(Mode mode, String diagnosticCode, String userMessage,
                       String homeType, String lockType, String clockTemplateId,
                       List<OemFileSpec> filesToBackup,
                       Map<String, SettingEntry> secureSettings,
                       Map<String, SettingEntry> systemSettings,
                       String expectedHomeSha256,
                       String expectedLockSha256,
                       boolean wmsSplitLock,
                       String wmsLockComp) {
            this.mode = mode;
            this.diagnosticCode = diagnosticCode;
            this.userMessage = userMessage;
            this.homeType = homeType;
            this.lockType = lockType;
            this.clockTemplateId = clockTemplateId;
            this.filesToBackup = filesToBackup != null ? filesToBackup : Collections.<OemFileSpec>emptyList();
            this.secureSettings = secureSettings != null ? secureSettings : Collections.<String, SettingEntry>emptyMap();
            this.systemSettings = systemSettings != null ? systemSettings : Collections.<String, SettingEntry>emptyMap();
            this.expectedHomeSha256 = expectedHomeSha256 != null ? expectedHomeSha256 : "";
            this.expectedLockSha256 = expectedLockSha256 != null ? expectedLockSha256 : "";
            this.wmsSplitLock = wmsSplitLock;
            this.wmsLockComp = wmsLockComp != null ? wmsLockComp : "";
        }
    }

    public static String themeMagicHomeXmlPath(int userId) {
        return "/data/system/theme_magic/users/" + userId + "/wallpaper/data/home.xml";
    }

    public static String themeMagicLockXmlPath(int userId) {
        return "/data/system/theme_magic/users/" + userId + "/wallpaper/data/lock.xml";
    }

    public static String themeMagicHomeImagePath(int userId) {
        return "/data/system/theme_magic/users/" + userId + "/wallpaper/image/home_wallpaper.jpg";
    }

    public static String themeMagicLockImagePath(int userId) {
        return "/data/system/theme_magic/users/" + userId + "/wallpaper/image/lock_wallpaper.jpg";
    }

    public static String aodTemplateDir(int userId) {
        return "/data/user_de/" + userId + "/com.miui.aod/files/templates/current";
    }

    public static String aodTemplateWhitelistPath(int userId, String fileName) {
        if (fileName == null) return null;
        for (String allowed : TEMPLATE_WHITELIST_FILES) {
            if (allowed.equals(fileName)) {
                return aodTemplateDir(userId) + "/" + allowed;
            }
        }
        return null;
    }

    public static boolean isWhitelistTemplatePath(String path, int userId) {
        if (hasUnsafePathSegments(path) || userId < 0) return false;
        for (String allowed : TEMPLATE_WHITELIST_FILES) {
            if (path.equals(aodTemplateDir(userId) + "/" + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnsafePathSegments(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) return true;
        if (path.indexOf('\0') >= 0) return true;
        for (String seg : path.split("/")) {
            if ("..".equals(seg) || ".".equals(seg)) {
                return true;
            }
        }
        return false;
    }

    public static WallpaperDataXml parseWallpaperDataXml(String xmlText) {
        if (xmlText == null || xmlText.trim().isEmpty()) {
            return null;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Throwable ignored) {}
            try {
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            } catch (Throwable ignored) {}
            try {
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Throwable ignored) {}
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xmlText)));
            Element root = doc.getDocumentElement();
            if (root == null || !"wallpaper_data".equals(root.getTagName())) {
                return null;
            }
            WallpaperDataXml data = new WallpaperDataXml();
            data.which = parseIntSafe(root.getAttribute("which"), -1);
            data.type = safeStr(root.getAttribute("type"));
            data.lastType = safeStr(root.getAttribute("last_type"));
            data.component = safeStr(root.getAttribute("component"));
            data.lastComponent = safeStr(root.getAttribute("last_component"));
            data.pathSize = parseIntSafe(root.getAttribute("pathSize"), 0);
            data.path0 = safeStr(root.getAttribute("path0"));
            data.originWidth = parseIntSafe(root.getAttribute("origin_bitmap_width"), 0);
            data.originHeight = parseIntSafe(root.getAttribute("origin_bitmap_height"), 0);
            data.clockStyleType = safeStr(root.getAttribute("clock_style_type"));
            int infoLen = parseIntSafe(root.getAttribute("clock_type_info_length"), 0);
            if (infoLen > 0 && infoLen <= 32) {
                int[] cti = new int[infoLen];
                for (int i = 0; i < infoLen; i++) {
                    cti[i] = parseIntSafe(root.getAttribute("clock_type_info" + i), -1);
                }
                data.clockTypeInfo = cti;
            }
            data.effectId = parseIntSafe(root.getAttribute("effect_id"), 0);
            data.enableBlur = safeStr(root.getAttribute("enable_blur"));
            data.supportDark = safeStr(root.getAttribute("support_dark"));
            data.carousel = safeStr(root.getAttribute("carousel"));
            data.loopVideo = safeStr(root.getAttribute("loopVideo"));
            data.content = safeStr(root.getAttribute("content"));
            data.allKeyFrame = parseIntSafe(root.getAttribute("all_key_frame"), 0);
            data.lastPendingPkg = safeStr(root.getAttribute("lastPendingPkg"));
            data.supportMatting = "true".equalsIgnoreCase(root.getAttribute("support_matting"));
            data.doodleEnable = safeStr(root.getAttribute("doodle_enable"));
            data.rawXml = xmlText;
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    public static TemplateSummary parseTemplateEditorInfo(String jsonStr, int userId) {
        TemplateSummary summary = new TemplateSummary();
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            summary.valid = false;
            return summary;
        }
        try {
            JSONObject root = new JSONObject(jsonStr);
            summary.updateTemplate = root.optLong("updateTemplate", 0L);

            JSONObject homeInfo = root.optJSONObject("homeInfo");
            if (homeInfo != null) {
                JSONObject wp = homeInfo.optJSONObject("wallpaperInfo");
                if (wp != null) {
                    summary.homeResourceType = wp.optString("resourceType", "");
                    summary.homeSourcePath = wp.optString("source", "");
                    if (!summary.homeSourcePath.isEmpty() && hasUnsafePathSegments(summary.homeSourcePath)) {
                        summary.valid = false;
                        return summary;
                    }
                    JSONObject pos = wp.optJSONObject("positionInfo");
                    if (pos != null) {
                        JSONArray arr = pos.optJSONArray("matrixArr");
                        summary.hasHomeMatrix = arr != null && arr.length() == 9;
                    }
                }
            }

            JSONObject lockInfo = root.optJSONObject("lockscreenInfo");
            if (lockInfo != null) {
                summary.templateName = lockInfo.optString("templateName", "");
                JSONObject clockInfo = lockInfo.optJSONObject("clockInfo");
                if (clockInfo != null) {
                    summary.lockClockTemplateId = clockInfo.optString("templateId", "");
                }
                JSONObject wp = lockInfo.optJSONObject("wallpaperInfo");
                if (wp != null) {
                    summary.lockResourceType = wp.optString("resourceType", "");
                    summary.lockSourcePath = wp.optString("source", "");
                    summary.lockSubjectPath = wp.optString("subject", "");
                    if ((!summary.lockSourcePath.isEmpty() && hasUnsafePathSegments(summary.lockSourcePath))
                            || (!summary.lockSubjectPath.isEmpty() && hasUnsafePathSegments(summary.lockSubjectPath))) {
                        summary.valid = false;
                        return summary;
                    }
                    JSONObject pos = wp.optJSONObject("positionInfo");
                    if (pos != null) {
                        JSONArray arr = pos.optJSONArray("matrixArr");
                        summary.hasLockMatrix = arr != null && arr.length() == 9;
                    }
                }
            }
            summary.valid = (homeInfo != null || lockInfo != null);
            return summary;
        } catch (Throwable t) {
            summary.valid = false;
            return summary;
        }
    }

    /**
     * 当标准 /data/system/users/<uid>/wallpaper* 均不存在且系统槽位指向 HyperOS ImageWallpaper 时，
     * 交叉核验 HyperOS 4 OEM 自定义壁纸/模板路径与元数据。
     */
    public static OemProbeResult probeAndValidateOemState(int userId, Gateway gw) {
        if (gw == null || userId < 0) {
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_invalid_args",
                    "HyperOS 壁纸参数异常，无法安全识别当前壁纸状态", "", "", "",
                    null, null, null);
        }

        String homeXmlPath = themeMagicHomeXmlPath(userId);
        String lockXmlPath = themeMagicLockXmlPath(userId);
        String homeImgPath = themeMagicHomeImagePath(userId);
        String lockImgPath = themeMagicLockImagePath(userId);

        TextReadResult homeXmlRes = gw.readTextFile(homeXmlPath);
        TextReadResult lockXmlRes = gw.readTextFile(lockXmlPath);
        int homeImgProbe = gw.probeFile(homeImgPath);
        int lockImgProbe = gw.probeFile(lockImgPath);
        int tplHomeProbe = gw.probeFile(aodTemplateWhitelistPath(userId, "wallpaper_home"));
        int tplLockProbe = gw.probeFile(aodTemplateWhitelistPath(userId, "wallpaper"));
        int tplSubjProbe = gw.probeFile(aodTemplateWhitelistPath(userId, "subject"));
        int tplMaskProbe = gw.probeFile(aodTemplateWhitelistPath(userId, "subject_mask"));

        if (homeXmlRes.status == -1 || lockXmlRes.status == -1
                || homeImgProbe == -1 || lockImgProbe == -1
                || tplHomeProbe == -1 || tplLockProbe == -1
                || tplSubjProbe == -1 || tplMaskProbe == -1) {
            Log.w(TAG, "stage=oem_probe source=theme_magic_or_aod committed=false code=oem_probe_io_error"
                    + " home_xml=" + homeXmlRes.status + " lock_xml=" + lockXmlRes.status
                    + " home_img=" + homeImgProbe + " lock_img=" + lockImgProbe
                    + " tpl_home=" + tplHomeProbe + " tpl_lock=" + tplLockProbe
                    + " tpl_subj=" + tplSubjProbe + " tpl_mask=" + tplMaskProbe);
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_probe_io_error",
                    "读取 HyperOS 壁纸或息屏模板元数据失败，已中止应用以防覆盖未知状态",
                    "", "", "", null, null, null);
        }

        Map<String, SettingEntry> secureMap = new LinkedHashMap<>();
        for (String k : OEM_SECURE_SETTING_KEYS) {
            SettingEntry entry = gw.readSetting("secure", k);
            if (entry == null || !entry.ok) {
                Log.w(TAG, "stage=oem_probe source=settings_secure committed=false code=oem_setting_read_error");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_setting_read_error",
                        "读取 HyperOS 锁屏模板系统设置失败，已中止应用",
                        "", "", "", null, null, null);
            }
            secureMap.put(k, entry);
        }

        Map<String, SettingEntry> systemMap = new LinkedHashMap<>();
        for (String k : OEM_SYSTEM_SETTING_KEYS) {
            SettingEntry entry = gw.readSetting("system", k);
            if (entry == null || !entry.ok) {
                Log.w(TAG, "stage=oem_probe source=settings_system committed=false code=oem_system_setting_read_error");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_system_setting_read_error",
                        "读取 HyperOS 息屏主题设置失败，已中止应用",
                        "", "", "", null, null, null);
            }
            systemMap.put(k, entry);
        }

        SettingEntry tplEditorEntry = secureMap.get("constant_template_editor_info");
        TemplateSummary tplSummary = null;
        if (tplEditorEntry != null && tplEditorEntry.existed && !tplEditorEntry.value.trim().isEmpty()) {
            tplSummary = parseTemplateEditorInfo(tplEditorEntry.value, userId);
            if (!tplSummary.valid) {
                Log.w(TAG, "stage=oem_probe source=constant_template_editor_info committed=false code=oem_template_json_invalid");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_template_json_invalid",
                        "HyperOS 锁屏模板元数据格式校验失败，已中止应用",
                        "", "", "", null, null, null);
            }
        }

        // Case A: 无 theme_magic data XML
        if (homeXmlRes.status == 0 && lockXmlRes.status == 0) {
            boolean anyLeftover = (homeImgProbe == 1 || lockImgProbe == 1
                    || tplHomeProbe == 1 || tplLockProbe == 1
                    || (tplSummary != null && tplSummary.hasImageResource()));
            if (anyLeftover) {
                Log.w(TAG, "stage=oem_probe source=theme_magic_missing_xml committed=false code=oem_unbacked_artifacts");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_unbacked_artifacts",
                        "检测到 HyperOS 自定义壁纸图片或模板文件但缺少对应声明文件，无法排除残留歧义，已中止应用",
                        "", "", "", null, null, null);
            }
            Log.i(TAG, "stage=oem_probe source=hyperos_default_no_oem committed=false code=confirmed_default_clean");
            return new OemProbeResult(Mode.CONFIRMED_DEFAULT, "confirmed_default_clean",
                    "已核验当前为系统默认无自定义壁纸状态",
                    "default", "default", "", null, secureMap, systemMap);
        }

        // Case B: 仅有单侧 XML 或解析失败 -> 保守中止
        if (homeXmlRes.status != 1 || lockXmlRes.status != 1) {
            Log.w(TAG, "stage=oem_probe source=theme_magic_xml committed=false code=oem_xml_single_sided");
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_xml_single_sided",
                    "HyperOS 壁纸声明文件仅单侧存在，无法完整核验桌面/锁屏状态，已中止应用",
                    "", "", "", null, null, null);
        }

        WallpaperDataXml homeXml = parseWallpaperDataXml(homeXmlRes.content);
        WallpaperDataXml lockXml = parseWallpaperDataXml(lockXmlRes.content);
        if (homeXml == null || lockXml == null || homeXml.which != 1 || lockXml.which != 2) {
            Log.w(TAG, "stage=oem_probe source=theme_magic_xml committed=false code=oem_xml_parse_invalid");
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_xml_parse_invalid",
                    "HyperOS 壁纸声明文件结构或槽位号校验失败，已中止应用",
                    "", "", "", null, null, null);
        }

        // Case B1: 双侧明确声明 type=default
        if ("default".equals(homeXml.type) && "default".equals(lockXml.type)) {
            SettingEntry lockTypeFlag = secureMap.get("flag_lock_wallpaper_type");
            if (lockTypeFlag != null && lockTypeFlag.existed && "image".equals(lockTypeFlag.value)) {
                Log.w(TAG, "stage=oem_probe source=flag_lock_wallpaper_type committed=false code=oem_default_flag_conflict");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_default_flag_conflict",
                        "HyperOS 默认声明与锁屏图片标志冲突，已中止应用",
                        homeXml.type, lockXml.type, "", null, null, null);
            }
            Log.i(TAG, "stage=oem_probe source=theme_magic_xml committed=false code=confirmed_default_xml");
            return new OemProbeResult(Mode.CONFIRMED_DEFAULT, "confirmed_default_xml",
                    "已核验 HyperOS 系统默认壁纸状态",
                    "default", "default", "", null, secureMap, systemMap);
        }

        // Case B2: 支持 image（或单侧 image + 单侧 default）；其他未知类型（video/sensor/super_wallpaper 等）保守中止
        boolean homeIsImage = "image".equals(homeXml.type);
        boolean lockIsImage = "image".equals(lockXml.type);
        boolean homeValidType = homeIsImage || "default".equals(homeXml.type);
        boolean lockValidType = lockIsImage || "default".equals(lockXml.type);
        if (!homeValidType || !lockValidType || (!homeIsImage && !lockIsImage)) {
            Log.w(TAG, "stage=oem_probe source=theme_magic_xml committed=false code=oem_unsupported_type");
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_unsupported_type",
                    "当前 HyperOS 壁纸类型（" + homeXml.type + "/" + lockXml.type + "）尚不支持安全核验备份，已中止应用",
                    homeXml.type, lockXml.type, "", null, null, null);
        }

        List<OemFileSpec> files = new ArrayList<>();
        files.add(new OemFileSpec("data_home.xml", homeXmlPath, false));
        files.add(new OemFileSpec("data_lock.xml", lockXmlPath, false));

        if (homeIsImage) {
            boolean compMatch = MIUI_IMAGE_COMPONENT_FULL.equals(homeXml.component);
            boolean pathMatch = homeImgPath.equals(homeXml.path0);
            if (!compMatch || homeXml.pathSize != 1 || !pathMatch || homeImgProbe != 1) {
                Log.w(TAG, "stage=oem_probe source=theme_magic_home committed=false code=oem_home_cross_check_fail"
                        + " comp_match=" + compMatch + " path_size=" + homeXml.pathSize
                        + " path_match=" + pathMatch + " img_probe=" + homeImgProbe);
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_home_cross_check_fail",
                        "HyperOS 桌面自定义壁纸路径、组件或图片文件核验失败（path_match=" + pathMatch
                                + ", probe=" + homeImgProbe + "），已中止应用",
                        homeXml.type, lockXml.type, "", null, null, null);
            }
            files.add(new OemFileSpec("home_wallpaper.jpg", homeImgPath, true));
        }

        if (lockIsImage) {
            boolean compMatch = MIUI_IMAGE_COMPONENT_FULL.equals(lockXml.component);
            boolean pathMatch = lockImgPath.equals(lockXml.path0);
            if (!compMatch || lockXml.pathSize != 1 || !pathMatch || lockImgProbe != 1) {
                Log.w(TAG, "stage=oem_probe source=theme_magic_lock committed=false code=oem_lock_cross_check_fail"
                        + " comp_match=" + compMatch + " path_size=" + lockXml.pathSize
                        + " path_match=" + pathMatch + " img_probe=" + lockImgProbe);
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_lock_cross_check_fail",
                        "HyperOS 锁屏自定义壁纸路径、组件或图片文件核验失败（path_match=" + pathMatch
                                + ", probe=" + lockImgProbe + "），已中止应用",
                        homeXml.type, lockXml.type, "", null, null, null);
            }
            SettingEntry lockFlag = secureMap.get("flag_lock_wallpaper_type");
            if (lockFlag != null && lockFlag.existed && !lockFlag.value.isEmpty()
                    && !"image".equals(lockFlag.value)) {
                Log.w(TAG, "stage=oem_probe source=flag_lock_wallpaper_type committed=false code=oem_lock_flag_mismatch");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_lock_flag_mismatch",
                        "HyperOS 锁屏类型标志与声明不一致，已中止应用",
                        homeXml.type, lockXml.type, "", null, null, null);
            }
            files.add(new OemFileSpec("lock_wallpaper.jpg", lockImgPath, true));
        }

        // 交叉核验锁屏/息屏模板元数据与白名单模板文件
        String clockTpl = lockXml.clockStyleType;
        if (tplSummary != null) {
            if (homeIsImage && !tplSummary.homeResourceType.isEmpty() && !"image".equals(tplSummary.homeResourceType)) {
                Log.w(TAG, "stage=oem_probe source=template_json committed=false code=oem_home_template_type_mismatch");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_home_template_type_mismatch",
                        "HyperOS 桌面模板资源类型与壁纸声明冲突，已中止应用",
                        homeXml.type, lockXml.type, clockTpl, null, null, null);
            }
            if (lockIsImage && !tplSummary.lockResourceType.isEmpty() && !"image".equals(tplSummary.lockResourceType)) {
                Log.w(TAG, "stage=oem_probe source=template_json committed=false code=oem_lock_template_type_mismatch");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_lock_template_type_mismatch",
                        "HyperOS 锁屏模板资源类型与壁纸声明冲突，已中止应用",
                        homeXml.type, lockXml.type, clockTpl, null, null, null);
            }
            String expectedTplHome = aodTemplateWhitelistPath(userId, "wallpaper_home");
            if (homeIsImage && !tplSummary.homeSourcePath.isEmpty()) {
                boolean pathMatch = expectedTplHome.equals(tplSummary.homeSourcePath);
                if (!pathMatch || tplHomeProbe != 1) {
                    boolean aodPrefix = tplSummary.homeSourcePath.startsWith(
                            "/data/user_de/" + userId + "/com.miui.aod/files/templates/current/");
                    Log.w(TAG, "stage=oem_probe source=template_home committed=false code=oem_tpl_home_missing"
                            + " path_match=" + pathMatch + " probe=" + tplHomeProbe
                            + " src_len=" + tplSummary.homeSourcePath.length()
                            + " aod_prefix=" + aodPrefix);
                    return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_tpl_home_missing",
                            "HyperOS 桌面模板源文件路径不在白名单或文件不可读（path_match=" + pathMatch
                                    + ", probe=" + tplHomeProbe + "），已中止应用",
                            homeXml.type, lockXml.type, clockTpl, null, null, null);
                }
            }
            String expectedTplLock = aodTemplateWhitelistPath(userId, "wallpaper");
            if (lockIsImage && !tplSummary.lockSourcePath.isEmpty()) {
                boolean pathMatch = expectedTplLock.equals(tplSummary.lockSourcePath);
                if (!pathMatch || tplLockProbe != 1) {
                    boolean aodPrefix = tplSummary.lockSourcePath.startsWith(
                            "/data/user_de/" + userId + "/com.miui.aod/files/templates/current/");
                    Log.w(TAG, "stage=oem_probe source=template_lock committed=false code=oem_tpl_lock_missing"
                            + " path_match=" + pathMatch + " probe=" + tplLockProbe
                            + " src_len=" + tplSummary.lockSourcePath.length()
                            + " aod_prefix=" + aodPrefix);
                    return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_tpl_lock_missing",
                            "HyperOS 锁屏模板源文件路径不在白名单或文件不可读（path_match=" + pathMatch
                                    + ", probe=" + tplLockProbe + "），已中止应用",
                            homeXml.type, lockXml.type, clockTpl, null, null, null);
                }
            }
            if (!tplSummary.lockSubjectPath.isEmpty()) {
                String expectedSubj = aodTemplateWhitelistPath(userId, "subject");
                String expectedMask = aodTemplateWhitelistPath(userId, "subject_mask");
                if (expectedSubj.equals(tplSummary.lockSubjectPath) && tplSubjProbe != 1) {
                    Log.w(TAG, "stage=oem_probe source=template_subject committed=false code=oem_tpl_subject_missing"
                            + " path_match=true probe=" + tplSubjProbe);
                    return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_tpl_subject_missing",
                            "HyperOS 锁屏景深主体文件缺失（probe=" + tplSubjProbe + "），已中止应用",
                            homeXml.type, lockXml.type, clockTpl, null, null, null);
                }
                if (expectedMask.equals(tplSummary.lockSubjectPath) && tplMaskProbe != 1) {
                    Log.w(TAG, "stage=oem_probe source=template_subject_mask committed=false code=oem_tpl_mask_missing"
                            + " path_match=true probe=" + tplMaskProbe);
                    return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_tpl_mask_missing",
                            "HyperOS 锁屏景深遮罩文件缺失（probe=" + tplMaskProbe + "），已中止应用",
                            homeXml.type, lockXml.type, clockTpl, null, null, null);
                }
            }
            if ((clockTpl == null || clockTpl.isEmpty() || "null".equals(clockTpl))
                    && !tplSummary.lockClockTemplateId.isEmpty()) {
                clockTpl = tplSummary.lockClockTemplateId;
            }
        }

        // 将存在的白名单模板文件纳入原子快照清单
        if (tplHomeProbe == 1) {
            files.add(new OemFileSpec("tpl_wallpaper_home", aodTemplateWhitelistPath(userId, "wallpaper_home"), false));
        }
        if (tplLockProbe == 1) {
            files.add(new OemFileSpec("tpl_wallpaper", aodTemplateWhitelistPath(userId, "wallpaper"), false));
        }
        if (tplSubjProbe == 1) {
            files.add(new OemFileSpec("tpl_subject", aodTemplateWhitelistPath(userId, "subject"), false));
        }
        if (tplMaskProbe == 1) {
            files.add(new OemFileSpec("tpl_subject_mask", aodTemplateWhitelistPath(userId, "subject_mask"), false));
        }

        Log.i(TAG, "stage=oem_probe source=hyperos_custom_verified committed=false files=" + files.size());
        return new OemProbeResult(Mode.OEM_CUSTOM_IMAGE, "oem_custom_verified",
                "已识别并交叉核验 HyperOS 自定义壁纸与模板状态",
                homeXml.type, lockXml.type, clockTpl != null ? clockTpl : "",
                files, secureMap, systemMap);
    }

    /**
     * 将文件系统/XML/设置探测结果与 {@code HyperOsWallpaperBridge -probe} 实时 AIDL/WMS 权威状态交叉核验，
     * 严禁仅凭磁盘历史残留文件判定当前状态。
     */
    public static OemProbeResult crossValidateWithBridge(
            OemProbeResult fsRes, BridgeProbeSummary bridge, int userId) {
        return crossValidateWithBridge(fsRes, bridge, userId, "");
    }

    /**
     * 在双槽静态拓扑场景下额外结合 WMS XML 已核实锁屏组件，兼容 {@code MiuiKeyguardPictorialWallpaper}
     * 或独立 {@code ImageWallpaper} 锁屏槽位。
     */
    public static OemProbeResult crossValidateWithBridge(
            OemProbeResult fsRes, BridgeProbeSummary bridge, int userId, String verifiedWmsLockComp) {
        if (fsRes == null) {
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_fs_probe_null",
                    "HyperOS 壁纸文件系统探测结果为空，已中止应用", "", "", "", null, null, null);
        }
        if (fsRes.mode == Mode.AMBIGUOUS_OR_UNVERIFIED) {
            return fsRes;
        }
        if (bridge == null || !bridge.ok || bridge.home == null || bridge.lock == null) {
            String err = bridge != null ? bridge.error : "NULL_BRIDGE";
            Log.w(TAG, "stage=oem_bridge_check source=aidl committed=false code=oem_bridge_unavailable err=" + err);
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_bridge_unavailable",
                    "无法通过 MiWallPaperControllerService AIDL 探针核验当前壁纸权威状态（" + err + "），为防误判残留已中止应用",
                    fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId, null, null, null);
        }
        if (bridge.wmsSysId <= 0
                || bridge.wmsSysComp.contains("com.hyperalive.aod")
                || bridge.wmsLockComp.contains("com.hyperalive.aod")
                || bridge.wmsSysComp.contains("com.qihang.aod")
                || bridge.wmsLockComp.contains("com.qihang.aod")
                || !isCleanOemSysComp(bridge.wmsSysComp)) {
            Log.w(TAG, "stage=oem_bridge_check source=wms committed=false code=oem_bridge_wms_mismatch");
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_bridge_wms_mismatch",
                    "WMS 壁纸组件/ID 与 HyperOS OEM 状态不一致，已中止应用",
                    fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId, null, null, null);
        }

        String effLockComp = resolveCleanActiveSplitLockComp(verifiedWmsLockComp, bridge.wmsLockComp);
        boolean splitLockClean = bridge.wmsLockId > 0 && !effLockComp.isEmpty();

        if (fsRes.mode == Mode.CONFIRMED_DEFAULT) {
            boolean homeDef = bridge.home.typeOk && !bridge.home.typeReturnedNull
                    && "default".equals(bridge.home.authoritativeType)
                    && bridge.home.isDefaultOk && bridge.home.isDefault;
            boolean lockDef = bridge.lock.typeOk && !bridge.lock.typeReturnedNull
                    && "default".equals(bridge.lock.authoritativeType)
                    && bridge.lock.isDefaultOk && bridge.lock.isDefault;
            if (!homeDef || !lockDef || (bridge.wmsLockId != -1 && !splitLockClean)) {
                Log.w(TAG, "stage=oem_bridge_check source=aidl committed=false code=oem_bridge_default_conflict");
                return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_bridge_default_conflict",
                        "MiWallPaperControllerService 报告当前并非纯系统默认壁纸，与静态默认判断冲突，已中止应用",
                        bridge.home.authoritativeType, bridge.lock.authoritativeType, "", null, null, null);
            }
            return fsRes;
        }

        // Mode.OEM_CUSTOM_IMAGE: 严格交叉比对 AIDL type / isDefault / componentUsing / path / sha256 与 WMS
        String homeErr = validateBridgeCustomSlot(bridge.home, fsRes.homeType, themeMagicHomeImagePath(userId), "home");
        if (!homeErr.isEmpty()) {
            Log.w(TAG, "stage=oem_bridge_check source=home committed=false code=" + homeErr);
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, homeErr,
                    "MiWallpaper AIDL 桌面状态与文件声明不一致（可能为历史残留：" + homeErr + "），已中止应用",
                    fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId, null, null, null);
        }
        String lockErr = validateBridgeCustomSlot(bridge.lock, fsRes.lockType, themeMagicLockImagePath(userId), "lock");
        if (!lockErr.isEmpty()) {
            Log.w(TAG, "stage=oem_bridge_check source=lock committed=false code=" + lockErr);
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, lockErr,
                    "MiWallpaper AIDL 锁屏状态与文件声明不一致（可能为历史残留：" + lockErr + "），已中止应用",
                    fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId, null, null, null);
        }
        if (bridge.wmsLockId != -1 && !splitLockClean) {
            Log.w(TAG, "stage=oem_bridge_check source=wms committed=false code=oem_bridge_unexpected_lock_id");
            return new OemProbeResult(Mode.AMBIGUOUS_OR_UNVERIFIED, "oem_bridge_unexpected_lock_id",
                    "检测到意外的独立锁屏 WMS 槽位 ID（" + bridge.wmsLockId + "），已中止应用",
                    fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId, null, null, null);
        }

        String expHomeSha = "image".equals(fsRes.homeType) ? bridge.home.resolvedSha256 : "";
        String expLockSha = "image".equals(fsRes.lockType) ? bridge.lock.resolvedSha256 : "";
        return new OemProbeResult(Mode.OEM_CUSTOM_IMAGE, "oem_custom_bridge_verified",
                fsRes.userMessage, fsRes.homeType, fsRes.lockType, fsRes.clockTemplateId,
                fsRes.filesToBackup, fsRes.secureSettings, fsRes.systemSettings,
                expHomeSha, expLockSha, splitLockClean, splitLockClean ? effLockComp : "");
    }

    private static String validateBridgeCustomSlot(
            BridgeSlotProbe slot, String expectedType, String expectedImgPath, String tag) {
        if (slot == null || !slot.typeOk || slot.typeReturnedNull) {
            return "oem_bridge_" + tag + "_type_unreadable";
        }
        if (!expectedType.equals(slot.authoritativeType)) {
            return "oem_bridge_" + tag + "_type_mismatch";
        }
        if ("image".equals(expectedType)) {
            if (!slot.isDefaultOk || slot.isDefault) {
                return "oem_bridge_" + tag + "_is_default_conflict";
            }
            if (!slot.componentUsingOk || !slot.componentUsing) {
                return "oem_bridge_" + tag + "_component_not_using";
            }
            if (!slot.pathOk || slot.pathReturnedNull || !expectedImgPath.equals(slot.path)) {
                return "oem_bridge_" + tag + "_path_mismatch";
            }
            if (!slot.resolvedExists || slot.resolvedBytes <= 0L || slot.resolvedSha256.isEmpty()) {
                return "oem_bridge_" + tag + "_resolved_missing";
            }
        } else if ("default".equals(expectedType)) {
            if (!slot.isDefaultOk || !slot.isDefault) {
                return "oem_bridge_" + tag + "_expected_default";
            }
        }
        return "";
    }

    public static String unwrapComponentInfo(String comp) {
        if (comp == null || comp.isEmpty()) return "";
        return comp.startsWith("ComponentInfo{") && comp.endsWith("}")
                ? comp.substring("ComponentInfo{".length(), comp.length() - 1)
                : comp;
    }

    public static String expandShortClassName(String pkg, String cls) {
        if (cls == null) return "";
        if (cls.startsWith(".") && pkg != null && !pkg.isEmpty()) {
            return pkg + cls;
        }
        return cls;
    }

    public static String canonicalizeComponent(String comp) {
        String norm = unwrapComponentInfo(comp != null ? comp.trim() : "");
        int slash = norm.indexOf('/');
        if (slash <= 0 || slash >= norm.length() - 1) {
            return norm;
        }
        String pkg = norm.substring(0, slash);
        String cls = expandShortClassName(pkg, norm.substring(slash + 1));
        return pkg + "/" + cls;
    }

    private static String normalizeComponentString(String comp) {
        return unwrapComponentInfo(comp);
    }

    private static boolean isCleanOemSysComp(String comp) {
        String norm = normalizeComponentString(comp);
        if (norm.isEmpty()) return false;
        return "null".equals(norm)
                || MIUI_IMAGE_COMPONENT_FULL.equals(norm)
                || MIUI_IMAGE_COMPONENT_SHORT.equals(norm);
    }

    public static boolean isCleanActiveSplitLockComp(String comp) {
        String norm = normalizeComponentString(comp);
        if (norm.isEmpty()) return false;
        return MIUI_IMAGE_COMPONENT_FULL.equals(norm)
                || MIUI_IMAGE_COMPONENT_SHORT.equals(norm)
                || MIUI_KEYGUARD_PICTORIAL_COMPONENT_FULL.equals(norm)
                || MIUI_KEYGUARD_PICTORIAL_COMPONENT_SHORT.equals(norm);
    }

    private static String resolveCleanActiveSplitLockComp(String primaryComp, String secondaryComp) {
        if (isCleanActiveSplitLockComp(primaryComp)) {
            return normalizeComponentString(primaryComp);
        }
        if (isCleanActiveSplitLockComp(secondaryComp)) {
            return normalizeComponentString(secondaryComp);
        }
        return "";
    }

    public static final class BridgeSlotProbe {
        public final int which;
        public final boolean typeOk;
        public final boolean typeReturnedNull;
        public final String authoritativeType;
        public final String lastType;
        public final String defaultType;
        public final boolean isDefaultOk;
        public final boolean isDefault;
        public final boolean componentUsingOk;
        public final boolean componentUsing;
        public final int effectId;
        public final boolean blurState;
        public final boolean pathOk;
        public final boolean pathReturnedNull;
        public final String pathError;
        public final String path;
        public final boolean resolvedExists;
        public final long resolvedBytes;
        public final String resolvedSha256;

        BridgeSlotProbe(int which, boolean typeOk, boolean typeReturnedNull, String authoritativeType,
                        String lastType, String defaultType, boolean isDefaultOk, boolean isDefault,
                        boolean componentUsingOk, boolean componentUsing, int effectId, boolean blurState,
                        boolean pathOk, boolean pathReturnedNull, String pathError, String path,
                        boolean resolvedExists, long resolvedBytes, String resolvedSha256) {
            this.which = which;
            this.typeOk = typeOk;
            this.typeReturnedNull = typeReturnedNull;
            this.authoritativeType = authoritativeType != null ? authoritativeType : "";
            this.lastType = lastType != null ? lastType : "";
            this.defaultType = defaultType != null ? defaultType : "";
            this.isDefaultOk = isDefaultOk;
            this.isDefault = isDefault;
            this.componentUsingOk = componentUsingOk;
            this.componentUsing = componentUsing;
            this.effectId = effectId;
            this.blurState = blurState;
            this.pathOk = pathOk;
            this.pathReturnedNull = pathReturnedNull;
            this.pathError = pathError != null ? pathError : "";
            this.path = path != null ? path : "";
            this.resolvedExists = resolvedExists;
            this.resolvedBytes = resolvedBytes;
            this.resolvedSha256 = resolvedSha256 != null ? resolvedSha256 : "";
        }

        public String getAuthoritativeType() {
            return authoritativeType;
        }
    }

    public static final class BridgeProbeSummary {
        public final boolean ok;
        public final String error;
        public final String detail;
        public final int userId;
        public final int uid;
        public final String bindMethod;
        public final BridgeSlotProbe home;
        public final BridgeSlotProbe lock;
        public final int wmsSysId;
        public final int wmsLockId;
        public final String wmsSysComp;
        public final String wmsLockComp;

        BridgeProbeSummary(boolean ok, String error, String detail, int userId, int uid, String bindMethod,
                           BridgeSlotProbe home, BridgeSlotProbe lock,
                           int wmsSysId, int wmsLockId, String wmsSysComp, String wmsLockComp) {
            this.ok = ok;
            this.error = error != null ? error : "";
            this.detail = detail != null ? detail : "";
            this.userId = userId;
            this.uid = uid;
            this.bindMethod = bindMethod != null ? bindMethod : "";
            this.home = home;
            this.lock = lock;
            this.wmsSysId = wmsSysId;
            this.wmsLockId = wmsLockId;
            this.wmsSysComp = wmsSysComp != null ? wmsSysComp : "";
            this.wmsLockComp = wmsLockComp != null ? wmsLockComp : "";
        }

        public boolean hasPermissionGap() {
            return hasSlotPermissionGap(home) || hasSlotPermissionGap(lock);
        }

        private static boolean hasSlotPermissionGap(BridgeSlotProbe s) {
            if (s == null) return true;
            if (!s.typeOk || s.typeReturnedNull) return true;
            if (!s.pathOk || s.pathReturnedNull) return true;
            return s.pathError.contains("SECURITY_EXCEPTION");
        }
    }

    /** 解析 HyperOsWallpaperBridge -probe 输出（PROBE_OK / PROBE_ERR），严格区分缺权限与超时。 */
    public static BridgeProbeSummary parseBridgeProbeOutput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new BridgeProbeSummary(false, "EMPTY_PROBE_OUTPUT", "", -1, -1, "",
                    null, null, -1, -1, "", "");
        }
        String jsonLine = null;
        boolean prefixOk = false;
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("PROBE_OK ")) {
                prefixOk = true;
                jsonLine = t.substring("PROBE_OK ".length()).trim();
                break;
            } else if (t.startsWith("PROBE_ERR ")) {
                prefixOk = false;
                jsonLine = t.substring("PROBE_ERR ".length()).trim();
                break;
            }
        }
        if (jsonLine == null || jsonLine.isEmpty()) {
            return new BridgeProbeSummary(false, "MALFORMED_PROBE_OUTPUT", "", -1, -1, "",
                    null, null, -1, -1, "", "");
        }
        try {
            JSONObject root = new JSONObject(jsonLine);
            boolean jsonOk = root.optBoolean("ok", false);
            String err = root.optString("error", "");
            String detail = root.optString("detail", "");
            int userId = root.optInt("userId", -1);
            int uid = root.optInt("uid", -1);
            String bindMethod = root.optString("bindMethod", "");
            JSONObject slots = root.optJSONObject("slots");
            BridgeSlotProbe home = slots != null ? parseBridgeSlot(slots.optJSONObject("home"), 1) : null;
            BridgeSlotProbe lock = slots != null ? parseBridgeSlot(slots.optJSONObject("lock"), 2) : null;
            JSONObject wms = root.optJSONObject("wms");
            int sysId = wms != null ? wms.optInt("sysId", -1) : -1;
            int lockId = wms != null ? wms.optInt("lockId", -1) : -1;
            String sysComp = wms != null ? wms.optString("sysComp", "") : "";
            String lockComp = wms != null ? wms.optString("lockComp", "") : "";
            boolean overallOk = prefixOk && jsonOk && err.isEmpty() && home != null && lock != null;
            return new BridgeProbeSummary(overallOk, err, detail, userId, uid, bindMethod,
                    home, lock, sysId, lockId, sysComp, lockComp);
        } catch (Throwable t) {
            return new BridgeProbeSummary(false, "PROBE_JSON_PARSE_ERROR", t.getMessage(), -1, -1, "",
                    null, null, -1, -1, "", "");
        }
    }

    private static BridgeSlotProbe parseBridgeSlot(JSONObject slot, int fallbackWhich) {
        if (slot == null) return null;
        int which = slot.optInt("which", fallbackWhich);
        JSONObject typeObj = slot.optJSONObject("type");
        JSONObject lastTypeObj = slot.optJSONObject("lastType");
        JSONObject defTypeObj = slot.optJSONObject("defaultType");
        JSONObject isDefObj = slot.optJSONObject("isDefault");
        JSONObject usingObj = slot.optJSONObject("componentUsing");
        JSONObject effObj = slot.optJSONObject("effectId");
        JSONObject blurObj = slot.optJSONObject("blurState");
        JSONObject pathCallObj = slot.optJSONObject("pathCall");
        JSONObject resolvedObj = slot.optJSONObject("resolvedFile");

        boolean typeOk = typeObj != null && typeObj.optBoolean("ok", false);
        boolean typeNull = typeObj == null || typeObj.optBoolean("returnedNull", false);
        String typeVal = typeObj != null ? typeObj.optString("value", "") : "";
        String lastTypeVal = lastTypeObj != null ? lastTypeObj.optString("value", "") : "";
        String defTypeVal = defTypeObj != null ? defTypeObj.optString("value", "") : "";
        boolean isDefOk = isDefObj != null && isDefObj.optBoolean("ok", false);
        boolean isDefVal = isDefOk && "true".equalsIgnoreCase(isDefObj.optString("value", ""));
        boolean usingOk = usingObj != null && usingObj.optBoolean("ok", false);
        boolean usingVal = usingOk && "true".equalsIgnoreCase(usingObj.optString("value", ""));
        int effectId = effObj != null ? parseIntSafe(effObj.optString("value", ""), -1) : -1;
        boolean blurState = blurObj != null && blurObj.optBoolean("ok", false)
                && "true".equalsIgnoreCase(blurObj.optString("value", ""));
        boolean pathOk = pathCallObj != null && pathCallObj.optBoolean("ok", false);
        boolean pathNull = pathCallObj == null || pathCallObj.optBoolean("returnedNull", false);
        String pathErr = pathCallObj != null ? pathCallObj.optString("error", "") : "";
        String pathVal = pathCallObj != null ? pathCallObj.optString("value", "") : "";
        boolean resExists = resolvedObj != null && resolvedObj.optBoolean("isFile", false);
        long resBytes = resolvedObj != null ? resolvedObj.optLong("bytes", -1L) : -1L;
        String resSha = resolvedObj != null ? resolvedObj.optString("sha256", "") : "";

        return new BridgeSlotProbe(which, typeOk, typeNull, typeVal, lastTypeVal, defTypeVal,
                isDefOk, isDefVal, usingOk, usingVal, effectId, blurState,
                pathOk, pathNull, pathErr, pathVal, resExists, resBytes, resSha);
    }

    public static final class BridgeRestoreResult {
        public final boolean ok;
        public final String error;
        public final String detail;
        public final boolean converged;
        public final int targetWhich;

        public BridgeRestoreResult(boolean ok, String error, String detail, boolean converged, int targetWhich) {
            this.ok = ok;
            this.error = error != null ? error : "";
            this.detail = detail != null ? detail : "";
            this.converged = converged;
            this.targetWhich = targetWhich;
        }
    }

    /** 解析 HyperOsWallpaperBridge -restore 输出（RESTORE_OK / RESTORE_ERR）。 */
    public static BridgeRestoreResult parseBridgeRestoreOutput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new BridgeRestoreResult(false, "EMPTY_RESTORE_OUTPUT", "", false, 0);
        }
        String okJson = null;
        String errJson = null;
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("RESTORE_OK ")) {
                okJson = line.substring("RESTORE_OK ".length()).trim();
            } else if (line.startsWith("RESTORE_ERR ")) {
                errJson = line.substring("RESTORE_ERR ".length()).trim();
            } else if (!line.isEmpty() && (line.startsWith("Exception") || line.contains("Error:") || line.contains("suRaw"))) {
                return new BridgeRestoreResult(false, "RESTORE_PROCESS_ERROR", line, false, 0);
            }
        }
        String target = okJson != null ? okJson : errJson;
        if (target == null) {
            return new BridgeRestoreResult(false, "NO_RESTORE_JSON_MARKER", "", false, 0);
        }
        try {
            JSONObject root = new JSONObject(target);
            boolean jsonOk = root.optBoolean("ok", false);
            String err = root.optString("error", "");
            String detail = root.optString("detail", "");
            int targetWhich = root.optInt("targetWhich", 0);
            JSONObject ver = root.optJSONObject("verification");
            boolean converged = ver != null && ver.optBoolean("converged", false);
            boolean finalOk = (okJson != null) && jsonOk && err.isEmpty() && converged;
            return new BridgeRestoreResult(finalOk, err, detail, converged, targetWhich);
        } catch (Throwable t) {
            return new BridgeRestoreResult(false, "RESTORE_JSON_PARSE_ERROR", t.getMessage(), false, 0);
        }
    }

    /** 依据备份 XML 与 secure 设置构造传递给 HyperOsWallpaperBridge -restore 的参数规范 JSON。 */
    public static JSONObject buildBridgeRestoreSpecJson(
            int userId,
            WallpaperDataXml homeXml,
            WallpaperDataXml lockXml,
            String homeBackupPath,
            String homeSha256,
            String lockBackupPath,
            String lockSha256,
            String clockTemplateFallback,
            String secureChangedHome,
            String secureChangedLock) throws Exception {
        return buildBridgeRestoreSpecJson(
                userId, homeXml, lockXml,
                homeBackupPath, homeSha256,
                lockBackupPath, lockSha256,
                clockTemplateFallback, secureChangedHome, secureChangedLock,
                false, "");
    }

    /** 支持记录已核实的原生双槽拓扑参数（allowSplitRestore / expectedWmsLockComp）。 */
    public static JSONObject buildBridgeRestoreSpecJson(
            int userId,
            WallpaperDataXml homeXml,
            WallpaperDataXml lockXml,
            String homeBackupPath,
            String homeSha256,
            String lockBackupPath,
            String lockSha256,
            String clockTemplateFallback,
            String secureChangedHome,
            String secureChangedLock,
            boolean allowSplitRestore,
            String expectedWmsLockComp) throws Exception {
        boolean homeIsImage = homeXml != null && "image".equals(homeXml.type);
        boolean lockIsImage = lockXml != null && "image".equals(lockXml.type);
        WallpaperDataXml primary = lockIsImage ? lockXml : homeXml;

        JSONObject spec = new JSONObject();
        spec.put("userId", userId);
        spec.put("homeIsImage", homeIsImage);
        spec.put("lockIsImage", lockIsImage);
        spec.put("homeImagePath", homeIsImage && homeBackupPath != null ? homeBackupPath : "");
        spec.put("homeSha256", homeIsImage && homeSha256 != null ? homeSha256 : "");
        spec.put("lockImagePath", lockIsImage && lockBackupPath != null ? lockBackupPath : "");
        spec.put("lockSha256", lockIsImage && lockSha256 != null ? lockSha256 : "");
        if (allowSplitRestore) {
            spec.put("allowSplitRestore", true);
            if (expectedWmsLockComp != null && !expectedWmsLockComp.isEmpty()) {
                spec.put("expectedWmsLockComp", expectedWmsLockComp);
            }
            if (homeIsImage && homeSha256 != null && !homeSha256.isEmpty()) {
                spec.put("stdHomeSha256", homeSha256);
            }
            if (lockIsImage && lockSha256 != null && !lockSha256.isEmpty()) {
                spec.put("stdLockSha256", lockSha256);
            }
        }

        String pendingPkg = resolveSafePendingPackage(secureChangedLock, secureChangedHome,
                primary != null ? primary.lastPendingPkg : "");
        spec.put("pendingPackage", pendingPkg);
        spec.put("content", primary != null ? safeStr(primary.content) : "");
        spec.put("loopVideo", primary != null && "true".equalsIgnoreCase(primary.loopVideo));
        spec.put("carousel", primary == null || !"false".equalsIgnoreCase(primary.carousel));
        spec.put("supportDark", primary != null && "true".equalsIgnoreCase(primary.supportDark));
        spec.put("effectId", primary != null ? primary.effectId : 0);
        spec.put("supportMatting", primary != null && primary.supportMatting);
        if (primary != null && !primary.doodleEnable.isEmpty()) {
            spec.put("doodleEnable", primary.doodleEnable);
        }
        int homeEffectId = homeXml != null ? homeXml.effectId : 0;
        int lockEffectId = lockXml != null ? lockXml.effectId : 0;
        int homeEnableBlur = normalizeEnableBlur(homeXml);
        int lockEnableBlur = normalizeEnableBlur(lockXml);
        boolean sameEffectAndBlur = (homeEffectId == lockEffectId) && (homeEnableBlur == lockEnableBlur);
        spec.put("homeEffectId", homeEffectId);
        spec.put("lockEffectId", lockEffectId);
        spec.put("homeEnableBlur", homeEnableBlur);
        spec.put("lockEnableBlur", lockEnableBlur);
        spec.put("sameEffectAndBlur", sameEffectAndBlur);

        int enableBlur = normalizeEnableBlur(primary);
        spec.put("enableBlur", enableBlur);

        String clockStyle = "";
        if (lockXml != null && !lockXml.clockStyleType.isEmpty() && !"null".equalsIgnoreCase(lockXml.clockStyleType)) {
            clockStyle = lockXml.clockStyleType;
        } else if (clockTemplateFallback != null && !"null".equalsIgnoreCase(clockTemplateFallback)) {
            clockStyle = clockTemplateFallback;
        }
        spec.put("clockStyleType", clockStyle);

        if (primary != null && primary.clockTypeInfo != null && primary.clockTypeInfo.length > 0) {
            JSONArray arr = new JSONArray();
            for (int v : primary.clockTypeInfo) {
                arr.put(v);
            }
            spec.put("clockTypeInfo", arr);
        }
        spec.put("originBitmapWidth", primary != null ? primary.originWidth : 0);
        spec.put("originBitmapHeight", primary != null ? primary.originHeight : 0);
        spec.put("allKeyFrame", primary != null ? primary.allKeyFrame : 0);
        return spec;
    }

    private static int normalizeEnableBlur(WallpaperDataXml xml) {
        if (xml == null || xml.enableBlur == null) return -1;
        String t = xml.enableBlur.trim();
        if ("true".equalsIgnoreCase(t) || "1".equals(t)) return 1;
        if ("false".equalsIgnoreCase(t) || "0".equals(t)) return 0;
        return -1;
    }

    public static boolean isTrustedMiWallpaperPendingPackage(String pkg) {
        if (pkg == null) return false;
        String t = pkg.trim();
        return "com.android.thememanager".equals(t)
                || "com.miui.aod".equals(t)
                || "com.miui.miwallpaper".equals(t)
                || "none".equals(t)
                || "com.android.wallpaper.livepicker".equals(t);
    }

    /**
     * 拦截会触发 MiWallpaper sameLayer/y() 锁屏 alpha=0 黑屏或重启合并的分槽恢复：
     * allowSplitRestore=true 且桌面与锁屏 SHA-256 相同、同特效同模糊且 pendingPackage 属于受信任 OEM 包。
     */
    public static boolean isSplitRestoreUnsafeSameLayer(JSONObject spec) {
        if (spec == null || !spec.optBoolean("allowSplitRestore", false)) {
            return false;
        }
        if (!spec.optBoolean("homeIsImage", false) || !spec.optBoolean("lockIsImage", false)) {
            return false;
        }
        String homeSha = spec.optString("homeSha256", "").trim();
        String lockSha = spec.optString("lockSha256", "").trim();
        if (homeSha.isEmpty() || !homeSha.equalsIgnoreCase(lockSha)) {
            return false;
        }
        boolean sameEffectAndBlur;
        if (spec.has("sameEffectAndBlur")) {
            sameEffectAndBlur = spec.optBoolean("sameEffectAndBlur", true);
        } else if (spec.has("homeEffectId") && spec.has("lockEffectId")) {
            sameEffectAndBlur = (spec.optInt("homeEffectId", 0) == spec.optInt("lockEffectId", 0))
                    && (spec.optInt("homeEnableBlur", -1) == spec.optInt("lockEnableBlur", -1));
        } else {
            sameEffectAndBlur = true;
        }
        if (!sameEffectAndBlur) {
            return false;
        }
        String rawPkg = spec.optString("pendingPackage", "");
        String effectivePkg = isTrustedMiWallpaperPendingPackage(rawPkg) ? rawPkg.trim() : "com.miui.aod";
        return isTrustedMiWallpaperPendingPackage(effectivePkg);
    }

    private static String resolveSafePendingPackage(String... candidates) {
        if (candidates != null) {
            for (String c : candidates) {
                if (c == null) continue;
                String t = c.trim();
                if (isTrustedMiWallpaperPendingPackage(t)) {
                    return t;
                }
            }
        }
        return "com.miui.aod";
    }

    public static final class LegacyDefaultSafetyResult {
        public final boolean safeToProceedDefaultClear;
        public final String code;
        public final String userMessage;

        LegacyDefaultSafetyResult(boolean safeToProceedDefaultClear, String code, String userMessage) {
            this.safeToProceedDefaultClear = safeToProceedDefaultClear;
            this.code = code != null ? code : "";
            this.userMessage = userMessage != null ? userMessage : "";
        }
    }

    /**
     * 恢复旧版（无 OEM 核验凭证）DEFAULT 快照前，结合文件与权威 MiWallpaper 服务进行兼容性冲突预检。
     * 只要权威 OEM 服务或模板/XML 仍显示自定义图片或非默认状态，严禁进入 clear 默认壁纸路径，并完整保留旧快照。
     */
    public static LegacyDefaultSafetyResult evaluateLegacyDefaultConflict(
            OemProbeResult fsRes, BridgeProbeSummary bridge) {
        boolean bridgeHomeCustom = isBridgeSlotImageOrNonDefault(bridge != null && bridge.ok ? bridge.home : null);
        boolean bridgeLockCustom = isBridgeSlotImageOrNonDefault(bridge != null && bridge.ok ? bridge.lock : null);
        boolean fsCustom = (fsRes != null && fsRes.mode == Mode.OEM_CUSTOM_IMAGE);
        if (bridgeHomeCustom || bridgeLockCustom || fsCustom) {
            String hType = (bridge != null && bridge.ok && bridge.home != null && !bridge.home.authoritativeType.isEmpty())
                    ? bridge.home.authoritativeType : (fsRes != null ? fsRes.homeType : "");
            String lType = (bridge != null && bridge.ok && bridge.lock != null && !bridge.lock.authoritativeType.isEmpty())
                    ? bridge.lock.authoritativeType : (fsRes != null ? fsRes.lockType : "");
            return new LegacyDefaultSafetyResult(
                    false,
                    "legacy_default_oem_custom_conflict",
                    "检测到当前备份为旧版（version=1）误判的 DEFAULT 记录（缺少原图与 OEM 证据字段），"
                            + "而权威 HyperOS 壁纸服务与系统目录仍保留自定义壁纸/模板资料"
                            + "（home=" + hType + ", lock=" + lType + "）。"
                            + "为防止清除为系统默认渐层，已禁止进入 clear 路径并完整保留旧快照与现有 OEM 资料；"
                            + "请先通过系统壁纸/现有 OEM 资料建立可信的恢复基线，再重新应用与备份。");
        }

        if (bridge == null || !bridge.ok) {
            String err = bridge != null ? bridge.error : "BRIDGE_NULL";
            return new LegacyDefaultSafetyResult(
                    false,
                    "legacy_default_bridge_unverified",
                    "检测到旧版未核验的 DEFAULT 快照（缺少 OEM 证据字段），"
                            + "且权威 HyperOS 服务状态探针未就绪（" + err + "），无法确认当前是否确为系统默认壁纸。"
                            + "为防止误清自定义壁纸，已中止默认重置并完整保留旧快照。");
        }

        if (fsRes == null || fsRes.mode != Mode.CONFIRMED_DEFAULT) {
            String fsCode = fsRes != null ? fsRes.diagnosticCode : "fs_null";
            return new LegacyDefaultSafetyResult(
                    false,
                    "legacy_default_fs_unverified",
                    "检测到旧版未核验的 DEFAULT 快照（缺少 OEM 证据字段），"
                            + "文件系统 OEM 资料核验未确认为干净默认（" + fsCode + "）。"
                            + "为防止误清保留的 OEM 资料，已中止默认重置并完整保留旧快照；请先建立可信恢复基线。");
        }

        if (!isBridgeSlotConfirmedDefault(bridge.home) || !isBridgeSlotConfirmedDefault(bridge.lock)) {
            return new LegacyDefaultSafetyResult(
                    false,
                    "legacy_default_slot_not_default",
                    "检测到旧版未核验的 DEFAULT 快照，权威 HyperOS 服务未确认桌面与锁屏均为默认状态，"
                            + "已中止默认重置并完整保留旧快照；请先建立可信恢复基线。");
        }

        return new LegacyDefaultSafetyResult(true, "legacy_default_verified_by_live_oem", "");
    }

    private static boolean isBridgeSlotImageOrNonDefault(BridgeSlotProbe slot) {
        if (slot == null) return false;
        if ("image".equals(slot.authoritativeType)) return true;
        if (slot.isDefaultOk && !slot.isDefault) return true;
        return slot.resolvedExists && slot.resolvedBytes > 0L;
    }

    private static boolean isBridgeSlotConfirmedDefault(BridgeSlotProbe slot) {
        return slot != null
                && slot.typeOk
                && !slot.typeReturnedNull
                && "default".equals(slot.authoritativeType)
                && slot.isDefaultOk
                && slot.isDefault;
    }

    public static String sha256Hex(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            try (FileInputStream fis = new FileInputStream(file)) {
                int n;
                while ((n = fis.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String sha256HexString(String text) {
        if (text == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }
}
