package com.hyperalive.aod.hook;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;

import com.hyperalive.aod.WallpaperPreviewProvider;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 针对系统个性化设置（com.android.thememanager）与锁屏编辑（com.miui.aod）的壁纸预览与原生应用链路 Hook。
 *
 * <p>严格遵循两项不变量：
 * 1) 全量精确方法签名注册（禁止任何 {@code hookAllMethods}/{@code getDeclaredMethods}/{@code getMethods} 枚举）；
 * 2) 严格隔离“仅展示降采样预览”（{@code inSampleSize=2}，不修改 {@code isThirdPartyWallpaper} 与坐标）与
 * “正式应用全分辨率准备”（{@code inSampleSize=1}，实时复核归属，遵守编辑意图与失败保守回滚）。
 */
public final class WallpaperPreviewHook {
    private static final String EDITOR = "com.miui.keyguard.editor.";

    private static final HookRegistrar XPOSED_REGISTRAR = HookUtils::hookMethod;
    private static final PreviewReader PROVIDER_PREVIEW_READER =
            (context, slot) -> readBitmap(context, slot, 2);
    private static final PreviewReader PROVIDER_FULLRES_READER =
            (context, slot) -> readBitmap(context, slot, 1);
    private static final OwnershipChecker PROVIDER_OWNERSHIP_CHECKER =
            WallpaperPreviewHook::checkOwnershipViaProvider;

    private static final int MAX_APPLY_INTENTS = 64;
    private static final AtomicLong NEXT_APPLY_GENERATION = new AtomicLong(0L);
    private static final List<ApplyIntentEntry> APPLY_INTENT_ENTRIES = new ArrayList<>();
    private static final ThreadLocal<Deque<ActiveApplyScope>> ACTIVE_APPLY_SCOPES = new ThreadLocal<>();

    public static final class ApplyIntent {
        public final long generation;
        public final boolean wallpaperChangedInEditor;

        ApplyIntent(long generation, boolean wallpaperChangedInEditor) {
            this.generation = generation;
            this.wallpaperChangedInEditor = wallpaperChangedInEditor;
        }
    }

    private static final class ApplyIntentEntry {
        final WeakReference<Object> configRef;
        final int identityHash;
        final ApplyIntent intent;

        ApplyIntentEntry(Object config, ApplyIntent intent) {
            this.configRef = new WeakReference<>(config);
            this.identityHash = System.identityHashCode(config);
            this.intent = intent;
        }
    }

    private static final class ActiveApplyScope {
        final Object paramIdentity;
        final Context context;
        final Object commonConfig;
        final OwnershipChecker ownershipChecker;
        final long generation;
        final boolean configOnly;
        boolean needApplyAllForcedFalse;
        boolean anySlotSuppressed;
        boolean anyNativeSlotExecuted;

        ActiveApplyScope(
                Object paramIdentity,
                Context context,
                Object commonConfig,
                OwnershipChecker ownershipChecker,
                long generation,
                boolean configOnly) {
            this.paramIdentity = paramIdentity;
            this.context = context;
            this.commonConfig = commonConfig;
            this.ownershipChecker = ownershipChecker;
            this.generation = generation;
            this.configOnly = configOnly;
        }
    }

    public interface PreviewReader {
        Bitmap read(Context context, String slot);
    }

    public interface OwnershipChecker {
        boolean isOwned(Context context, String slot);
    }

    public interface HookRegistrar {
        boolean hookExact(Class<?> clazz, String methodName, XC_MethodHook hook, Class<?>... paramTypes);
    }

    private WallpaperPreviewHook() {}

    /**
     * 针对 AOD 编辑页（com.miui.aod / com.miui.aod:keyguardeditor）的预览图层与原生应用链路 Hook。
     */
    public static void installAod(ClassLoader loader) {
        installAod(
                loader,
                XPOSED_REGISTRAR,
                PROVIDER_PREVIEW_READER,
                PROVIDER_FULLRES_READER,
                PROVIDER_OWNERSHIP_CHECKER);
    }

    public static void installAod(
            ClassLoader loader,
            HookRegistrar registrar,
            PreviewReader previewReader,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker) {
        if (loader == null || registrar == null) return;
        installBaseTemplateLayerHook(loader, "AOD", registrar, previewReader, fullResReader, ownershipChecker);
        installAodApplyChainHooks(loader, registrar, previewReader, fullResReader, ownershipChecker);
    }

    /**
     * 针对系统个性化首页（com.android.thememanager）当前卡片的独立展示注入 Hook（仅展示预览，不修改第三方标记与坐标）。
     */
    public static void installThemeManager(ClassLoader loader) {
        installThemeManager(
                loader,
                XPOSED_REGISTRAR,
                PROVIDER_PREVIEW_READER,
                PROVIDER_OWNERSHIP_CHECKER);
    }

