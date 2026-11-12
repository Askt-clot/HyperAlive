package com.qihang.aod.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;

import com.qihang.aod.WallpaperPreviewProvider;

import java.io.InputStream;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 针对系统个性化设置（com.android.thememanager）与锁屏编辑（com.miui.aod）的壁纸预览 Hook。
 */
public final class WallpaperPreviewHook {
    private static final String EDITOR = "com.miui.keyguard.editor.";
    private static final Uri PREVIEWS = Uri.parse("content://" + WallpaperPreviewProvider.AUTHORITY);

    private WallpaperPreviewHook() {}

    /**
     * 针对 AOD 编辑页（com.miui.aod:keyguardeditor）的预览图层 Hook。
     * 精确拦截 BaseTemplateView.createWallpaperLayer，在原始第三方判定命中时注入真实位图。
     */
    public static void installAod(ClassLoader loader) {
        if (loader == null) return;
        Class<?> template = HookUtils.findClass(EDITOR + "edit.base.BaseTemplateView", loader);
        Class<?> home = HookUtils.findClass(EDITOR + "edit.base.HomeTemplateView", loader);
        Class<?> info = HookUtils.findClass(EDITOR + "data.bean.WallpaperInfo", loader);
        Class<?> fragment = HookUtils.findClass(EDITOR + "edit.EditFragment", loader);

        if (template == null || home == null || info == null || fragment == null) {
            HookUtils.log("WallpaperPreviewHook (AOD): 所需类未就绪 (template=" + (template != null)
                    + ", home=" + (home != null) + ", info=" + (info != null)
                    + ", fragment=" + (fragment != null) + ")");
            return;
        }

        try {
            Method thirdParty = template.getMethod("isInThirdPartyWallpaperService");
            Method setBitmap = info.getMethod("setCurrentWallpaper", Bitmap.class);

            boolean hooked = HookUtils.hookMethod(template, "createWallpaperLayer", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof Context)
                            || !info.isInstance(param.args[1])) {
                        return;
                    }

                    String slot = home.isInstance(param.thisObject) ? "home" : "lock";
                    try {
                        Object thirdPartyResult = thirdParty.invoke(param.thisObject);
                        if (!Boolean.TRUE.equals(thirdPartyResult)) {
                            HookUtils.log("WallpaperPreviewHook (AOD): isInThirdPartyWallpaperService false (" + slot + ")，跳过预览注入");
                            return;
                        }

                        Context context = (Context) param.args[0];
                        Bitmap bitmap = readPreview(context, slot);
                        if (bitmap != null) {
                            setBitmap.invoke(param.args[1], bitmap);
                            HookUtils.log("WallpaperPreviewHook (AOD): 真实照片按 " + slot + " 注入 ("
                                    + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                        } else {
                            HookUtils.log("WallpaperPreviewHook (AOD): provider 调用失败或未返回图片 (" + slot + ")");
                        }
                    } catch (Throwable e) {
                        HookUtils.log("WallpaperPreviewHook (AOD): 预览注入执行异常 (" + slot + ")", e);
                    }
                }
            }, Context.class, info, boolean.class, fragment, String.class);

            if (hooked) {
                HookUtils.log("WallpaperPreviewHook (AOD): 已成功安装精确签名 Hook (BaseTemplateView.createWallpaperLayer)");
            } else {
                HookUtils.log("WallpaperPreviewHook (AOD): 精确签名 Hook 安装失败");
            }
        } catch (Throwable e) {
            HookUtils.log("WallpaperPreviewHook (AOD): Hook 安装异常安全回退", e);
        }
    }

    /**
     * 针对系统个性化首页（com.android.thememanager）当前卡片的独立注入 Hook。
     * 精确拦截 SettingsMyTemplateViewHolder.vep5(String, CommonConfig)。
     */
    public static void installThemeManager(ClassLoader loader) {
        if (loader == null) return;
        Class<?> holderClass = HookUtils.findClass(EDITOR + "settings.SettingsMyTemplateViewHolder", loader);
        Class<?> configClass = HookUtils.findClass(EDITOR + "data.bean.CommonConfig", loader);
        Class<?> infoClass = HookUtils.findClass(EDITOR + "data.bean.WallpaperInfo", loader);

        if (holderClass == null || configClass == null || infoClass == null) {
            HookUtils.log("WallpaperPreviewHook (ThemeManager): 所需类未就绪 (holder=" + (holderClass != null)
                    + ", config=" + (configClass != null) + ", info=" + (infoClass != null) + ")");
            return;
        }

        try {
            Method getLockInfo = configClass.getMethod("getLockscreenInfo");
            Method getHomeInfo = configClass.getMethod("getHomeInfo");
            Method setBitmap = infoClass.getMethod("setCurrentWallpaper", Bitmap.class);

            boolean hooked = HookUtils.hookMethod(holderClass, "vep5", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2 || param.args[1] == null) {
                        return;
                    }

                    Context context = null;
                    try {
                        Object itemView = HookUtils.getField(param.thisObject, "itemView");
                        if (itemView instanceof View) {
                            context = ((View) itemView).getContext();
                        }
                    } catch (Throwable ignored) {}

                    if (context == null) {
                        HookUtils.log("WallpaperPreviewHook (ThemeManager): 无法获取 Context，跳过注入");
                        return;
                    }

                    Object commonConfig = param.args[1];

                    // 1. 锁屏卡片
                    try {
                        Object lockscreenInfo = getLockInfo.invoke(commonConfig);
                        if (lockscreenInfo != null) {
                            Method getWp = lockscreenInfo.getClass().getMethod("getWallpaperInfo");
                            Object wp = getWp.invoke(lockscreenInfo);
                            if (wp != null) {
                                Bitmap lockBmp = readPreview(context, "lock");
                                if (lockBmp != null) {
                                    setBitmap.invoke(wp, lockBmp);
                                    HookUtils.log("WallpaperPreviewHook (ThemeManager): 真实照片按 lock 注入当前卡片 ("
                                            + lockBmp.getWidth() + "x" + lockBmp.getHeight() + ")");
                                } else {
                                    HookUtils.log("WallpaperPreviewHook (ThemeManager): lock 槽未返回预览图");
                                }
                            }
                        }
                    } catch (Throwable e) {
                        HookUtils.log("WallpaperPreviewHook (ThemeManager): lock 卡片注入异常", e);
                    }

                    // 2. 桌面卡片
                    try {
                        Object homeInfo = getHomeInfo.invoke(commonConfig);
                        if (homeInfo != null) {
                            Method getWp = homeInfo.getClass().getMethod("getWallpaperInfo");
                            Object wp = getWp.invoke(homeInfo);
                            if (wp != null) {
                                Bitmap homeBmp = readPreview(context, "home");
                                if (homeBmp != null) {
                                    setBitmap.invoke(wp, homeBmp);
                                    HookUtils.log("WallpaperPreviewHook (ThemeManager): 真实照片按 home 注入当前卡片 ("
                                            + homeBmp.getWidth() + "x" + homeBmp.getHeight() + ")");
                                } else {
                                    HookUtils.log("WallpaperPreviewHook (ThemeManager): home 槽未返回预览图");
                                }
                            }
                        }
                    } catch (Throwable e) {
                        HookUtils.log("WallpaperPreviewHook (ThemeManager): home 卡片注入异常", e);
                    }
                }
            }, String.class, configClass);

            if (hooked) {
                HookUtils.log("WallpaperPreviewHook (ThemeManager): 已成功安装精确签名 Hook (SettingsMyTemplateViewHolder.vep5)");
            } else {
                HookUtils.log("WallpaperPreviewHook (ThemeManager): 精确签名 Hook 安装失败 (SettingsMyTemplateViewHolder.vep5)");
            }
        } catch (Throwable e) {
            HookUtils.log("WallpaperPreviewHook (ThemeManager): Hook 安装异常安全回退", e);
        }
    }

    private static Bitmap readPreview(Context context, String slot) {
        Uri uri = Uri.withAppendedPath(PREVIEWS, slot);
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (Throwable t) {
            HookUtils.log("WallpaperPreviewHook: readPreview 异常 (" + slot + "): " + t.getMessage());
            return null;
        }
    }
}