    public static void installThemeManager(
            ClassLoader loader,
            HookRegistrar registrar,
            PreviewReader previewReader,
            OwnershipChecker ownershipChecker) {
        if (loader == null || registrar == null) return;
        Class<?> holderClass = HookUtils.findClass(EDITOR + "settings.SettingsMyTemplateViewHolder", loader);
        Class<?> configClass = HookUtils.findClass(EDITOR + "data.bean.CommonConfig", loader);

        if (holderClass == null || configClass == null) {
            HookUtils.log("WallpaperPreviewHook (ThemeManager): 所需类未就绪 (holder=" + (holderClass != null)
                    + ", config=" + (configClass != null) + ")");
        } else {
            XC_MethodHook holderHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    Object config = findArgInstance(param.args, configClass);
                    if (config == null) return;
                    Object itemViewObj = HookUtils.getField(param.thisObject, "itemView");
                    if (!(itemViewObj instanceof View)) return;
                    Context context = ((View) itemViewObj).getContext();
                    if (context == null) return;
                    int updated = applyDisplayPreviewToCommonConfig(
                            context, config, previewReader, ownershipChecker);
                    if (updated > 0) {
                        HookUtils.log("WallpaperPreviewHook (ThemeManager): 已向首页卡片注入展示预览位图 (slots=" + updated + ")");
                    }
                }
            };
            boolean hookedNamed = registrar.hookExact(
                    holderClass, "createOrLoadTemplateView", holderHook, String.class, configClass);
            boolean hookedLegacy = registrar.hookExact(
                    holderClass, "vep5", holderHook, String.class, configClass);
            HookUtils.log("WallpaperPreviewHook (ThemeManager): 卡片展示精确 Hook (createOrLoadTemplateView="
                    + hookedNamed + ", vep5=" + hookedLegacy + ")");
        }

        // ThemeManager 同进程渲染预览卡片的 BaseTemplateView.createWallpaperLayer（仅展示，不清除标记/不改坐标）
        installBaseTemplateLayerHook(loader, "ThemeManager", registrar, previewReader, previewReader, ownershipChecker);
    }

    private static void installBaseTemplateLayerHook(
            ClassLoader loader,
            String hostTag,
            HookRegistrar registrar,
            PreviewReader cardPreviewReader,
            PreviewReader editablePreviewReader,
            OwnershipChecker ownershipChecker) {
        Class<?> template = HookUtils.findClass(EDITOR + "edit.base.BaseTemplateView", loader);
        Class<?> home = HookUtils.findClass(EDITOR + "edit.base.HomeTemplateView", loader);
        Class<?> info = HookUtils.findClass(EDITOR + "data.bean.WallpaperInfo", loader);
        Class<?> editFragment = HookUtils.findClass(EDITOR + "edit.EditFragment", loader);

        if (template == null || info == null || editFragment == null) {
            HookUtils.log("WallpaperPreviewHook (" + hostTag + "): BaseTemplateView/WallpaperInfo/EditFragment 未就绪");
            return;
        }

        boolean hooked = registrar.hookExact(
                template,
                "createWallpaperLayer",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof Context)) return;
                        Object wInfo = param.args[1];
                        if (wInfo == null || !info.isInstance(wInfo)) return;
                        // 用户已在编辑页更换过壁纸时，绝不再注入旧 HyperAlive 位图
                        if (Boolean.TRUE.equals(invokeNoArg(param.thisObject, "getWallpaperHasChanged"))
                                || Boolean.TRUE.equals(invokeNoArg(param.thisObject, "isWallpaperChanged"))) {
                            return;
                        }

                        Context context = (Context) param.args[0];
                        String slot = (home != null && home.isInstance(param.thisObject))
                                ? "home"
                                : resolveSlotFromWallpaperInfo(wInfo);
                        // Display-only 路径直接以实时槽位归属为准：ThemeManager/AOD 卡片常携带
                        // isThirdPartyWallpaper=false 与灰色/静态旧 Bitmap，不能因此跳过归属槽位展示注入。
                        if (!isSlotOwnedFresh(context, slot, ownershipChecker)) {
                            return;
                        }

                        boolean editable = Boolean.TRUE.equals(HookUtils.getField(param.thisObject, "editable"));
                        PreviewReader chosenReader = (editable && editablePreviewReader != null)
                                ? editablePreviewReader
                                : cardPreviewReader;
                        if (chosenReader == null) return;
                        Bitmap bmp = chosenReader.read(context, slot);
                        if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                            invokeOneArg(wInfo, "setCurrentWallpaper", Bitmap.class, bmp);
                            HookUtils.log("WallpaperPreviewHook (" + hostTag
                                    + "): createWallpaperLayer 已注入展示位图 (" + slot + ", editable=" + editable + ")");
                        }
                    }
                },
                Context.class,
                info,
                boolean.class,
                editFragment,
                String.class);
        HookUtils.log("WallpaperPreviewHook (" + hostTag + "): createWallpaperLayer 精确 Hook=" + hooked);
    }

    private static void installAodApplyChainHooks(
            ClassLoader loader,
            HookRegistrar registrar,
            PreviewReader previewReader,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker) {
        Class<?> apiImplClass = HookUtils.findClass(EDITOR + "data.template.TemplateApiImpl", loader);
        Class<?> infoClass = HookUtils.findClass(EDITOR + "data.bean.WallpaperInfo", loader);
        Class<?> commonConfigClass = HookUtils.findClass(EDITOR + "data.bean.CommonConfig", loader);
        Class<?> screenshotSourceClass = HookUtils.findClass(EDITOR + "data.bean.ScreenshotSource", loader);
        Class<?> continuationClass = HookUtils.findClass("kotlin.coroutines.Continuation", loader);

        if (apiImplClass != null) {
            boolean hookedGetWall = false;
            if (infoClass != null) {
                hookedGetWall = registrar.hookExact(
                        apiImplClass,
                        "getCurrentWallpaper",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object ctxObj = HookUtils.getField(param.thisObject, "context");
                                if (!(ctxObj instanceof Context)) return;
                                Context context = (Context) ctxObj;
                                Object wInfo = (param.args != null && param.args.length >= 1) ? param.args[0] : null;
                                String slot = resolveSlotFromWallpaperInfo(wInfo);
                                boolean thirdParty = isTemplateApiSlotThirdParty(param.thisObject, slot, loader);
                                if (!thirdParty && param.getResult() != null) {
                                    return;
                                }
                                if (!isSlotOwnedFresh(context, slot, ownershipChecker) || previewReader == null) {
                                    return;
                                }
                                Bitmap bmp = previewReader.read(context, slot);
                                if (bmp != null) {
                                    param.setResult(bmp);
                                    HookUtils.log("WallpaperPreviewHook (AOD): getCurrentWallpaper 已返回 HyperAlive 展示预览图 (" + slot + ")");
                                }
                            }
                        },
                        infoClass);
            }

            boolean hookedApplyEdit = false;
            if (commonConfigClass != null && screenshotSourceClass != null && continuationClass != null) {
                hookedApplyEdit = registrar.hookExact(
                        apiImplClass,
                        "applyEditPageTemplate",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                                Object ctxObj = HookUtils.getField(param.thisObject, "context");
                                if (!(ctxObj instanceof Context)) return;
                                Context context = (Context) ctxObj;
                                Object commonConfig = param.args[0];
                                ApplyIntent recorded = findApplyIntent(commonConfig);
                                boolean wallpaperChanged = recorded != null && recorded.wallpaperChangedInEditor;
                                if (recorded == null
                                        && hasOwnedThirdPartySlot(context, commonConfig, ownershipChecker)) {
                                    recordApplyIntent(commonConfig, false);
                                }
                                // applyEditPageTemplate 兜底检查：严格遵守已有编辑意图，仅在 config-only 时对缺失全分辨率位图或无效坐标的归属槽位进行补全。
                                int prepped = prepareCommonConfigForNativeApply(
                                        context,
                                        commonConfig,
                                        wallpaperChanged,
                                        false,
                                        fullResReader,
                                        ownershipChecker,
                                        loader);
                                if (prepped > 0) {
                                    HookUtils.log("WallpaperPreviewHook (AOD): applyEditPageTemplate 已兜底补全全分辨率配置 (" + prepped + ")");
                                }
                            }
                        },
                        commonConfigClass,
                        screenshotSourceClass,
                        Bitmap.class,
                        Bitmap.class,
                        Bitmap.class,
                        long.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        continuationClass);
            }

            Class<?> pickColorClass = HookUtils.findClass(EDITOR + "utils.PickWallpaperColorInfo", loader);
            Class<?> pairClass = HookUtils.findClass("kotlin.Pair", loader);
            Class<?> applyParamClass = HookUtils.findClass(EDITOR + "data.template.WallpaperApplyParam", loader);

            boolean hookedNeedApplyAll = false;
            if (commonConfigClass != null) {
                hookedNeedApplyAll = registrar.hookExact(
                        apiImplClass,
                        "needApplyAll",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object commonConfig = (param.args != null && param.args.length >= 2) ? param.args[1] : null;
                                handleNeedApplyAllHook(param, commonConfig, ownershipChecker);
                            }
                        },
                        Bitmap.class,
                        commonConfigClass);
            }

            boolean hookedApplyWithConfig = false;
            if (commonConfigClass != null && pickColorClass != null && pairClass != null && applyParamClass != null) {
                hookedApplyWithConfig = registrar.hookExact(
                        apiImplClass,
                        "applyWallpaperWithConfig",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object ctxObj = HookUtils.getField(param.thisObject, "context");
                                if (!(ctxObj instanceof Context)) return;
                                Context context = (Context) ctxObj;
                                Object commonConfig = (param.args != null && param.args.length >= 1) ? param.args[0] : null;
                                ApplyIntent intent = findApplyIntent(commonConfig);
                                boolean configOnly = intent != null
                                        ? !intent.wallpaperChangedInEditor
                                        : hasOwnedThirdPartySlot(context, commonConfig, ownershipChecker);
                                long generation = intent != null ? intent.generation : 0L;
                                pushActiveApplyScope(new ActiveApplyScope(
                                        param, context, commonConfig, ownershipChecker, generation, configOnly));
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                ActiveApplyScope scope = popMatchingApplyScope(param);
                                if (scope != null
                                        && scope.configOnly
                                        && scope.anySlotSuppressed
                                        && !scope.anyNativeSlotExecuted) {
                                    // 所有触发到真实写入的槽位均归属 HyperAlive 且已被阻止写入：
                                    // 返回 false 通知 applyEditPageTemplate 跳过 WallpaperChangedListener 等待，同时保留 template/history 保存。
                                    param.setResult(Boolean.FALSE);
                                }
                            }
                        },
                        commonConfigClass,
                        Bitmap.class,
                        Bitmap.class,
                        boolean.class,
                        String.class,
                        String.class,
                        Bitmap.class,
                        boolean.class,
                        pickColorClass,
                        pairClass,
                        pairClass,
                        applyParamClass,
                        boolean.class);
            }

            boolean hookedApplyImage = false;
            if (infoClass != null && pairClass != null && applyParamClass != null) {
                hookedApplyImage = registrar.hookExact(
                        apiImplClass,
                        "applyImageWallpaper",
                        createSlotWriterGateHook(5, 10, "TemplateApiImpl.applyImageWallpaper"),
                        Bitmap.class,
                        boolean.class,
                        String.class,
                        infoClass,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        Bitmap.class,
                        pairClass,
                        applyParamClass,
                        boolean.class);
            }

            boolean hookedApplyGallery = false;
            if (infoClass != null) {
                hookedApplyGallery = registrar.hookExact(
                        apiImplClass,
                        "applyGalleryWallpaper",
                        createSlotWriterGateHook(4, 7, "TemplateApiImpl.applyGalleryWallpaper"),
                        Bitmap.class,
                        boolean.class,
                        infoClass,
                        String.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class);
            }

            boolean hookedApplySensor = false;
            if (infoClass != null) {
                hookedApplySensor = registrar.hookExact(
                        apiImplClass,
                        "applySensorWallpaper",
                        createSlotWriterGateHook(4, 3, "TemplateApiImpl.applySensorWallpaper"),
                        infoClass,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class);
            }

            boolean hookedApplyOuter = false;
            if (infoClass != null) {
                hookedApplyOuter = registrar.hookExact(
                        apiImplClass,
                        "applySensorOuterVideo",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                gateSensorOuterVideoCall(param);
                            }
                        },
                        infoClass,
                        infoClass,
                        String.class);
            }

            boolean hookedToast = registrar.hookExact(
                    apiImplClass,
                    "showCantApplyThirdPartyWallpaperToast",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object ctxObj = HookUtils.getField(param.thisObject, "context");
                            if (!(ctxObj instanceof Context)) return;
                            Context context = (Context) ctxObj;
                            if (isTemplateApiSlotThirdParty(param.thisObject, "lock", loader)
                                    && isSlotOwnedFresh(context, "lock", ownershipChecker)) {
                                // 在仅调整时钟/模板并故意保留 HyperAlive 动态壁纸时，抑制系统误报的第三方壁纸不可用提示
                                param.setResult(null);
                            }
                        }
                    });

            HookUtils.log("WallpaperPreviewHook (AOD): TemplateApiImpl 精确 Hook (getWall="
                    + hookedGetWall + ", applyEdit=" + hookedApplyEdit
                    + ", needAll=" + hookedNeedApplyAll + ", withCfg=" + hookedApplyWithConfig
                    + ", img=" + hookedApplyImage + ", gallery=" + hookedApplyGallery
                    + ", sensor=" + hookedApplySensor + ", outer=" + hookedApplyOuter
                    + ", toast=" + hookedToast + ")");
        } else {
            HookUtils.log("WallpaperPreviewHook (AOD): 未找到 TemplateApiImpl，跳过应用链路 Hook");
        }

        Class<?> controllerClass = HookUtils.findClass(EDITOR + "edit.wallpaper.WallpaperController", loader);
        if (controllerClass != null) {
            Class<?> pairClass = HookUtils.findClass("kotlin.Pair", loader);
            Class<?> applyParamClass = HookUtils.findClass(EDITOR + "data.template.WallpaperApplyParam", loader);
            boolean hookedVideo = registrar.hookExact(
                    controllerClass,
                    "applyVideoWallpaper",
                    createSlotWriterGateHook(4, 7, "WallpaperController.applyVideoWallpaper"),
                    String.class,
                    Object.class,
                    int.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    int.class,
                    Boolean.class);
            boolean hookedSuper = registrar.hookExact(
                    controllerClass,
                    "applySuperWallpaper",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            gateSuperWallpaperCall(param);
                        }
                    },
                    String.class,
                    Context.class,
                    boolean.class);
            boolean hookedImageLock = false;
            if (pairClass != null && applyParamClass != null) {
                hookedImageLock = registrar.hookExact(
                        controllerClass,
                        "applyImageLockWallpaper",
                        createSlotWriterGateHook(2, 10, "WallpaperController.applyImageLockWallpaper"),
                        Bitmap.class,
                        int.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        int.class,
                        pairClass,
                        applyParamClass,
                        boolean.class);
            }
            HookUtils.log("WallpaperPreviewHook (AOD): WallpaperController 精确 Hook (video="
                    + hookedVideo + ", super=" + hookedSuper + ", imgLock=" + hookedImageLock + ")");
        }

        Class<?> applierClass = HookUtils.findClass(EDITOR + "edit.base.TemplateApplier", loader);
        Class<?> baseTemplateClass = HookUtils.findClass(EDITOR + "edit.base.BaseTemplateView", loader);
        if (applierClass != null && baseTemplateClass != null) {
            boolean hookedApplier = registrar.hookExact(
                    applierClass,
                    "applyTemplate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof View)) return;
                            View templateView = (View) param.args[0];
                            Context context = templateView.getContext();
                            if (context == null) return;
                            boolean wallpaperChangedInEditor = Boolean.TRUE.equals(
                                    invokeNoArg(templateView, "getWallpaperHasChanged"))
                                    || Boolean.TRUE.equals(invokeNoArg(templateView, "isWallpaperChanged"));
                            Object config = invokeNoArg(templateView, "templateConfig");
                            if (config == null) {
                                config = invokeNoArg(templateView, "getTemplateConfig");
                            }
                            if (config == null) {
                                config = HookUtils.getField(templateView, "templateConfig");
                            }
                            if (config == null) return;
                            recordApplyIntent(config, wallpaperChangedInEditor);
                            int prepped = prepareCommonConfigForNativeApply(
                                    context,
                                    config,
                                    wallpaperChangedInEditor,
                                    true,
                                    fullResReader,
                                    ownershipChecker,
                                    loader);
                            if (prepped > 0) {
                                HookUtils.log("WallpaperPreviewHook (AOD): TemplateApplier.applyTemplate 已准备全分辨率配置 (" + prepped + ")");
                            }
                        }
                    },
                    baseTemplateClass,
                    boolean.class);
            HookUtils.log("WallpaperPreviewHook (AOD): TemplateApplier.applyTemplate 精确 Hook=" + hookedApplier);
        }
    }

    public static ApplyIntent recordApplyIntent(Object commonConfig, boolean wallpaperChangedInEditor) {
        if (commonConfig == null) return null;
        ApplyIntent intent = new ApplyIntent(NEXT_APPLY_GENERATION.incrementAndGet(), wallpaperChangedInEditor);
        int identityHash = System.identityHashCode(commonConfig);
        synchronized (APPLY_INTENT_ENTRIES) {
            for (Iterator<ApplyIntentEntry> it = APPLY_INTENT_ENTRIES.iterator(); it.hasNext(); ) {
                ApplyIntentEntry entry = it.next();
                Object ref = entry.configRef.get();
                if (ref == null || ref == commonConfig) {
                    it.remove();
                }
            }
            while (APPLY_INTENT_ENTRIES.size() >= MAX_APPLY_INTENTS) {
                APPLY_INTENT_ENTRIES.remove(0);
            }
            APPLY_INTENT_ENTRIES.add(new ApplyIntentEntry(commonConfig, intent));
        }
        return intent;
    }

    public static ApplyIntent findApplyIntent(Object commonConfig) {
        if (commonConfig == null) return null;
        int identityHash = System.identityHashCode(commonConfig);
        synchronized (APPLY_INTENT_ENTRIES) {
            for (int i = APPLY_INTENT_ENTRIES.size() - 1; i >= 0; i--) {
                ApplyIntentEntry entry = APPLY_INTENT_ENTRIES.get(i);
                Object ref = entry.configRef.get();
                if (ref == null) {
                    APPLY_INTENT_ENTRIES.remove(i);
                } else if (entry.identityHash == identityHash && ref == commonConfig) {
                    return entry.intent;
                }
            }
        }
        return null;
    }

    private static boolean hasOwnedThirdPartySlot(
            Context context,
            Object commonConfig,
            OwnershipChecker ownershipChecker) {
        if (context == null || commonConfig == null) return false;
        Object lockCfg = getSlotConfig(commonConfig, "lock");
        if (isThirdPartySlot(lockCfg) && isSlotOwnedFresh(context, "lock", ownershipChecker)) {
            return true;
        }
        Object homeCfg = getSlotConfig(commonConfig, "home");
        return isThirdPartySlot(homeCfg) && isSlotOwnedFresh(context, "home", ownershipChecker);
    }

    private static void pushActiveApplyScope(ActiveApplyScope scope) {
        Deque<ActiveApplyScope> deque = ACTIVE_APPLY_SCOPES.get();
        if (deque == null) {
            deque = new ArrayDeque<>(2);
            ACTIVE_APPLY_SCOPES.set(deque);
        }
        deque.push(scope);
    }

    private static ActiveApplyScope peekActiveApplyScope() {
        Deque<ActiveApplyScope> deque = ACTIVE_APPLY_SCOPES.get();
        return (deque == null || deque.isEmpty()) ? null : deque.peek();
    }

    private static ActiveApplyScope popMatchingApplyScope(Object paramIdentity) {
        Deque<ActiveApplyScope> deque = ACTIVE_APPLY_SCOPES.get();
        if (deque == null || deque.isEmpty()) return null;
        ActiveApplyScope top = deque.peek();
        if (top != null && top.paramIdentity == paramIdentity) {
            deque.pop();
            if (deque.isEmpty()) {
                ACTIVE_APPLY_SCOPES.remove();
            }
            return top;
        }
        return null;
    }

    private static boolean isSupersededApplyScope(ActiveApplyScope scope) {
        if (scope == null || scope.commonConfig == null || scope.generation <= 0L) return false;
        ApplyIntent latest = findApplyIntent(scope.commonConfig);
        return latest != null && latest.generation > scope.generation;
    }

    private static void handleNeedApplyAllHook(
            XC_MethodHook.MethodHookParam param,
            Object commonConfig,
            OwnershipChecker defaultChecker) {
        ActiveApplyScope scope = peekActiveApplyScope();
        boolean configOnly;
        Context context;
        OwnershipChecker checker = defaultChecker;
        if (scope != null) {
            configOnly = scope.configOnly;
            context = scope.context;
            if (scope.ownershipChecker != null) {
                checker = scope.ownershipChecker;
            }
        } else {
            Object ctxObj = HookUtils.getField(param.thisObject, "context");
            if (!(ctxObj instanceof Context)) return;
            context = (Context) ctxObj;
            ApplyIntent intent = findApplyIntent(commonConfig);
            configOnly = intent != null
                    ? !intent.wallpaperChangedInEditor
                    : hasOwnedThirdPartySlot(context, commonConfig, checker);
        }
        if (!configOnly) return;
        boolean lockOwned = isSlotOwnedFresh(context, "lock", checker);
        boolean homeOwned = isSlotOwnedFresh(context, "home", checker);
        if (lockOwned || homeOwned) {
            param.setResult(Boolean.FALSE);
            if (scope != null) {
                scope.needApplyAllForcedFalse = true;
            }
            HookUtils.log("WallpaperPreviewHook (AOD): config-only 保留 HyperAlive 槽位，强制 needApplyAll=false (lock="
                    + lockOwned + ", home=" + homeOwned + ")");
        }
    }

    private static XC_MethodHook createSlotWriterGateHook(
            final int isLockArgIdx,
            final int applyAllArgIdx,
            final String tag) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                gateSlotWriterCall(param, isLockArgIdx, applyAllArgIdx, tag);
            }
        };
    }

    private static void gateSlotWriterCall(
            XC_MethodHook.MethodHookParam param,
            int isLockArgIdx,
            int applyAllArgIdx,
            String tag) {
        ActiveApplyScope scope = peekActiveApplyScope();
        if (scope == null || !scope.configOnly) return;
        if (param.args == null
                || param.args.length <= Math.max(isLockArgIdx, applyAllArgIdx)) {
            return;
        }
        if (isSupersededApplyScope(scope)) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            HookUtils.log("WallpaperPreviewHook (AOD): " + tag + " 请求代次已过期，跳过写入 (gen=" + scope.generation + ")");
            return;
        }
        boolean isLock = Boolean.TRUE.equals(param.args[isLockArgIdx]);
        boolean applyAll = Boolean.TRUE.equals(param.args[applyAllArgIdx]);
        String targetSlot = isLock ? "lock" : "home";
        String otherSlot = isLock ? "home" : "lock";
        if (isSlotOwnedFresh(scope.context, targetSlot, scope.ownershipChecker)) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            HookUtils.log("WallpaperPreviewHook (AOD): config-only 已阻止 " + tag + " 覆盖模块槽位 ("
                    + targetSlot + ", applyAll=" + applyAll + ", gen=" + scope.generation + ")");
            return;
        }
        if (applyAll && isSlotOwnedFresh(scope.context, otherSlot, scope.ownershipChecker)) {
            param.args[applyAllArgIdx] = Boolean.FALSE;
            HookUtils.log("WallpaperPreviewHook (AOD): config-only 已将 " + tag + " 的 applyAll 降级为单槽 ("
                    + targetSlot + ", protect=" + otherSlot + ")");
        }
        scope.anyNativeSlotExecuted = true;
    }

    private static void gateSensorOuterVideoCall(XC_MethodHook.MethodHookParam param) {
        ActiveApplyScope scope = peekActiveApplyScope();
        if (scope == null || !scope.configOnly || param.args == null || param.args.length < 2) return;
        if (isSupersededApplyScope(scope)) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            return;
        }
        boolean lockOwned = isSlotOwnedFresh(scope.context, "lock", scope.ownershipChecker);
        boolean homeOwned = isSlotOwnedFresh(scope.context, "home", scope.ownershipChecker);
        if (lockOwned) {
            param.args[0] = null;
        }
        if (homeOwned) {
            param.args[1] = null;
        }
        if (param.args[0] == null && param.args[1] == null) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            HookUtils.log("WallpaperPreviewHook (AOD): config-only 已跳过 applySensorOuterVideo (lockOwned="
                    + lockOwned + ", homeOwned=" + homeOwned + ")");
        }
    }

    private static void gateSuperWallpaperCall(XC_MethodHook.MethodHookParam param) {
        ActiveApplyScope scope = peekActiveApplyScope();
        if (scope == null || !scope.configOnly) return;
        if (isSupersededApplyScope(scope)) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            return;
        }
        boolean lockOwned = isSlotOwnedFresh(scope.context, "lock", scope.ownershipChecker);
        boolean homeOwned = isSlotOwnedFresh(scope.context, "home", scope.ownershipChecker);
        if (lockOwned || homeOwned) {
            scope.anySlotSuppressed = true;
            param.setResult(null);
            HookUtils.log("WallpaperPreviewHook (AOD): config-only 已阻止 applySuperWallpaper 覆盖模块槽位 (lock="
                    + lockOwned + ", home=" + homeOwned + ")");
            return;
        }
        scope.anyNativeSlotExecuted = true;
    }

    /**
     * 仅用于 ThemeManager/预览卡片展示：
     * 只向实时校验归属 HyperAlive 的槽位注入降采样展示位图，绝不清除 {@code isThirdPartyWallpaper}，也绝不构造应用坐标。
     */
    public static int applyDisplayPreviewToCommonConfig(
            Context context,
            Object commonConfig,
            PreviewReader previewReader,
            OwnershipChecker ownershipChecker) {
        if (context == null || commonConfig == null || previewReader == null) return 0;
        int updated = 0;
        Object lockCfg = invokeNoArg(commonConfig, "getLockscreenInfo");
        if (lockCfg != null) {
            Object lockWallpaper = invokeNoArg(lockCfg, "getWallpaperInfo");
            if (applyDisplayPreviewToSlot(context, lockCfg, lockWallpaper, "lock", previewReader, ownershipChecker)) {
                updated++;
            }
        }
        Object homeCfg = invokeNoArg(commonConfig, "getHomeInfo");
        if (homeCfg != null) {
            Object homeWallpaper = invokeNoArg(homeCfg, "getWallpaperInfo");
            if (applyDisplayPreviewToSlot(context, homeCfg, homeWallpaper, "home", previewReader, ownershipChecker)) {
                updated++;
            }
        }
        return updated;
    }

    public static boolean applyDisplayPreviewToSlot(
            Context context,
            Object slotConfig,
            Object wallpaperInfo,
            String slot,
            PreviewReader previewReader,
            OwnershipChecker ownershipChecker) {
        if (context == null || wallpaperInfo == null || previewReader == null) return false;
        // 仅展示路径直接以实时槽位归属为准：ThemeManager/个人主页卡片经常把
        // isThirdPartyWallpaper=false 与缓存静态/灰色 Bitmap 一并传入，不能因此跳过归属槽位预览注入；
        // 且此处只更新展示位图，不修改 isThirdPartyWallpaper，也不写入 positionInfo。
        if (!isSlotOwnedFresh(context, slot, ownershipChecker)) {
            return false;
        }
        Bitmap preview = previewReader.read(context, slot);
        if (preview == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
            return false;
        }
        return invokeOneArg(wallpaperInfo, "setCurrentWallpaper", Bitmap.class, preview);
    }

    /**
     * 仅用于正式应用前校验与准备（{@code TemplateApplier.applyTemplate} / {@code TemplateApiImpl.applyEditPageTemplate}）：
     * 1) 若用户在编辑器中主动切换过壁纸（{@code wallpaperChangedInEditor == true}），完全交由原生链路处理；
     * 2) 实时复核各槽位归属（不信任缓存的 {@code isThirdPartyWallpaper}），归属缺失时原样保留原生配置；
     * 3) 为归属槽位加载 {@code inSampleSize=1} 全分辨率位图并构造匹配的 {@code WallpaperPositionInfo}，
     *    若任一反射设置或回读校验失败则保守回滚全部修改，绝不擅自清除 {@code isThirdPartyWallpaper}。
     */
    public static int prepareCommonConfigForNativeApply(
            Context context,
            Object commonConfig,
            boolean wallpaperChangedInEditor,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker,
            ClassLoader loader) {
        return prepareCommonConfigForNativeApply(
                context, commonConfig, wallpaperChangedInEditor, true, fullResReader, ownershipChecker, loader);
    }

    public static int prepareCommonConfigForNativeApply(
            Context context,
            Object commonConfig,
            boolean wallpaperChangedInEditor,
            boolean forceFullResDecode,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker,
            ClassLoader loader) {
        if (context == null || commonConfig == null || fullResReader == null || wallpaperChangedInEditor) {
            return 0;
        }
        int prepared = 0;
        Object lockCfg = invokeNoArg(commonConfig, "getLockscreenInfo");
        if (lockCfg != null) {
            Object lockWallpaper = invokeNoArg(lockCfg, "getWallpaperInfo");
            if (prepareSlotForNativeApply(
                    context, lockCfg, lockWallpaper, "lock", forceFullResDecode, fullResReader, ownershipChecker, loader)) {
                prepared++;
            }
        }
        Object homeCfg = invokeNoArg(commonConfig, "getHomeInfo");
        if (homeCfg != null) {
            Object homeWallpaper = invokeNoArg(homeCfg, "getWallpaperInfo");
            if (prepareSlotForNativeApply(
                    context, homeCfg, homeWallpaper, "home", forceFullResDecode, fullResReader, ownershipChecker, loader)) {
                prepared++;
            }
        }
        return prepared;
    }

    public static boolean prepareSlotForNativeApply(
            Context context,
            Object slotConfig,
            Object wallpaperInfo,
            String slot,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker,
            ClassLoader loader) {
        return prepareSlotForNativeApply(
                context, slotConfig, wallpaperInfo, slot, true, fullResReader, ownershipChecker, loader);
    }

    public static boolean prepareSlotForNativeApply(
            Context context,
            Object slotConfig,
            Object wallpaperInfo,
            String slot,
            boolean forceFullResDecode,
            PreviewReader fullResReader,
            OwnershipChecker ownershipChecker,
            ClassLoader loader) {
        if (context == null || wallpaperInfo == null || fullResReader == null) return false;
        if (!isSlotOwnedFresh(context, slot, ownershipChecker)) {
            return false;
        }

        Object prevBitmap = invokeNoArg(wallpaperInfo, "getCurrentWallpaper");
        if (!forceFullResDecode && prevBitmap instanceof Bitmap) {
            Bitmap prevBmp = (Bitmap) prevBitmap;
            if (prevBmp.getWidth() > 0
                    && prevBmp.getHeight() > 0
                    && hasMatchingValidPositionInfo(wallpaperInfo, prevBmp.getWidth(), prevBmp.getHeight())) {
                return false;
            }
        }

        Bitmap fullRes = fullResReader.read(context, slot);
        if (fullRes == null || fullRes.getWidth() <= 0 || fullRes.getHeight() <= 0) {
            return false;
        }

        Class<?> posClass = findPositionInfoClass(wallpaperInfo, loader);
        Object prevResType = invokeNoArg(wallpaperInfo, "getResourceType");
        Object prevPosInfo = invokeNoArg(wallpaperInfo, "getPositionInfo");

        if (!invokeOneArg(wallpaperInfo, "setCurrentWallpaper", Bitmap.class, fullRes)) {
            return false;
        }

        boolean touchedResType = false;
        if (!(prevResType instanceof String) || ((String) prevResType).isEmpty()) {
            if (!invokeOneArg(wallpaperInfo, "setResourceType", String.class, "image")) {
                rollbackSlotApplyPrep(wallpaperInfo, prevBitmap, prevResType, prevPosInfo, posClass, false);
                return false;
            }
            touchedResType = true;
        }

        Object targetPos = createValidPositionInfo(posClass, fullRes.getWidth(), fullRes.getHeight());
        if (targetPos == null || !invokeOneArg(wallpaperInfo, "setPositionInfo", posClass, targetPos)) {
            rollbackSlotApplyPrep(wallpaperInfo, prevBitmap, prevResType, prevPosInfo, posClass, touchedResType);
            return false;
        }

        // 回读确认位图与坐标均严格匹配且合法；任何不一致均完整回滚且不修改 isThirdPartyWallpaper
        Object readBackBmp = invokeNoArg(wallpaperInfo, "getCurrentWallpaper");
        if (readBackBmp != fullRes || !hasMatchingValidPositionInfo(wallpaperInfo, fullRes.getWidth(), fullRes.getHeight())) {
            rollbackSlotApplyPrep(wallpaperInfo, prevBitmap, prevResType, prevPosInfo, posClass, touchedResType);
            return false;
        }
        return true;
    }

    private static void rollbackSlotApplyPrep(
            Object wallpaperInfo,
            Object prevBitmap,
            Object prevResType,
            Object prevPosInfo,
            Class<?> posClass,
            boolean restoreResType) {
        if (wallpaperInfo == null) return;
        invokeOneArg(wallpaperInfo, "setCurrentWallpaper", Bitmap.class, prevBitmap);
        if (restoreResType) {
            invokeOneArg(wallpaperInfo, "setResourceType", String.class, prevResType);
        }
        if (posClass != null) {
            invokeOneArg(wallpaperInfo, "setPositionInfo", posClass, prevPosInfo);
        }
    }

    private static boolean isSlotOwnedFresh(Context context, String slot, OwnershipChecker checker) {
        if (context == null || checker == null) return false;
        try {
            return checker.isOwned(context, slot);
        } catch (Throwable t) {
            HookUtils.log("WallpaperPreviewHook: 实时归属检查异常 (" + slot + "): " + t.getMessage());
            return false;
        }
    }

    private static boolean checkOwnershipViaProvider(Context context, String slot) {
        Uri uri = Uri.withAppendedPath(Uri.parse("content://" + WallpaperPreviewProvider.AUTHORITY), slot);
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String resolveSlotFromWallpaperInfo(Object wallpaperInfo) {
        if (wallpaperInfo == null) return "lock";
        Object infoType = invokeNoArg(wallpaperInfo, "getInfoType");
        if (infoType != null && infoType.toString().contains("HOME")) {
            return "home";
        }
        return "lock";
    }

    public static Object createValidPositionInfo(Class<?> posClass, int width, int height) {
        if (posClass == null || width <= 0 || height <= 0) return null;
        try {
            Constructor<?> ctor = posClass.getConstructor(
                    float.class, float.class, float.class, float.class,
                    float[].class, Boolean.class, Float[].class, Integer.class);
            float[] identity = new float[]{
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f
            };
            return ctor.newInstance(
                    0f, 0f, (float) width, (float) height,
                    identity, Boolean.FALSE, new Float[]{0f, 0f, 0f, 0f}, Integer.valueOf(0));
        } catch (Throwable t) {
            HookUtils.log("WallpaperPreviewHook: 构造 WallpaperPositionInfo 失败: " + t.getMessage());
            return null;
        }
    }

    private static Class<?> findPositionInfoClass(Object wallpaperInfo, ClassLoader loader) {
        try {
            Method getter = wallpaperInfo.getClass().getMethod("getPositionInfo");
            Class<?> ret = getter.getReturnType();
            if (ret != null && ret != Object.class) {
                return ret;
            }
        } catch (Throwable ignored) {
        }
        return HookUtils.findClass(EDITOR + "data.bean.WallpaperPositionInfo",
                loader != null ? loader : wallpaperInfo.getClass().getClassLoader());
    }

    private static boolean hasMatchingValidPositionInfo(Object wallpaperInfo, int expectedWidth, int expectedHeight) {
        Object pos = invokeNoArg(wallpaperInfo, "getPositionInfo");
        if (pos == null) return false;
        Object left = invokeNoArg(pos, "getLeft");
        Object top = invokeNoArg(pos, "getTop");
        Object right = invokeNoArg(pos, "getRight");
        Object bottom = invokeNoArg(pos, "getBottom");
        if (!(left instanceof Number) || !(top instanceof Number)
                || !(right instanceof Number) || !(bottom instanceof Number)) {
            return false;
        }
        float l = ((Number) left).floatValue();
        float t = ((Number) top).floatValue();
        float r = ((Number) right).floatValue();
        float b = ((Number) bottom).floatValue();
        return l < r && t < b && r == (float) expectedWidth && b == (float) expectedHeight;
    }

    private static Object getSlotConfig(Object commonConfig, String slot) {
        if (commonConfig == null) return null;
        return invokeNoArg(commonConfig, "home".equals(slot) ? "getHomeInfo" : "getLockscreenInfo");
    }

    private static boolean isThirdPartySlot(Object slotConfig) {
        return slotConfig != null && Boolean.TRUE.equals(invokeNoArg(slotConfig, "isThirdPartyWallpaper"));
    }

    private static boolean isTemplateApiSlotThirdParty(Object templateApiImpl, String slot, ClassLoader loader) {
        if (templateApiImpl == null) return false;
        ClassLoader cl = loader != null ? loader : templateApiImpl.getClass().getClassLoader();
        Class<?> whichCls = HookUtils.findClass(EDITOR + "data.bean.WallpaperWhich", cl);
        String whichSub = "home".equals(slot) ? "HomeWhich" : "LockWhich";
        Class<?> slotWhichCls = HookUtils.findClass(EDITOR + "data.bean.WallpaperWhich$" + whichSub, cl);
        if (whichCls != null && slotWhichCls != null) {
            try {
                Method m = templateApiImpl.getClass().getMethod("isThirdPartyWallpaper", whichCls);
                Object inst = slotWhichCls.getField("INSTANCE").get(null);
                return Boolean.TRUE.equals(m.invoke(templateApiImpl, inst));
            } catch (Throwable ignored) {
            }
        }
        try {
            Method m0 = templateApiImpl.getClass().getMethod("isThirdPartyWallpaper");
            return Boolean.TRUE.equals(m0.invoke(templateApiImpl));
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Object findArgInstance(Object[] args, Class<?> targetClass) {
        for (Object arg : args) {
            if (targetClass.isInstance(arg)) {
                return arg;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeOneArg(Object target, String methodName, Class<?> paramType, Object arg) {
        if (target == null || paramType == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, paramType);
            m.setAccessible(true);
            m.invoke(target, arg);
            return true;
        } catch (Throwable t) {
            HookUtils.log("WallpaperPreviewHook: 调用 " + methodName + " 失败: " + t.getMessage());
            return false;
        }
    }

    private static Bitmap readBitmap(Context context, String slot, int inSampleSize) {
        Uri uri = Uri.withAppendedPath(Uri.parse("content://" + WallpaperPreviewProvider.AUTHORITY), slot);
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, inSampleSize);
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (Throwable t) {
            HookUtils.log("WallpaperPreviewHook: readBitmap 异常 (" + slot + ", sample=" + inSampleSize + "): " + t.getMessage());
            return null;
        }
    }
}
